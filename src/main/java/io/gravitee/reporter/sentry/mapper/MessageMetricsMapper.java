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

import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.sentry.config.SentryReporterConfiguration;
import io.sentry.ITransaction;
import io.sentry.MeasurementUnit;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SpanStatus;
import io.sentry.TransactionOptions;
import io.sentry.protocol.Message;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps Gravitee v4 {@link MessageMetrics} (async/Event-native API telemetry) to Sentry primitives.
 *
 * <p>Each {@link MessageMetrics} represents a batch of messages processed by an endpoint or
 * entrypoint connector. This mapper creates a lightweight Sentry transaction to track
 * message throughput and latency, and optionally captures an error event for error batches.
 */
public class MessageMetricsMapper {

  private static final Logger LOGGER = LoggerFactory.getLogger(MessageMetricsMapper.class);

  private final SentryReporterConfiguration configuration;

  public MessageMetricsMapper(SentryReporterConfiguration configuration) {
    this.configuration = configuration;
  }

  /**
   * Maps a single {@link MessageMetrics} batch to a Sentry transaction and optional error event.
   *
   * @param metrics the async-message metrics batch
   */
  public void map(MessageMetrics metrics) {
    String operation = Optional.ofNullable(metrics.getOperation()).map(Enum::name).orElse("UNKNOWN");
    String connectorId = metrics.getConnectorId() != null ? metrics.getConnectorId() : "unknown";
    String txName = operation + " " + connectorId;

    long startMs = metrics.getTimestamp();
    TransactionOptions opts = new TransactionOptions();
    opts.setStartTimestamp(MetricsToSentryMapper.toSentryDate(startMs));

    ITransaction tx = Sentry.startTransaction(txName, "message.broker", opts);
    try {
      tx.setStatus(metrics.isError() ? SpanStatus.INTERNAL_ERROR : SpanStatus.OK);

      tx.setData("message.operation", operation);
      tx.setData(
        "message.connector_type",
        Optional.ofNullable(metrics.getConnectorType()).map(Enum::name).orElse(null)
      );
      tx.setData("message.connector_id", connectorId);
      tx.setData("message.count", metrics.getCount());
      tx.setData("message.error_count", metrics.getErrorCount());
      tx.setData("message.content_length", metrics.getContentLength());

      SentryTags.ifPresent(metrics.getApiId(), v -> tx.setTag("gravitee.api_id", v));
      SentryTags.ifPresent(metrics.getApiName(), v -> tx.setTag("gravitee.api_name", v));

      tx.setMeasurement("gateway_latency", metrics.getGatewayLatencyMs(), MeasurementUnit.Duration.MILLISECOND);
      // Count metrics have no unit — use the 2-arg overload
      tx.setMeasurement("message_count", metrics.getCountIncrement());
      tx.setMeasurement("error_count", metrics.getErrorCountIncrement());

      if (configuration.isCaptureErrors() && metrics.isError()) {
        captureErrorEvent(metrics, txName);
      }
    } catch (Exception e) {
      LOGGER.warn("Failed to report message metrics to Sentry for '{}': {}", txName, e.getMessage());
    } finally {
      long endMs = startMs + metrics.getGatewayLatencyMs();
      tx.finish(tx.getStatus(), MetricsToSentryMapper.toSentryDate(endMs));
    }
  }

  private void captureErrorEvent(MessageMetrics metrics, String txName) {
    SentryEvent event = new SentryEvent();
    event.setLevel(SentryLevel.ERROR);
    event.setTransaction(txName);

    Message msg = new Message();
    msg.setMessage("Message error on %s (errorCount=%d)".formatted(txName, metrics.getErrorCount()));
    event.setMessage(msg);

    SentryTags.ifPresent(metrics.getApiId(), v -> event.setTag("gravitee.api_id", v));
    SentryTags.ifPresent(metrics.getApiName(), v -> event.setTag("gravitee.api_name", v));

    Sentry.captureEvent(event);
  }
}
