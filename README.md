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

## Distributed trace propagation

When a browser or upstream service sends a request with Sentry trace headers, this reporter
can attach the gateway transaction as a child of the caller's trace so the full
request path appears in a single Sentry waterfall:

```
webapp (project: frontend)
  └── POST /emr/fhir/R4/$graphql  (http.client, 4199ms)
        └── POST /emr/fhir/R4/$graphql  (http.server, 4096ms)  ← gateway span, linked
```

Without propagation, the gateway transaction lands on its own disconnected trace.

The reporter reads two standard headers: `sentry-trace` (carries the parent trace/span ID and
sampling flag) and `baggage` (carries Sentry baggage for the Dynamic Sampling Context). Both are
set automatically by the Sentry browser and server SDKs.

### Option 1 — Enable request-header logging on the API (recommended)

The gateway populates `metrics.log` only when logging is enabled for the API. With logging on,
the reporter reads the headers directly from the incoming request.

In the APIM Console, open the API → **Analytics** → **Logging**, enable logging, and set the
mode to include at minimum **Request headers** (body logging is not required):

```yaml
# Equivalent gravitee.yml / API definition snippet
analytics:
  logging:
    mode:
      entrypoint: true   # capture entrypoint request headers
    content:
      headers: true      # headers are enough — body is optional
      payload: false
```

This is the zero-policy approach: no extra policy is needed on the API.

### Option 2 — Assign Metrics policy (when logging cannot be enabled)

If you cannot enable logging on an API (e.g. to avoid capturing sensitive bodies), add a
**Transform Headers** or **Assign Metrics** policy in the request phase to copy the trace headers
into Gravitee custom metrics. The reporter reads these as a fallback.

**Assign Metrics policy** (APIM v4 policy JSON):

```json
{
  "name": "Assign Metrics",
  "enabled": true,
  "policy": "policy-assign-metrics",
  "configuration": {
    "metrics": [
      {
        "name": "sentry-trace",
        "value": "{#request.headers['sentry-trace'][0]}"
      },
      {
        "name": "baggage",
        "value": "{#request.headers['baggage'][0]}"
      }
    ]
  }
}
```

Add this policy to the **Request** phase of each API you want to trace. The reporter checks
`customMetrics["sentry-trace"]` and `customMetrics["baggage"]` if the log-based path returns
nothing.

### Fallback behaviour

When neither source provides a `sentry-trace` header (logging disabled and no policy configured),
`Sentry.continueTrace` receives `null` and returns a fresh `TransactionContext`. The transaction
is recorded normally but is not linked to the caller's trace. This was the behaviour before
trace propagation was added.

## Linting

using maven prettier plugin

```bash
mvn prettier:write license:format
```

## Building

```bash
# Compile and run all tests
mvn verify

# Run integration tests 
# This will require populating local.properties using local.properties.template
mvn clean verify --activate-profiles integration-test

# Build the distributable plugin ZIP
mvn clean package
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
