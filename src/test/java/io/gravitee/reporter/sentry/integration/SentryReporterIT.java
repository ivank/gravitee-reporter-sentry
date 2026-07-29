/*
 * Copyright 2026 Ivan Kerin (http://github.com/ivank)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.gravitee.reporter.sentry.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

/**
 * End-to-end integration test for the {@code gravitee-reporter-sentry} plugin.
 *
 * <p><b>What it does:</b>
 * <ol>
 *   <li>Starts a full Gravitee APIM 4.9 stack (MongoDB + Management API + Gateway) plus a
 *       go-httpbin mock backend using Testcontainers, with the sentry reporter plugin
 *       mounted into the gateway.</li>
 *   <li>Creates two V4 HTTP proxy APIs via the Management REST API — one backed by
 *       {@code /get} (200) and one by {@code /status/500} (500).</li>
 *   <li>Waits for the gateway to sync and serve both APIs.</li>
 *   <li>Sends HTTP requests through the gateway and asserts that the expected Sentry
 *       events (transaction + error issue) arrive in the Sentry project.</li>
 * </ol>
 *
 * <p><b>Configuration:</b> All Gravitee settings are passed as environment variables using
 * the same lowercase {@code gravitee_*} convention as the project's docker-compose.yaml.
 * No custom gravitee.yml files are mounted.
 *
 * <p><b>Correlation:</b> The API UUID returned by the Management API is used as the Sentry
 * query tag {@code gravitee.api_id}. This makes every assertion idempotent — re-runs create
 * new APIs with new UUIDs, so events from previous runs never interfere.
 *
 * <p><b>Prerequisites:</b> Docker running, plugin ZIP present in {@code target/} (built by the
 * {@code package} phase, which precedes {@code integration-test}).
 *
 * <p><b>Configuration (local):</b> Copy {@code local.properties.template} to
 * {@code local.properties} and fill in your Sentry credentials. Maven loads it automatically
 * via {@code properties-maven-plugin} — no need to export env variables manually.
 *
 * <p><b>Configuration (CI/CD):</b> Set {@code SENTRY_DSN}, {@code SENTRY_TEST_TOKEN},
 * {@code SENTRY_TEST_ORG}, and {@code SENTRY_TEST_PROJECT} as environment variables.
 * Environment variables always take precedence over {@code local.properties}.
 *
 * <p>Run with: {@code mvn clean verify -Pintegration-test}
 */
@Tag("integration")
class SentryReporterIT {

  // Shared Docker network — all containers communicate via their network aliases.
  private static final Network NETWORK = Network.newNetwork();

  // MongoDB URI shared by all services — mirrors docker-compose.yaml.
  private static final String MONGO_URI =
    "mongodb://mongodb:27017/gravitee" + "?serverSelectionTimeoutMS=5000&connectTimeoutMS=5000&socketTimeoutMS=5000";

  // Containers — wired in @BeforeAll, null-checked in @AfterAll.
  private static MongoDBContainer mongodb;
  private static GenericContainer<?> managementApi;
  private static GenericContainer<?> gateway;
  private static GenericContainer<?> httpbin;

  // Shared helpers — constructed after containers are started.
  private static ManagementApiHelper mgmtHelper;
  private static SentryApiClient sentryClient;

  // API UUIDs returned by Management REST — used as Sentry tag filters.
  private static String successApiId;
  private static String errorApiId;
  private static String tracePropagationApiId;

  // Re-usable HTTP client for sending traffic through the gateway.
  private static HttpClient http;
  private static String gatewayBase;

