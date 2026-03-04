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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.reporter.api.v4.common.MessageConnectorType;
import io.gravitee.reporter.api.v4.common.MessageOperation;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.sentry.config.SentryReporterConfiguration;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SpanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MessageMetricsMapperTest {

  @Mock
  private SentryReporterConfiguration config;

  private MessageMetricsMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new MessageMetricsMapper(config);
  }

  @Test
  void map_nonErrorMetrics_createsOkTransaction() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      ITransaction mockTx = mockTransaction(sentryMock, SpanStatus.OK);

      MessageMetrics metrics = MessageMetrics.builder()
        .operation(MessageOperation.SUBSCRIBE)
        .connectorId("my-endpoint")
        .apiId("api-1")
        .error(false)
        .gatewayLatencyMs(20L)
        .build();

      mapper.map(metrics);

      sentryMock.verify(() -> Sentry.startTransaction(eq("SUBSCRIBE my-endpoint"), eq("message.broker"), any()));
      verify(mockTx).setStatus(SpanStatus.OK);
      verify(mockTx).finish(any(), any());
    }
  }

  @Test
  void map_errorMetrics_setsInternalErrorStatus() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      ITransaction mockTx = mockTransaction(sentryMock, SpanStatus.INTERNAL_ERROR);

      MessageMetrics metrics = MessageMetrics.builder()
        .operation(MessageOperation.PUBLISH)
        .connectorId("my-endpoint")
        .error(true)
        .build();

      mapper.map(metrics);

      verify(mockTx).setStatus(SpanStatus.INTERNAL_ERROR);
    }
  }

  @Test
  void map_errorMetrics_withCaptureErrors_capturesErrorEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      when(config.isCaptureErrors()).thenReturn(true);
      mockTransaction(sentryMock, SpanStatus.INTERNAL_ERROR);

      MessageMetrics metrics = MessageMetrics.builder()
        .operation(MessageOperation.PUBLISH)
        .connectorId("my-endpoint")
        .apiId("api-2")
        .error(true)
        .errorCount(3L)
        .build();

      mapper.map(metrics);

      sentryMock.verify(() -> Sentry.captureEvent(any(SentryEvent.class)), times(1));
    }
  }

  @Test
  void map_nonErrorMetrics_doesNotCaptureErrorEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      when(config.isCaptureErrors()).thenReturn(true);
      mockTransaction(sentryMock, SpanStatus.OK);

      MessageMetrics metrics = MessageMetrics.builder()
        .operation(MessageOperation.SUBSCRIBE)
        .connectorId("my-endpoint")
        .error(false)
        .build();

      mapper.map(metrics);

      sentryMock.verify(() -> Sentry.captureEvent(any(SentryEvent.class)), never());
    }
  }

  @Test
  void map_nullOperation_usesUnknownInTxName() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      mockTransaction(sentryMock, SpanStatus.OK);

      MessageMetrics metrics = MessageMetrics.builder().operation(null).connectorId("endpoint-x").error(false).build();

      mapper.map(metrics);

      sentryMock.verify(() -> Sentry.startTransaction(eq("UNKNOWN endpoint-x"), anyString(), any()));
    }
  }

  @Test
  void map_transactionIsAlwaysFinished() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      ITransaction mockTx = mockTransaction(sentryMock, SpanStatus.OK);
      doThrow(new RuntimeException("tag error")).when(mockTx).setTag(anyString(), anyString());

      MessageMetrics metrics = MessageMetrics.builder()
        .operation(MessageOperation.SUBSCRIBE)
        .connectorId("endpoint")
        .apiId("api-3")
        .error(false)
        .build();

      mapper.map(metrics);

      verify(mockTx, atLeastOnce()).finish(any(), any());
    }
  }

  private ITransaction mockTransaction(MockedStatic<Sentry> sentryMock, SpanStatus status) {
    return SentryTestSupport.mockTransaction(sentryMock, status);
  }
}
