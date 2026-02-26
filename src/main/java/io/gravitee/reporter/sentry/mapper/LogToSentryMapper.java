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

import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.v4.log.Log;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps Gravitee v4 {@link Log} (full request/response body+headers) to Sentry breadcrumbs.
 *
 * <p>Breadcrumbs are lightweight entries in Sentry's rolling buffer that provide context for
 * the next captured event. They are attached to the global scope so they appear in subsequent
 * error events for the same Sentry session.
 *
 * <p>Only called when {@code reporters.sentry.reportLogs=true} — disabled by default to avoid
 * high-volume breadcrumb noise in production.
 */
public class LogToSentryMapper {

  private static final Logger LOGGER = LoggerFactory.getLogger(LogToSentryMapper.class);

  /**
   * Adds request and response breadcrumbs for the given {@link Log}.
   *
   * @param log the full request/response log
   */
  public void map(Log log) {
    try {
      Request req = log.getEntrypointRequest();
      if (req != null) {
        Breadcrumb requestCrumb = new Breadcrumb();
        requestCrumb.setType("http");
        requestCrumb.setCategory("http.request");
        requestCrumb.setMessage((req.getMethod() != null ? req.getMethod().name() : "UNKNOWN") + " " + req.getUri());
        requestCrumb.setData("request_id", log.getRequestId());
        requestCrumb.setData("method", req.getMethod() != null ? req.getMethod().name() : null);
        requestCrumb.setData("url", req.getUri());
        if (req.getBody() != null && !req.getBody().isEmpty()) {
          requestCrumb.setData("body", truncate(req.getBody(), 2048));
        }
        Sentry.addBreadcrumb(requestCrumb);
      }

      Response resp = log.getEntrypointResponse();
      if (resp != null) {
        Breadcrumb responseCrumb = new Breadcrumb();
        responseCrumb.setType("http");
        responseCrumb.setCategory("http.response");
        responseCrumb.setMessage("HTTP " + resp.getStatus() + " (requestId=" + log.getRequestId() + ")");
        responseCrumb.setData("status_code", resp.getStatus());
        responseCrumb.setData("request_id", log.getRequestId());
        if (resp.getBody() != null && !resp.getBody().isEmpty()) {
          responseCrumb.setData("body", truncate(resp.getBody(), 2048));
        }
        Sentry.addBreadcrumb(responseCrumb);
      }
    } catch (Exception e) {
      LOGGER.warn("Failed to add log breadcrumbs to Sentry for requestId '{}': {}", log.getRequestId(), e.getMessage());
    }
  }

  /**
   * Truncates a string to at most {@code maxChars} characters to prevent oversized payloads.
   */
  static String truncate(String value, int maxChars) {
    if (value == null || value.length() <= maxChars) {
      return value;
    }
    return value.substring(0, maxChars) + "…";
  }
}
