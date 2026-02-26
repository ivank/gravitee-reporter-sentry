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

import java.util.function.Consumer;

/**
 * Shared utility for setting nullable Sentry tags without boilerplate null-checks.
 *
 * <p>Accepts any {@link Consumer<String>} setter so it works with both
 * {@code ITransaction.setTag} and {@code SentryEvent.setTag} without overloading.
 *
 * <pre>{@code
 * SentryTags.ifPresent(metrics.getApiId(),   v -> tx.setTag("gravitee.api_id", v));
 * SentryTags.ifPresent(metrics.getApiName(), v -> tx.setTag("gravitee.api_name", v));
 * }</pre>
 */
final class SentryTags {

  private SentryTags() {}

  static void ifPresent(String value, Consumer<String> setter) {
    if (value != null) {
      setter.accept(value);
    }
  }
}
