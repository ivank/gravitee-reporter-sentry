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
