# Refactor Plan — Java 21 / Kotlin-style modernisation

> **Compatibility note**: All changes marked ⚠️ require **Gravitee APIM ≥ 4.7**.
> APIM 4.7 was the first release to ship JDK 21 Docker images. Plugins compiled with
> `--release 21` will not load on earlier JVMs. This is explicitly accepted.

---

## 1. Build — bump to Java 21

**File**: `pom.xml`

- Change `<maven.compiler.release>17</maven.compiler.release>` → `21`. ⚠️
- Change the `<release>17</release>` inside `maven-compiler-plugin` → `21`. ⚠️
- Remove the `lombok` dependency and its `annotationProcessorPaths` block entirely.
  Lombok is currently declared in the pom but **never actually applied** — no source file
  carries a single Lombok annotation. It is dead weight; removing it has zero effect on
  compiled output.

---

## 2. `SentryReporterConfiguration` — no change now, record planned for Spring 6

**File**: `src/main/java/.../config/SentryReporterConfiguration.java`

The class has 10 manually written getters that are pure boilerplate. The obvious fix would
be a Java `record`, but Spring Framework 5.x (shipped with APIM 4.x) does **not** support
`@Value` injection into record canonical constructors — only mutable fields work. Lombok
`@Getter` would also eliminate them, but we just removed Lombok (§1).

**Decision**: leave the class unchanged for now.

> When Gravitee migrates to Spring Framework 6 (expected around APIM 5.x), the path is:
> delete the class, replace with a `@ConfigurationProperties("reporters.sentry")` record,
> and bind it via `@EnableConfigurationProperties` in `PluginConfiguration`. That will
> reduce the file from ~150 lines to ~20 lines.

---

## 3. `SentryReporter` — pattern matching `switch` for dispatch

**File**: `src/main/java/.../SentryReporter.java`

### 3a. `report()` method

Replace the `if/else if` chain with a Java 21 pattern matching `switch` (guarded patterns
with `when` are finalised in Java 21, not just preview as they were in 17): ⚠️

```java
// BEFORE
if (reportable instanceof Metrics metrics) { ... }
else if (reportable instanceof Log log && configuration.isReportLogs()) { ... }
...

// AFTER
switch (reportable) {
    case Metrics metrics          -> Sentry.withScope(scope -> metricsMapper.map(metrics, scope));
    case Log log
        when configuration.isReportLogs()             -> logMapper.map(log);
    case MessageMetrics mm
        when configuration.isReportMessageMetrics()   -> messageMetricsMapper.map(mm);
    case EndpointStatus es
        when configuration.isReportHealthChecks()     -> endpointStatusMapper.map(es);
    default -> { /* not handled */ }
}
```

### 3b. `canHandle()` method

Replace the multi-level boolean expression with a switch expression:

```java
return configuration.isEnabled() && switch (reportable) {
    case Metrics ignored              -> true;
    case Log ignored                  -> configuration.isReportLogs();
    case MessageMetrics ignored       -> configuration.isReportMessageMetrics();
    case EndpointStatus ignored       -> configuration.isReportHealthChecks();
    default                           -> false;
};
```

---

## 4. Mappers — remove `setTagIfNotNull` duplication

**Files**: `MetricsToSentryMapper.java`, `MessageMetricsMapper.java`, `EndpointStatusMapper.java`

All three classes contain identical private-static two-overload pairs:

```java
private static void setTagIfNotNull(ITransaction tx, String key, String value) { ... }
private static void setTagIfNotNull(SentryEvent event, String key, String value) { ... }
```

**Plan**: Create a new `src/main/java/.../mapper/SentryTags.java` package-private utility
class with a single generic helper that accepts a `Consumer<String>`:

```java
// SentryTags.java
static void ifPresent(String value, Consumer<String> setter) {
    if (value != null) setter.accept(value);
}
```

Call sites become:
```java
SentryTags.ifPresent(metrics.getApiId(),   v -> tx.setTag("gravitee.api_id", v));
SentryTags.ifPresent(metrics.getApiName(), v -> tx.setTag("gravitee.api_name", v));
```

This collapses the four duplicated methods (two per file, two files) to a single
six-line class and makes the call sites read like a fluent DSL.

---

## 5. Mappers — `String.formatted()` for message construction

**Files**: `EndpointStatusMapper.java`, `MessageMetricsMapper.java`, `ManagementApiHelper.java`

Replace concatenated string messages with `String.formatted()` (available since Java 15,
no version bump needed, but matches the "Kotlin-like" goal of readability):