  @BeforeAll
  static void startInfrastructure() throws Exception {
    String sentryDsn = System.getProperty("SENTRY_DSN");
    String sentryToken = System.getProperty("SENTRY_TEST_TOKEN");
    String sentryOrg = System.getProperty("SENTRY_TEST_ORG");
    String sentryProject = System.getProperty("SENTRY_TEST_PROJECT");
    String pluginVersion = System.getProperty("project.version", "1.0.0-SNAPSHOT");

    // Fail fast: the plugin ZIP must be present before any container starts.
    // The failsafe plugin runs after the package phase, so this should always be true
    // when invoked via mvn verify -Pintegration-test.
    Path pluginZip = Paths.get("target/gravitee-reporter-sentry-" + pluginVersion + ".zip");
    assertThat(pluginZip)
      .as("Plugin ZIP not found at %s — run 'mvn package -DskipTests' first", pluginZip.toAbsolutePath())
      .exists();

    // 1. MongoDB
    mongodb = new MongoDBContainer("mongo:7.0").withNetwork(NETWORK).withNetworkAliases("mongodb");

    // 2. Gravitee Management API
    managementApi = new GenericContainer<>("graviteeio/apim-management-api:" + TestVersions.APIM)
      .withNetwork(NETWORK)
      .withNetworkAliases("management-api")
      .withExposedPorts(8083, 18083)
      .withEnv("gravitee_management_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_reporters_elasticsearch_enabled", "false")
      .withEnv("gravitee_analytics_type", "none")
      .withEnv("gravitee_plugins_path_0", "/opt/graviteeio-management-api/plugins")
      // Health check url
      .withEnv("gravitee_services_core_http_enabled", "true")
      .withEnv("gravitee_services_core_http_port", "18083")
      .withEnv("gravitee_services_core_http_host", "0.0.0.0")
      .withEnv("gravitee_services_core_http_authentication_type", "none")
      .dependsOn(mongodb)
      .withLogConsumer(filteredLogConsumer("management-api"))
      .waitingFor(
        Wait.forHttp("/_node/health")
          .forPort(18083)
          .forStatusCode(200)
          .withStartupTimeout(TestVersions.CONTAINER_STARTUP_TIMEOUT)
      );

    // 3. go-httpbin mock backend — actively maintained, same API as httpbin.
    httpbin = new GenericContainer<>("mccutchen/go-httpbin")
      .withNetwork(NETWORK)
      .withNetworkAliases("httpbin")
      .withExposedPorts(8080)
      .withLogConsumer(filteredLogConsumer("httpbin"))
      .waitingFor(Wait.forHttp("/get").forPort(8080).forStatusCode(200));

    // 4. Gravitee Gateway with the sentry reporter plugin.
    //    The plugin ZIP is mounted into the second plugins-ext directory.
    //    The node management API on port 18082 provides the health-check endpoint.
    gateway = new GenericContainer<>("graviteeio/apim-gateway:" + TestVersions.APIM)
      .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
      .withNetwork(NETWORK)
      .withAccessToHost(true)
      .withNetworkAliases("gateway")
      .withExposedPorts(8082, 18082)
      .withCopyFileToContainer(
        MountableFile.forHostPath(pluginZip.toAbsolutePath().toString()),
        "/opt/graviteeio-gateway/plugins-ext/gravitee-reporter-sentry.zip"
      )
      // MongoDB — mirrors docker-compose.yaml
      .withEnv("gravitee_management_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_ratelimit_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_reporters_elasticsearch_enabled", "false")
      .withEnv("gravitee_plugins_path_0", "/opt/graviteeio-gateway/plugins")
      .withEnv("gravitee_plugins_path_1", "/opt/graviteeio-gateway/plugins-ext")
      // Node management HTTP service — required for /_node/health health-check
      .withEnv("gravitee_services_core_http_enabled", "true")
      .withEnv("gravitee_services_core_http_port", "18082")
      .withEnv("gravitee_services_core_http_host", "0.0.0.0")
      .withEnv("gravitee_services_core_http_authentication_type", "none")
      // Sentry reporter plugin configuration
      .withEnv("gravitee_reporters_sentry_enabled", "true")
      .withEnv("gravitee_reporters_sentry_dsn", sentryDsn)
      .withEnv("gravitee_reporters_sentry_environment", "integration-test")
      .withEnv("gravitee_reporters_sentry_release", pluginVersion)
      .withEnv("gravitee_reporters_sentry_tracessamplerate", "1.0")
      .withEnv("gravitee_reporters_sentry_captureerrors", "true")
      .withEnv("gravitee_reporters_sentry_reporthealthchecks", "false")
      .withEnv("gravitee_reporters_sentry_reportlogs", "false")
      .withEnv("gravitee_reporters_sentry_reportmessagemetrics", "false")
      .dependsOn(managementApi)
      .withLogConsumer(filteredLogConsumer("gateway"))
      .waitingFor(
        Wait.forHttp("/_node/health")
          .forPort(18082)
          .forStatusCode(200)
          .withStartupTimeout(TestVersions.CONTAINER_STARTUP_TIMEOUT)
      );

    // deepStart resolves the dependsOn chains:
    //   mongodb → managementApi → gateway
    //   httpbin (independent)
    Startables.deepStart(gateway, httpbin).join();

    gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);
    String mgmtBase = "http://localhost:" + managementApi.getMappedPort(8083);

