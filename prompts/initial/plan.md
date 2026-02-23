# Implementation Plan: gravitee-reporter-sentry

## Overview

A Gravitee APIM 4.x reporter plugin that forwards API gateway telemetry — HTTP metrics, full
request/response logs, health-check results, and async-message metrics — to Sentry.io using the
official Sentry Java SDK. The plugin follows the established patterns of `gravitee-reporter-file`
(the canonical 4.x reporter) and `gravitee-reporter-kafka` (a community example).

---

## Research Summary

### Gravitee Reporter Plugin Architecture (APIM 4.x)

All reporters implement the `io.gravitee.reporter.api.Reporter` interface, which extends
`io.gravitee.common.service.Service<Reporter>`:

```java
public interface Reporter extends Service<Reporter> {
    void report(Reportable reportable);
    default boolean canHandle(Reportable reportable) { return true; }
}
```

The `Service` interface provides lifecycle methods `start()` / `stop()` backed by abstract hooks
`doStart()` / `doStop()`. Plugins extend `AbstractService<Reporter>` which handles the lifecycle
orchestration. Spring DI wires all beans through a `@Configuration` class in
`io.gravitee.reporter.<name>.spring`.

**Plugin descriptor** (`src/main/resources/plugin.properties`):
```properties
id=sentry
name=${project.name}
version=${project.version}
description=${project.description}
class=io.gravitee.reporter.sentry.SentryReporter
type=reporter
```

**Spring context** is discovered automatically by the Gravitee plugin classloader via
`@Configuration` on the class `io.gravitee.reporter.sentry.spring.PluginConfiguration`.

**Packaging**: `maven-assembly-plugin` produces a ZIP containing the plugin JAR and all
non-`provided` runtime dependencies, which is deployed into `$GRAVITEE_HOME/plugins/`.

### Reportable Types (APIM 4.x)

| Class | Package | What it represents |
|---|---|---|
| `io.gravitee.reporter.api.v4.metric.Metrics` | v4/metric | HTTP request/response metrics (latency, status, size, API id, etc.) |
| `io.gravitee.reporter.api.v4.log.Log` | v4/log | Full request/response body+headers log linked to a Metrics |
| `io.gravitee.reporter.api.v4.metric.MessageMetrics` | v4/metric | Async-message (Event-native API) metrics |
| `io.gravitee.reporter.api.v4.log.MessageLog` | v4/log | Full message body log |
| `io.gravitee.reporter.api.health.EndpointStatus` | health | Health-check probe result (success/failure, response time, steps) |

> Note: The legacy `io.gravitee.reporter.api.http.Metrics` and `io.gravitee.reporter.api.log.Log`
> still exist for APIM 3.x back-compat. The v4 versions are used in APIM 4.9.

### gravitee-reporter-file Patterns (canonical 4.x reference)

- `FileReporter extends AbstractService<Reporter> implements Reporter`
- Constructor injection (not field injection): `FileReporter(FileReporterConfiguration, Vertx, FormatterFactory)`
- `canHandle()` checks `configuration.isEnabled()` + whether the type is registered in a writers map
- `doStart()` initialises Vert.x async writers per `MetricsType`; `doStop()` flushes them
- `PluginConfiguration` (`@Configuration`) declares all beans including `FileReporterConfiguration`
- `FileReporterConfiguration` uses `@Value` Spring annotations and `ConfigurableEnvironment`

### gravitee-reporter-kafka Patterns (community reference)

- Field injection via `@Autowired` with `required = false` guards
- `canHandle()` filters by a configured set of `MessageType` enums
- `doStop()` closes the Kafka producer cleanly
- Older-style plugin: no v4 types, uses `io.gravitee.reporter.api.http.Metrics`

### Sentry Java SDK (v8.x)

**Maven artifact**: `io.sentry:sentry:8.33.0` (latest stable as of Feb 2026)

**Programmatic init** (no properties file — we control DSN, env, release via plugin config):
```java
Sentry.init(options -> {
    options.setDsn(configuration.getDsn());
    options.setEnvironment(configuration.getEnvironment());
    options.setRelease(configuration.getRelease());
    options.setServerName(configuration.getServerName());
    options.setTracesSampleRate(configuration.getTracesSampleRate());
    options.setDebug(configuration.isDebug());
    options.setEnabled(true);
});
```

**Capturing HTTP request metrics as Sentry Transactions** (Performance Monitoring):
```java
ITransaction tx = Sentry.startTransaction(
    metrics.getUri(),          // transaction name, e.g. "GET /api/v1/users"
    "http.server"              // op type following OpenTelemetry conventions
);
tx.setStatus(toSentryStatus(metrics.getStatus()));
tx.setData("http.method", metrics.getHttpMethod().name());
tx.setData("http.status_code", metrics.getStatus());
tx.setData("http.response_content_length", metrics.getResponseContentLength());
tx.setData("gravitee.api_id", metrics.getApiId());
// Use typed measurement units — appear in Sentry Performance dashboard
tx.setMeasurement("gateway_latency", metrics.getGatewayLatencyMs(), MeasurementUnit.Duration.MILLISECOND);
tx.setMeasurement("endpoint_latency", metrics.getEndpointResponseTimeMs(), MeasurementUnit.Duration.MILLISECOND);
tx.setMeasurement("response_size", metrics.getResponseContentLength(), MeasurementUnit.Information.BYTE);
tx.finish();
```

