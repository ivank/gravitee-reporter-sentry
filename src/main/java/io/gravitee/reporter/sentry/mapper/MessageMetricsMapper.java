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
    String operation = metrics.getOperation() != null ? metrics.getOperation().name() : "UNKNOWN";
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
        metrics.getConnectorType() != null ? metrics.getConnectorType().name() : null
      );
      tx.setData("message.connector_id", connectorId);
      tx.setData("message.count", metrics.getCount());
      tx.setData("message.error_count", metrics.getErrorCount());
      tx.setData("message.content_length", metrics.getContentLength());

      setTagIfNotNull(tx, "gravitee.api_id", metrics.getApiId());
      setTagIfNotNull(tx, "gravitee.api_name", metrics.getApiName());
      setTagIfNotNull(tx, "gravitee.environment_id", metrics.getEnvironmentId());

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
    msg.setMessage("Message error on " + txName + " (errorCount=" + metrics.getErrorCount() + ")");
    event.setMessage(msg);

    setTagIfNotNull(event, "gravitee.api_id", metrics.getApiId());
    setTagIfNotNull(event, "gravitee.api_name", metrics.getApiName());

    Sentry.captureEvent(event);
  }

  private static void setTagIfNotNull(ITransaction tx, String key, String value) {
    if (value != null) {
      tx.setTag(key, value);
    }
  }

  private static void setTagIfNotNull(SentryEvent event, String key, String value) {
    if (value != null) {
      event.setTag(key, value);
    }
  }
}
