/*
 * Copyright 2026 Ivan Kerin (http://github.com/ivank)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.gravitee.reporter.sentry.mapper;

import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.sentry.config.SentryReporterConfiguration;
import io.sentry.IScope;
import io.sentry.ITransaction;
import io.sentry.MeasurementUnit;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryNanotimeDate;
import io.sentry.SpanStatus;
import io.sentry.TransactionOptions;
import io.sentry.protocol.Message;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps Gravitee v4 {@link Metrics} (HTTP request/response telemetry) to Sentry primitives:
 *
 * <ul>
 *   <li>A Sentry Performance <em>Transaction</em> per request, enabling p50/p95/p99 latency charts.
 *   <li>An optional Sentry error <em>Event</em> for HTTP 5xx responses.
 * </ul>
 */
public class MetricsToSentryMapper {

  private static final Logger LOGGER = LoggerFactory.getLogger(MetricsToSentryMapper.class);

  /**
   * Matches path segments that look like numeric IDs or UUIDs so they can be replaced with
   * a placeholder, reducing transaction name cardinality.
   * e.g. /users/123/orders → /users/{id}/orders
   */
  private static final Pattern ID_SEGMENT = Pattern.compile(
    "(?<=/|^)([0-9]+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?=/|$)"
  );

  private final SentryReporterConfiguration configuration;

  public MetricsToSentryMapper(SentryReporterConfiguration configuration) {
    this.configuration = configuration;
  }

  /**
   * Maps a single {@link Metrics} object to a Sentry transaction and optional error event.
   *
   * @param metrics the gateway request metrics
   * @param scope   the isolated Sentry scope for this request (prevents tag bleed between threads)
   */
  public void map(Metrics metrics, IScope scope) {
    String method = Optional.ofNullable(metrics.getHttpMethod()).map(Enum::name).orElse("UNKNOWN");
    String path = sanitizePath(metrics.getMappedPath() != null ? metrics.getMappedPath() : metrics.getUri());
    String txName = method + " " + (path != null ? path : "/");

    long startMs = metrics.getTimestamp();
    TransactionOptions opts = new TransactionOptions();
    opts.setStartTimestamp(toSentryDate(startMs));

    ITransaction tx = Sentry.startTransaction(txName, "http.server", opts);
    try {
      tx.setStatus(SentryStatusMapper.fromHttpStatus(metrics.getStatus()));

      // OpenTelemetry semantic conventions for HTTP server spans
      tx.setData("http.method", method);
      tx.setData("http.status_code", metrics.getStatus());
      tx.setData("http.url", metrics.getUri());
      tx.setData("http.request_content_length", metrics.getRequestContentLength());
      tx.setData("http.response_content_length", metrics.getResponseContentLength());
      tx.setData("net.host.name", metrics.getHost());
      tx.setData("net.peer.ip", metrics.getRemoteAddress());

      // Gravitee-specific searchable tags (indexed in Sentry UI)
      SentryTags.ifPresent(metrics.getApiId(), v -> tx.setTag("gravitee.api_id", v));
      SentryTags.ifPresent(metrics.getApiName(), v -> tx.setTag("gravitee.api_name", v));
      SentryTags.ifPresent(metrics.getPlanId(), v -> tx.setTag("gravitee.plan_id", v));
      SentryTags.ifPresent(metrics.getApplicationId(), v -> tx.setTag("gravitee.application_id", v));
      SentryTags.ifPresent(metrics.getSubscriptionId(), v -> tx.setTag("gravitee.subscription_id", v));
      SentryTags.ifPresent(Optional.ofNullable(metrics.getSecurityType()).map(Enum::name).orElse(null), v ->
        tx.setTag("gravitee.security_type", v)
      );

      // Performance measurements — appear as charts in Sentry Performance dashboard
      tx.setMeasurement(
        "gateway_response_time",
        metrics.getGatewayResponseTimeMs(),
        MeasurementUnit.Duration.MILLISECOND
      );
      tx.setMeasurement("gateway_latency", metrics.getGatewayLatencyMs(), MeasurementUnit.Duration.MILLISECOND);
      tx.setMeasurement(
        "endpoint_response_time",
        metrics.getEndpointResponseTimeMs(),
        MeasurementUnit.Duration.MILLISECOND
      );
      tx.setMeasurement("response_size", metrics.getResponseContentLength(), MeasurementUnit.Information.BYTE);
      tx.setMeasurement("request_size", metrics.getRequestContentLength(), MeasurementUnit.Information.BYTE);

      // Capture an error event for 5xx responses if configured
      if (configuration.isCaptureErrors() && metrics.getStatus() >= 500) {
        captureErrorEvent(metrics, txName);
      }
    } catch (Exception e) {
      // Reporters must never propagate exceptions — gateway stability takes priority.
      LOGGER.warn("Failed to report metrics to Sentry for transaction '{}': {}", txName, e.getMessage());
    } finally {
      // Always finish the transaction — even if an exception occurred above
      long endMs = startMs + metrics.getGatewayResponseTimeMs();
      tx.finish(tx.getStatus(), toSentryDate(endMs));
    }
  }

  private void captureErrorEvent(Metrics metrics, String txName) {
    SentryEvent event = new SentryEvent();
    event.setLevel(SentryLevel.ERROR);
    event.setTransaction(txName);

    Message msg = new Message();
    msg.setMessage(
      "HTTP " +
        metrics.getStatus() +
        " on " +
        txName +
        (metrics.getErrorKey() != null ? " [" + metrics.getErrorKey() + "]" : "")
    );
    event.setMessage(msg);

    event.setTag("http.status_code", String.valueOf(metrics.getStatus()));
    SentryTags.ifPresent(metrics.getApiId(), v -> event.setTag("gravitee.api_id", v));
    SentryTags.ifPresent(metrics.getApiName(), v -> event.setTag("gravitee.api_name", v));
    SentryTags.ifPresent(metrics.getErrorKey(), v -> event.setTag("gravitee.error_key", v));

    Sentry.captureEvent(event);
  }

  /**
   * Replaces numeric path segments and UUIDs with {@code {id}} to reduce cardinality.
   * Returns {@code null}-safe: if path is null, returns null.
   */
  static String sanitizePath(String path) {
    if (path == null) {
      return null;
    }
    return ID_SEGMENT.matcher(path).replaceAll("{id}");
  }

  /**
   * Converts a Unix epoch millisecond timestamp to Sentry's {@link SentryNanotimeDate}.
   */
  static SentryNanotimeDate toSentryDate(long epochMillis) {
    return new SentryNanotimeDate(new Date(epochMillis), TimeUnit.MILLISECONDS.toNanos(epochMillis));
  }
}