**Aggregate metrics** via `Sentry.metrics()` (counters/distributions — separate from transactions):
```java
// Increment per-API request counter (for throughput dashboards)
Sentry.metrics().increment("gravitee.api.requests", 1.0, null,
    Map.of("api_id", metrics.getApiId(), "status", String.valueOf(metrics.getStatus())));

// Distribution of response times (histogram)
Sentry.metrics().distribution("gravitee.api.response_time", metrics.getGatewayResponseTimeMs(),
    MeasurementUnit.Duration.MILLISECOND,
    Map.of("api_id", metrics.getApiId()));
```

**Thread-safety via `withScope`** — critical on the gateway hot path where `report()` is
called from many concurrent threads. Each call must have an isolated scope:
```java
Sentry.withScope(scope -> {
    scope.setTag("gravitee.api_id", metrics.getApiId());
    // ... create transaction, capture events within this isolated scope
});
```

**Capturing errors** (HTTP 5xx or endpoint health failures):
```java
SentryEvent event = new SentryEvent();
event.setLevel(SentryLevel.ERROR);
Sentry.captureEvent(event);
```

**Tagging events** for filterable dimensions in Sentry UI:
```java
Sentry.configureScope(scope -> {
    scope.setTag("gravitee.api", metrics.getApiName());
    scope.setTag("gravitee.plan", metrics.getPlanId());
    scope.setTag("gravitee.env", metrics.getEnvironmentId());
});
```

---

## Architecture Design

### Package Structure

```
io.gravitee.reporter.sentry
├── SentryReporter.java              # Main Reporter implementation
├── config/
│   └── SentryReporterConfiguration.java   # @Value-injected config bean
├── mapper/
│   ├── MetricsToSentryMapper.java   # Maps v4.Metrics → Sentry Transaction
│   ├── LogToSentryMapper.java       # Maps v4.Log → Sentry breadcrumbs/event
│   ├── MessageMetricsMapper.java    # Maps MessageMetrics → Sentry Transaction
│   ├── EndpointStatusMapper.java    # Maps EndpointStatus → Sentry Event/Check-In
│   └── SentryStatusMapper.java      # HTTP status → SpanStatus utility
└── spring/
    └── PluginConfiguration.java     # Spring @Configuration
```

### Maven Project Layout

```
gravitee-reporter-sentry/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/io/gravitee/reporter/sentry/
│   │   └── resources/
│   │       ├── plugin.properties
│   │       └── assembly/
│   │           └── plugin-assembly.xml
│   └── test/
│       └── java/io/gravitee/reporter/sentry/
│           ├── SentryReporterTest.java
│           ├── mapper/
│           │   ├── MetricsToSentryMapperTest.java
│           │   └── EndpointStatusMapperTest.java
│           └── config/
│               └── SentryReporterConfigurationTest.java
```

---

## Implementation Steps

### Step 1 — Maven POM

**Parent**: `io.gravitee:gravitee-parent:22.6.0` (matches gravitee-reporter-file 4.x)

**Key properties**:
```xml
<gravitee-apim.version>4.9.0</gravitee-apim.version>
<sentry.version>8.33.0</sentry.version>
```

**Dependencies** (all Gravitee deps are `provided` scope — runtime furnishes them):
```xml
<!-- Gravitee APIs (provided) -->
<dependency>
  <groupId>io.gravitee.reporter</groupId>
  <artifactId>gravitee-reporter-api</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>io.gravitee</groupId>
  <artifactId>gravitee-common</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>io.gravitee.node</groupId>
  <artifactId>gravitee-node-api</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>org.springframework</groupId>
  <artifactId>spring-context</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-api</artifactId>
  <scope>provided</scope>
</dependency>

<!-- Sentry SDK (bundled in plugin ZIP) -->
<dependency>
  <groupId>io.sentry</groupId>
  <artifactId>sentry</artifactId>
  <version>${sentry.version}</version>
</dependency>

<!-- Test -->
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.mockito</groupId>
  <artifactId>mockito-core</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.assertj</groupId>
  <artifactId>assertj-core</artifactId>
  <scope>test</scope>
</dependency>
```

**Build plugins**:
- `maven-dependency-plugin`: copies runtime (non-provided) deps to `target/dependencies/`
  in the `prepare-package` phase — feeds the assembly
- `maven-assembly-plugin`: creates `gravitee-reporter-sentry-${version}.zip`
  (references `plugin-assembly.xml` descriptor that bundles the JAR + `lib/*.jar` deps)

