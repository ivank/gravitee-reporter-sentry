# Todo Plan: gravitee-reporter-sentry

Execution order is strict: each checkpoint validates the work done so far before proceeding.
Build/validation commands are shown inline. All Maven commands run from the project root.

---

## Phase 1 — Project Skeleton & Build Infrastructure

### 1.1 Create `pom.xml`
- [ ] Set parent: `io.gravitee:gravitee-parent:22.6.0`
- [ ] Set coordinates: `io.gravitee.reporter:gravitee-reporter-sentry:1.0.0-SNAPSHOT`
- [ ] Define properties: `gravitee-apim.version=4.9.0`, `sentry.version=8.33.0`
- [ ] Import BOM: `io.gravitee.apim:gravitee-apim-bom:${gravitee-apim.version}` in `dependencyManagement`
- [ ] Add `gravitee-reporter-api` dependency (`provided`)
- [ ] Add `gravitee-common` dependency (`provided`)
- [ ] Add `gravitee-node-api` dependency (`provided`)
- [ ] Add `spring-context` dependency (`provided`)
- [ ] Add `slf4j-api` dependency (`provided`)
- [ ] Add `io.sentry:sentry:${sentry.version}` dependency (runtime, bundled)
- [ ] Add `junit-jupiter` dependency (`test`)
- [ ] Add `mockito-core` dependency (`test`) — must be ≥5.x for `MockedStatic` support
- [ ] Add `mockito-inline` or confirm Mockito 5 includes inline mocking (needed for static mocking)
- [ ] Add `assertj-core` dependency (`test`)
- [ ] Configure `maven-compiler-plugin`: Java 17 source/target, `--enable-preview` if needed
- [ ] Configure `maven-surefire-plugin`: JUnit 5 provider
- [ ] Configure `maven-dependency-plugin`: copy runtime deps to `target/dependencies/` in `prepare-package` phase
- [ ] Configure `maven-assembly-plugin`: single execution bound to `package` phase using `plugin-assembly.xml`
- [ ] Add `<resources>` filtering for `plugin.properties` so Maven property placeholders resolve

### 1.2 Create resource files
- [ ] Create `src/main/resources/plugin.properties` with Maven-filtered placeholders
- [ ] Create `src/main/resources/gravitee.json` with full JSON schema for Console UI
- [ ] Create `src/main/resources/assembly/plugin-assembly.xml` — ZIP layout with JAR at root + deps in `lib/`

### CHECKPOINT 1 — Verify the build skeleton compiles and packages
```
mvn clean package -DskipTests
```
**Expected**: BUILD SUCCESS, `target/gravitee-reporter-sentry-1.0.0-SNAPSHOT.zip` exists
**Verify ZIP contents**:
```
unzip -l target/gravitee-reporter-sentry-1.0.0-SNAPSHOT.zip
```
Expected entries: `gravitee-reporter-sentry-1.0.0-SNAPSHOT.jar` at root, `lib/sentry-8.33.0.jar` under `lib/`
**Verify `plugin.properties` placeholders resolved**:
```
unzip -p target/gravitee-reporter-sentry-1.0.0-SNAPSHOT.zip gravitee-reporter-sentry-1.0.0-SNAPSHOT.jar | jar tf /dev/stdin | grep plugin.properties
jar xf target/gravitee-reporter-sentry-1.0.0-SNAPSHOT.jar -C /tmp plugin.properties && cat /tmp/plugin.properties
```
Confirm `name=`, `version=`, `description=` are resolved (not literal `${...}`)

---

## Phase 2 — Configuration Class

### 2.1 Create `SentryReporterConfiguration`
- [ ] Create `src/main/java/io/gravitee/reporter/sentry/config/SentryReporterConfiguration.java`
- [ ] Add `@Value` fields for all config keys (`enabled`, `dsn`, `environment`, `release`, `serverName`, `tracesSampleRate`, `captureErrors`, `reportHealthChecks`, `reportLogs`, `reportMessageMetrics`, `debug`)
- [ ] Default `enabled=true`, `tracesSampleRate=1.0`, `captureErrors=true`, `reportHealthChecks=true`, `reportLogs=true`, `reportMessageMetrics=true`, `debug=false`
- [ ] Add getters for all fields (no setters — immutable after Spring injection)
- [ ] Annotate with `@Autowired` on `ConfigurableEnvironment` if needed for future rule lookups

