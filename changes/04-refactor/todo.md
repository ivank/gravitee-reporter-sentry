# Refactor Todo — Java 21 / Kotlin-style modernisation

Verification commands used throughout:
- **Format**: `mvn prettier:write license:format`
- **Unit tests**: `mvn test`
- **Full build + unit tests**: `mvn clean verify`
- **Integration tests**: `mvn clean verify -Pintegration-test`

---

## Step 1 — Bump Java to 21, remove unused Lombok

**Files**: `pom.xml`

- [ ] Change `<maven.compiler.release>` from `17` to `21`
- [ ] Change `<release>` inside `maven-compiler-plugin` from `17` to `21`
- [ ] Remove the `lombok` `<dependency>` block
- [ ] Remove the `lombok` entry from `maven-compiler-plugin` `<annotationProcessorPaths>`

**Verify**:
- [ ] `mvn clean verify` — baseline must still compile and all unit tests pass before any further changes

---

## Step 2 — `SentryReporter`: pattern matching switch in `report()`

**File**: `SentryReporter.java`

- [ ] Replace the `if / else if` chain in `report()` with a Java 21 pattern matching `switch` with `when` guards

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — `SentryReporterTest` must pass unchanged

---

## Step 3 — `SentryReporter`: switch expression in `canHandle()`

**File**: `SentryReporter.java`

- [ ] Replace the multi-condition boolean in `canHandle()` with a switch expression

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — all `canHandle_*` tests must pass

---

## Step 4 — Extract `SentryTags` utility, delete duplicated `setTagIfNotNull` overloads

**New file**: `src/main/java/.../mapper/SentryTags.java`
**Files changed**: `MetricsToSentryMapper.java`, `MessageMetricsMapper.java`, `EndpointStatusMapper.java`

- [ ] Create `SentryTags.java` with a single `ifPresent(String value, Consumer<String> setter)` method
- [ ] Replace all `setTagIfNotNull(tx, ...)` call sites in `MetricsToSentryMapper` with `SentryTags.ifPresent`
- [ ] Delete both `setTagIfNotNull` overloads from `MetricsToSentryMapper`
- [ ] Replace all `setTagIfNotNull(tx, ...)` and `setTagIfNotNull(event, ...)` call sites in `MessageMetricsMapper` with `SentryTags.ifPresent`
- [ ] Delete both `setTagIfNotNull` overloads from `MessageMetricsMapper`
- [ ] Replace all `setTagIfNotNull(event, ...)` call sites in `EndpointStatusMapper` with `SentryTags.ifPresent`
- [ ] Delete both `setTagIfNotNull` overloads from `EndpointStatusMapper`

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — all mapper tests must pass

---

## Step 5 — `String.formatted()` for all message construction

**Files**: `EndpointStatusMapper.java`, `MessageMetricsMapper.java`, `ManagementApiHelper.java`

- [ ] Replace string concatenation in `EndpointStatusMapper.buildMessage()` with `String.formatted()`
- [ ] Replace string concatenation in `MessageMetricsMapper.captureErrorEvent()` with `String.formatted()`
- [ ] Replace string concatenation in `ManagementApiHelper.assertStatus()` with `String.formatted()`

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — all tests pass (no logic change, only string construction)

---

## Step 6 — `Optional.ofNullable` for null-safe enum `.name()` lookups

**Files**: `MetricsToSentryMapper.java`, `MessageMetricsMapper.java`

- [ ] Replace `x != null ? x.name() : "UNKNOWN"` ternaries in `MetricsToSentryMapper.map()` with `Optional.ofNullable(x).map(Enum::name).orElse("UNKNOWN")`
- [ ] Replace the same ternary pattern in `MessageMetricsMapper.map()`

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — `map_nullHttpMethod_doesNotThrow` and `map_nullOperation_usesUnknownInTxName` must pass

---

## Step 7 — Extract `ApiClient` record, refactor `ManagementApiHelper` and `SentryApiClient`

**New file**: `src/test/java/.../integration/ApiClient.java`
**Files changed**: `ManagementApiHelper.java`, `SentryApiClient.java`

- [ ] Create `ApiClient.java` as a package-private record with:
  - `basicAuth(baseUrl, username, password)` named constructor
  - `bearerToken(baseUrl, token)` named constructor
  - `get(path)` method
  - `post(path, jsonBody)` method
  - Private `requestBuilder(path)` helper
  - Private `newHttpClient()` using virtual-thread executor (Java 21)
- [ ] Refactor `ManagementApiHelper`:
  - Replace `HttpClient` field + `authHeader` field + `post()` method with a single `ApiClient` field
  - Update all call sites to use `http.post(path, body)`
- [ ] Refactor `SentryApiClient`:
  - Replace `HttpClient` field + `bearerRequest()` method with a single `ApiClient` field
  - Update all call sites to use `http.get(path)`
  - Replace both `ArrayList` accumulator patterns with `StreamSupport.stream(...).toList()`

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — unit tests are unaffected (integration helpers are not under test here)

---

## Step 8 — `SentryStatusMapperTest`: collapse to `@ParameterizedTest`

**File**: `SentryStatusMapperTest.java`

- [ ] Delete the 12 individual `@Test` methods
- [ ] Add a single `@ParameterizedTest` with `@CsvSource` covering all 12 cases

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — 12 test cases must still appear in the Surefire report (as parameterised variants)

---

## Step 9 — `EndpointStatusMapperTest`: replace bare `assert` with AssertJ

**File**: `EndpointStatusMapperTest.java`

- [ ] Replace every `assert x.equals(y)` / `assert x == y` statement with `assertThat(x).isEqualTo(y)`
- [ ] Add `import static org.assertj.core.api.Assertions.assertThat;` if not already present

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — all `EndpointStatusMapperTest` cases must pass and would now fail meaningfully if assertions fire

---

## Step 10 — Extract shared `SentryTestSupport` helper for `mockTransaction()`

**New file**: `src/test/java/.../mapper/SentryTestSupport.java`
**Files changed**: `MetricsToSentryMapperTest.java`, `MessageMetricsMapperTest.java`

- [ ] Create `SentryTestSupport.java` with a package-private static `mockTransaction(MockedStatic<Sentry>, SpanStatus)` method (body copied from either test class)
- [ ] Replace the private `mockTransaction()` in `MetricsToSentryMapperTest` with a delegation to `SentryTestSupport.mockTransaction()`
- [ ] Replace the private `mockTransaction()` in `MessageMetricsMapperTest` with a delegation to `SentryTestSupport.mockTransaction()`

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — all mapper tests must pass

---

## Step 11 — `LogToSentryMapperTest`: collapse `truncate_*` tests to `@ParameterizedTest`

**File**: `LogToSentryMapperTest.java`

- [ ] Delete the four `truncate_*` `@Test` methods
- [ ] Add a single `@ParameterizedTest` with `@MethodSource` and a `truncateCases()` factory method covering all four cases

**Verify**:
- [ ] `mvn prettier:write license:format`
- [ ] `mvn test` — 4 truncate cases must still appear in the Surefire report as parameterised variants

---

## Final — Full integration test

- [ ] `mvn clean verify` — complete build + all unit tests green
- [ ] `mvn clean verify -Pintegration-test` — end-to-end: gateway receives traffic, Sentry receives transaction and error events
  - `shouldCreateSentryTransactionForSuccessfulRequest` passes
  - `shouldCaptureErrorEventFor5xxResponse` passes
