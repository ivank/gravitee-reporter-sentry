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
import static org.mockito.Mockito.*;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.v4.log.Log;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class LogToSentryMapperTest {

  private LogToSentryMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new LogToSentryMapper();
  }

  @Test
  void map_withRequestAndResponse_addsTwoBreadcrumbs() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      Request req = new Request();
      req.setMethod(HttpMethod.GET);
      req.setUri("/api/v1/users");

      Response resp = new Response(200);

      Log log = Log.builder().requestId("req-1").entrypointRequest(req).entrypointResponse(resp).build();

      mapper.map(log);

      sentryMock.verify(() -> Sentry.addBreadcrumb(any(Breadcrumb.class)), times(2));
    }
  }

  @Test
  void map_withNullRequest_addsOnlyResponseBreadcrumb() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      Response resp = new Response(500);

      Log log = Log.builder().requestId("req-2").entrypointRequest(null).entrypointResponse(resp).build();

      mapper.map(log);

      sentryMock.verify(() -> Sentry.addBreadcrumb(any(Breadcrumb.class)), times(1));
    }
  }

  @Test
  void map_withNullResponse_addsOnlyRequestBreadcrumb() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      Request req = new Request();
      req.setMethod(HttpMethod.DELETE);
      req.setUri("/api/v1/item/1");

      Log log = Log.builder().requestId("req-3").entrypointRequest(req).entrypointResponse(null).build();

      mapper.map(log);

      sentryMock.verify(() -> Sentry.addBreadcrumb(any(Breadcrumb.class)), times(1));
    }
  }

  @Test
  void map_requestBreadcrumb_containsMethodAndUri() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      Request req = new Request();
      req.setMethod(HttpMethod.POST);
      req.setUri("/api/v1/orders");

      Log log = Log.builder().requestId("req-4").entrypointRequest(req).build();

      mapper.map(log);

      ArgumentCaptor<Breadcrumb> captor = ArgumentCaptor.forClass(Breadcrumb.class);
      sentryMock.verify(() -> Sentry.addBreadcrumb(captor.capture()), times(1));

      Breadcrumb crumb = captor.getValue();
      assertThat(crumb.getMessage()).isEqualTo("POST /api/v1/orders");
      assertThat(crumb.getData()).containsEntry("method", "POST");
      assertThat(crumb.getData()).containsEntry("url", "/api/v1/orders");
    }
  }

  @Test
  void map_requestBody_truncatedAt2048Chars() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      Request req = new Request();
      req.setMethod(HttpMethod.POST);
      req.setUri("/upload");
      req.setBody("x".repeat(3000));

      Log log = Log.builder().requestId("req-5").entrypointRequest(req).build();

      mapper.map(log);

      ArgumentCaptor<Breadcrumb> captor = ArgumentCaptor.forClass(Breadcrumb.class);
      sentryMock.verify(() -> Sentry.addBreadcrumb(captor.capture()));

      String body = (String) captor.getValue().getData().get("body");
      assertThat(body).hasSize(2049); // 2048 chars + "…"
      assertThat(body).endsWith("…");
    }
  }

  @Test
  void map_nullRequestAndResponse_doesNotAddBreadcrumbs() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      Log log = Log.builder().requestId("req-6").entrypointRequest(null).entrypointResponse(null).build();

      mapper.map(log);

      sentryMock.verify(() -> Sentry.addBreadcrumb(any(Breadcrumb.class)), never());
    }
  }

  // --- truncate tests ---

  @ParameterizedTest(name = "truncate({0}, {1}) = {2}")
  @MethodSource("truncateCases")
  void truncate(String input, int max, String expected) {
    assertThat(LogToSentryMapper.truncate(input, max)).isEqualTo(expected);
  }

  static Stream<Arguments> truncateCases() {
    return Stream.of(
      Arguments.of("hello", 10, "hello"),
      Arguments.of("hello", 5, "hello"),
      Arguments.of("hello world", 5, "hello…"),
      Arguments.of(null, 10, null)
    );
  }
}
