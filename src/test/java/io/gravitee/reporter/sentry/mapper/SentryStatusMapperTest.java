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

import io.sentry.SpanStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SentryStatusMapperTest {

  @ParameterizedTest(name = "HTTP {0} → {1}")
  @CsvSource(
    {
      "200, OK",
      "201, OK",
      "301, OK",
      "400, INVALID_ARGUMENT",
      "401, UNAUTHENTICATED",
      "403, PERMISSION_DENIED",
      "404, NOT_FOUND",
      "409, ALREADY_EXISTS",
      "429, RESOURCE_EXHAUSTED",
      "500, INTERNAL_ERROR",
      "503, INTERNAL_ERROR",
      "418, UNKNOWN_ERROR",
    }
  )
  void fromHttpStatus(int code, SpanStatus expected) {
    assertThat(SentryStatusMapper.fromHttpStatus(code)).isEqualTo(expected);
  }
}
