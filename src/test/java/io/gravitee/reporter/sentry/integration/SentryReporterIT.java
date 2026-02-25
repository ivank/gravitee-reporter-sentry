/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.reporter.sentry.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
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
 * <p><b>Prerequisites:</b> Docker running, {@code .env} sourced, plugin ZIP present in
 * {@code target/} (built by the {@code package} phase, which precedes {@code integration-test}).
 *
 * <p>Run with: {@code export $(grep -v '^#' .env | xargs) && mvn clean verify -Pintegration-test}
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

  // Re-usable HTTP client for sending traffic through the gateway.
  private static HttpClient http;
  private static String gatewayBase;

  @BeforeAll
  static void startInfrastructure() throws Exception {
    String sentryDsn = requireEnv("SENTRY_DSN");
    String sentryToken = requireEnv("SENTRY_TEST_TOKEN");
    String sentryOrg = requireEnv("SENTRY_TEST_ORG");
    String sentryProject = requireEnv("SENTRY_TEST_PROJECT");
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
    managementApi = new GenericContainer<>("graviteeio/apim-management-api:4.9.13")
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
      // .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.management-api")))
      .waitingFor(Wait.forHttp("/_node/health").forPort(18083).forStatusCode(200));

    // 3. go-httpbin mock backend — actively maintained, same API as httpbin.
    httpbin = new GenericContainer<>("mccutchen/go-httpbin")
      .withNetwork(NETWORK)
      .withNetworkAliases("httpbin")
      .withExposedPorts(8080)
      // .withLogConsumer(
      //   new Slf4jLogConsumer(LoggerFactory.getLogger("tc.httpbin"))
      // )
      .waitingFor(Wait.forHttp("/get").forPort(8080).forStatusCode(200));

    // 4. Gravitee Gateway with the sentry reporter plugin.
    //    The gateway reads API definitions from MongoDB (same as docker-compose.yaml) —
    //    no HTTP management-API sync needed.
    //    The plugin ZIP is mounted into the second plugins-ext directory.
    //    The node management API on port 18082 provides the health-check endpoint.
    gateway = new GenericContainer<>("graviteeio/apim-gateway:4.9.13")
      .withNetwork(NETWORK)
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
      .withEnv("gravitee_reporters_sentry_release", "1.0.0-SNAPSHOT")
      .withEnv("gravitee_reporters_sentry_tracessamplerate", "1.0")
      .withEnv("gravitee_reporters_sentry_captureerrors", "true")
      .withEnv("gravitee_reporters_sentry_reporthealthchecks", "false")
      .withEnv("gravitee_reporters_sentry_reportlogs", "false")
      .withEnv("gravitee_reporters_sentry_reportmessagemetrics", "false")
      .dependsOn(managementApi)
      .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.gateway")))
      .waitingFor(Wait.forHttp("/_node/health").forPort(18082).forStatusCode(200));

    // deepStart resolves the dependsOn chains:
    //   mongodb → managementApi → gateway
    //   httpbin (independent)
    Startables.deepStart(gateway, httpbin).join();

    ObjectMapper objectMapper = new ObjectMapper();
    gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);
    String mgmtBase = "http://localhost:" + managementApi.getMappedPort(8083);

    http = HttpClient.newHttpClient();
    mgmtHelper = new ManagementApiHelper(mgmtBase, objectMapper);
    sentryClient = new SentryApiClient(sentryToken, sentryOrg, sentryProject, objectMapper);

    // Create two APIs via Management REST — each returns a unique UUID which becomes
    // the gravitee.api_id tag on every Sentry event for that API.
    successApiId = mgmtHelper.createAndDeployApi("Sentry IT Success", "/sentry-it-ok", "http://httpbin:8080/get");
    errorApiId = mgmtHelper.createAndDeployApi("Sentry IT Error", "/sentry-it-err", "http://httpbin:8080/status/500");

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

  // ---- Private helpers ----------------------------------------------------------------

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    assertThat(value).as("Environment variable '%s' must be set — source .env before running Maven", name).isNotBlank();
    return value;
  }
}
