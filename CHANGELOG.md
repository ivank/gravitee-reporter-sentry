# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased] — 1.0.0-SNAPSHOT

### Added

- **`MetricsToSentryMapper`**: Maps `io.gravitee.reporter.api.v4.metric.Metrics` to a Sentry
  Performance transaction per HTTP request, with OpenTelemetry-compatible span data, Gravitee
  custom tags, and five performance measurements (`gateway_response_time`, `gateway_latency`,
  `endpoint_response_time`, `response_size`, `request_size`).
- **`EndpointStatusMapper`**: Captures Sentry error events on health-check state transitions
  (up→down = ERROR, down→up = INFO); non-transition checks are silently ignored to prevent noise.
- **`LogToSentryMapper`**: Adds request and response breadcrumbs to the Sentry scope when
  `reportLogs=true` (disabled by default). Bodies are truncated at 2 048 characters.
- **`MessageMetricsMapper`**: Maps `v4.metric.MessageMetrics` (Event-native API) to a Sentry
  Performance transaction under the `message.broker` operation.
- **`SentryStatusMapper`**: Pure utility that maps HTTP status codes to Sentry `SpanStatus`
  (follows OTel gRPC status mapping convention).
- **`SentryReporter`**: Main plugin class extending `AbstractService<Reporter>`. Initialises
  the Sentry SDK on `doStart()`, flushes and closes on `doStop()`. Routes each `Reportable`
  type to the appropriate mapper. Never throws from `report()`.
- **`PluginConfiguration`**: Spring `@Configuration` class that wires `SentryReporterConfiguration`
  and `SentryReporter` beans.
- **`SentryReporterConfiguration`**: `@Value`-bound configuration POJO covering DSN, environment,
  release, server name, traces sample rate, error capture, health-check, log, and message-metrics
  toggles.
- `plugin.properties` and `gravitee.json` (JSON Schema for Console UI).
- Maven assembly producing a Gravitee-compatible plugin ZIP
  (`main-jar + lib/sentry-8.3.0.jar`).
- 58 unit tests (JUnit 5 + Mockito 5 `MockedStatic`).

### Dependencies

- Gravitee APIM BOM 4.9.0
- `io.gravitee.reporter:gravitee-reporter-api:2.1.0` (BOM override — provides v4 API types)
- `io.sentry:sentry:8.3.0`