    http = HttpClient.newHttpClient();
    mgmtHelper = new ManagementApiHelper(mgmtBase);
    sentryClient = new SentryApiClient(sentryToken, sentryOrg, sentryProject);

    // Create two APIs via Management REST — each returns a unique UUID which becomes
    // the gravitee.api_id tag on every Sentry event for that API.
    successApiId = mgmtHelper.createAndDeployApi("Sentry IT Success", "/sentry-it-ok", "http://httpbin:8080/get");
    errorApiId = mgmtHelper.createAndDeployApi("Sentry IT Error", "/sentry-it-err", "http://httpbin:8080/status/500");
    // Logging enabled so metrics.getLog().getEntrypointRequest().getHeaders() is populated,
    // which is required for the reporter to read sentry-trace/baggage and continue the trace.
    tracePropagationApiId = mgmtHelper.createAndDeployApiWithLogging(
      "Sentry IT Trace",
      "/sentry-it-trace",
      "http://httpbin:8080/get"
    );

    // Wait for the gateway to sync both APIs. The gateway polls MongoDB
    // every ~5 s; Awaitility retries until a non-404 response is received.
    await("gateway to serve success API")
      .atMost(Duration.ofSeconds(90))
      .pollInterval(Duration.ofSeconds(3))
      .until(
        () ->
          http
            .send(
              HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/sentry-it-ok")).build(),
              HttpResponse.BodyHandlers.discarding()
            )
            .statusCode() !=
          404
      );