### CHECKPOINT 2 — Verify configuration class compiles clean
```
mvn clean compile
```
Expected: BUILD SUCCESS, zero warnings about missing imports

---

## Phase 3 — Utility: SentryStatusMapper

### 3.1 Create `SentryStatusMapper`
- [ ] Create `src/main/java/io/gravitee/reporter/sentry/mapper/SentryStatusMapper.java`
- [ ] Implement `public static SpanStatus fromHttpStatus(int httpStatus)` method
- [ ] Map ranges: `<400 → OK`, `400 → INVALID_ARGUMENT`, `401 → UNAUTHENTICATED`, `403 → PERMISSION_DENIED`, `404 → NOT_FOUND`, `409 → ALREADY_EXISTS`, `429 → RESOURCE_EXHAUSTED`, `>=500 → INTERNAL_ERROR`, fallback `UNKNOWN_ERROR`

### 3.2 Write `SentryStatusMapperTest`
- [ ] Create `src/test/java/io/gravitee/reporter/sentry/mapper/SentryStatusMapperTest.java`
- [ ] Test case: `200 → SpanStatus.OK`
- [ ] Test case: `201 → SpanStatus.OK`
- [ ] Test case: `301 → SpanStatus.OK`
- [ ] Test case: `400 → SpanStatus.INVALID_ARGUMENT`
- [ ] Test case: `401 → SpanStatus.UNAUTHENTICATED`
- [ ] Test case: `403 → SpanStatus.PERMISSION_DENIED`
- [ ] Test case: `404 → SpanStatus.NOT_FOUND`
- [ ] Test case: `409 → SpanStatus.ALREADY_EXISTS`
- [ ] Test case: `429 → SpanStatus.RESOURCE_EXHAUSTED`
- [ ] Test case: `500 → SpanStatus.INTERNAL_ERROR`
- [ ] Test case: `503 → SpanStatus.INTERNAL_ERROR`
- [ ] Test case: `418 → SpanStatus.UNKNOWN_ERROR` (unrecognised 4xx)

### CHECKPOINT 3 — Run first tests
```
mvn test -pl . -Dtest=SentryStatusMapperTest
```
Expected: all 12 tests pass, BUILD SUCCESS

---

## Phase 4 — Mapper: MetricsToSentryMapper

### 4.1 Create `MetricsToSentryMapper`
- [ ] Create `src/main/java/io/gravitee/reporter/sentry/mapper/MetricsToSentryMapper.java`
- [ ] Constructor takes `SentryReporterConfiguration`
- [ ] Implement `public void map(io.gravitee.reporter.api.v4.metric.Metrics metrics, IScope scope)`
- [ ] Build transaction name: `"${HTTP_METHOD} ${sanitizedPath}"` using `getMappedPath()` falling back to `getUri()`
- [ ] Implement `sanitizePath(String path)` helper: collapse path parameters (e.g. `/users/123` → `/users/{id}`) to reduce cardinality — strip numeric segments
- [ ] Create `TransactionOptions` with `startTimestamp` from `metrics.timestamp()` (convert ms to `SentryDateUtils` format or `java.util.Date`)
- [ ] Call `Sentry.startTransaction(txName, "http.server", opts)`
- [ ] Set `SpanStatus` via `SentryStatusMapper.fromHttpStatus()`
- [ ] Set `setData()` for all OTel semantic HTTP attributes
- [ ] Set `setTag()` for all Gravitee dimensions (`api_id`, `api_name`, `plan_id`, `application_id`, `environment_id`, `subscription_id`, `security_type`)
- [ ] Call `tx.setMeasurement()` with `MeasurementUnit.Duration.MILLISECOND` for latencies
- [ ] Call `tx.setMeasurement()` with `MeasurementUnit.Information.BYTE` for content lengths
- [ ] Call `Sentry.metrics().increment()` for request counter with status+api tags
- [ ] Call `Sentry.metrics().distribution()` for response time histogram
- [ ] Call `tx.finish()` with correct end timestamp
- [ ] If `configuration.isCaptureErrors()` and status >= 500: build `SentryEvent`, set level/message/tags, call `Sentry.captureEvent()`
- [ ] Guard all `getXxx()` calls against `null` (API, method, path can be null in edge cases)

