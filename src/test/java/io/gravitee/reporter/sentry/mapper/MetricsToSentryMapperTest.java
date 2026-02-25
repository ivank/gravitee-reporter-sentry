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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.sentry.config.SentryReporterConfiguration;
import io.sentry.IScope;
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
class MetricsToSentryMapperTest {

  @Mock
  private SentryReporterConfiguration config;

  @Mock
  private IScope scope;

  private MetricsToSentryMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new MetricsToSentryMapper(config);
  }

  @Test
  void map_http200Get_createsOkTransactionWithCorrectName() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      ITransaction mockTx = mockTransaction(sentryMock, SpanStatus.OK);

      Metrics metrics = Metrics.builder()
        .httpMethod(HttpMethod.GET)
        .uri("/api/v1/users")
        .mappedPath("/api/v1/users")
        .status(200)
        .gatewayResponseTimeMs(42L)
        .apiId("api-123")
        .build();

      mapper.map(metrics, scope);

      sentryMock.verify(() -> Sentry.startTransaction(eq("GET /api/v1/users"), eq("http.server"), any()));
      verify(mockTx).setStatus(SpanStatus.OK);
      verify(mockTx).finish(any(), any());
    }
  }

  @Test
  void map_http200_setsMeasurements() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      ITransaction mockTx = mockTransaction(sentryMock, SpanStatus.OK);

      Metrics metrics = Metrics.builder()
        .httpMethod(HttpMethod.GET)
        .uri("/api/v1/test")
        .status(200)
        .gatewayResponseTimeMs(100L)
        .gatewayLatencyMs(10L)
        .endpointResponseTimeMs(80L)
        .responseContentLength(512L)
        .requestContentLength(128L)
        .build();

      mapper.map(metrics, scope);

      verify(mockTx).setMeasurement(eq("gateway_response_time"), eq(100L), any());
      verify(mockTx).setMeasurement(eq("response_size"), eq(512L), any());
    }
  }

  @Test
  void map_http200_doesNotCaptureErrorEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      when(config.isCaptureErrors()).thenReturn(true);
      mockTransaction(sentryMock, SpanStatus.OK);

      Metrics metrics = Metrics.builder().httpMethod(HttpMethod.GET).uri("/api/v1/ok").status(200).build();

      mapper.map(metrics, scope);

      sentryMock.verify(() -> Sentry.captureEvent(any(SentryEvent.class)), never());
    }
  }

  @Test
  void map_http500_setsInternalErrorStatus() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      ITransaction mockTx = mockTransaction(sentryMock, SpanStatus.INTERNAL_ERROR);

      Metrics metrics = Metrics.builder().httpMethod(HttpMethod.POST).uri("/api/v1/orders").status(500).build();

      mapper.map(metrics, scope);

      verify(mockTx).setStatus(SpanStatus.INTERNAL_ERROR);
    }
  }

  @Test
  void map_http500_withCaptureErrors_capturesErrorEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      when(config.isCaptureErrors()).thenReturn(true);
      mockTransaction(sentryMock, SpanStatus.INTERNAL_ERROR);

      Metrics metrics = Metrics.builder()
        .httpMethod(HttpMethod.POST)
        .uri("/api/v1/orders")
        .status(500)
        .apiId("api-abc")
        .build();

      mapper.map(metrics, scope);

      sentryMock.verify(() -> Sentry.captureEvent(any(SentryEvent.class)), times(1));
    }
  }

  @Test
  void map_http500_withCaptureErrorsFalse_doesNotCaptureEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      when(config.isCaptureErrors()).thenReturn(false);
      mockTransaction(sentryMock, SpanStatus.INTERNAL_ERROR);

      Metrics metrics = Metrics.builder().httpMethod(HttpMethod.POST).uri("/api/v1/orders").status(500).build();

      mapper.map(metrics, scope);

      sentryMock.verify(() -> Sentry.captureEvent(any(SentryEvent.class)), never());
    }
  }

  @Test
  void map_nullHttpMethod_doesNotThrow() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      mockTransaction(sentryMock, SpanStatus.OK);

      Metrics metrics = Metrics.builder().uri("/api/v1/test").status(200).build();

      // must not throw
      mapper.map(metrics, scope);
    }
  }

  @Test
  void map_nullMappedPath_fallsBackToUri() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      mockTransaction(sentryMock, SpanStatus.OK);

      Metrics metrics = Metrics.builder()
        .httpMethod(HttpMethod.DELETE)
        .uri("/fallback/path")
        .mappedPath(null)
        .status(204)
        .build();

      mapper.map(metrics, scope);

      sentryMock.verify(() -> Sentry.startTransaction(eq("DELETE /fallback/path"), eq("http.server"), any()));
    }
  }

  @Test
  void map_transactionIsAlwaysFinished_evenOnException() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      ITransaction mockTx = mockTransaction(sentryMock, SpanStatus.OK);
      // Simulate an exception during tag-setting
      doThrow(new RuntimeException("tag error")).when(mockTx).setTag(anyString(), anyString());

      Metrics metrics = Metrics.builder()
        .httpMethod(HttpMethod.GET)
        .uri("/api/v1/test")
        .status(200)
        .apiId("api-123")
        .build();

      mapper.map(metrics, scope);

      // finish must still be called
      verify(mockTx, atLeastOnce()).finish(any(), any());
    }
  }

  // --- sanitizePath tests ---

  @Test
  void sanitizePath_numericSegment_replacedWithPlaceholder() {
    assertThat(MetricsToSentryMapper.sanitizePath("/users/123/orders")).isEqualTo("/users/{id}/orders");
  }

  @Test
  void sanitizePath_uuid_replacedWithPlaceholder() {
    assertThat(MetricsToSentryMapper.sanitizePath("/apis/550e8400-e29b-41d4-a716-446655440000/keys")).isEqualTo(
      "/apis/{id}/keys"
    );
  }

  @Test
  void sanitizePath_noIds_unchanged() {
    assertThat(MetricsToSentryMapper.sanitizePath("/api/v1/users")).isEqualTo("/api/v1/users");
  }

  @Test
  void sanitizePath_null_returnsNull() {
    assertThat(MetricsToSentryMapper.sanitizePath(null)).isNull();
  }

  // Helper: sets up Sentry.startTransaction mock to return a transaction stub
  private ITransaction mockTransaction(MockedStatic<Sentry> sentryMock, SpanStatus status) {
    ITransaction mockTx = mock(ITransaction.class);
    when(mockTx.getStatus()).thenReturn(status);
    sentryMock.when(() -> Sentry.startTransaction(anyString(), anyString(), any())).thenReturn(mockTx);
    return mockTx;
  }
}
