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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final String ORG_ENV = "/management/v2/organizations/DEFAULT/environments/DEFAULT";

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper mapper;
  private final String baseUrl;
  private final String authHeader;

  ManagementApiHelper(String baseUrl, ObjectMapper mapper) {
    this.baseUrl = baseUrl;
    this.mapper = mapper;
    this.authHeader = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
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
      // Step 1: Create API
      String apiBody = mapper.writeValueAsString(buildApiBody(name, path, backendUrl));
      HttpResponse<String> createResp = post(ORG_ENV + "/apis", apiBody);
      assertStatus("create API '" + name + "'", createResp, 201);
      String apiId = mapper.readTree(createResp.body()).path("id").asText();
      LOGGER.info("Created API '{}' → id={}", name, apiId);

      // Step 2: Create keyless plan
      String planBody = mapper.writeValueAsString(buildPlanBody());
      HttpResponse<String> planResp = post(ORG_ENV + "/apis/" + apiId + "/plans", planBody);
      assertStatus("create plan for API '" + name + "'", planResp, 201);
      String planId = mapper.readTree(planResp.body()).path("id").asText();
      LOGGER.info("Created plan {} for API {}", planId, apiId);

      // Step 3: Publish plan
      HttpResponse<String> publishResp = post(ORG_ENV + "/apis/" + apiId + "/plans/" + planId + "/_publish", "");
      assertStatus("publish plan for API '" + name + "'", publishResp, 200);

      // Step 4: Start API
      HttpResponse<String> startResp = post(ORG_ENV + "/apis/" + apiId + "/_start", "");
      assertStatus("start API '" + name + "'", startResp, 204, 200);

      // Step 5: Deploy (triggers gateway sync)
      HttpResponse<String> deployResp = post(ORG_ENV + "/apis/" + apiId + "/deployments", "");
      assertStatus("deploy API '" + name + "'", deployResp, 200, 202, 204);
      LOGGER.info("Deployed API '{}' (id={})", name, apiId);

      return apiId;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create and deploy API '" + name + "'", e);
    }
  }

  // ---- Private helpers ----------------------------------------------------------------

  private HttpResponse<String> post(String path, String jsonBody) throws Exception {
    HttpRequest.BodyPublisher body = jsonBody.isEmpty()
      ? HttpRequest.BodyPublishers.noBody()
      : HttpRequest.BodyPublishers.ofString(jsonBody);

    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl + path))
      .header("Authorization", authHeader)
      .header("Content-Type", "application/json")
      .POST(body)
      .build();

    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void assertStatus(String step, HttpResponse<String> response, int... expected) {
    for (int code : expected) {
      if (response.statusCode() == code) {
        return;
      }
    }
    throw new IllegalStateException(
      "Step '" +
        step +
        "' returned HTTP " +
        response.statusCode() +
        " (expected one of " +
        java.util.Arrays.toString(expected) +
        "): " +
        response.body()
    );
  }

  private ObjectNode buildApiBody(String name, String path, String backendUrl) {
    ObjectNode body = mapper.createObjectNode();
    body.put("name", name);
    body.put("apiVersion", "1.0.0");
    body.put("definitionVersion", "V4");
    body.put("type", "PROXY");
    body.put("description", "Integration test API for gravitee-reporter-sentry");

    // Listener — HTTP proxy on the given context path
    ArrayNode listeners = body.putArray("listeners");
    ObjectNode listener = listeners.addObject();
    listener.put("type", "HTTP");
    ArrayNode paths = listener.putArray("paths");
    paths.addObject().put("path", path);
    ArrayNode entrypoints = listener.putArray("entrypoints");
    entrypoints.addObject().put("type", "http-proxy");

    // Endpoint group — single backend target
    ArrayNode groups = body.putArray("endpointGroups");
    ObjectNode group = groups.addObject();
    group.put("name", "default");
    group.put("type", "http-proxy");
    ArrayNode endpoints = group.putArray("endpoints");
    ObjectNode endpoint = endpoints.addObject();
    endpoint.put("name", "main");
    endpoint.put("type", "http-proxy");
    endpoint.put("weight", 1);
    endpoint.put("inheritConfiguration", false);
    ObjectNode config = endpoint.putObject("configuration");
    config.put("target", backendUrl);

    return body;
  }

  private ObjectNode buildPlanBody() {
    ObjectNode plan = mapper.createObjectNode();
    plan.put("name", "Default Plan");
    plan.put("definitionVersion", "V4");
    ObjectNode security = plan.putObject("security");
    security.put("type", "KEY_LESS");
    return plan;
  }
}
