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

import io.gravitee.reporter.api.health.EndpointStatus;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps Gravitee {@link EndpointStatus} (health-check results) to Sentry error events.
 *
 * <p>Only <em>transition</em> events are reported — i.e., when an endpoint changes state
 * (up→down or down→up). This prevents flooding Sentry when an endpoint stays down across
 * consecutive health-check intervals.
 *
 * <ul>
 *   <li>Transition to <em>unavailable</em> → {@code SentryLevel.ERROR}
 *   <li>Recovery (transition to <em>available</em>) → {@code SentryLevel.INFO}
 *   <li>Successful non-transition checks → silently ignored
 *   <li>Failing non-transition checks → silently ignored (already reported)
 * </ul>
 */
public class EndpointStatusMapper {

  private static final Logger LOGGER = LoggerFactory.getLogger(
    EndpointStatusMapper.class
  );

  /**
   * Maps a single {@link EndpointStatus} to a Sentry event.
   *
   * @param status the health-check result
   */
  public void map(EndpointStatus status) {
    // Only report state transitions to avoid Sentry noise
    if (!status.isTransition()) {
      return;
    }

    try {
      SentryEvent event = new SentryEvent();

      if (!status.isAvailable()) {
        // Endpoint just went down
        event.setLevel(SentryLevel.ERROR);
        event.setMessage(buildMessage("Endpoint unavailable", status));
      } else {
        // Endpoint recovered
        event.setLevel(SentryLevel.INFO);
        event.setMessage(buildMessage("Endpoint recovered", status));
      }

      setTagIfNotNull(event, "gravitee.api_id", status.getApi());
      setTagIfNotNull(event, "gravitee.api_name", status.getApiName());
      setTagIfNotNull(event, "gravitee.endpoint", status.getEndpoint());
      event.setTag("gravitee.available", String.valueOf(status.isAvailable()));

      Sentry.captureEvent(event);
    } catch (Exception e) {
      LOGGER.warn(
        "Failed to report endpoint status to Sentry for endpoint '{}': {}",
        status.getEndpoint(),
        e.getMessage()
      );
    }
  }

  private static Message buildMessage(String prefix, EndpointStatus status) {
    Message msg = new Message();
    msg.setMessage(
      prefix +
        ": " +
        status.getEndpoint() +
        " (api=" +
        status.getApi() +
        ", responseTime=" +
        status.getResponseTime() +
        "ms)"
    );
    return msg;
  }

  private static void setTagIfNotNull(
    SentryEvent event,
    String key,
    String value
  ) {
    if (value != null) {
      event.setTag(key, value);
    }
  }
}
