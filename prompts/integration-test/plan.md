# Integration Test Plan: gravitee-reporter-sentry

## 1. Objective

Stand up a real Gravitee APIM 4.9 Community Edition gateway using pure **Testcontainers**
(no docker-compose file), with the `gravitee-reporter-sentry` plugin installed. Send API
traffic through the gateway and assert that the expected Sentry events (transactions, error
events) actually arrive in the Sentry project, using the Sentry REST API to verify.

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│ Host (JUnit 5 + Testcontainers)                                      │
│                                                                      │
│  SentryReporterIT                                                    │
│    @BeforeAll ──► start containers (deepStart), create API via REST  │
│    @Test      ──► GET → gateway → go-httpbin (200)                   │
│    @Test      ──► GET → gateway → go-httpbin /status/500 (5xx)       │
│    poll Sentry REST API using gravitee.api_id tag as correlation key  │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │ shared Docker Network (apim-net)
          ┌──────────────────────┼───────────────────────┐
          │                      │                       │
 ┌────────▼────────┐  ┌──────────▼──────────┐  ┌────────▼────────┐
 │ Management API  │  │ Gateway 4.9          │  │  go-httpbin     │
 │ graviteeio/apim │  │ graviteeio/apim      │  │ mccutchen/      │
 │ -management-api │  │ -gateway:4.9         │  │ go-httpbin      │
 │ :8083           │  │ :8082  :18082        │  │ :8080           │
 └────────┬────────┘  └──────────┬───────────┘  └─────────────────┘
          │                      │ HTTP sync (management.type=http)
          └──────────────┐       │
                  ┌───────▼───────▼──┐
                  │   MongoDB 6      │
                  │   MongoDBContainer│
                  │   :27017         │
                  └──────────────────┘

Note: Elasticsearch is disabled via environment variable. The Management API logs
ES warnings at startup; these are harmless and do not affect API management.
```

---

## 3. Files to Create / Modify

```
.env                                                         ← NEW (git-ignored)
.gitignore                                                   ← MODIFY (add .env)
pom.xml                                                      ← MODIFY

src/test/resources/integration/
└── gateway-config/
    └── gravitee.yml                                         ← NEW (classpath resource)

src/test/java/io/gravitee/reporter/sentry/integration/
├── SentryReporterIT.java                                    ← NEW
├── ManagementApiHelper.java                                 ← NEW
└── SentryApiClient.java                                     ← NEW

