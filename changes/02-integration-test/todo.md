# Integration Test — Implementation Checklist

Each section ends with a **verification gate**: a set of commands that must pass cleanly
before moving to the next section. A gate failure means the step is not done.

---

## 0. Prerequisites

- [ ] Docker Desktop (or Docker Engine) is running
  - Verify: `docker info` exits 0 and shows a server version
- [ ] Docker has at least 4 GB of memory allocated (APIM gateway is JVM-heavy)
  - Verify: `docker info | grep "Total Memory"` — should show ≥ 4 GiB
- [ ] Java 17+ is active on PATH
  - Verify: `java -version` reports 17 or higher
- [ ] Maven wrapper or `mvn` is available
  - Verify: `mvn --version` exits 0
- [ ] The project builds and unit tests pass in their current state (baseline)
  - Verify: `mvn clean test` — all tests green, zero build errors

---

## 1. Secrets — `.env` and `.gitignore`

- [ ] Add `.env` to `.gitignore` (new line; do not disturb existing entries)
- [ ] Create `.env` at the project root containing `SENTRY_DSN` and `SENTRY_TOKEN`
  (values from the plan)

**Verification gate 1**

```bash
# 1a. .env is present and readable
cat .env | grep -c "SENTRY_DSN"   # must print 1
cat .env | grep -c "SENTRY_TOKEN" # must print 1

# 1b. Git treats .env as ignored — must print ".env"
git check-ignore -v .env

# 1c. .env does NOT appear in git status (not tracked, not staged)
git status --short | grep -v "^?" | grep ".env" # must produce NO output

# 1d. Existing unit tests still pass (no regressions)
mvn clean test
```

---

## 2. `pom.xml` — Testcontainers BOM, new test dependencies, `integration-test` profile

### 2a. `<dependencyManagement>` — add Testcontainers BOM

- [ ] Add `org.testcontainers:testcontainers-bom:1.20.4` as `<type>pom</type>` `<scope>import</scope>`
  alongside the existing `gravitee-apim-bom` entry

### 2b. `<dependencies>` — add test-scoped libraries

- [ ] `org.testcontainers:testcontainers` (scope: test, version from BOM)
- [ ] `org.testcontainers:junit-jupiter` (scope: test, version from BOM)
- [ ] `org.testcontainers:mongodb` (scope: test, version from BOM)
- [ ] `com.fasterxml.jackson.core:jackson-databind:2.17.2` (scope: test)
- [ ] `org.awaitility:awaitility:4.2.2` (scope: test)

### 2c. `<profiles>` — add `integration-test` profile

- [ ] Profile id: `integration-test`
- [ ] `maven-failsafe-plugin:3.2.5` with goals `integration-test` and `verify`
- [ ] `<includes>` pattern: `**/*IT.java`
- [ ] `<systemPropertyVariables>`: `project.version` → `${project.version}`
- [ ] `<environmentVariables>`: `SENTRY_DSN` → `${env.SENTRY_DSN}`, `SENTRY_TOKEN` → `${env.SENTRY_TOKEN}`
- [ ] `<argLine>`: `-Dnet.bytebuddy.experimental=true` (consistent with surefire config)

**Verification gate 2**

```bash
# 2a. POM is well-formed XML
mvn validate

# 2b. All five new test dependencies resolve without conflict
mvn dependency:resolve -Pintegration-test -q

# 2c. Testcontainers version comes from the BOM, not a hardcoded version attribute
#     (all three lines should show version 1.20.4 and no "version" in the <dependency> tag)
mvn dependency:tree -Pintegration-test -Dincludes=org.testcontainers:

# 2d. No dependency convergence problems introduced
mvn dependency:tree -Pintegration-test | grep "omitted for conflict" # must produce NO output

# 2e. Existing unit tests still compile and pass
mvn clean test

# 2f. Failsafe plugin is visible in the integration-test profile
mvn help:effective-pom -Pintegration-test | grep "failsafe" # must find the plugin
```

---

## 3. Gateway classpath resource — `gravitee.yml`

- [ ] Create `src/test/resources/integration/gateway-config/gravitee.yml`
- [ ] File content: only the `plugins.path` list and the `services.core.http` block
  (everything else is injected via container env vars — do not duplicate config here)
- [ ] Confirm the file is under `src/test/resources/` so it lands on the test classpath
  without any additional resource filtering configuration

**Verification gate 3**

```bash
# 3a. Maven copies the file to the test classpath output directory
mvn process-test-resources -q
ls target/test-classes/integration/gateway-config/gravitee.yml  # must exist

# 3b. The file contains the two expected top-level keys and nothing else
grep -c "^plugins:" target/test-classes/integration/gateway-config/gravitee.yml  # 1
grep -c "^services:" target/test-classes/integration/gateway-config/gravitee.yml # 1

# 3c. Full unit test suite still passes
mvn clean test
```