### 4.2 Write `MetricsToSentryMapperTest`
- [ ] Create `src/test/java/io/gravitee/reporter/sentry/mapper/MetricsToSentryMapperTest.java`
- [ ] Use `@ExtendWith(MockitoExtension.class)`
- [ ] Use `MockedStatic<Sentry>` for all tests that verify Sentry API calls
- [ ] Test: HTTP 200 GET creates transaction with name `"GET /path"` and `SpanStatus.OK`
- [ ] Test: HTTP 200 verifies `setMeasurement("gateway_latency", ...)` is called
- [ ] Test: HTTP 200 verifies `Sentry.metrics().distribution(...)` is called for response time
- [ ] Test: HTTP 200 verifies `Sentry.metrics().increment(...)` is called for request counter
- [ ] Test: HTTP 200 verifies `tx.finish()` is called exactly once
- [ ] Test: HTTP 200 verifies `Sentry.captureEvent()` is NOT called (`captureErrors=true` but no error)
- [ ] Test: HTTP 500 verifies `SpanStatus.INTERNAL_ERROR` on transaction
- [ ] Test: HTTP 500 with `captureErrors=true` verifies `Sentry.captureEvent()` is called once
- [ ] Test: HTTP 500 with `captureErrors=false` verifies `Sentry.captureEvent()` is NOT called
- [ ] Test: null `httpMethod` does not throw NPE
- [ ] Test: null `mappedPath` falls back to `uri`
- [ ] Test: `sanitizePath` correctly reduces `/users/123/orders/456` to a stable name (no high-cardinality IDs)

### CHECKPOINT 4 — Run mapper tests
```
mvn test -Dtest=SentryStatusMapperTest,MetricsToSentryMapperTest
```
Expected: all tests pass, BUILD SUCCESS

---

## Phase 5 — Mapper: EndpointStatusMapper

### 5.1 Create `EndpointStatusMapper`
- [ ] Create `src/main/java/io/gravitee/reporter/sentry/mapper/EndpointStatusMapper.java`
- [ ] Implement `public void map(EndpointStatus status, IScope scope)`
- [ ] If `status.isSuccess()`: add an informational breadcrumb only (no event captured)
- [ ] If `!status.isSuccess()`: build `SentryEvent` with level `WARNING` (available) or `ERROR` (unavailable)
- [ ] Set tags: `gravitee.api_id`, `gravitee.api_name`, `gravitee.endpoint`, `gravitee.endpoint.available`
- [ ] Set `Message` with human-readable text
- [ ] Iterate `status.getSteps()` and add each step as a `Breadcrumb` on the event with `response_time_ms` data
- [ ] Call `Sentry.captureEvent(event)` for failures

### 5.2 Write `EndpointStatusMapperTest`
- [ ] Create `src/test/java/io/gravitee/reporter/sentry/mapper/EndpointStatusMapperTest.java`
- [ ] Test: successful health check does NOT call `Sentry.captureEvent()`
- [ ] Test: failed check with `available=true` captures `WARNING` level event
- [ ] Test: failed check with `available=false` captures `ERROR` level event
- [ ] Test: event has correct tags (api_id, endpoint)
- [ ] Test: failed check with steps attaches the correct number of breadcrumbs
- [ ] Test: null `steps` list does not throw NPE
- [ ] Test: null `apiName` / `api` fields handled gracefully

### CHECKPOINT 5 — Run endpoint mapper tests
```
mvn test -Dtest=SentryStatusMapperTest,MetricsToSentryMapperTest,EndpointStatusMapperTest
```
Expected: all tests pass

---

## Phase 6 — Mapper: LogToSentryMapper

### 6.1 Create `LogToSentryMapper`
- [ ] Create `src/main/java/io/gravitee/reporter/sentry/mapper/LogToSentryMapper.java`
- [ ] Implement `public void map(io.gravitee.reporter.api.v4.log.Log log, IScope scope)`
- [ ] Build `Breadcrumb` of type `"http"`, category `"gravitee.request_log"`
- [ ] Add `request_id`, `api_id` data
- [ ] If `clientRequest != null`: add method, uri, body length
- [ ] If `clientResponse != null`: add status code
- [ ] If `proxyRequest != null`: add proxied URI
- [ ] Call `scope.addBreadcrumb(breadcrumb)` (scope-local, not global `Sentry.addBreadcrumb`)
- [ ] Implement `public void mapMessageLog(io.gravitee.reporter.api.v4.log.MessageLog ml, IScope scope)`
- [ ] Build `Breadcrumb` of category `"gravitee.message_log"` with operation, connectorId, connectorType, payload length