prompts/integration-test/
└── plan.md                                                  ← THIS FILE
```

No docker-compose file. All infrastructure is declared in Java.

---

## 4. Environment File

### `.env` (git-ignored)

```dotenv
SENTRY_DSN=https://cd2bb2f3364481147be14cee4a8711e9@o4510792156315648.ingest.de.sentry.io/4510935322984528
SENTRY_TOKEN=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
```

### `.gitignore` addition

```
.env
```

Both values are read at test time via `System.getenv()`. Failsafe forwards them as
environment variables so they are available inside the test JVM.

---

## 5. Maven Changes (`pom.xml`)

### 5a. Add `integration-test` profile

```xml
<profile>
  <id>integration-test</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <version>3.2.5</version>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
        <configuration>
          <includes>
            <include>**/*IT.java</include>
          </includes>
          <!--
            Inject project.version so the test can locate the plugin ZIP in target/
            without hardcoding the version string.
          -->
          <systemPropertyVariables>
            <project.version>${project.version}</project.version>
          </systemPropertyVariables>
          <!--
            Forward SENTRY_DSN and SENTRY_TOKEN from the shell environment
            (populated by sourcing .env) into the test JVM.
          -->
          <environmentVariables>
            <SENTRY_DSN>${env.SENTRY_DSN}</SENTRY_DSN>
            <SENTRY_TOKEN>${env.SENTRY_TOKEN}</SENTRY_TOKEN>
          </environmentVariables>
          <argLine>-Dnet.bytebuddy.experimental=true</argLine>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

### 5b. Test dependencies — add to existing `<dependencies>` block

```xml
<!-- Testcontainers BOM in <dependencyManagement> -->
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-bom</artifactId>
  <version>1.20.4</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>

<!-- Testcontainers core + JUnit 5 bridge + MongoDB module -->
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>mongodb</artifactId>
  <scope>test</scope>
</dependency>

<!-- JSON: build Management API request bodies and parse responses -->
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.17.2</version>
  <scope>test</scope>
</dependency>

<!-- Async assertion polling (replaces Thread.sleep) -->
<dependency>
  <groupId>org.awaitility</groupId>
  <artifactId>awaitility</artifactId>
  <version>4.2.2</version>
  <scope>test</scope>
</dependency>
```

### 5c. Why failsafe?

Failsafe runs in the `integration-test` Maven lifecycle phase, which comes **after**
`package`. This guarantees that `target/gravitee-reporter-sentry-${project.version}.zip`
is already built before Testcontainers tries to mount it into the gateway container.

### 5d. Running the tests

```bash
# Source .env so SENTRY_DSN and SENTRY_TOKEN are available to Maven
export $(grep -v '^#' .env | xargs)

# Full build including IT tests
mvn clean verify -Pintegration-test

# Re-run IT only (plugin ZIP must already exist in target/)
mvn integration-test verify -Pintegration-test -DskipTests
```

(`-DskipTests` skips surefire unit tests while still letting failsafe run. Never use
`mvn failsafe:integration-test` directly — it skips the `pre-integration-test` lifecycle
phase that testcontainers hooks into for startup ordering.)

---

## 6. Gateway Configuration (classpath resource)

`src/test/resources/integration/gateway-config/gravitee.yml`

This file is the minimal configuration needed that cannot be expressed via Gravitee's
`GRAVITEE_*` environment variable convention (specifically the `plugins.path` list).
Everything else is injected at container startup as environment variables.

```yaml
# Extra plugin directory — where we copy the sentry reporter ZIP
plugins:
  path:
    - ${gravitee.home}/plugins
    - /opt/graviteeio-gateway/plugins-ext

# Gateway node management API — used as the container health check target
services:
  core:
    http:
      enabled: true
      port: 18082
      host: 0.0.0.0
```

All other gateway settings (management connection URL, reporter config, etc.) are set
via environment variables on the container in Java code.

---

## 7. Integration Test Design

### 7a. Container wiring (`SentryReporterIT.java`)

Containers are managed **manually** in `@BeforeAll` / `@AfterAll` rather than using
`@Container` annotations. This gives us explicit control over the startup order chain
(MongoDB → Management API → Gateway) and makes container interdependencies obvious.

```java
@Tag("integration")                                          // exclude from unit-test runs
class SentryReporterIT {

    // Shared Docker network — all containers communicate via service aliases
    private static final Network NETWORK = Network.newNetwork();

    // Containers — declared here, wired and started in @BeforeAll
    private static MongoDBContainer mongodb;
    private static GenericContainer<?> managementApi;
    private static GenericContainer<?> gateway;
    private static GenericContainer<?> httpbin;

    // Helpers shared by all test methods
    private static ManagementApiHelper mgmtHelper;
    private static SentryApiClient sentryClient;

    // Correlation: the API ID returned by Management REST is used as the Sentry
    // query filter (tag gravitee.api_id). Unique per test run; no timestamp needed.
    private static String successApiId;
    private static String errorApiId;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        String sentryDsn   = requireEnv("SENTRY_DSN");
        String sentryToken = requireEnv("SENTRY_TOKEN");
        String pluginVersion = System.getProperty("project.version", "1.0.0-SNAPSHOT");

        // 1. MongoDB
        mongodb = new MongoDBContainer("mongo:6")
            .withNetwork(NETWORK)
            .withNetworkAliases("mongodb");

        // 2. Gravitee Management API
        managementApi = new GenericContainer<>("graviteeio/apim-management-api:4.9")
            .withNetwork(NETWORK)
            .withNetworkAliases("management-api")
            .withExposedPorts(8083)
            .withEnv("GRAVITEE_MANAGEMENT_MONGODB_URI", "mongodb://mongodb:27017/gravitee")
            .withEnv("GRAVITEE_ANALYTICS_ELASTICSEARCH_ENABLED", "false")
            .dependsOn(mongodb)
            .waitingFor(
                Wait.forHttp("/management/health")
                    .forPort(8083)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)));

        // 3. Mock backend — actively maintained, lightweight Go implementation
        httpbin = new GenericContainer<>("mccutchen/go-httpbin")
            .withNetwork(NETWORK)
            .withNetworkAliases("httpbin")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/get").forPort(8080).forStatusCode(200));

        // 4. Gravitee Gateway with sentry plugin
        //    Plugin ZIP is built in the package phase (before integration-test phase runs).
        //    MountableFile.forHostPath() resolves absolute paths reliably — no relative path
        //    fragility that docker-compose volume mounts have.
        Path pluginZip = Paths.get("target/gravitee-reporter-sentry-" + pluginVersion + ".zip");
        assertThat(pluginZip).exists(); // fail fast with a clear message if not built

        gateway = new GenericContainer<>("graviteeio/apim-gateway:4.9")
            .withNetwork(NETWORK)
            .withNetworkAliases("gateway")
            .withExposedPorts(8082, 18082)
            // Custom gravitee.yml (plugins path only — everything else via env vars below)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("integration/gateway-config/gravitee.yml"),
                "/opt/graviteeio-gateway/config/gravitee.yml")
            // Plugin ZIP from target/
            .withCopyFileToContainer(
                MountableFile.forHostPath(pluginZip.toAbsolutePath().toString()),
                "/opt/graviteeio-gateway/plugins-ext/gravitee-reporter-sentry.zip")
            // Management API connection
            .withEnv("GRAVITEE_MANAGEMENT_TYPE", "http")
            .withEnv("GRAVITEE_MANAGEMENT_HTTP_URL", "http://management-api:8083/management/")
            .withEnv("GRAVITEE_MANAGEMENT_HTTP_AUTHENTICATION_TYPE", "basic")
            .withEnv("GRAVITEE_MANAGEMENT_HTTP_AUTHENTICATION_BASIC_USERNAME", "admin")
            .withEnv("GRAVITEE_MANAGEMENT_HTTP_AUTHENTICATION_BASIC_PASSWORD", "adminadmin")
            // Disable ES reporter — Sentry is the only reporter under test
            .withEnv("GRAVITEE_REPORTERS_ELASTICSEARCH_ENABLED", "false")
            // Sentry reporter config
            .withEnv("GRAVITEE_REPORTERS_SENTRY_ENABLED", "true")
            .withEnv("GRAVITEE_REPORTERS_SENTRY_DSN", sentryDsn)
            .withEnv("GRAVITEE_REPORTERS_SENTRY_ENVIRONMENT", "integration-test")
            .withEnv("GRAVITEE_REPORTERS_SENTRY_TRACESSAMPLERATE", "1.0")
            .withEnv("GRAVITEE_REPORTERS_SENTRY_CAPTUREERRORS", "true")
            .withEnv("GRAVITEE_REPORTERS_SENTRY_REPORTHEALTHCHECKS", "false")
            .withEnv("GRAVITEE_REPORTERS_SENTRY_REPORTLOGS", "false")
            .withEnv("GRAVITEE_REPORTERS_SENTRY_DEBUG", "true")
            .dependsOn(managementApi)
            // Use the gateway's node management API — accurate readiness signal
            .waitingFor(
                Wait.forHttp("/_node/health")
                    .forPort(18082)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)));

        // Start all containers; deepStart resolves the dependsOn chains so mongodb
        // starts first, then managementApi, then gateway and httpbin in parallel.
        Startables.deepStart(gateway, httpbin).join();

        // Build helper objects now that ports are mapped
        ObjectMapper objectMapper = new ObjectMapper();
        String mgmtBase = "http://localhost:" + managementApi.getMappedPort(8083);
        String gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);

        mgmtHelper = new ManagementApiHelper(mgmtBase, objectMapper);
        sentryClient = new SentryApiClient(sentryToken, objectMapper);

        // Create two test APIs via Management REST — each returns a unique API ID (UUID)
        // that is emitted as the gravitee.api_id tag in every Sentry event.
        // Using the API ID as the Sentry query filter makes the test idempotent:
        // no timestamp windows, no test-name pollution from previous runs.
        successApiId = mgmtHelper.createAndDeployApi("Sentry IT Success", "/sentry-it-ok",
            "http://httpbin:8080/get");
        errorApiId   = mgmtHelper.createAndDeployApi("Sentry IT Error",   "/sentry-it-err",
            "http://httpbin:8080/status/500");

        // Wait for the gateway to sync both APIs. The gateway polls the Management API
        // on a 5-second interval by default; Awaitility drives the poll loop.
        HttpClient http = HttpClient.newHttpClient();
        await("gateway to serve success API")
            .atMost(60, SECONDS).pollInterval(3, SECONDS)
            .until(() -> http.send(
                HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/sentry-it-ok")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode() != 404);
        await("gateway to serve error API")
            .atMost(30, SECONDS).pollInterval(3, SECONDS)
            .until(() -> http.send(
                HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/sentry-it-err")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode() != 404);
    }

    @AfterAll
    static void stopInfrastructure() {
        // Stop in reverse dependency order for clean shutdown
        Stream.of(gateway, httpbin, managementApi, mongodb)
            .filter(Objects::nonNull)
            .forEach(GenericContainer::stop);
        NETWORK.close();
    }

    // Convenience: fail early with a readable message when the env var is absent
    private static String requireEnv(String name) {
        String value = System.getenv(name);
        assertThat(value).as("Environment variable %s must be set (source .env)", name)
            .isNotBlank();
        return value;
    }
}
```

### 7b. Test scenarios — independent, no `@Order`

Each test method is self-contained. `@TestMethodOrder` and `@Order` are omitted; tests
do not depend on each other's side effects.

#### Test 1 — `shouldCreateSentryTransactionForSuccessfulRequest`

```java
@Test
void shouldCreateSentryTransactionForSuccessfulRequest() throws Exception {
    String gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);
    HttpClient http = HttpClient.newHttpClient();

    // Send a request through the gateway — httpbin /get always returns 200
    var response = http.send(
        HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/sentry-it-ok")).build(),
        HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(200);

    // Poll Sentry until the transaction appears (Sentry SDK flushes async)
    // Filter by gravitee.api_id tag — unique to this test run's API
    List<JsonNode> events = sentryClient.pollForTransactions(
        "gravitee.api_id:" + successApiId, Duration.ofSeconds(60));

    assertThat(events).isNotEmpty();
    JsonNode event = events.get(0);
    assertThat(event.path("contexts").path("trace").path("op").asText())
        .isEqualTo("http.server");
    assertThat(event.path("tags").path("gravitee.api_id").asText())
        .isEqualTo(successApiId);
    assertThat(event.path("tags").path("gravitee.api_name").asText())
        .isEqualTo("Sentry IT Success");
}
```

#### Test 2 — `shouldCaptureErrorEventFor5xxResponse`

```java
@Test
void shouldCaptureErrorEventFor5xxResponse() throws Exception {
    String gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);
    HttpClient http = HttpClient.newHttpClient();

    // The error API is backed by httpbin /status/500 — always returns 500
    var response = http.send(
        HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/sentry-it-err")).build(),
        HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(500);

    // Poll Sentry for an error issue with this API's tag
    List<JsonNode> issues = sentryClient.pollForIssues(
        "is:unresolved gravitee.api_id:" + errorApiId, Duration.ofSeconds(60));

    assertThat(issues).isNotEmpty();
    assertThat(issues.get(0).path("level").asText()).isEqualTo("error");
}
```

---

### 7c. `ManagementApiHelper.java`

A dedicated top-level class (not a static inner class) in the `integration` package.
Uses `java.net.http.HttpClient` + `ObjectMapper` for all Management REST calls.

Responsibilities:

1. **`createAndDeployApi(name, path, backendUrl)`** — orchestrates the full lifecycle:
   a. `POST /management/v4/organizations/DEFAULT/environments/DEFAULT/apis`
      → V4 HTTP proxy API definition targeting `backendUrl`; returns `apiId`
   b. `POST .../apis/{apiId}/plans`
      → keyless plan (no API key needed)
   c. `POST .../apis/{apiId}/plans/{planId}/_publish`
   d. `POST .../apis/{apiId}/_start`
   e. `POST .../apis/{apiId}/deployments`
      → triggers gateway sync
   f. Returns `apiId` (UUID string)

2. Each step validates the HTTP response status code and throws an
   `IllegalStateException` with the step name and response body on failure.
   This prevents silent failures where a misconfigured Management API call
   causes the test to fail with a cryptic "no events found" message.

3. Uses `Authorization: Basic <base64(admin:adminadmin)>` — the APIM default credentials.

**V4 API creation request body** (key fields):

```json
{
  "name": "Sentry IT Success",
  "apiVersion": "1.0.0",
  "definitionVersion": "V4",
  "type": "PROXY",
  "listeners": [
    {
      "type": "HTTP",
      "paths": [{ "path": "/sentry-it-ok" }],
      "entrypoints": [{ "type": "http-proxy" }]
    }
  ],
  "endpointGroups": [
    {
      "name": "default",
      "type": "http-proxy",
      "endpoints": [
        {
          "name": "default",
          "type": "http-proxy",
          "configuration": { "target": "http://httpbin:8080/get" }
        }
      ]
    }
  ]
}
```

**Keyless plan request body**:

```json
{
  "name": "Default Plan",
  "definitionVersion": "V4",
  "security": { "type": "KEY_LESS" }
}
```

---

### 7d. `SentryApiClient.java`

A dedicated top-level class in the `integration` package. All Sentry REST calls go
through `java.net.http.HttpClient`; responses are parsed with Jackson's `ObjectMapper`
into `JsonNode` trees (avoids raw `Map<String,Object>` and unchecked casts).

**Sentry API base**: `https://de.sentry.io/api/0`
(The DSN routes to Sentry's EU/DE region — the correct API host is `de.sentry.io`,
not `sentry.io`.)

**Constructor** — discovers org and project slugs eagerly on construction:

```java
SentryApiClient(String token, ObjectMapper mapper) {
    this.token  = token;
    this.mapper = mapper;
    // GET /api/0/projects/ → [{slug, organization: {slug}}, ...]
    // picks the first project; both slugs stored as final fields
    SentryCoordinates coords = discoverCoordinates();
    this.orgSlug     = coords.orgSlug();
    this.projectSlug = coords.projectSlug();
}

private record SentryCoordinates(String orgSlug, String projectSlug) {}
```

Eager discovery makes `SentryApiClient` immediately usable without an explicit
initialisation call, and failures surface at construction time with a clear message.

**Polling methods**:

```java
/**
 * Polls Sentry for transactions matching {@code query} until at least one result
 * is found or {@code timeout} elapses. Polls every 5 seconds using Awaitility.
 *
 * Endpoint: GET /api/0/organizations/{org}/events/
 *           ?query=<query>&dataset=transactions&per_page=5
 */