**Why this approach**: Gravitee's plugin classloader loads only the classes inside the plugin ZIP.
All `provided` scope deps are already on the gateway classpath (Spring, Vert.x, Gravitee APIs).
Sentry SDK must be bundled because it is not a gateway dependency.

---

### Step 2 — SentryReporterConfiguration

```java
@Component
public class SentryReporterConfiguration {

    @Value("${reporters.sentry.enabled:true}")
    private boolean enabled;

    @Value("${reporters.sentry.dsn:}")
    private String dsn;

    @Value("${reporters.sentry.environment:production}")
    private String environment;

    @Value("${reporters.sentry.release:unknown}")
    private String release;

    @Value("${reporters.sentry.serverName:#{null}}")
    private String serverName;

    /** 0.0 = no tracing; 1.0 = 100% sampling */
    @Value("${reporters.sentry.tracesSampleRate:1.0}")
    private double tracesSampleRate;

    /** Whether to capture HTTP 5xx as Sentry error events (in addition to transactions) */
    @Value("${reporters.sentry.captureErrors:true}")
    private boolean captureErrors;

    /** Whether to report health-check failures as Sentry issues */
    @Value("${reporters.sentry.reportHealthChecks:true}")
    private boolean reportHealthChecks;

    /** Whether to attach request/response log detail as Sentry breadcrumbs */
    @Value("${reporters.sentry.reportLogs:true}")
    private boolean reportLogs;

    /** Whether to report async message metrics */
    @Value("${reporters.sentry.reportMessageMetrics:true}")
    private boolean reportMessageMetrics;

    @Value("${reporters.sentry.debug:false}")
    private boolean debug;

    // getters...
}
```

**Why**: Mirrors the `FileReporterConfiguration` pattern. All settings come from `gravitee.yml`
under `reporters.sentry.*`, following Gravitee's naming convention.

---

### Step 3 — SentryReporter (main class)

```java
public class SentryReporter extends AbstractService<Reporter> implements Reporter {

    private final SentryReporterConfiguration configuration;
    private final MetricsToSentryMapper metricsMapper;
    private final LogToSentryMapper logMapper;
    private final MessageMetricsMapper messageMetricsMapper;
    private final EndpointStatusMapper endpointStatusMapper;

    // Constructor injection (preferred in gravitee-reporter-file style)
    public SentryReporter(SentryReporterConfiguration config,
                          MetricsToSentryMapper metricsMapper,
                          LogToSentryMapper logMapper,
                          MessageMetricsMapper messageMetricsMapper,
                          EndpointStatusMapper endpointStatusMapper) { ... }

    @Override
    protected void doStart() throws Exception {
        if (!configuration.isEnabled()) return;
        if (configuration.getDsn() == null || configuration.getDsn().isBlank()) {
            LOGGER.warn("Sentry reporter is enabled but no DSN configured. Disabling.");
            return;
        }
        Sentry.init(options -> {
            options.setDsn(configuration.getDsn());
            options.setEnvironment(configuration.getEnvironment());
            options.setRelease(configuration.getRelease());
            options.setTracesSampleRate(configuration.getTracesSampleRate());
            options.setDebug(configuration.isDebug());
            if (configuration.getServerName() != null) {
                options.setServerName(configuration.getServerName());
            }
        });
        LOGGER.info("Sentry reporter started (env={}, dsn={}...)",
            configuration.getEnvironment(), truncateDsn(configuration.getDsn()));
    }

    @Override
    protected void doStop() throws Exception {
        // flush(millis) ensures in-flight events are delivered before the SDK closes.
        // Without this, events queued just before shutdown are silently dropped.
        Sentry.flush(2000);
        Sentry.close();
        LOGGER.info("Sentry reporter stopped.");
    }

    @Override
    public boolean canHandle(Reportable reportable) {
        if (!configuration.isEnabled() || !isRunning()) return false;
        if (reportable instanceof io.gravitee.reporter.api.v4.metric.Metrics) return true;
        if (reportable instanceof io.gravitee.reporter.api.v4.log.Log)
            return configuration.isReportLogs();
        if (reportable instanceof io.gravitee.reporter.api.v4.metric.MessageMetrics)
            return configuration.isReportMessageMetrics();
        if (reportable instanceof io.gravitee.reporter.api.v4.log.MessageLog)
            return configuration.isReportLogs();
        if (reportable instanceof io.gravitee.reporter.api.health.EndpointStatus)
            return configuration.isReportHealthChecks();
        return false;
    }

    @Override
    public void report(Reportable reportable) {
        // withScope creates an isolated thread-local scope per call, preventing tag
        // bleed-through between concurrent request threads on the gateway hot path.
        Sentry.withScope(scope -> {
            try {
                if (reportable instanceof io.gravitee.reporter.api.v4.metric.Metrics m) {
                    metricsMapper.map(m, scope);
                } else if (reportable instanceof io.gravitee.reporter.api.v4.log.Log l) {
                    logMapper.map(l, scope);
                } else if (reportable instanceof io.gravitee.reporter.api.v4.metric.MessageMetrics mm) {
                    messageMetricsMapper.map(mm, scope);
                } else if (reportable instanceof io.gravitee.reporter.api.v4.log.MessageLog ml) {
                    logMapper.mapMessageLog(ml, scope);
                } else if (reportable instanceof io.gravitee.reporter.api.health.EndpointStatus es) {
                    endpointStatusMapper.map(es, scope);
                }
            } catch (Exception e) {
                LOGGER.error("Error while reporting to Sentry: {}", e.getMessage(), e);
            }
        });
    }
}
```