    await("gateway to serve error API")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(3))
      .until(
        () ->
          http
            .send(
              HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/sentry-it-err")).build(),
              HttpResponse.BodyHandlers.discarding()
            )
            .statusCode() !=
          404
      );

    await("gateway to serve trace-propagation API")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(3))
      .until(
        () ->
          http
            .send(
              HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/sentry-it-trace")).build(),
              HttpResponse.BodyHandlers.discarding()
            )
            .statusCode() !=
          404
      );
  }

  @AfterAll
  static void stopInfrastructure() {
    // Stop in reverse dependency order for a clean shutdown.
    // Null-checks guard against @BeforeAll failing before all containers were created.
    Stream.of(gateway, httpbin, managementApi, mongodb).filter(Objects::nonNull).forEach(GenericContainer::stop);
    NETWORK.close();
  }

  /**
   * Verifies that a Sentry transaction is created when a successful request flows through
   * the gateway to the backend.
   *
   * <p>The Sentry SDK sends transactions asynchronously; Awaitility polls until the
   * transaction appears (up to 60 s).
   */
  @Test
  void shouldCreateSentryTransactionForSuccessfulRequest() throws Exception {
    // Send a GET through the gateway — httpbin /get returns 200.
    var response = http.send(
      HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/sentry-it-ok")).build(),
      HttpResponse.BodyHandlers.discarding()
    );
    assertThat(response.statusCode()).isEqualTo(200);

    // Poll Sentry for a transaction tagged with this API's unique ID.
    // The transaction name is "GET /sentry-it-ok" (method + sanitized path).
    List<JsonNode> events = sentryClient.pollForTransactions(
      "transaction:\"GET /sentry-it-ok\"",
      Duration.ofSeconds(60)
    );

    assertThat(events).isNotEmpty();
    assertThat(events.get(0).path("transaction").asText()).isEqualTo("GET /sentry-it-ok");
  }

  /**
   * Verifies that the gateway transaction is linked to the caller's distributed trace when the
   * request carries {@code sentry-trace} and {@code baggage} headers.
   *
   * <p>The API used here has entrypoint request-header logging enabled, which populates
   * {@code metrics.getLog().getEntrypointRequest().getHeaders()} so the reporter can extract
   * the trace headers and pass them to {@code Sentry.continueTrace()}.
   *
   * <p>The assertion queries Sentry for a transaction whose {@code trace} field equals the
   * trace ID from the outbound header. If trace propagation is broken, the transaction lands
   * on an orphaned trace and the poll times out.
   */
  @Test
  void shouldLinkGatewayTransactionToIncomingTrace() throws Exception {
    // A deterministic trace ID — unique enough per test run to avoid collisions with other runs.
    String traceId = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";
    String spanId = "a1b2c3d4e5f6a1b2";
    String sentryTrace = traceId + "-" + spanId + "-1";
    String baggage = "sentry-environment=integration-test,sentry-trace_id=" + traceId + ",sentry-sample_rand=0.123";

    var response = http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/sentry-it-trace"))
        .header("sentry-trace", sentryTrace)
        .header("baggage", baggage)
        .build(),
      HttpResponse.BodyHandlers.discarding()
    );
    assertThat(response.statusCode()).isEqualTo(200);

    // The transaction must appear under the same trace ID that was sent in the header.
    // If continueTrace() worked, Sentry stored the transaction with trace=traceId.
    // If it was ignored, the transaction would have a fresh trace ID and this would time out.
    List<JsonNode> events = sentryClient.pollForTransactionInTrace(
      traceId,
      "GET /sentry-it-trace",
      Duration.ofSeconds(60)
    );

    assertThat(events).isNotEmpty();
    assertThat(events.get(0).path("trace").asText()).isEqualTo(traceId);
  }

  /**
   * Verifies that a Sentry error issue is captured when the backend returns a 5xx response.
   *
   * <p>The error API routes to go-httpbin {@code /status/500}, which always returns 500.
   * The reporter's {@code captureErrors=true} setting causes an error event to be sent
   * in addition to the performance transaction.
   */
  @Test
  void shouldCaptureErrorEventFor5xxResponse() throws Exception {
    // Send a GET through the gateway — httpbin /status/500 returns 500.
    var response = http.send(
      HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/sentry-it-err")).build(),
      HttpResponse.BodyHandlers.discarding()
    );
    assertThat(response.statusCode()).isEqualTo(500);

    // Poll Sentry for an error issue correlated by the API's unique tag.
    // gravitee.api_id is set as an indexed tag on every error event by MetricsToSentryMapper.
    List<JsonNode> issues = sentryClient.pollForIssues(
      "is:unresolved gravitee.api_id:" + errorApiId,
      Duration.ofSeconds(60)
    );

    assertThat(issues).isNotEmpty();
    assertThat(issues.get(0).path("level").asText()).isEqualTo("error");
  }

  /**
   * Builds a Testcontainers log consumer that drastically trims forwarded container output.
   *
   * <p>The default {@link org.testcontainers.containers.output.Slf4jLogConsumer} forwards every
   * line from each container, which produces thousands of lines of Spring/Jetty/Mongo bootstrap
   * noise for a single IT run. This consumer keeps only the lines that materially help when
   * debugging:
   * <ul>
   *   <li>Anything at {@code ERROR} level, except a small explicit allow-list of known harmless
   *       lines emitted during normal startup (e.g. Gravitee's K8s probe error which fires when
   *       the gateway is not running inside Kubernetes).</li>
   *   <li>Anything mentioning our reporter ({@code io.gravitee.reporter.sentry} or
   *       {@code SentryReporter}) at {@code INFO} — so the reporter's own startup signal and any
   *       transaction/error reporting it logs survives the filter.</li>
   *   <li>{@code ApiManagerImpl} deploy/undeploy events — these confirm Gravitee picked up the
   *       APIs Terraform/Management-API created. Lines unrelated to API deployment (shared policy
   *       groups, etc.) are dropped.</li>
   * </ul>
   *
   * <p>Each forwarded line has the duplicated {@code HH:mm:ss.SSS [thread]} prefix stripped, since
   * SLF4J adds its own timestamp. The exclusion list is explicit (no regex catch-alls) and each
   * entry has a comment explaining what it suppresses, so a future real error matching one of
   * these signatures won't be silently swallowed.
   */
  private static Consumer<OutputFrame> filteredLogConsumer(String prefix) {
    Logger logger = LoggerFactory.getLogger("tc." + prefix);
    return frame -> {
      String raw = frame.getUtf8String();
      if (raw == null || raw.isBlank()) {
        return;
      }
      // Each frame can contain multiple newline-separated log records.
      for (String line : raw.split("\\R")) {
        if (line.isBlank()) {
          continue;
        }
        String stripped = stripTimestampPrefix(line);
        if (isError(line)) {
          // Suppress known benign ERROR lines from Gravitee boot. Everything else surfaces.
          if (isKnownHarmlessError(stripped)) {
            continue;
          }
          logger.error("{}", stripped);
        } else if (isReporterSignal(stripped) || isApiDeploymentEvent(stripped)) {
          logger.info("{}", stripped);
        }
        // Otherwise drop the line.
      }
    };
  }

  private static boolean isError(String line) {
    // Match the standard Logback level token. Avoid false positives on lines that merely
    // contain the word "error" (e.g. URLs, payloads).
    return line.contains(" ERROR ") || line.contains("ERROR ");
  }

  private static boolean isReporterSignal(String line) {
    return line.contains("io.gravitee.reporter.sentry") || line.contains("SentryReporter");
  }

  private static boolean isApiDeploymentEvent(String line) {
    // Only forward API-level deploy/undeploy logs from ApiManagerImpl. Shared policy group
    // deploys for the same logger get filtered out — they're not relevant to our test.
    return (
      line.contains("ApiManagerImpl") && (line.contains("has been deployed") || line.contains("has been undeployed"))
    );
  }

  private static boolean isKnownHarmlessError(String line) {
    // Gravitee gateway probes Kubernetes config when not running in K8s; the resulting
    // ERROR is expected and harmless in a Testcontainers run.
    if (line.contains("KubernetesClientFactory") || line.contains("Unable to retrieve Kubernetes")) {
      return true;
    }
    return false;
  }

  /**
   * Strips the leading {@code HH:mm:ss.SSS [thread-name]} that Gravitee/Spring services emit.
   * SLF4J adds its own timestamp once we forward, so leaving the container's prefix produces a
   * confusing double-timestamp.
   */
  private static String stripTimestampPrefix(String line) {
    // Drop a trailing line-feed if the frame retained it.
    String trimmed = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
    // Strip "HH:mm:ss.SSS " if present at the start.
    if (trimmed.length() > 13 && trimmed.charAt(2) == ':' && trimmed.charAt(5) == ':' && trimmed.charAt(8) == '.') {
      trimmed = trimmed.substring(13).stripLeading();
    }
    // Strip an immediately-following "[thread] " segment.
    if (trimmed.startsWith("[")) {
      int closer = trimmed.indexOf(']');
      if (closer > 0 && closer + 1 < trimmed.length()) {
        trimmed = trimmed.substring(closer + 1).stripLeading();
      }
    }
    return trimmed;
  }
}
