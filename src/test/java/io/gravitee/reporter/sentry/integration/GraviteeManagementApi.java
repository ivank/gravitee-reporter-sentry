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
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Retrofit interface for the subset of the Gravitee APIM Management REST API used in
 * integration tests. Base URL is scoped to the DEFAULT org/environment.
 */
interface GraviteeManagementApi {
  @POST("apis")
  Call<JsonNode> createApi(@Body RequestBody body);

  @POST("apis/{id}/plans")
  Call<JsonNode> createPlan(@Path("id") String apiId, @Body RequestBody body);

  @POST("apis/{id}/plans/{planId}/_publish")
  Call<Void> publishPlan(@Path("id") String apiId, @Path("planId") String planId, @Body RequestBody body);

  @POST("apis/{id}/_start")
  Call<Void> startApi(@Path("id") String apiId, @Body RequestBody body);

  @POST("apis/{id}/deployments")
  Call<Void> deployApi(@Path("id") String apiId, @Body RequestBody body);
}