**Why**: The `try/catch` in `report()` is critical — the reporter is called on the hot path of
every API request; any unhandled exception would crash the gateway thread. Pattern follows the
approach used in both reference implementations.

---

### Step 4 — Mapper Classes

#### 4a. MetricsToSentryMapper

**Strategy**: Map each HTTP request as a Sentry **Performance Transaction**. This populates
Sentry's Performance dashboard with throughput, latency percentiles, and error rates.

```java
public void map(io.gravitee.reporter.api.v4.metric.Metrics metrics) {
    String txName = metrics.getHttpMethod() + " " + sanitizePath(metrics.getMappedPath());
    TransactionOptions opts = new TransactionOptions();
    opts.setStartTimestamp(SentryDateUtils.nanosToDate(metrics.timestamp()));

    ITransaction tx = Sentry.startTransaction(txName, "http.server", opts);
    tx.setStatus(SentryStatusMapper.fromHttpStatus(metrics.getStatus()));

    // OpenTelemetry semantic conventions for HTTP spans
    tx.setData("http.method", metrics.getHttpMethod() != null ? metrics.getHttpMethod().name() : null);
    tx.setData("http.status_code", metrics.getStatus());
    tx.setData("http.url", metrics.getUri());
    tx.setData("http.request_content_length", metrics.getRequestContentLength());
    tx.setData("http.response_content_length", metrics.getResponseContentLength());

    // Gravitee-specific dimensions (searchable tags)
    tx.setTag("gravitee.api_id", metrics.getApiId());
    tx.setTag("gravitee.api_name", metrics.getApiName());
    tx.setTag("gravitee.plan_id", metrics.getPlanId());
    tx.setTag("gravitee.application_id", metrics.getApplicationId());
    tx.setTag("gravitee.environment_id", metrics.getEnvironmentId());
    tx.setTag("gravitee.subscription_id", metrics.getSubscriptionId());
    tx.setTag("gravitee.security_type",
        metrics.getSecurityType() != null ? metrics.getSecurityType().name() : null);

    // Performance measurements
    tx.setData("gravitee.gateway_latency_ms", metrics.getGatewayLatencyMs());
    tx.setData("gravitee.endpoint_response_time_ms", metrics.getEndpointResponseTimeMs());
    tx.setData("gravitee.gateway_response_time_ms", metrics.getGatewayResponseTimeMs());

    // Finish the transaction with the correct end timestamp
    tx.finish(tx.getStatus(),
        SentryDateUtils.millisToDate(metrics.timestamp() + metrics.getGatewayResponseTimeMs()));

    // Optionally capture errors for 5xx responses
    if (configuration.isCaptureErrors() && metrics.getStatus() >= 500) {
        SentryEvent event = new SentryEvent();
        event.setLevel(SentryLevel.ERROR);
        event.setTransaction(txName);
        event.setTag("http.status_code", String.valueOf(metrics.getStatus()));
        event.setTag("gravitee.api_id", metrics.getApiId());
        event.setTag("gravitee.error_key", metrics.getErrorKey());
        event.setMessage(new Message());
        event.getMessage().setMessage(
            "HTTP " + metrics.getStatus() + " on " + txName + " [" + metrics.getErrorKey() + "]");
        Sentry.captureEvent(event);
    }
}
```

**Why transactions over events**: Sentry Performance Monitoring is the right primitive for
request/response telemetry. Events are for errors. Using transactions enables p50/p95/p99 latency
charts and apdex scoring in the Sentry UI out of the box.

**Why tags vs data**: Tags are indexed and searchable/filterable in Sentry UI. Data (extra context)
is not indexed but is attached to the event for detail. Gravitee API ID, plan, application are
high-cardinality but important for filtering so they go as tags.

#### 4b. LogToSentryMapper

**Strategy**: Attach request/response details as Sentry **Breadcrumbs** on scope, tied to the
transaction by correlation. Full log bodies (potentially large) are stored as event `extras`.

