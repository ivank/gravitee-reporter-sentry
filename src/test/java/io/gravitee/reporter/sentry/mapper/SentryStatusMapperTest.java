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
