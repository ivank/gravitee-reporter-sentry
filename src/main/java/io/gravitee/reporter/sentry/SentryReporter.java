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
package io.gravitee.reporter.sentry;

import io.gravitee.common.component.Lifecycle;
import io.gravitee.common.service.AbstractService;
import io.gravitee.reporter.api.Reportable;
import io.gravitee.reporter.api.Reporter;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.sentry.config.SentryReporterConfiguration;
import io.gravitee.reporter.sentry.mapper.EndpointStatusMapper;
import io.gravitee.reporter.sentry.mapper.LogToSentryMapper;
import io.gravitee.reporter.sentry.mapper.MessageMetricsMapper;
import io.gravitee.reporter.sentry.mapper.MetricsToSentryMapper;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gravitee APIM Reporter plugin that forwards gateway telemetry to Sentry.io.
 *
 * <p>Handles the following {@link Reportable} types:
 * <ul>
 *   <li>{@link Metrics} — HTTP request/response → Sentry Performance transaction
 *   <li>{@link Log} — Full request/response body → Sentry breadcrumbs (if reportLogs=true)
 *   <li>{@link MessageMetrics} — Async message metrics → Sentry Performance transaction
 *   <li>{@link EndpointStatus} — Health-check results → Sentry error event on state change
 * </ul>
 */
public class SentryReporter extends AbstractService<Reporter> implements Reporter {

  private static final Logger LOGGER = LoggerFactory.getLogger(SentryReporter.class);

  private final SentryReporterConfiguration configuration;
  private final MetricsToSentryMapper metricsMapper;
  private final EndpointStatusMapper endpointStatusMapper;
  private final LogToSentryMapper logMapper;
  private final MessageMetricsMapper messageMetricsMapper;

  public SentryReporter(SentryReporterConfiguration configuration) {
    this.configuration = configuration;
    this.metricsMapper = new MetricsToSentryMapper(configuration);
    this.endpointStatusMapper = new EndpointStatusMapper();
    this.logMapper = new LogToSentryMapper();
    this.messageMetricsMapper = new MessageMetricsMapper(configuration);
  }

  @Override
  protected void doStart() throws Exception {
    super.doStart();

    if (!configuration.isEnabled()) {
      LOGGER.info("Sentry reporter is disabled — no events will be sent.");
      return;
    }

    String dsn = configuration.getDsn();
    if (dsn == null || dsn.isBlank()) {
      LOGGER.warn("Sentry DSN is not configured. Reporter will start but send nothing.");
      return;
    }

    Sentry.init(options -> {
      options.setDsn(dsn);
      options.setEnvironment(configuration.getEnvironment());
      options.setRelease(configuration.getRelease());
      options.setTracesSampleRate(configuration.getTracesSampleRate());
      options.setDebug(configuration.isDebug());
      if (configuration.getServerName() != null && !configuration.getServerName().isBlank()) {
        options.setServerName(configuration.getServerName());
      }
      // Disable auto-session tracking — we are a server-side reporter, not a user-facing app
      options.setEnableAutoSessionTracking(false);
      // Required to flush events on shutdown in a VM that may exit without drain time
      options.setShutdownTimeoutMillis(2000);
      options.setDiagnosticLevel(configuration.isDebug() ? SentryLevel.DEBUG : SentryLevel.ERROR);
    });

    LOGGER.info(
      "Sentry reporter started (env={}, release={}, tracesSampleRate={})",
      configuration.getEnvironment(),
      configuration.getRelease(),
      configuration.getTracesSampleRate()
    );
  }

  @Override
  protected void doStop() throws Exception {
    super.doStop();
    // Flush buffered events before the JVM or classloader shuts down
    Sentry.flush(2000);
    Sentry.close();
    LOGGER.info("Sentry reporter stopped.");
  }

  /**
   * Routes a {@link Reportable} to the appropriate mapper.
   *
   * <p>This method is called on the gateway hot path — it must not throw.
   */
  @Override
  public void report(Reportable reportable) {
    if (lifecycleState() != Lifecycle.State.STARTED || !configuration.isEnabled()) {
      return;
    }

    try {
      if (reportable instanceof Metrics metrics) {
        Sentry.withScope(scope -> metricsMapper.map(metrics, scope));
      } else if (reportable instanceof Log log && configuration.isReportLogs()) {
        logMapper.map(log);
      } else if (reportable instanceof MessageMetrics messageMetrics && configuration.isReportMessageMetrics()) {
        messageMetricsMapper.map(messageMetrics);
      } else if (reportable instanceof EndpointStatus endpointStatus && configuration.isReportHealthChecks()) {
        endpointStatusMapper.map(endpointStatus);
      }
    } catch (Exception e) {
      LOGGER.warn(
        "Unexpected error in Sentry reporter while handling {}: {}",
        reportable.getClass().getSimpleName(),
        e.getMessage()
      );
    }
  }

  @Override
  public boolean canHandle(Reportable reportable) {
    return (
      configuration.isEnabled() &&
      (reportable instanceof Metrics ||
        (reportable instanceof Log && configuration.isReportLogs()) ||
        (reportable instanceof MessageMetrics && configuration.isReportMessageMetrics()) ||
        (reportable instanceof EndpointStatus && configuration.isReportHealthChecks()))
    );
  }
}
