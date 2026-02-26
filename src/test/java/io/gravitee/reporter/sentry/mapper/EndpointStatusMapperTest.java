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
import static org.mockito.Mockito.*;

import io.gravitee.reporter.api.health.EndpointStatus;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class EndpointStatusMapperTest {

  private EndpointStatusMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new EndpointStatusMapper();
  }

  @Test
  void map_nonTransition_doesNotCaptureEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      EndpointStatus status = buildStatus(false /* success */, false /* available */, false /* transition */);

      mapper.map(status);

      sentryMock.verify(() -> Sentry.captureEvent(any(SentryEvent.class)), never());
    }
  }

  @Test
  void map_successNonTransition_doesNotCaptureEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      EndpointStatus status = buildStatus(true /* success */, true /* available */, false /* transition */);

      mapper.map(status);

      sentryMock.verify(() -> Sentry.captureEvent(any(SentryEvent.class)), never());
    }
  }

  @Test
  void map_transitionToUnavailable_capturesErrorEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      EndpointStatus status = buildStatus(false /* success */, false /* available */, true /* transition */);

      mapper.map(status);

      ArgumentCaptor<SentryEvent> captor = ArgumentCaptor.forClass(SentryEvent.class);
      sentryMock.verify(() -> Sentry.captureEvent(captor.capture()), times(1));

      assertThat(captor.getValue().getLevel()).isEqualTo(SentryLevel.ERROR);
    }
  }

  @Test
  void map_transitionToAvailable_capturesInfoEvent() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      EndpointStatus status = buildStatus(true /* success */, true /* available */, true /* transition */);

      mapper.map(status);

      ArgumentCaptor<SentryEvent> captor = ArgumentCaptor.forClass(SentryEvent.class);
      sentryMock.verify(() -> Sentry.captureEvent(captor.capture()), times(1));

      assertThat(captor.getValue().getLevel()).isEqualTo(SentryLevel.INFO);
    }
  }

  @Test
  void map_transitionToUnavailable_setsApiTags() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      EndpointStatus status = EndpointStatus.builder()
        .api("api-123")
        .apiName("My API")
        .endpoint("https://backend/health")
        .available(false)
        .transition(true)
        .build();

      mapper.map(status);

      ArgumentCaptor<SentryEvent> captor = ArgumentCaptor.forClass(SentryEvent.class);
      sentryMock.verify(() -> Sentry.captureEvent(captor.capture()), times(1));

      SentryEvent event = captor.getValue();
      assertThat(event.getTag("gravitee.api_id")).isEqualTo("api-123");
      assertThat(event.getTag("gravitee.api_name")).isEqualTo("My API");
      assertThat(event.getTag("gravitee.endpoint")).isEqualTo("https://backend/health");
      assertThat(event.getTag("gravitee.available")).isEqualTo("false");
    }
  }

  @Test
  void map_withNullApiFields_doesNotThrow() {
    try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
      EndpointStatus status = EndpointStatus.builder()
        .api(null)
        .apiName(null)
        .endpoint(null)
        .available(false)
        .transition(true)
        .build();

      // Must not throw
      mapper.map(status);
    }
  }

  // Helper: build an EndpointStatus using the builder API
  private static EndpointStatus buildStatus(boolean success, boolean available, boolean transition) {
    return EndpointStatus.builder()
      .api("api-abc")
      .apiName("Test API")
      .endpoint("https://backend/health")
      .available(available)
      .transition(transition)
      .build();
  }
}
