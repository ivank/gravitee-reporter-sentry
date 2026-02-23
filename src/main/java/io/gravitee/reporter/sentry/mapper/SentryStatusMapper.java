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

import io.sentry.SpanStatus;

/**
 * Utility class that translates HTTP status codes to the Sentry {@link SpanStatus} enum,
 * following OpenTelemetry semantic conventions for HTTP spans.
 */
public final class SentryStatusMapper {

  private SentryStatusMapper() {}

  /**
   * Maps an HTTP status code to the corresponding {@link SpanStatus}.
   *
   * <ul>
   *   <li>{@code < 400} → {@link SpanStatus#OK}
   *   <li>{@code 400} → {@link SpanStatus#INVALID_ARGUMENT}
   *   <li>{@code 401} → {@link SpanStatus#UNAUTHENTICATED}
   *   <li>{@code 403} → {@link SpanStatus#PERMISSION_DENIED}
   *   <li>{@code 404} → {@link SpanStatus#NOT_FOUND}
   *   <li>{@code 409} → {@link SpanStatus#ALREADY_EXISTS}
   *   <li>{@code 429} → {@link SpanStatus#RESOURCE_EXHAUSTED}
   *   <li>{@code >= 500} → {@link SpanStatus#INTERNAL_ERROR}
   *   <li>other 4xx → {@link SpanStatus#UNKNOWN_ERROR}
   * </ul>
   *
   * @param httpStatus the HTTP response status code
   * @return the corresponding Sentry span status
   */
  public static SpanStatus fromHttpStatus(int httpStatus) {
    if (httpStatus < 400) {
      return SpanStatus.OK;
    }
    return switch (httpStatus) {
      case 400 -> SpanStatus.INVALID_ARGUMENT;
      case 401 -> SpanStatus.UNAUTHENTICATED;
      case 403 -> SpanStatus.PERMISSION_DENIED;
      case 404 -> SpanStatus.NOT_FOUND;
      case 409 -> SpanStatus.ALREADY_EXISTS;
      case 429 -> SpanStatus.RESOURCE_EXHAUSTED;
      default -> httpStatus >= 500
        ? SpanStatus.INTERNAL_ERROR
        : SpanStatus.UNKNOWN_ERROR;
    };
  }
}