### 6.2 Write `LogToSentryMapperTest`
- [ ] Create `src/test/java/io/gravitee/reporter/sentry/mapper/LogToSentryMapperTest.java`
- [ ] Test: `map(log)` calls `scope.addBreadcrumb()` with category `"gravitee.request_log"`
- [ ] Test: null `clientRequest` does not throw NPE
- [ ] Test: null `clientResponse` does not throw NPE
- [ ] Test: breadcrumb data contains `request_id` from log
- [ ] Test: `mapMessageLog()` calls `scope.addBreadcrumb()` with category `"gravitee.message_log"`
- [ ] Test: null `message` field in `MessageLog` does not throw NPE

### CHECKPOINT 6 — Run log mapper tests
```
mvn test -Dtest=SentryStatusMapperTest,MetricsToSentryMapperTest,EndpointStatusMapperTest,LogToSentryMapperTest
```
Expected: all tests pass

---

## Phase 7 — Mapper: MessageMetricsMapper

### 7.1 Create `MessageMetricsMapper`
- [ ] Create `src/main/java/io/gravitee/reporter/sentry/mapper/MessageMetricsMapper.java`
- [ ] Implement `public void map(MessageMetrics metrics, IScope scope)`
- [ ] Build transaction name: `"${operation} ${connectorId}"`
- [ ] Set op to `"message." + operation.name().toLowerCase()` (e.g. `message.subscribe`)
- [ ] Set data: `messaging.connector_id`, `messaging.connector_type`, `messaging.count`, `messaging.error_count`, `messaging.content_length`, `messaging.gateway_latency_ms`
- [ ] Set tags: `gravitee.api_id`, `gravitee.api_name`, `gravitee.environment_id`
- [ ] Set status: `metrics.isError() ? SpanStatus.INTERNAL_ERROR : SpanStatus.OK`
- [ ] Call `tx.finish()`
- [ ] Guard against null `operation` and `connectorType`

### 7.2 Write `MessageMetricsMapperTest`
- [ ] Create `src/test/java/io/gravitee/reporter/sentry/mapper/MessageMetricsMapperTest.java`
- [ ] Test: non-error metrics creates transaction with `SpanStatus.OK`
- [ ] Test: error metrics creates transaction with `SpanStatus.INTERNAL_ERROR`
- [ ] Test: transaction op is `"message.subscribe"` for subscribe operation
- [ ] Test: `tx.finish()` is called exactly once
- [ ] Test: null operation does not throw NPE

### CHECKPOINT 7 — Run all mapper tests
```
mvn test -Dtest="SentryStatusMapperTest,MetricsToSentryMapperTest,EndpointStatusMapperTest,LogToSentryMapperTest,MessageMetricsMapperTest"
```
Expected: all tests pass

---

## Phase 8 — Main Reporter Class

### 8.1 Create `SentryReporter`
- [ ] Create `src/main/java/io/gravitee/reporter/sentry/SentryReporter.java`
- [ ] Extend `AbstractService<Reporter>`, implement `Reporter`
- [ ] Constructor injection of `SentryReporterConfiguration`, `MetricsToSentryMapper`, `LogToSentryMapper`, `MessageMetricsMapper`, `EndpointStatusMapper`
- [ ] Implement `doStart()`:
  - [ ] Return early if `!configuration.isEnabled()`
  - [ ] Log warning and return early if DSN is null or blank
  - [ ] Call `Sentry.init(options -> { ... })` with all config values
  - [ ] Log INFO with env and truncated DSN on success
- [ ] Implement `doStop()`:
  - [ ] Call `Sentry.flush(2000)` to drain the send queue
  - [ ] Call `Sentry.close()`
  - [ ] Log INFO on stop
