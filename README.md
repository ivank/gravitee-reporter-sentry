# gravitee-reporter-sentry

A [Gravitee APIM](https://www.gravitee.io/) reporter plugin that forwards API gateway
telemetry to [Sentry.io](https://sentry.io/).

## What it does

| Gravitee event                                     | Sentry output                                                                           |
| -------------------------------------------------- | --------------------------------------------------------------------------------------- |
| HTTP request/response (`v4.metric.Metrics`)        | Performance **Transaction** (p50/p95/p99 latency charts) + optional error event for 5xx |
| Full request/response body (`v4.log.Log`)          | **Breadcrumbs** on the global scope (opt-in, off by default)                            |
| Async-message metrics (`v4.metric.MessageMetrics`) | Performance **Transaction** per message batch                                           |
| Health-check result (`health.EndpointStatus`)      | **Error event** on state transition (up→down = ERROR, down→up = INFO)                   |

## Requirements

| Component                 | Minimum | Notes                                                                 |
| ------------------------- | ------- | --------------------------------------------------------------------- |
| Gravitee APIM             | 4.7     | First release to ship JDK 21 gateway images                           |
| JDK                       | 21      | Uses Java 21 language features (pattern matching switch, text blocks) |
| Sentry Java SDK (bundled) | 8.3.0   |                                                                       |

## Installation

### Download from GitHub Releases (recommended)

Replace `1.0.0` with the version you want, then run:

```bash
VERSION=1.0.0
curl -fsSL \
  "https://github.com/ivank/gravitee-reporter-sentry/releases/download/v${VERSION}/gravitee-reporter-sentry-${VERSION}.zip" \
  -o gravitee-reporter-sentry.zip
cp gravitee-reporter-sentry.zip $GRAVITEE_HOME/plugins/
```

Or as a one-liner that always fetches the latest release:

```bash
curl -fsSL \
  "https://github.com/ivank/gravitee-reporter-sentry/releases/latest/download/gravitee-reporter-sentry.zip" \
  -o "$GRAVITEE_HOME/plugins/gravitee-reporter-sentry.zip"
```

Restart the gateway after copying.

### Build from source

```bash
mvn clean package -DskipTests
cp target/gravitee-reporter-sentry-*.zip $GRAVITEE_HOME/plugins/
```

## Configuration

Add the following block to `gravitee.yml`:

```yaml
reporters:
  sentry:
    enabled: true
    dsn: https://YOUR_KEY@oXXXXXX.ingest.sentry.io/YOUR_PROJECT_ID
    environment: production        # Sentry environment tag
    release: 1.0.0                 # Application version
    serverName:                    # Override server name (default: auto-detect hostname)
    tracesSampleRate: 0.1          # Fraction of requests to sample (0.0–1.0). Use 0.1 in production.
    captureErrors: true            # Send HTTP 5xx + message errors as Sentry error events
    reportHealthChecks: true       # Send endpoint health-check state changes to Sentry
    reportLogs: false              # Attach request/response bodies as breadcrumbs (HIGH VOLUME)
    reportMessageMetrics: true     # Report async-message (Event-native API) metrics
    debug: false                   # Enable Sentry SDK debug logging (verbose)
```

### Key settings

- **`dsn`** (required): Your Sentry project DSN. Find it under *Settings → Projects → [Project] → Client Keys*.
- **`tracesSampleRate`**: Set to `0.1` (10%) or lower in production. 1.0 = 100% of all requests become Sentry transactions.
- **`reportLogs`**: Disabled by default. Enable only in dev/staging — it sends full request and response bodies to Sentry.
- **`captureErrors`**: When true, 5xx responses are captured as Sentry **issues** (appear in the Issues tab) in addition to the performance transaction.

## Sentry Performance dashboard

Transactions produced by this reporter follow
[OpenTelemetry semantic conventions](https://opentelemetry.io/docs/reference/specification/trace/semantic_conventions/http/):

| Sentry field     | Value                                                                                                 |
| ---------------- | ----------------------------------------------------------------------------------------------------- |
| Transaction name | `{METHOD} {sanitized-path}` e.g. `GET /users/{id}/orders`                                             |
| Operation        | `http.server`                                                                                         |
| Tags             | `gravitee.api_id`, `gravitee.plan_id`, `gravitee.application_id`, `gravitee.subscription_id`, …       |
| Measurements     | `gateway_response_time`, `gateway_latency`, `endpoint_response_time`, `response_size`, `request_size` |

Numeric IDs and UUIDs in paths are replaced with `{id}` to reduce cardinality
(e.g. `/users/123/orders` → `/users/{id}/orders`).

## Linting

using maven prettier plugin

```bash
mvn prettier:write license:format
```

## Building

```bash
# Compile and run all tests
mvn verify

# Build the distributable plugin ZIP
mvn clean package
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
