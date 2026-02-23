/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

  private static final Logger LOGGER = LoggerFactory.getLogger(
    MetricsToSentryMapper.class
  );

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
    String method = metrics.getHttpMethod() != null
      ? metrics.getHttpMethod().name()
      : "UNKNOWN";
    String path = sanitizePath(
      metrics.getMappedPath() != null
        ? metrics.getMappedPath()
        : metrics.getUri()
    );
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
      tx.setData(
        "http.request_content_length",
        metrics.getRequestContentLength()
      );
      tx.setData(
        "http.response_content_length",
        metrics.getResponseContentLength()
      );
      tx.setData("net.host.name", metrics.getHost());
      tx.setData("net.peer.ip", metrics.getRemoteAddress());

      // Gravitee-specific searchable tags (indexed in Sentry UI)
      setTagIfNotNull(tx, "gravitee.api_id", metrics.getApiId());
      setTagIfNotNull(tx, "gravitee.api_name", metrics.getApiName());
      setTagIfNotNull(tx, "gravitee.plan_id", metrics.getPlanId());
      setTagIfNotNull(
        tx,
        "gravitee.application_id",
        metrics.getApplicationId()
      );
      setTagIfNotNull(
        tx,
        "gravitee.environment_id",
        metrics.getEnvironmentId()
      );
      setTagIfNotNull(
        tx,
        "gravitee.subscription_id",
        metrics.getSubscriptionId()
      );
      setTagIfNotNull(
        tx,
        "gravitee.security_type",
        metrics.getSecurityType() != null
          ? metrics.getSecurityType().name()
          : null
      );

      // Performance measurements — appear as charts in Sentry Performance dashboard
      tx.setMeasurement(
        "gateway_response_time",
        metrics.getGatewayResponseTimeMs(),
        MeasurementUnit.Duration.MILLISECOND
      );
      tx.setMeasurement(
        "gateway_latency",
        metrics.getGatewayLatencyMs(),
        MeasurementUnit.Duration.MILLISECOND
      );
      tx.setMeasurement(
        "endpoint_response_time",
        metrics.getEndpointResponseTimeMs(),
        MeasurementUnit.Duration.MILLISECOND
      );
      tx.setMeasurement(
        "response_size",
        metrics.getResponseContentLength(),
        MeasurementUnit.Information.BYTE
      );
      tx.setMeasurement(
        "request_size",
        metrics.getRequestContentLength(),
        MeasurementUnit.Information.BYTE
      );

      // Capture an error event for 5xx responses if configured
      if (configuration.isCaptureErrors() && metrics.getStatus() >= 500) {
        captureErrorEvent(metrics, txName);
      }
    } catch (Exception e) {
      // Reporters must never propagate exceptions — gateway stability takes priority.
      LOGGER.warn(
        "Failed to report metrics to Sentry for transaction '{}': {}",
        txName,
        e.getMessage()
      );
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
        (metrics.getErrorKey() != null
            ? " [" + metrics.getErrorKey() + "]"
            : "")
    );
    event.setMessage(msg);

    event.setTag("http.status_code", String.valueOf(metrics.getStatus()));
    setTagIfNotNull(event, "gravitee.api_id", metrics.getApiId());
    setTagIfNotNull(event, "gravitee.api_name", metrics.getApiName());
    setTagIfNotNull(event, "gravitee.error_key", metrics.getErrorKey());

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
    return new SentryNanotimeDate(
      new Date(epochMillis),
      TimeUnit.MILLISECONDS.toNanos(epochMillis)
    );
  }

  private static void setTagIfNotNull(
    ITransaction tx,
    String key,
    String value
  ) {
    if (value != null) {
      tx.setTag(key, value);
    }
  }

  private static void setTagIfNotNull(
    SentryEvent event,
    String key,
    String value
  ) {
    if (value != null) {
      event.setTag(key, value);
    }
  }
}
