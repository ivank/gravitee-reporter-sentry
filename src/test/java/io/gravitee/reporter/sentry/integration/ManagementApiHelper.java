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

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/**
 * Drives the Gravitee APIM Management REST API to create and fully deploy a V4 HTTP proxy
 * API during integration test setup.
 *
 * <p>All five lifecycle steps are executed in sequence:
 * <ol>
 *   <li>Create API (POST /apis)</li>
 *   <li>Create keyless plan (POST /apis/{id}/plans)</li>
 *   <li>Publish plan (POST /apis/{id}/plans/{planId}/_publish)</li>
 *   <li>Start API (POST /apis/{id}/_start)</li>
 *   <li>Deploy API (POST /apis/{id}/deployments) — triggers gateway sync</li>
 * </ol>
 *
 * <p>Each step asserts an expected HTTP status and throws {@link IllegalStateException} with
 * the step name and response body on failure, so test setup errors are immediately actionable.
 */
class ManagementApiHelper {

  private static final Logger LOGGER = LoggerFactory.getLogger(ManagementApiHelper.class);

  private static final String ORG_ENV = "management/v2/organizations/DEFAULT/environments/DEFAULT/";

  private final GraviteeManagementApi api;

  ManagementApiHelper(String baseUrl) {
    OkHttpClient okHttp = new OkHttpClient.Builder()
      .addInterceptor(chain ->
        chain.proceed(chain.request().newBuilder().header("Authorization", basicAuth("admin", "admin")).build())
      )
      .build();
    this.api = new Retrofit.Builder()
      .baseUrl(baseUrl + "/" + ORG_ENV)
      .client(okHttp)
      .addConverterFactory(JacksonConverterFactory.create())
      .build()
      .create(GraviteeManagementApi.class);
  }

  /**
   * Creates a V4 HTTP proxy API, attaches a keyless plan, publishes it, starts the API,
   * and triggers a deployment so the gateway syncs the new API.
   *
   * @param name       display name for the API (also used as {@code gravitee.api_name} in Sentry)
   * @param path       context path the gateway will expose (e.g. {@code /sentry-it-ok})
   * @param backendUrl backend URL inside the Docker network (e.g. {@code http://httpbin:8080/get})
   * @return the API UUID assigned by the Management API (used as the Sentry filter tag)
   */
  String createAndDeployApi(String name, String path, String backendUrl) {
    try {
      Response<JsonNode> createResp = api.createApi(buildApiBody(name, path, backendUrl)).execute();
      assertStatus("create API '" + name + "'", createResp, 201);
      String apiId = createResp.body().path("id").asText();
      LOGGER.info("Created API '{}' → id={}", name, apiId);

      Response<JsonNode> planResp = api.createPlan(apiId, buildPlanBody()).execute();
      assertStatus("create plan for API '" + name + "'", planResp, 201);
      String planId = planResp.body().path("id").asText();
      LOGGER.info("Created plan {} for API {}", planId, apiId);

      assertStatus("publish plan for API '" + name + "'", api.publishPlan(apiId, planId, EMPTY_BODY).execute(), 200);
      assertStatus("start API '" + name + "'", api.startApi(apiId, EMPTY_BODY).execute(), 204, 200);
      assertStatus("deploy API '" + name + "'", api.deployApi(apiId, EMPTY_BODY).execute(), 200, 202, 204);
      LOGGER.info("Deployed API '{}' (id={})", name, apiId);

      return apiId;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create and deploy API '" + name + "'", e);
    }
  }

  // ---- Private helpers ----------------------------------------------------------------

  private static String basicAuth(String user, String pass) {
    return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
  }

  private void assertStatus(String step, Response<?> resp, int... expected) {
    for (int code : expected) {
      if (resp.code() == code) return;
    }
    String body;
    try {
      body = resp.errorBody() != null ? resp.errorBody().string() : "";
    } catch (Exception e) {
      body = "(unreadable)";
    }
    throw new IllegalStateException(
      "Step '%s' returned HTTP %d (expected one of %s): %s".formatted(
        step,
        resp.code(),
        Arrays.toString(expected),
        body
      )
    );
  }

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final RequestBody EMPTY_BODY = RequestBody.create(JSON, "");

  private static RequestBody json(String body) {
    return RequestBody.create(JSON, body);
  }

  private RequestBody buildApiBody(String name, String path, String backendUrl) {
    return json(
      """
      {
        "name": "%s",
        "apiVersion": "1.0.0",
        "definitionVersion": "V4",
        "type": "PROXY",
        "description": "Integration test API for gravitee-reporter-sentry",
        "listeners": [
          {
            "type": "HTTP",
            "paths": [{ "path": "%s" }],
            "entrypoints": [{ "type": "http-proxy" }]
          }
        ],
        "endpointGroups": [
          {
            "name": "default",
            "type": "http-proxy",
            "endpoints": [
              {
                "name": "main",
                "type": "http-proxy",
                "weight": 1,
                "inheritConfiguration": false,
                "configuration": { "target": "%s" }
              }
            ]
          }
        ]
      }
      """.formatted(name, path, backendUrl)
    );
  }

  private static RequestBody buildPlanBody() {
    return json(
      """
      {
        "name": "Default Plan",
        "definitionVersion": "V4",
        "security": { "type": "KEY_LESS" }
      }
      """
    );
  }
}