- [ ] Implement `canHandle(Reportable reportable)`:
  - [ ] Return false if `!configuration.isEnabled() || !isRunning()`
  - [ ] Return true for `v4.metric.Metrics` unconditionally
  - [ ] Return `configuration.isReportLogs()` for `v4.log.Log` and `v4.log.MessageLog`
  - [ ] Return `configuration.isReportMessageMetrics()` for `v4.metric.MessageMetrics`
  - [ ] Return `configuration.isReportHealthChecks()` for `health.EndpointStatus`
  - [ ] Return false for any unrecognised type
- [ ] Implement `report(Reportable reportable)`:
  - [ ] Wrap entire body in `Sentry.withScope(scope -> { ... })`
  - [ ] Dispatch to correct mapper via `instanceof` pattern matching (Java 16+)
  - [ ] Catch `Exception` inside the scope lambda, log with LOGGER.error, do not rethrow
- [ ] Implement private helper `truncateDsn(String dsn)` — returns first 20 chars + `"..."` for log safety

### CHECKPOINT 8 — Full compile check
```
mvn clean compile
```
Expected: BUILD SUCCESS, zero compilation errors

---

## Phase 9 — Spring Wiring

### 9.1 Create `PluginConfiguration`
- [ ] Create `src/main/java/io/gravitee/reporter/sentry/spring/PluginConfiguration.java`
- [ ] Annotate with `@Configuration`
- [ ] Declare `@Bean` for `SentryReporterConfiguration` (no-arg constructor so Spring can inject `@Value` fields)
- [ ] Declare `@Bean` for `MetricsToSentryMapper(SentryReporterConfiguration config)`
- [ ] Declare `@Bean` for `LogToSentryMapper()`
- [ ] Declare `@Bean` for `MessageMetricsMapper()`
- [ ] Declare `@Bean` for `EndpointStatusMapper()`
- [ ] Declare `@Bean` for `SentryReporter(SentryReporterConfiguration, MetricsToSentryMapper, LogToSentryMapper, MessageMetricsMapper, EndpointStatusMapper)`

### CHECKPOINT 9 — Compile check after wiring
```
mvn clean compile
```
Expected: BUILD SUCCESS

---

## Phase 10 — SentryReporter Unit Tests

### 10.1 Write `SentryReporterTest`
- [ ] Create `src/test/java/io/gravitee/reporter/sentry/SentryReporterTest.java`
- [ ] Use `@ExtendWith(MockitoExtension.class)`
- [ ] Mock all five constructor dependencies
- [ ] Test `doStart()` — when DSN is blank: no `Sentry.init()` call (use `MockedStatic`)
- [ ] Test `doStart()` — when disabled: no `Sentry.init()` call
- [ ] Test `doStart()` — when valid DSN: `Sentry.init()` is called with the DSN
- [ ] Test `doStop()` — verifies `Sentry.flush()` then `Sentry.close()` are called in order
- [ ] Test `canHandle()` — returns false when `isEnabled()=false`
- [ ] Test `canHandle()` — returns true for `v4.metric.Metrics` when enabled
- [ ] Test `canHandle()` — returns false for `v4.log.Log` when `reportLogs=false`
- [ ] Test `canHandle()` — returns true for `v4.log.Log` when `reportLogs=true`
- [ ] Test `canHandle()` — returns false for `EndpointStatus` when `reportHealthChecks=false`
- [ ] Test `canHandle()` — returns false for unknown type
- [ ] Test `report()` with `v4.metric.Metrics` — verifies `metricsMapper.map()` is called
- [ ] Test `report()` with `v4.log.Log` — verifies `logMapper.map()` is called
- [ ] Test `report()` with `EndpointStatus` — verifies `endpointStatusMapper.map()` is called
- [ ] Test `report()` with `v4.metric.MessageMetrics` — verifies `messageMetricsMapper.map()` is called
- [ ] Test `report()` — when mapper throws `RuntimeException`, exception does NOT propagate

### CHECKPOINT 10 — Run full test suite
```
mvn test
```
Expected: all tests pass, BUILD SUCCESS, zero test failures or errors
Review test output for any skipped tests

---

## Phase 11 — Full Build & Package Validation

### 11.1 Run complete build with tests
```
mvn clean package
```
Expected: BUILD SUCCESS, all tests pass

