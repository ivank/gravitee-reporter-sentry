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
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/**
 * Thin client for the Sentry REST API used to verify that the reporter sent the expected
 * events during integration tests.
 *
 * <p><b>Region:</b> The DSN targets Sentry's EU (DE) region; the API base is
 * {@code https://de.sentry.io/api/0/}, not {@code https://sentry.io/api/0/}.
 *
 * <p><b>Org slug</b> is supplied via the {@code SENTRY_TEST_ORG} environment variable.
 *
 * <p><b>Polling:</b> both {@link #pollForTransactions} and {@link #pollForIssues} use
 * Awaitility internally. On timeout, Awaitility throws {@code ConditionTimeoutException}
 * which JUnit reports as a test failure with the human-readable alias.
 */
class SentryApiClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(SentryApiClient.class);
  private static final String API_BASE = "https://de.sentry.io/api/0/";

  private final SentryOrganizationApi api;
  private final String orgSlug;

  SentryApiClient(String token, String orgSlug, String projectSlug) {
    OkHttpClient okHttp = new OkHttpClient.Builder()
      .addInterceptor(chain ->
        chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer " + token).build())
      )
      .build();
    this.api = new Retrofit.Builder()
      .baseUrl(API_BASE)
      .client(okHttp)
      .addConverterFactory(JacksonConverterFactory.create())
      .build()
      .create(SentryOrganizationApi.class);
    this.orgSlug = orgSlug;
    LOGGER.info("Sentry client ready — org={} project={}", orgSlug, projectSlug);
  }

  /**
   * Polls the Sentry Discover (transactions) API until at least one transaction matching
   * {@code query} is found, or {@code timeout} elapses.
   *
   * @param query   Sentry search query, e.g. {@code transaction:"GET /sentry-it-ok"}
   * @param timeout maximum time to wait
   * @return list of matching event nodes from the {@code data} array (never empty)
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
   * @param query   Sentry search query, e.g. {@code is:unresolved gravitee.api_id:<uuid>}
   * @param timeout maximum time to wait
   * @return list of matching issue nodes (never empty)
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
    Response<JsonNode> resp = api.getEvents(orgSlug, "transactions", query, List.of("id", "transaction"), 5).execute();
    if (!resp.isSuccessful()) {
      LOGGER.warn("Sentry events API returned {}", resp.code());
      return List.of();
    }
    JsonNode data = resp.body().path("data");
    return data.isArray() ? StreamSupport.stream(data.spliterator(), false).toList() : List.of();
  }

  private List<JsonNode> fetchIssues(String query) throws Exception {
    Response<JsonNode> resp = api.getIssues(orgSlug, query, 5).execute();
    if (!resp.isSuccessful()) {
      LOGGER.warn("Sentry issues API returned {}", resp.code());
      return List.of();
    }
    JsonNode root = resp.body();
    return root.isArray() ? StreamSupport.stream(root.spliterator(), false).toList() : List.of();
  }
}