```java
// BEFORE — EndpointStatusMapper.buildMessage()
prefix + ": " + status.getEndpoint() + " (api=" + status.getApi() +
    ", responseTime=" + status.getResponseTime() + "ms)"

// AFTER
"%s: %s (api=%s, responseTime=%dms)".formatted(
    prefix, status.getEndpoint(), status.getApi(), status.getResponseTime())
```

```java
// BEFORE — ManagementApiHelper.assertStatus()
"Step '" + step + "' returned HTTP " + response.statusCode() +
    " (expected one of " + Arrays.toString(expected) + "): " + response.body()

// AFTER
"Step '%s' returned HTTP %d (expected one of %s): %s".formatted(
    step, response.statusCode(), Arrays.toString(expected), response.body())
```

---

## 6. Mappers — `Optional` for null-safe enum `.name()` lookups

**Files**: `MetricsToSentryMapper.java`, `MessageMetricsMapper.java`

The pattern `x != null ? x.name() : "UNKNOWN"` appears in every mapper. Replace with:

```java
// BEFORE
String method = metrics.getHttpMethod() != null ? metrics.getHttpMethod().name() : "UNKNOWN";

// AFTER
String method = Optional.ofNullable(metrics.getHttpMethod())
    .map(Enum::name)
    .orElse("UNKNOWN");
```

This reads closer to Kotlin's `?.let { it.name } ?: "UNKNOWN"` / `?.name ?: "UNKNOWN"`.

---

## 7. Integration tests — shared `ApiClient` (axios/httpx-style)

**New file**: `src/test/java/.../integration/ApiClient.java`
**Files affected**: `ManagementApiHelper.java`, `SentryApiClient.java`

`ManagementApiHelper` and `SentryApiClient` are independently solving the same problem:
wrap Java's `HttpClient` with a pre-configured auth header and a base URL. The duplication
is structural:

| Concern | `ManagementApiHelper` | `SentryApiClient` |
|---|---|---|
| Auth | `Basic admin:admin` (hard-coded) | `Bearer <token>` |
| Base URL | `mgmtBase` constructor arg | `https://de.sentry.io/api/0` constant |
| Auth header stamping | inside `post()` each call | inside `bearerRequest()` each call |
| Own `HttpClient` instance | yes | yes |

**Plan**: extract a package-private `ApiClient` record that holds base URL + auth header +
shared `HttpClient`, and exposes clean `get` / `post` methods. No new third-party
dependency needed — this is a thin façade over `java.net.http`.

```java
// ApiClient.java
record ApiClient(HttpClient http, String baseUrl, String authHeader) {

    // Named constructors (like axios.create({ baseURL, auth }))
    static ApiClient basicAuth(String baseUrl, String username, String password) {
        String encoded = Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(UTF_8));
        return new ApiClient(newHttpClient(), baseUrl, "Basic " + encoded);
    }

    static ApiClient bearerToken(String baseUrl, String token) {
        return new ApiClient(newHttpClient(), baseUrl, "Bearer " + token);
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(requestBuilder(path).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String jsonBody) throws Exception {
        var body = jsonBody == null || jsonBody.isBlank()
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(jsonBody);
        return http.send(
            requestBuilder(path).header("Content-Type", "application/json").POST(body).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Authorization", authHeader);
    }

    private static HttpClient newHttpClient() {        // Virtual threads (Java 21) ⚠️ — each IO call gets its own lightweight thread
        return HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    }
}
```

**`ManagementApiHelper` after**: replace the `HttpClient` field + `authHeader` field + the
entire `post()` method body with a single field:

```java
private final ApiClient http = ApiClient.basicAuth(baseUrl, "admin", "admin");
// all post() calls become: http.post(path, body)
```

**`SentryApiClient` after**: replace the `HttpClient` field + `bearerRequest()` method with:

```java
private final ApiClient http = ApiClient.bearerToken(API_BASE, token);
// all fetch calls become: http.get(path)
```

`SentryApiClient` builds full URLs with query params, so the `path` argument passed to
`get()` will be the full URL string (base URL is `""` for those calls), or alternatively
`ApiClient` can be constructed with an empty base URL for those sites and paths become
absolute URLs. Decide during implementation — both work.

**Also**: collapse the two identical `ArrayList` accumulator patterns in `SentryApiClient`:

```java
// BEFORE
List<JsonNode> items = new ArrayList<>();
data.forEach(items::add);
return items;

// AFTER
return StreamSupport.stream(data.spliterator(), false).toList();
```

---

## 8. Tests — `@ParameterizedTest` for `SentryStatusMapperTest`

