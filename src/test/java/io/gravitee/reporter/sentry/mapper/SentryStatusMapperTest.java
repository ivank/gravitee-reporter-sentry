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
import org.junit.jupiter.api.Test;

class SentryStatusMapperTest {

  @Test
  void http200_mapsToOk() {
    assertThat(SentryStatusMapper.fromHttpStatus(200)).isEqualTo(SpanStatus.OK);
  }

  @Test
  void http201_mapsToOk() {
    assertThat(SentryStatusMapper.fromHttpStatus(201)).isEqualTo(SpanStatus.OK);
  }

  @Test
  void http301_mapsToOk() {
    assertThat(SentryStatusMapper.fromHttpStatus(301)).isEqualTo(SpanStatus.OK);
  }

  @Test
  void http400_mapsToInvalidArgument() {
    assertThat(SentryStatusMapper.fromHttpStatus(400)).isEqualTo(
      SpanStatus.INVALID_ARGUMENT
    );
  }

  @Test
  void http401_mapsToUnauthenticated() {
    assertThat(SentryStatusMapper.fromHttpStatus(401)).isEqualTo(
      SpanStatus.UNAUTHENTICATED
    );
  }

  @Test
  void http403_mapsToPermissionDenied() {
    assertThat(SentryStatusMapper.fromHttpStatus(403)).isEqualTo(
      SpanStatus.PERMISSION_DENIED
    );
  }

  @Test
  void http404_mapsToNotFound() {
    assertThat(SentryStatusMapper.fromHttpStatus(404)).isEqualTo(
      SpanStatus.NOT_FOUND
    );
  }

  @Test
  void http409_mapsToAlreadyExists() {
    assertThat(SentryStatusMapper.fromHttpStatus(409)).isEqualTo(
      SpanStatus.ALREADY_EXISTS
    );
  }

  @Test
  void http429_mapsToResourceExhausted() {
    assertThat(SentryStatusMapper.fromHttpStatus(429)).isEqualTo(
      SpanStatus.RESOURCE_EXHAUSTED
    );
  }

  @Test
  void http500_mapsToInternalError() {
    assertThat(SentryStatusMapper.fromHttpStatus(500)).isEqualTo(
      SpanStatus.INTERNAL_ERROR
    );
  }

  @Test
  void http503_mapsToInternalError() {
    assertThat(SentryStatusMapper.fromHttpStatus(503)).isEqualTo(
      SpanStatus.INTERNAL_ERROR
    );
  }

  @Test
  void http418_mapsToUnknownError() {
    // 418 I'm a teapot — recognised 4xx but not specifically mapped
    assertThat(SentryStatusMapper.fromHttpStatus(418)).isEqualTo(
      SpanStatus.UNKNOWN_ERROR
    );
  }
}