```java
public void map(io.gravitee.reporter.api.v4.log.Log log) {
    Breadcrumb breadcrumb = new Breadcrumb();
    breadcrumb.setType("http");
    breadcrumb.setCategory("gravitee.request_log");
    breadcrumb.setData("request_id", log.getRequestId());
    breadcrumb.setData("api_id", log.getApiId());
    // Client request
    if (log.getClientRequest() != null) {
        breadcrumb.setData("client.method", log.getClientRequest().getMethod());
        breadcrumb.setData("client.uri", log.getClientRequest().getUri());
        breadcrumb.setData("client.body_length",
            log.getClientRequest().getBody() != null
                ? log.getClientRequest().getBody().length() : 0);
    }
    // Proxy response
    if (log.getClientResponse() != null) {
        breadcrumb.setData("response.status", log.getClientResponse().getStatus());
    }
    Sentry.addBreadcrumb(breadcrumb);
}
```

#### 4c. EndpointStatusMapper

**Strategy**: Map health-check failures as Sentry **Error Events** and successes as informational
breadcrumbs. This surfaces endpoint outages as issues in Sentry.

```java
public void map(EndpointStatus status) {
    if (!status.isSuccess()) {
        SentryEvent event = new SentryEvent();
        event.setLevel(status.isAvailable() ? SentryLevel.WARNING : SentryLevel.ERROR);
        event.setTag("gravitee.api_id", status.getApi());
        event.setTag("gravitee.api_name", status.getApiName());
        event.setTag("gravitee.endpoint", status.getEndpoint());
        event.setTag("gravitee.endpoint.available", String.valueOf(status.isAvailable()));

        Message msg = new Message();
        msg.setMessage("Health check failed for endpoint [" + status.getEndpoint() +
            "] on API [" + status.getApiName() + "]");
        event.setMessage(msg);

        // Steps as breadcrumbs
        if (status.getSteps() != null) {
            for (var step : status.getSteps()) {
                Breadcrumb crumb = new Breadcrumb();
                crumb.setMessage(step.getName() + ": " + (step.isSuccess() ? "OK" : "FAIL"));
                crumb.setData("response_time_ms", step.getResponseTime());
                event.addBreadcrumb(crumb);
            }
        }
        Sentry.captureEvent(event);
    }
}
```

#### 4d. MessageMetricsMapper

**Strategy**: Map async message metrics (Event-native APIs) as Sentry Transactions with op type
`message.receive` or `message.publish` following OpenTelemetry messaging conventions.

```java
public void map(MessageMetrics metrics) {
    String txName = metrics.getOperation() + " " + metrics.getConnectorId();
    ITransaction tx = Sentry.startTransaction(txName,
        "message." + metrics.getOperation().name().toLowerCase());
    tx.setData("messaging.connector_id", metrics.getConnectorId());
    tx.setData("messaging.connector_type",
        metrics.getConnectorType() != null ? metrics.getConnectorType().name() : null);
    tx.setData("messaging.count", metrics.getCount());
    tx.setData("messaging.error_count", metrics.getErrorCount());
    tx.setData("messaging.content_length", metrics.getContentLength());
    tx.setData("messaging.gateway_latency_ms", metrics.getGatewayLatencyMs());
    tx.setTag("gravitee.api_id", metrics.getApiId());
    tx.setTag("gravitee.api_name", metrics.getApiName());
    tx.setTag("gravitee.environment_id", metrics.getEnvironmentId());
    tx.setStatus(metrics.isError() ? SpanStatus.INTERNAL_ERROR : SpanStatus.OK);
    tx.finish();
}
```

#### 4e. SentryStatusMapper (utility)

```java
public static SpanStatus fromHttpStatus(int httpStatus) {
    if (httpStatus < 400) return SpanStatus.OK;
    if (httpStatus == 400) return SpanStatus.INVALID_ARGUMENT;
    if (httpStatus == 401) return SpanStatus.UNAUTHENTICATED;
    if (httpStatus == 403) return SpanStatus.PERMISSION_DENIED;
    if (httpStatus == 404) return SpanStatus.NOT_FOUND;
    if (httpStatus == 409) return SpanStatus.ALREADY_EXISTS;
    if (httpStatus == 429) return SpanStatus.RESOURCE_EXHAUSTED;
    if (httpStatus >= 500) return SpanStatus.INTERNAL_ERROR;
    return SpanStatus.UNKNOWN_ERROR;
}
```

---

### Step 5 — Spring PluginConfiguration

```java
@Configuration
public class PluginConfiguration {

    @Bean
    public SentryReporterConfiguration sentryReporterConfiguration() {
        return new SentryReporterConfiguration();
    }

    @Bean
    public MetricsToSentryMapper metricsToSentryMapper(SentryReporterConfiguration config) {
        return new MetricsToSentryMapper(config);
    }

    @Bean
    public LogToSentryMapper logToSentryMapper() {
        return new LogToSentryMapper();
    }

    @Bean
    public MessageMetricsMapper messageMetricsMapper() {
        return new MessageMetricsMapper();
    }

    @Bean
    public EndpointStatusMapper endpointStatusMapper() {
        return new EndpointStatusMapper();
    }

    @Bean
    public SentryReporter sentryReporter(SentryReporterConfiguration config,
                                         MetricsToSentryMapper metricsMapper,
                                         LogToSentryMapper logMapper,
                                         MessageMetricsMapper messageMetricsMapper,
                                         EndpointStatusMapper endpointStatusMapper) {
        return new SentryReporter(config, metricsMapper, logMapper,
            messageMetricsMapper, endpointStatusMapper);
    }
}
```