**File**: `src/test/java/.../mapper/SentryStatusMapperTest.java`

The test has 12 individual `@Test` methods for a pure function with no side effects. This
is the Java equivalent of un-DRY test data. Collapse to a single parameterised test:

```java
@ParameterizedTest(name = "HTTP {0} → {1}")
@CsvSource({
    "200, OK",
    "201, OK",
    "301, OK",
    "400, INVALID_ARGUMENT",
    "401, UNAUTHENTICATED",
    "403, PERMISSION_DENIED",
    "404, NOT_FOUND",
    "409, ALREADY_EXISTS",
    "429, RESOURCE_EXHAUSTED",
    "500, INTERNAL_ERROR",
    "503, INTERNAL_ERROR",
    "418, UNKNOWN_ERROR"
})
void fromHttpStatus(int code, SpanStatus expected) {
    assertThat(SentryStatusMapper.fromHttpStatus(code)).isEqualTo(expected);
}
```

Net effect: 12 test methods → 1 parameterised method; ~50 lines → ~20 lines.

---

## 9. Tests — replace bare `assert` with AssertJ in `EndpointStatusMapperTest`

**File**: `src/test/java/.../mapper/EndpointStatusMapperTest.java`

Four tests use Java's built-in `assert` statement (`assert x.equals(y)`), which:
- is disabled unless the JVM is started with `-ea`
- gives no useful failure message
- is inconsistent with AssertJ used everywhere else

Replace all `assert` calls with AssertJ `assertThat(…).isEqualTo(…)`.

---

## 10. Tests — extract shared `mockTransaction()` helper

**Files**: `MetricsToSentryMapperTest.java`, `MessageMetricsMapperTest.java`

Both test classes contain an identical private `mockTransaction(MockedStatic<Sentry>, SpanStatus)` helper. Two options:

**Option A** (preferred): Extract a `package-private` `SentryTestSupport` utility class in
the test package with a static `mockTransaction()` method. Both tests delegate to it.

**Option B**: Create a shared abstract `AbstractMapperTest` base class both extend (heavier,
but opens door for more shared infrastructure later).

Recommend **Option A** — it avoids inheritance for a utility concern.

---

## 11. Tests — `LogToSentryMapperTest` `truncate` tests → `@ParameterizedTest`

**File**: `src/test/java/.../mapper/LogToSentryMapperTest.java`

The four `truncate_*` tests follow the same table-driven pattern as `SentryStatusMapperTest`.
Consolidate to:

```java
@ParameterizedTest(name = "{0}")
@MethodSource("truncateCases")
void truncate(String input, int max, String expected) {
    assertThat(LogToSentryMapper.truncate(input, max)).isEqualTo(expected);
}

static Stream<Arguments> truncateCases() {
    return Stream.of(
        Arguments.of("hello",       10, "hello"),
        Arguments.of("hello",        5, "hello"),
        Arguments.of("hello world",  5, "hello…"),
        Arguments.of(null,          10, null)
    );
}
```

---

## 12. `PluginConfiguration` — inline bean or keep as-is

**File**: `src/main/java/.../spring/PluginConfiguration.java`

With Lombok `@Getter` added to `SentryReporterConfiguration`, the `@Bean` method remains
trivially simple (`return new SentryReporterConfiguration()`). No change needed here until
Spring 6 / constructor injection is possible.

---

## Execution order

| # | File(s) | Feature used | Risk |
|---|---------|-------------|------|
| 1 | `pom.xml` | Java 21 release ⚠️ | Build-only; validate all tests pass |
| 2 | `SentryReporterConfiguration` | No change (Spring 5.x constraint) | — |
| 3 | `SentryReporter` | Pattern matching switch ⚠️ | Replace if-else exactly, no logic change |
| 4 | New `SentryTags.java` | Functional consumer | Delete 4 duplicated methods |
| 5 | Mappers | `String.formatted()` | Pure string change |
| 6 | Mappers | `Optional.ofNullable` | Pure expression change |
| 7 | New `ApiClient.java` + refactor both helpers | Record façade, virtual threads ⚠️ | Test-only |
| 8 | `SentryStatusMapperTest` | `@ParameterizedTest` | Test-only |
| 9 | `EndpointStatusMapperTest` | AssertJ | Test-only |
| 10 | Two mapper tests | Shared helper | Test-only |
| 11 | `LogToSentryMapperTest` | `@ParameterizedTest` | Test-only |

> After step 1 (Java 21 bump), run `mvn clean verify` to confirm the baseline still
> compiles and all unit tests pass before proceeding with the remaining steps.