---

## 4. `ManagementApiHelper.java`

- [ ] Create `src/test/java/io/gravitee/reporter/sentry/integration/ManagementApiHelper.java`
- [ ] Constructor accepts `String mgmtBaseUrl` and `ObjectMapper`
- [ ] Method `createAndDeployApi(String name, String path, String backendUrl)` returns `String` (the API UUID)
- [ ] Implements all five Management REST steps in order:
  - [ ] `POST .../apis` — V4 HTTP proxy definition; asserts response is 201
  - [ ] `POST .../apis/{id}/plans` — KEY_LESS plan; asserts response is 201
  - [ ] `POST .../apis/{id}/plans/{planId}/_publish`; asserts response is 200
  - [ ] `POST .../apis/{id}/_start`; asserts response is 204
  - [ ] `POST .../apis/{id}/deployments`; asserts response is 202 or 204
- [ ] Each step throws `IllegalStateException` with step name + response status + body on failure
- [ ] Uses `Authorization: Basic <base64(admin:adminadmin)>` header on every request
- [ ] Uses `Content-Type: application/json` header on every request
- [ ] Uses Jackson `ObjectMapper` (constructor-injected) to build request bodies and parse response IDs
- [ ] No `System.out.println`; use `LoggerFactory.getLogger(ManagementApiHelper.class)`

**Verification gate 4**

```bash
# 4a. Format and add license header before compiling
mvn prettier:write license:format

# 4b. File compiles cleanly in the IT profile
mvn clean compile test-compile -Pintegration-test -DskipTests

# 4c. Prettier confirms the file is already correctly formatted (idempotent)
mvn prettier:check

# 4d. License header check passes
mvn license:check

# 4e. Existing unit tests still pass
mvn test
```

---

## 5. `SentryApiClient.java`

- [ ] Create `src/test/java/io/gravitee/reporter/sentry/integration/SentryApiClient.java`
- [ ] API base constant: `https://de.sentry.io/api/0` (EU region — matches the DSN)
- [ ] Constructor: `SentryApiClient(String token, ObjectMapper mapper)`
  - [ ] Calls a private `discoverCoordinates()` method
  - [ ] Stores discovered values as `private final String orgSlug` and `private final String projectSlug`
  - [ ] `discoverCoordinates()` calls `GET /api/0/projects/`, parses the JSON array,
    picks the first element, extracts `slug` (project) and `organization.slug` (org)
  - [ ] Throws `IllegalStateException` if the token has no projects (misconfiguration caught early)
- [ ] `List<JsonNode> pollForTransactions(String query, Duration timeout)`:
  - [ ] Uses `GET /api/0/organizations/{orgSlug}/events/` with `?query=<query>&dataset=transactions&per_page=5`
  - [ ] Drives polling via `Awaitility` (`await(...).atMost(timeout).pollInterval(5, SECONDS)`)
  - [ ] Returns all matching `JsonNode` items from the `"data"` array
- [ ] `List<JsonNode> pollForIssues(String query, Duration timeout)`:
  - [ ] Uses `GET /api/0/organizations/{orgSlug}/issues/` with `?query=<query>&per_page=5`
  - [ ] Same Awaitility polling pattern
  - [ ] Returns items from the top-level JSON array
- [ ] All HTTP calls carry `Authorization: Bearer {token}`
- [ ] Uses `java.net.http.HttpClient` (no additional HTTP library needed)
- [ ] Uses `JsonNode` throughout — no `Map<String, Object>` or manual JSON string manipulation
- [ ] No `System.out.println`; use `LoggerFactory.getLogger(SentryApiClient.class)`

**Verification gate 5**

```bash
# 5a. Format and add license header
mvn prettier:write license:format

# 5b. Compiles cleanly
mvn clean compile test-compile -Pintegration-test -DskipTests

# 5c. Prettier and license checks are clean (no drift after format step)
mvn prettier:check
mvn license:check

# 5d. Verify the Sentry token can actually reach the API and has at least one project
#     (manual smoke-test — run once to confirm credentials before the full IT run)
export $(grep -v '^#' .env | xargs)
curl -s -H "Authorization: Bearer $SENTRY_TOKEN" \
  https://de.sentry.io/api/0/projects/ | python3 -m json.tool | grep '"slug"'
# Must print at least one slug line; an empty array means the token is wrong

# 5e. Unit tests still pass
mvn test
```

---

## 6. `SentryReporterIT.java`

- [ ] Create `src/test/java/io/gravitee/reporter/sentry/integration/SentryReporterIT.java`
- [ ] Annotated with `@Tag("integration")` — keeps it out of the surefire unit-test run
- [ ] **No** `@Testcontainers` class annotation — container lifecycle is managed manually
- [ ] **No** `@TestMethodOrder` or `@Order` annotations — tests are independent
- [ ] `private static final Network NETWORK = Network.newNetwork()`
- [ ] Four `private static` container fields (not `@Container`-annotated): `mongodb`,
  `managementApi`, `gateway`, `httpbin`
