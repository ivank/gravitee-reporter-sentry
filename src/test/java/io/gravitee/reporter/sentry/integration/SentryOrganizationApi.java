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
import java.util.List;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Retrofit interface for the Sentry organization-level API endpoints used to verify
 * integration test results. Base URL: {@code https://de.sentry.io/api/0/}.
 */
interface SentryOrganizationApi {
  /** Discover / transactions endpoint — returns {@code {"data": [...]}}. */
  @GET("organizations/{org}/events/")
  Call<JsonNode> getEvents(
    @Path("org") String org,
    @Query("dataset") String dataset,
    @Query("query") String query,
    @Query("field") List<String> fields,
    @Query("per_page") int perPage
  );

  /** Issues endpoint — returns a JSON array of issue objects. */
  @GET("organizations/{org}/issues/")
  Call<JsonNode> getIssues(@Path("org") String org, @Query("query") String query, @Query("per_page") int perPage);
}
