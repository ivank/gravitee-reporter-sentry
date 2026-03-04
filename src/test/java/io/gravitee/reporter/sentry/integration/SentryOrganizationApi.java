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
