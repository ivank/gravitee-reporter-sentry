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

  /**
   * Polls the Sentry Discover API until a transaction with {@code txName} that belongs to
   * {@code traceId} is found, or {@code timeout} elapses.
   *
   * <p>This is the assertion for distributed trace propagation: if the gateway correctly called
   * {@code Sentry.continueTrace(sentryTrace, baggage)}, the resulting transaction will carry the
   * same trace ID as the incoming {@code sentry-trace} header. If trace propagation is broken,
   * the transaction lands on a fresh orphaned trace and this method times out.
   *
   * @param traceId  the 32-hex-char Sentry trace ID from the outbound {@code sentry-trace} header
   * @param txName   expected transaction name, e.g. {@code "GET /sentry-it-trace"}
   * @param timeout  maximum time to wait
   * @return list of matching event nodes (never empty)
   */
  List<JsonNode> pollForTransactionInTrace(String traceId, String txName, Duration timeout) {
    String query = "transaction:\"%s\" trace:%s".formatted(txName, traceId);
    LOGGER.info("Polling Sentry for transaction in trace: {}", query);
    await("Sentry transaction in trace: " + traceId)
      .atMost(timeout)
      .pollInterval(Duration.ofSeconds(5))
      .until(() -> {
        try {
          return !fetchTransactionsByTrace(traceId, txName).isEmpty();
        } catch (Exception e) {
          LOGGER.warn("Transient error polling Sentry transactions: {}", e.getMessage());
          return false;
        }
      });
    try {
      return fetchTransactionsByTrace(traceId, txName);
    } catch (Exception e) {
      throw new RuntimeException("Failed to fetch Sentry transactions after polling succeeded", e);
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

  private List<JsonNode> fetchTransactionsByTrace(String traceId, String txName) throws Exception {
    String query = "transaction:\"%s\" trace:%s".formatted(txName, traceId);
    Response<JsonNode> resp = api
      .getEvents(orgSlug, "transactions", query, List.of("id", "transaction", "trace"), 5)
      .execute();
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