- [ ] `@BeforeAll static void startInfrastructure()`:
  - [ ] Reads `SENTRY_DSN` and `SENTRY_TOKEN` from `System.getenv()` via a
    private `requireEnv(String name)` helper that calls `assertThat(value).isNotBlank()`
    with a descriptive message
  - [ ] Reads plugin version via `System.getProperty("project.version", "1.0.0-SNAPSHOT")`
  - [ ] Asserts the plugin ZIP path exists (`assertThat(pluginZip).exists()`) before
    any container is started — fail fast with a clear message if `mvn package` was skipped
  - [ ] Wires `MongoDBContainer` with `withNetwork(NETWORK).withNetworkAliases("mongodb")`
  - [ ] Wires `managementApi` `GenericContainer` with correct `dependsOn(mongodb)`,
    env vars, exposed port 8083, and `Wait.forHttp("/management/health").forStatusCode(200)`
    with a 5-minute timeout
  - [ ] Wires `httpbin` `GenericContainer` (`mccutchen/go-httpbin`) with
    `Wait.forHttp("/get").forStatusCode(200)`
  - [ ] Wires `gateway` `GenericContainer` with:
    - [ ] `withCopyFileToContainer(MountableFile.forClasspathResource(...), ...)` for `gravitee.yml`
    - [ ] `withCopyFileToContainer(MountableFile.forHostPath(...), ...)` for plugin ZIP
      using the **absolute** path from `pluginZip.toAbsolutePath()`
    - [ ] All `GRAVITEE_*` env vars from the plan (management URL, reporter config, etc.)
    - [ ] `dependsOn(managementApi)`
    - [ ] `Wait.forHttp("/_node/health").forPort(18082).forStatusCode(200)` with 5-minute timeout
  - [ ] Calls `Startables.deepStart(gateway, httpbin).join()`
  - [ ] Constructs `ManagementApiHelper` and `SentryApiClient` after containers are started
  - [ ] Calls `mgmtHelper.createAndDeployApi(...)` twice — one for success path, one for error path
  - [ ] Stores returned API UUIDs in `private static String successApiId` and `errorApiId`
  - [ ] Uses `Awaitility` (not `Thread.sleep`) to wait until the gateway responds non-404
    on both API paths before recording is complete
- [ ] `@AfterAll static void stopInfrastructure()`:
  - [ ] Stops `gateway`, `httpbin`, `managementApi`, `mongodb` in that order
    (wrapping each in a null-check in case `@BeforeAll` failed mid-way)
  - [ ] Closes `NETWORK`
- [ ] `@Test void shouldCreateSentryTransactionForSuccessfulRequest()`:
  - [ ] Sends `GET /sentry-it-ok` via `java.net.http.HttpClient`
  - [ ] Asserts HTTP response status is 200
  - [ ] Calls `sentryClient.pollForTransactions("gravitee.api_id:" + successApiId, Duration.ofSeconds(60))`
  - [ ] Asserts result list is not empty
  - [ ] Asserts `contexts.trace.op == "http.server"`
  - [ ] Asserts `tags.gravitee.api_id == successApiId`
  - [ ] Asserts `tags.gravitee.api_name == "Sentry IT Success"`
- [ ] `@Test void shouldCaptureErrorEventFor5xxResponse()`:
  - [ ] Sends `GET /sentry-it-err` via `java.net.http.HttpClient`
  - [ ] Asserts HTTP response status is 500
  - [ ] Calls `sentryClient.pollForIssues("is:unresolved gravitee.api_id:" + errorApiId, Duration.ofSeconds(60))`
  - [ ] Asserts result list is not empty
  - [ ] Asserts `level == "error"`

**Verification gate 6**

```bash
# 6a. Format and add license header
mvn prettier:write license:format

# 6b. All three IT classes compile cleanly under the integration-test profile
mvn clean compile test-compile -Pintegration-test -DskipTests

# 6c. Prettier and license checks pass (no drift)
mvn prettier:check
mvn license:check

# 6d. Existing unit tests still pass — IT class must NOT be picked up by surefire
#     (the @Tag("integration") guard + *IT.java naming convention protects this)
mvn clean test
# Expected: surefire reports the same test count as before (no SentryReporterIT listed)

# 6e. Confirm surefire excludes the IT class by name pattern
mvn test -Dtest="SentryReporterIT"
# Expected: "No tests were executed!" (failsafe owns *IT.java, surefire skips it)
```

---

## 7. Final quality gate — static checks on all new files

Run these on the complete set of new and modified files before executing the
integration test. Every command must exit 0.

