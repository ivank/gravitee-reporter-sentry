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

  private static final Logger LOGGER = LoggerFactory.getLogger(EndpointStatusMapper.class);

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

      SentryTags.ifPresent(status.getApi(), v -> event.setTag("gravitee.api_id", v));
      SentryTags.ifPresent(status.getApiName(), v -> event.setTag("gravitee.api_name", v));
      SentryTags.ifPresent(status.getEndpoint(), v -> event.setTag("gravitee.endpoint", v));
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
      "%s: %s (api=%s, responseTime=%dms)".formatted(
        prefix,
        status.getEndpoint(),
        status.getApi(),
        status.getResponseTime()
      )
    );
    return msg;
  }
}