### 11.2 Verify ZIP structure is correct
```
unzip -l target/gravitee-reporter-sentry-1.0.0-SNAPSHOT.zip
```
Expected layout:
```
gravitee-reporter-sentry-1.0.0-SNAPSHOT.jar
lib/sentry-8.33.0.jar
lib/<any sentry transitive deps>.jar
```
No Spring, Vert.x, SLF4J, or Gravitee jars should appear in `lib/` (they are `provided`).

### 11.3 Verify plugin descriptor inside the JAR
```
jar tf target/gravitee-reporter-sentry-1.0.0-SNAPSHOT.jar | grep -E "plugin\.properties|gravitee\.json"
```
Expected: both files present

### 11.4 Verify `plugin.properties` has resolved values
Extract and inspect:
```
jar xf target/gravitee-reporter-sentry-1.0.0-SNAPSHOT.jar -C /tmp plugin.properties
cat /tmp/plugin.properties
```
Confirm: `name=Gravitee.io APIM - Reporter - Sentry`, `version=1.0.0-SNAPSHOT`, `class=io.gravitee.reporter.sentry.SentryReporter`

---

## Phase 12 — Code Quality Pass

### 12.1 Check for common issues
```
mvn clean verify
```
- [ ] Confirm zero compiler warnings on `@SuppressWarnings`-free classes
- [ ] Confirm Surefire reports show correct test counts per class

### 12.2 Review for security issues
- [ ] Confirm DSN is never written to logs at INFO level (only truncated hint)
- [ ] Confirm no body content from `Log` or `MessageLog` is logged to SLF4J (could contain PII)
- [ ] Confirm `sendDefaultPii` is NOT set on `SentryOptions` (defaults to false — correct)
- [ ] Confirm `options.setDebug(false)` is the default

### 12.3 Edge-case review
- [ ] Confirm all `getXxx()` calls on Gravitee `Metrics` that can return null are null-guarded before passing to Sentry tag/data setters (Sentry rejects null tag values)
- [ ] Confirm `Sentry.withScope()` lambda catches exceptions — not the outer method
- [ ] Confirm `tx.finish()` is always called even if an exception occurs inside the mapper (use try/finally if needed)

---

## Phase 13 — Documentation

### 13.1 Create README
- [ ] Create `README.md` at project root
- [ ] Document what the plugin does and which Gravitee APIM version it targets
- [ ] Document installation: copy ZIP to `$GRAVITEE_HOME/plugins/`, restart gateway
- [ ] Document all `gravitee.yml` configuration options with types and defaults
- [ ] Document what each reportable type maps to in Sentry (transactions, events, breadcrumbs)
- [ ] Add a sample `gravitee.yml` block

### 13.2 Create `CHANGELOG.md`
- [ ] Add entry for `1.0.0-SNAPSHOT` — initial release

### FINAL CHECKPOINT — End-to-end build from clean state
```
mvn clean package
```
Expected: BUILD SUCCESS, all tests green, ZIP artifact produced with correct structure

---

## Summary of Checkpoints

| Checkpoint | Command | Gate condition |
|---|---|---|
| 1 — Skeleton builds & packages | `mvn clean package -DskipTests` | ZIP exists with correct layout, `plugin.properties` resolved |
| 2 — Config class compiles | `mvn clean compile` | Zero errors |
| 3 — Status mapper tests | `mvn test -Dtest=SentryStatusMapperTest` | 12 tests pass |
| 4 — Metrics mapper tests | `mvn test -Dtest=...,MetricsToSentryMapperTest` | All pass |
| 5 — Endpoint mapper tests | `mvn test -Dtest=...,EndpointStatusMapperTest` | All pass |
| 6 — Log mapper tests | `mvn test -Dtest=...,LogToSentryMapperTest` | All pass |
| 7 — Message mapper tests | `mvn test -Dtest=...,MessageMetricsMapperTest` | All pass |
| 8 — Reporter compiles | `mvn clean compile` | Zero errors |
| 9 — Spring wiring compiles | `mvn clean compile` | Zero errors |
| 10 — Full test suite | `mvn test` | All tests pass, no failures |
| 11 — Final package | `mvn clean package` | BUILD SUCCESS, ZIP correct |
| Final — Clean build | `mvn clean package` | Reproducible from zero state |