```bash
# 7a. Whole-project prettier format (idempotent — must produce no diffs)
mvn prettier:write
git diff --exit-code src/  # must print nothing (formatter made no changes)

# 7b. License header check on all sources
mvn license:check

# 7c. Full unit test suite — zero failures, zero errors
mvn clean test

# 7d. Clean package including assembly ZIP (needed for the IT mount)
mvn clean package -DskipTests
ls -lh target/gravitee-reporter-sentry-*.zip  # must exist and be > 0 bytes
```

---

## 8. Integration test run

### 8a. Pre-flight checks (run once before the first IT execution)

```bash
# Docker is running and healthy
docker info > /dev/null

# No stale containers from a previous failed run that might cause port conflicts
docker ps -a --filter "name=gravitee" --filter "name=mongo" --filter "name=httpbin"
# If any are listed: docker rm -f <container_id> to clean up

# .env is present and both variables are set
source .env 2>/dev/null || export $(grep -v '^#' .env | xargs)
[ -n "$SENTRY_DSN" ]   && echo "SENTRY_DSN OK"   || echo "SENTRY_DSN MISSING"
[ -n "$SENTRY_TOKEN" ] && echo "SENTRY_TOKEN OK" || echo "SENTRY_TOKEN MISSING"

# Plugin ZIP is present (built in gate 7d above)
ls target/gravitee-reporter-sentry-*.zip
```

### 8b. Run the integration tests

```bash
export $(grep -v '^#' .env | xargs)
mvn clean verify -Pintegration-test
```

**Expected console output milestones** (in order):

- `[INFO] Building tar: ...` — assembly ZIP created
- `[INFO] --- testcontainers ---` log lines — containers starting
- `Container graviteeio/apim-gateway:4.9 started` — gateway up
- `[INFO] Running ...SentryReporterIT` — failsafe begins
- `Tests run: 2, Failures: 0, Errors: 0` — both tests pass
- `[INFO] BUILD SUCCESS`

### 8c. Expected test output (per test)

```
shouldCreateSentryTransactionForSuccessfulRequest — PASSED
shouldCaptureErrorEventFor5xxResponse             — PASSED
```

If either test fails, failsafe will print a full stack trace. Common failure modes and
their diagnoses are in §9 below.

---

## 9. Post-run verification in Sentry UI

After a successful test run, confirm the events are visible in the Sentry project UI
as a manual sanity check (this is not automated).

- [ ] Log in to https://de.sentry.io and open the project from the DSN
- [ ] **Performance → Transactions**:
  - [ ] Filter by tag `gravitee.api_id:<successApiId>` (copy from the test output log)
  - [ ] Confirm a transaction named `GET /sentry-it-ok` is present
  - [ ] Open the transaction and verify the following measurements exist:
    `gateway_response_time`, `gateway_latency`, `endpoint_response_time`
  - [ ] Verify tags: `gravitee.api_name = Sentry IT Success`, `gravitee.environment_id`
- [ ] **Issues**:
  - [ ] Filter by tag `gravitee.api_id:<errorApiId>`
  - [ ] Confirm an error issue is present with level `error`
  - [ ] Title should contain `HTTP 500 on GET /sentry-it-err`

---

## 10. Failure diagnosis guide

| Symptom | Likely cause | Fix |
|---|---|---|
| `AssertionError: target/gravitee-reporter-sentry-*.zip does not exist` | `mvn package` was skipped | Run `mvn clean package -DskipTests` first, then rerun IT |
| `SENTRY_DSN MISSING` in pre-flight | `.env` not sourced | `export $(grep -v '^#' .env \| xargs)` before `mvn verify` |
| Gateway container exits with code 1 during startup | Plugin ZIP is corrupt or gateway config YAML has syntax errors | Check `docker logs <container_id>`; validate YAML at https://yaml-online-parser.appspot.com |
| `IllegalStateException: step 'create API' returned HTTP 404` | Management API path is wrong for APIM 4.9 | Check the Management API version — may need `/management/v4/` prefix; consult APIM 4.9 REST API docs |
| `Management API not ready after 5 minutes` | MongoDB failed to start; Management API container OOM | `docker logs` on the management-api container; increase Docker memory limit |
| `ConditionTimeoutException: Sentry events for: gravitee.api_id:…` | Sentry SDK did not flush; network cannot reach de.sentry.io | Check gateway container logs for Sentry SDK errors; verify DSN is correct and outbound HTTPS is allowed |
| `401 Unauthorized` from Sentry API | Token expired or wrong scope | Regenerate token with `project:read` and `org:read` scopes in Sentry settings |
| Existing unit tests broken after pom.xml changes | Dependency version conflict introduced | Run `mvn dependency:tree -Pintegration-test | grep "omitted for conflict"` to identify the clash |