List<JsonNode> pollForTransactions(String query, Duration timeout);

/**
 * Polls Sentry for issues matching {@code query}.
 * Endpoint: GET /api/0/organizations/{org}/issues/?query=<query>&per_page=5
 */
List<JsonNode> pollForIssues(String query, Duration timeout);
```

Both methods use `Awaitility` internally:

```java
AtomicReference<List<JsonNode>> result = new AtomicReference<>(List.of());
await("Sentry events for: " + query)
    .atMost(timeout)
    .pollInterval(5, SECONDS)
    .until(() -> {
        List<JsonNode> found = fetchFromSentry(query);
        result.set(found);
        return !found.isEmpty();
    });
return result.get();
```

All requests carry `Authorization: Bearer {token}`.

---

## 8. Isolation: why `gravitee.api_id` is the right correlation key

The `MetricsToSentryMapper` always sets `gravitee.api_id` as an indexed Sentry tag on
every transaction and error event. This ID is:

- **Globally unique** — it is the UUID assigned by the Management API when the API is
  created. No other test run will ever produce the same ID.
- **Stable within a run** — the same ID is emitted on every request through that API.
- **Not affected by path sanitization** — the ID never appears in the URL path; it is
  set directly as a tag value.
- **Queryable in Sentry** — indexed tags can be used directly in Sentry search queries:
  `gravitee.api_id:<uuid>`.

This makes every Sentry assertion idempotent: re-running the test suite creates new APIs
with new IDs, so old events from previous runs never interfere.

---

## 9. Step-by-Step Implementation Order

1. **Update `.gitignore`** — add `.env`.

2. **Create `.env`** — DSN and token.

3. **Update `pom.xml`**:
   - Add Testcontainers BOM to `<dependencyManagement>`.
   - Add `testcontainers`, `junit-jupiter`, `mongodb`, `jackson-databind`, `awaitility`
     as `<scope>test</scope>` dependencies.
   - Add `integration-test` profile with failsafe plugin.

4. **Create `gateway-config/gravitee.yml`** — minimal classpath resource (plugins path +
   node service port only).

5. **Create `ManagementApiHelper.java`** — full API lifecycle (create, plan, publish,
   start, deploy), each step with assertion on HTTP status code.

6. **Create `SentryApiClient.java`** — constructor-level discovery, `pollForTransactions`,
   `pollForIssues`, all using `JsonNode`.

7. **Create `SentryReporterIT.java`** — container wiring, `@BeforeAll`, two `@Test`
   methods (independent, no `@Order`).

8. **Run formatter** — `mvn prettier:write license:format` on all new Java files.

9. **Run the test**:
   ```bash
   export $(grep -v '^#' .env | xargs)
   mvn clean verify -Pintegration-test
   ```

---

## 10. Expected Timing

| Phase                            | Duration (approx.) |
|----------------------------------|--------------------|
| `mvn package` (build plugin ZIP) | ~30 s              |
| Container startup (deepStart)    | 2–4 min            |
| API creation + gateway sync      | ~30 s (Awaitility) |
| Test 1 (transaction poll)        | ≤ 60 s             |
| Test 2 (error event poll)        | ≤ 60 s             |
| **Total**                        | **~5–7 min**       |

---

## 11. Assertions Summary

| Test                              | Sentry query                               | Asserted fields                                          |
|-----------------------------------|--------------------------------------------|----------------------------------------------------------|
| `shouldCreateSentry...Transaction`| `gravitee.api_id:{successApiId}`           | `contexts.trace.op == "http.server"`, `tags.gravitee.api_id` |
| `shouldCaptureSentry...Error`     | `is:unresolved gravitee.api_id:{errorApiId}` | `level == "error"`                                     |

---

## 12. Design Decisions

| Decision | Rationale |
|---|---|
| Pure Testcontainers, no docker-compose | Container config is in Java (type-safe, version-injectable, no path fragility). |
| `MongoDBContainer` for MongoDB | Purpose-built wait strategy; no need to tune healthcheck commands. |
| `MountableFile.forHostPath()` for plugin ZIP | Absolute path — no relative-path resolution ambiguity across OS/CWD combinations. |
| `MountableFile.forClasspathResource()` for gravitee.yml | Always found on the test classpath; no filesystem path dependency. |
| Minimal gateway YAML + env vars for the rest | Env vars keep secrets out of committed files; the YAML only captures config that cannot be expressed as env vars (`plugins.path` list). |
| `/_node/health` as gateway healthcheck | Accurate readiness signal from the node's own management API, rather than a gateway 404 on the traffic port. |
| `gravitee.api_id` as Sentry filter | UUID assigned at API creation; globally unique, unaffected by path sanitization, queryable as an indexed Sentry tag. |
| `Awaitility` for all async waits | Replaces `Thread.sleep`; adaptive polling with clear timeout messages and per-condition labels. |
| `JsonNode` for all JSON | Navigable without casting; fails with clear path info on missing fields. |
| `SentryApiClient` eager slug discovery | Surfaces misconfigured tokens at construction time, not buried inside a poll loop. |
| `de.sentry.io` as Sentry API base | DSN uses `ingest.de.sentry.io` — the EU region API host is `de.sentry.io`, not `sentry.io`. |
| `mccutchen/go-httpbin` | Actively maintained; `kennethreitz/httpbin` is abandoned. |
| `@Tag("integration")` | Allows `mvn test` (surefire) to exclude IT tests without the profile. |
| No `@Order` / `@TestMethodOrder` | Tests are independent; ordering would imply a dependency that does not exist. |
