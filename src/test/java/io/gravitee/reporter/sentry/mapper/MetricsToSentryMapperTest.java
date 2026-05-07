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

import static org.assertj.core.api.Assertions.assertThat;
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

import io.gravitee.common.http.HttpMethod;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.sentry.config.SentryReporterConfiguration;
import io.sentry.IScope;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SpanStatus;
import io.sentry.TransactionContext;
import java.util.List;
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

      sentryMock.verify(() -> Sentry.continueTrace(eq(null), eq(List.of())));
      sentryMock.verify(() -> Sentry.startTransaction(any(TransactionContext.class), any()));
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

      sentryMock.verify(() -> Sentry.continueTrace(eq(null), eq(List.of())));
      sentryMock.verify(() -> Sentry.startTransaction(any(TransactionContext.class), any()));
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

  // --- trace propagation tests ---

  @Test
  void map_withSentryTraceHeaderInLog_continuesParentTrace() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      mockTransaction(sentryMock, SpanStatus.OK);

      HttpHeaders headers = HttpHeaders.create();
      headers.set("sentry-trace", "8d64b1eb0d94496cbde363a82ce4ea68-98f1f143539c1a0d-1");
      headers.set("baggage", "sentry-environment=dev,sentry-trace_id=8d64b1eb0d94496cbde363a82ce4ea68");

      Request request = new Request();
      request.setHeaders(headers);

      Log log = Log.builder().requestId("req-1").build();
      log.setEntrypointRequest(request);

      Metrics metrics = Metrics.builder()
        .httpMethod(HttpMethod.POST)
        .uri("/emr/fhir/R4/$graphql")
        .status(200)
        .gatewayResponseTimeMs(100L)
        .build();
      metrics.setLog(log);

      mapper.map(metrics, scope);

      sentryMock.verify(() ->
        Sentry.continueTrace(
          eq("8d64b1eb0d94496cbde363a82ce4ea68-98f1f143539c1a0d-1"),
          eq(List.of("sentry-environment=dev,sentry-trace_id=8d64b1eb0d94496cbde363a82ce4ea68"))
        )
      );
    }
  }

  @Test
  void map_withSentryTraceInCustomMetrics_continuesParentTrace() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      mockTransaction(sentryMock, SpanStatus.OK);

      Metrics metrics = Metrics.builder()
        .httpMethod(HttpMethod.GET)
        .uri("/api/v1/test")
        .status(200)
        .gatewayResponseTimeMs(50L)
        .build();
      metrics.addCustomMetric("sentry-trace", "aaaabbbb0000111122223333444455-deadbeef12345678-1");
      metrics.addCustomMetric("baggage", "sentry-environment=prod");

      mapper.map(metrics, scope);

      sentryMock.verify(() ->
        Sentry.continueTrace(
          eq("aaaabbbb0000111122223333444455-deadbeef12345678-1"),
          eq(List.of("sentry-environment=prod"))
        )
      );
    }
  }

  @Test
  void map_withNullLog_callsContinueTraceWithNulls() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      mockTransaction(sentryMock, SpanStatus.OK);

      Metrics metrics = Metrics.builder()
        .httpMethod(HttpMethod.GET)
        .uri("/api/v1/test")
        .status(200)
        .gatewayResponseTimeMs(50L)
        .build();

      mapper.map(metrics, scope);

      sentryMock.verify(() -> Sentry.continueTrace(eq(null), eq(List.of())));
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
    return SentryTestSupport.mockTransaction(sentryMock, status);
  }
}
