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
