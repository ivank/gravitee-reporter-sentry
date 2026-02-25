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
package io.gravitee.reporter.sentry.integration;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin client for the Sentry REST API used to verify that the reporter sent the expected
 * events during integration tests.
 *
 * <p><b>Region:</b> The DSN targets Sentry's EU (DE) region; the API base is
 * {@code https://de.sentry.io/api/0}, not {@code https://sentry.io/api/0}.
 *
 * <p><b>Org and project slugs</b> are supplied directly via {@code SENTRY_TEST_ORG} and
 * {@code SENTRY_TEST_PROJECT} environment variables (set in {@code .env}).
 *
 * <p><b>Polling:</b> both {@link #pollForTransactions} and {@link #pollForIssues} use
 * Awaitility internally. On timeout, Awaitility throws {@code ConditionTimeoutException}
 * which JUnit reports as a test failure with the human-readable alias.
 */
class SentryApiClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(SentryApiClient.class);
  private static final String API_BASE = "https://de.sentry.io/api/0";

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper mapper;
  private final String token;
  private final String orgSlug;
  private final String projectSlug;

  SentryApiClient(String token, String orgSlug, String projectSlug, ObjectMapper mapper) {
    this.token = token;
    this.orgSlug = orgSlug;
    this.projectSlug = projectSlug;
    this.mapper = mapper;
    LOGGER.info("Sentry client ready — org={} project={}", orgSlug, projectSlug);
  }

  /**
   * Polls the Sentry Discover (transactions) API until at least one transaction matching
   * {@code query} is found, or {@code timeout} elapses.
   *
   * <p>Uses {@code GET /api/0/organizations/{org}/events/?dataset=transactions}.
   *
   * @param query   Sentry search query, e.g. {@code transaction:"GET /sentry-it-ok"}
   * @param timeout maximum time to wait
   * @return list of matching event nodes from the {@code data} array (never empty — method
   *         throws {@code ConditionTimeoutException} if no events arrive in time)
   */
  List<JsonNode> pollForTransactions(String query, Duration timeout) {
    LOGGER.info("Polling Sentry transactions: {}", query);
    await("Sentry transactions: " + query)
      .atMost(timeout)
      .pollInterval(Duration.ofSeconds(5))
      .until(() -> {
        try {
          return !fetchTransactions(query).isEmpty();
        } catch (Exception e) {
          LOGGER.warn("Transient error polling Sentry transactions: {}", e.getMessage());
          return false;
        }
      });
    try {
      return fetchTransactions(query);
    } catch (Exception e) {
      throw new RuntimeException("Failed to fetch Sentry transactions after polling succeeded", e);
    }
  }

  /**
   * Polls the Sentry Issues API until at least one issue matching {@code query} is found,
   * or {@code timeout} elapses.
   *
   * <p>Uses {@code GET /api/0/organizations/{org}/issues/}.
   *
   * @param query   Sentry search query, e.g. {@code is:unresolved gravitee.api_id:<uuid>}
   * @param timeout maximum time to wait
   * @return list of matching issue nodes (never empty — throws on timeout)
   */
  List<JsonNode> pollForIssues(String query, Duration timeout) {
    LOGGER.info("Polling Sentry issues: {}", query);
    await("Sentry issues: " + query)
      .atMost(timeout)
      .pollInterval(Duration.ofSeconds(5))
      .until(() -> {
        try {
          return !fetchIssues(query).isEmpty();
        } catch (Exception e) {
          LOGGER.warn("Transient error polling Sentry issues: {}", e.getMessage());
          return false;
        }
      });
    try {
      return fetchIssues(query);
    } catch (Exception e) {
      throw new RuntimeException("Failed to fetch Sentry issues after polling succeeded", e);
    }
  }

  // ---- Private helpers ----------------------------------------------------------------

  private List<JsonNode> fetchTransactions(String query) throws Exception {
    String url =
      API_BASE +
      "/organizations/" +
      orgSlug +
      "/events/" +
      "?dataset=transactions" +
      "&query=" +
      URLEncoder.encode(query, StandardCharsets.UTF_8) +
      "&field=id&field=transaction&per_page=5";

    HttpResponse<String> resp = http.send(bearerRequest(url).build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      LOGGER.warn("Sentry events API returned {}: {}", resp.statusCode(), resp.body());
      return List.of();
    }
    JsonNode data = mapper.readTree(resp.body()).path("data");
    if (!data.isArray()) {
      return List.of();
    }
    List<JsonNode> items = new ArrayList<>();
    data.forEach(items::add);
    return items;
  }

  private List<JsonNode> fetchIssues(String query) throws Exception {
    String url =
      API_BASE +
      "/organizations/" +
      orgSlug +
      "/issues/" +
      "?query=" +
      URLEncoder.encode(query, StandardCharsets.UTF_8) +
      "&per_page=5";

    HttpResponse<String> resp = http.send(bearerRequest(url).build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      LOGGER.warn("Sentry issues API returned {}: {}", resp.statusCode(), resp.body());
      return List.of();
    }
    JsonNode root = mapper.readTree(resp.body());
    if (!root.isArray()) {
      return List.of();
    }
    List<JsonNode> items = new ArrayList<>();
    root.forEach(items::add);
    return items;
  }

  private HttpRequest.Builder bearerRequest(String url) {
    return HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + token);
  }
}
