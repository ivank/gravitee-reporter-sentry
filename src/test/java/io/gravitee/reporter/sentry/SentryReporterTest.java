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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.sentry.config.SentryReporterConfiguration;
import io.sentry.Sentry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SentryReporterTest {

  @Mock
  private SentryReporterConfiguration config;

  private SentryReporter reporter;

  @BeforeEach
  void setUp() throws Exception {
    when(config.isEnabled()).thenReturn(true);
    when(config.getDsn()).thenReturn("https://key@sentry.io/123");
    when(config.getEnvironment()).thenReturn("test");
    when(config.getRelease()).thenReturn("1.0.0");
    when(config.getTracesSampleRate()).thenReturn(1.0);
    when(config.isDebug()).thenReturn(false);

    reporter = new SentryReporter(config);
    try (MockedStatic<Sentry> ignored = mockStatic(Sentry.class)) {
      reporter.start();
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    try (MockedStatic<Sentry> ignored = mockStatic(Sentry.class)) {
      reporter.stop();
    }
  }

  // --- canHandle tests ---

  @Test
  void canHandle_metrics_returnsTrue() {
    Metrics metrics = Metrics.builder().build();
    assertThat(reporter.canHandle(metrics)).isTrue();
  }

  @Test
  void canHandle_log_withReportLogsFalse_returnsFalse() {
    when(config.isReportLogs()).thenReturn(false);
    Log log = Log.builder().build();
    assertThat(reporter.canHandle(log)).isFalse();
  }

  @Test
  void canHandle_log_withReportLogsTrue_returnsTrue() {
    when(config.isReportLogs()).thenReturn(true);
    Log log = Log.builder().build();
    assertThat(reporter.canHandle(log)).isTrue();
  }

  @Test
  void canHandle_messageMetrics_withReportMessageMetricsFalse_returnsFalse() {
    when(config.isReportMessageMetrics()).thenReturn(false);
    MessageMetrics messageMetrics = new MessageMetrics();
    assertThat(reporter.canHandle(messageMetrics)).isFalse();
  }

  @Test
  void canHandle_messageMetrics_withReportMessageMetricsTrue_returnsTrue() {
    when(config.isReportMessageMetrics()).thenReturn(true);
    MessageMetrics messageMetrics = new MessageMetrics();
    assertThat(reporter.canHandle(messageMetrics)).isTrue();
  }

  @Test
  void canHandle_endpointStatus_withReportHealthChecksTrue_returnsTrue() {
    when(config.isReportHealthChecks()).thenReturn(true);
    EndpointStatus status = EndpointStatus.builder().build();
    assertThat(reporter.canHandle(status)).isTrue();
  }

  @Test
  void canHandle_endpointStatus_withReportHealthChecksFalse_returnsFalse() {
    when(config.isReportHealthChecks()).thenReturn(false);
    EndpointStatus status = EndpointStatus.builder().build();
    assertThat(reporter.canHandle(status)).isFalse();
  }

  @Test
  void canHandle_disabledReporter_returnsFalse() {
    when(config.isEnabled()).thenReturn(false);
    Metrics metrics = Metrics.builder().build();
    assertThat(reporter.canHandle(metrics)).isFalse();
  }

  // --- report routing tests ---

  @Test
  void report_whenDisabled_doesNothing() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      when(config.isEnabled()).thenReturn(false);

      reporter.report(Metrics.builder().build());

      sentryMock.verifyNoInteractions();
    }
  }

  @Test
  void report_metrics_callsWithScope() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      Metrics metrics = Metrics.builder().status(200).build();

      reporter.report(metrics);

      sentryMock.verify(() -> Sentry.withScope(any()), atLeastOnce());
    }
  }

  @Test
  void report_log_whenReportLogsFalse_doesNotCallAddBreadcrumb() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      when(config.isReportLogs()).thenReturn(false);
      Log log = Log.builder().build();

      reporter.report(log);

      sentryMock.verify(
        () -> Sentry.addBreadcrumb(any(io.sentry.Breadcrumb.class)),
        never()
      );
    }
  }
}
