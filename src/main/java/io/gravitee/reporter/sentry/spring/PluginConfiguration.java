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
package io.gravitee.reporter.sentry.spring;

import io.gravitee.reporter.sentry.config.SentryReporterConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} for the Sentry reporter plugin.
 *
 * <p>Discovered automatically by the Gravitee plugin classloader because this class
 * resides in the {@code io.gravitee.reporter.sentry.spring} package, which follows
 * the Gravitee plugin convention for Spring context wiring.
 */
@Configuration
public class PluginConfiguration {

  @Bean
  public SentryReporterConfiguration sentryReporterConfiguration() {
    return new SentryReporterConfiguration();
  }
}
