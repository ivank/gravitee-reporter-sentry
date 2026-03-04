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
      default -> httpStatus >= 500 ? SpanStatus.INTERNAL_ERROR : SpanStatus.UNKNOWN_ERROR;
    };
  }
}