---

### Step 6 — Plugin Descriptors

`src/main/resources/plugin.properties` (classpath-root, loaded by the plugin classloader):
```properties
id=sentry
name=${project.name}
version=${project.version}
description=${project.description}
class=io.gravitee.reporter.sentry.SentryReporter
type=reporter
```

`src/main/resources/gravitee.json` (optional — enables the Gravitee Console UI to render a
configuration form for this reporter):
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "title": "Sentry Reporter",
  "description": "Reports API gateway metrics, logs and errors to Sentry.io",
  "properties": {
    "enabled": {
      "type": "boolean",
      "title": "Enabled",
      "default": true
    },
    "dsn": {
      "type": "string",
      "title": "Sentry DSN",
      "description": "Sentry project DSN URL"
    },
    "environment": {
      "type": "string",
      "title": "Environment",
      "default": "production"
    },
    "release": {
      "type": "string",
      "title": "Release",
      "default": "unknown"
    },
    "tracesSampleRate": {
      "type": "number",
      "title": "Traces Sample Rate",
      "description": "Fraction of requests to send as Performance transactions (0.0–1.0)",
      "default": 0.1
    },
    "captureErrors": {
      "type": "boolean",
      "title": "Capture HTTP Errors",
      "description": "Send HTTP 5xx responses as Sentry error events",
      "default": true
    },
    "reportHealthChecks": {
      "type": "boolean",
      "title": "Report Health Checks",
      "description": "Send endpoint health-check failures as Sentry issues",
      "default": true
    },
    "reportLogs": {
      "type": "boolean",
      "title": "Report Full Logs",
      "description": "Attach request/response body logs as Sentry breadcrumbs",
      "default": false
    },
    "debug": {
      "type": "boolean",
      "title": "Debug",
      "default": false
    }
  },
  "required": ["dsn"]
}
```

---

### Step 7 — Assembly Descriptor

The ZIP layout must match what Gravitee's plugin classloader expects:
```
gravitee-reporter-sentry-1.0.0.zip
├── gravitee-reporter-sentry-1.0.0.jar   ← main plugin JAR (contains plugin.properties + gravitee.json)
└── lib/
    └── sentry-8.33.0.jar                ← bundled runtime dependencies
```

`src/main/resources/assembly/plugin-assembly.xml`:
```xml
<assembly xmlns="http://maven.apache.org/plugins/maven-assembly-plugin/assembly/1.1.3"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/plugins/maven-assembly-plugin/assembly/1.1.3
              http://maven.apache.org/xsd/assembly-1.1.3.xsd">
  <id>plugin-assembly</id>
  <formats><format>zip</format></formats>
  <includeBaseDirectory>false</includeBaseDirectory>
  <fileSets>
    <!-- The plugin jar itself goes in the root of the ZIP -->
    <fileSet>
      <directory>${project.build.directory}</directory>
      <outputDirectory>.</outputDirectory>
      <includes>
        <include>${project.build.finalName}.jar</include>
      </includes>
    </fileSet>
    <!-- All bundled (non-provided) runtime deps go into lib/ -->
    <fileSet>
      <directory>${project.build.directory}/dependencies</directory>
      <outputDirectory>lib</outputDirectory>
    </fileSet>
  </fileSets>
</assembly>
```

The `maven-dependency-plugin` populates `target/dependencies/` with runtime deps:
```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions>
    <execution>
      <id>copy-dependencies</id>
      <phase>prepare-package</phase>
      <goals><goal>copy-dependencies</goal></goals>
      <configuration>
        <outputDirectory>${project.build.directory}/dependencies</outputDirectory>
        <includeScope>runtime</includeScope>
        <excludeScope>provided</excludeScope>
      </configuration>
    </execution>
  </executions>
</plugin>
```

---

### Step 8 — Tests

#### SentryReporterTest

Uses Mockito to mock `Sentry` static methods via `MockedStatic<Sentry>` (Mockito 5+ / JUnit 5):

```java
@ExtendWith(MockitoExtension.class)
class SentryReporterTest {

    @Mock SentryReporterConfiguration config;
    @Mock MetricsToSentryMapper metricsMapper;
    @Mock LogToSentryMapper logMapper;
    @Mock MessageMetricsMapper messageMetricsMapper;
    @Mock EndpointStatusMapper endpointStatusMapper;

    SentryReporter reporter;

    @BeforeEach void setUp() {
        reporter = new SentryReporter(config, metricsMapper, logMapper,
            messageMetricsMapper, endpointStatusMapper);
    }

    @Test void doStart_whenDsnBlank_shouldNotInitSentry() throws Exception {
        when(config.isEnabled()).thenReturn(true);
        when(config.getDsn()).thenReturn("");
        reporter.doStart(); // should log a warning but not throw
    }

    @Test void canHandle_metrics_whenEnabled_returnsTrue() {
        when(config.isEnabled()).thenReturn(true);
        var metrics = io.gravitee.reporter.api.v4.metric.Metrics.builder().build();
        assertThat(reporter.canHandle(metrics)).isTrue();
    }

    @Test void canHandle_logs_whenLogsDisabled_returnsFalse() {
        when(config.isEnabled()).thenReturn(true);
        when(config.isReportLogs()).thenReturn(false);
        var log = io.gravitee.reporter.api.v4.log.Log.builder().build();
        assertThat(reporter.canHandle(log)).isFalse();
    }

    @Test void report_metrics_delegatesToMapper() {
        var metrics = io.gravitee.reporter.api.v4.metric.Metrics.builder().build();
        reporter.report(metrics);
        verify(metricsMapper).map(metrics);
    }

    @Test void report_endpointStatus_delegatesToMapper() {
        var status = EndpointStatus.builder().success(false).build();
        reporter.report(status);
        verify(endpointStatusMapper).map(status);
    }

    @Test void report_exceptionDoesNotPropagate() {
        doThrow(new RuntimeException("boom")).when(metricsMapper).map(any());
        var metrics = io.gravitee.reporter.api.v4.metric.Metrics.builder().build();
        assertThatCode(() -> reporter.report(metrics)).doesNotThrowAnyException();
    }
}
```

#### MetricsToSentryMapperTest

Uses `MockedStatic<Sentry>` to verify transaction creation:

```java
@Test void map_http200_createsOkTransaction() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
        ITransaction mockTx = mock(ITransaction.class);
        sentryMock.when(() -> Sentry.startTransaction(anyString(), anyString(), any()))
            .thenReturn(mockTx);

        var metrics = io.gravitee.reporter.api.v4.metric.Metrics.builder()
            .httpMethod(HttpMethod.GET)
            .uri("/api/v1/users")
            .status(200)
            .gatewayResponseTimeMs(42L)
            .apiId("api-123")
            .build();

        mapper.map(metrics);

        sentryMock.verify(() -> Sentry.startTransaction(eq("GET /api/v1/users"),
            eq("http.server"), any()));
        verify(mockTx).setStatus(SpanStatus.OK);
        verify(mockTx).finish(any(), any());
    }
}

@Test void map_http500_capturesErrorEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
        ITransaction mockTx = mock(ITransaction.class);
        sentryMock.when(() -> Sentry.startTransaction(anyString(), anyString(), any()))
            .thenReturn(mockTx);
        when(config.isCaptureErrors()).thenReturn(true);

        var metrics = io.gravitee.reporter.api.v4.metric.Metrics.builder()
            .httpMethod(HttpMethod.POST)
            .uri("/api/v1/orders")
            .status(500)
            .build();

        mapper.map(metrics);

        sentryMock.verify(() -> Sentry.captureEvent(any(SentryEvent.class)));
    }
}
```

#### SentryStatusMapperTest

Simple unit test, no mocking needed:
```java
@Test void http200_mapsToOk() {
    assertThat(SentryStatusMapper.fromHttpStatus(200)).isEqualTo(SpanStatus.OK);
}
@Test void http404_mapsToNotFound() {
    assertThat(SentryStatusMapper.fromHttpStatus(404)).isEqualTo(SpanStatus.NOT_FOUND);
}
@Test void http503_mapsToInternalError() {
    assertThat(SentryStatusMapper.fromHttpStatus(503)).isEqualTo(SpanStatus.INTERNAL_ERROR);
}
```

---

## Configuration Example (gravitee.yml)

```yaml
reporters:
  sentry:
    enabled: true
    dsn: https://your-key@o123456.ingest.sentry.io/789
    environment: production
    release: 1.0.0
    serverName: gw-node-1
    tracesSampleRate: 0.1      # Sample 10% of transactions for performance
    captureErrors: true         # Send HTTP 5xx as Sentry issues
    reportHealthChecks: true    # Send endpoint failures as Sentry issues
    reportLogs: false           # Disable full body logs (high volume)
    reportMessageMetrics: true  # Report async message metrics
    debug: false
```

---

## Key Design Decisions

1. **Sentry Transactions for HTTP metrics, not Events**: Transactions are the correct primitive for
   request/response telemetry. They feed Sentry's Performance dashboard automatically.

2. **Constructor injection over @Autowired field injection**: Follows `gravitee-reporter-file` 4.x
   pattern; makes classes testable without a Spring context.

3. **Mapper classes separate from Reporter**: The `report()` method is on the hot path of every
   gateway request; keeping it lean and delegating to mappers improves testability and
   separation of concerns.

4. **Guard against exceptions in report()**: Any uncaught exception in `report()` would propagate
   into the gateway's request processing thread. The try/catch wrapper with LOGGER.error is
   essential.

5. **No Vert.x async buffering**: Unlike the file reporter which uses Vert.x async file writers,
   Sentry's SDK already handles its own async event queue internally. We call Sentry APIs
   synchronously in the calling thread; the SDK dispatches to its transport on a background thread.

6. **`Sentry.withScope()` in `report()`**: The gateway calls `report()` from many concurrent
   request threads. Using `withScope()` creates a throw-away isolated scope for each call,
   preventing tag/context state leaking between concurrent requests. This is essential
   for correctness in a multi-threaded environment.

7. **tracesSampleRate configurable**: In production, sending 100% of transactions to Sentry would
   be expensive. Default to 1.0 for development, users should lower for production (0.1 = 10%).

8. **v4 API types**: Target `io.gravitee.reporter.api.v4.*` types to be compatible with APIM 4.x
   (both HTTP and Event-native APIs). No legacy v3 types needed.

9. **Sentry.close() in doStop()**: Ensures the Sentry SDK flushes its event queue before the
   plugin is unloaded, preventing data loss during graceful shutdown.

10. **Dual reporting: Transactions + Metrics API**: Transactions feed the Sentry Performance
    dashboard (per-request latency, throughput, apdex). The `Sentry.metrics()` API (counters,
    distributions) provides aggregate views. Both are complementary and should be used together.

11. **`BeforeSend` hook** (optional, advanced): `options.setBeforeSend((event, hint) -> ...)` can
    be exposed as a configurable filter (e.g. to drop events for specific API IDs or status codes).
    Not in scope for v1 but noted for the future.

---

## Files to Create (in order)

1. `pom.xml`
2. `src/main/resources/plugin.properties`
3. `src/main/resources/gravitee.json`
4. `src/main/resources/assembly/plugin-assembly.xml`
5. `src/main/java/io/gravitee/reporter/sentry/config/SentryReporterConfiguration.java`
6. `src/main/java/io/gravitee/reporter/sentry/mapper/SentryStatusMapper.java`
7. `src/main/java/io/gravitee/reporter/sentry/mapper/MetricsToSentryMapper.java`
8. `src/main/java/io/gravitee/reporter/sentry/mapper/LogToSentryMapper.java`
9. `src/main/java/io/gravitee/reporter/sentry/mapper/MessageMetricsMapper.java`
10. `src/main/java/io/gravitee/reporter/sentry/mapper/EndpointStatusMapper.java`
11. `src/main/java/io/gravitee/reporter/sentry/SentryReporter.java`
12. `src/main/java/io/gravitee/reporter/sentry/spring/PluginConfiguration.java`
13. `src/test/java/io/gravitee/reporter/sentry/SentryReporterTest.java`
14. `src/test/java/io/gravitee/reporter/sentry/mapper/MetricsToSentryMapperTest.java`
15. `src/test/java/io/gravitee/reporter/sentry/mapper/EndpointStatusMapperTest.java`
16. `src/test/java/io/gravitee/reporter/sentry/mapper/SentryStatusMapperTest.java`

---

## Open Questions / Risks

- **Sentry SDK classloader isolation**: Sentry SDK uses static state (`Sentry.init()`). If the
  gateway loads multiple plugins using Sentry (unlikely but possible), there may be conflicts.
  Mitigation: document that the DSN and options are global per JVM.

- **gravitee-reporter-api exact version for 4.9**: The pom.xml from `gravitee-reporter-file` uses
  `gravitee-apim.version=4.10.0-alpha.4` as a BOM import. For APIM 4.9 we should use
  `4.9.x` BOM. Exact artifact versions should be verified against Maven Central before the build.

- **v4.metric.Metrics vs legacy http.Metrics**: APIM 4.9 should send v4 types. Need to confirm
  that `MetricsType` enum in `gravitee-reporter-common` covers both v4 and legacy types if
  the plugin must support gateways running mixed API types.

- **Thread safety of Sentry static APIs**: `Sentry.startTransaction()` and `Sentry.captureEvent()`
  are documented as thread-safe. The Sentry SDK uses a hub-per-thread model. No additional
  synchronization is needed in our mappers.

---

*Sources:*
- [gravitee-reporter-file](https://github.com/gravitee-io/gravitee-reporter-file) (4.0.1)
- [gravitee-reporter-kafka](https://github.com/gravitee-io-community/gravitee-reporter-kafka) (1.4.0)
- [gravitee-reporter-api](https://github.com/gravitee-io/gravitee-reporter-api)
- [Sentry Java SDK docs](https://docs.sentry.io/platforms/java/)
- [Sentry Java tracing](https://docs.sentry.io/platforms/java/tracing/instrumentation/custom-instrumentation/)
- [Sentry Maven artifact](https://github.com/getsentry/sentry-java/releases) (8.33.0)
