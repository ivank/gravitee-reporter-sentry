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

import java.time.Duration;

/**
 * Container image versions shared by the integration tests.
 *
 * <p>CI runs the suite across a matrix of supported APIM releases — see {@code
 * .github/workflows/ci.yml}. Keeping the value here means every IT moves together and a version
 * can never be bumped in one and forgotten in another.
 */
final class TestVersions {

  /** Override locally with {@code -Dapim.version=4.12.0}. */
  static final String APIM = System.getProperty("apim.version", "4.12.12");

  /**
   * How long to allow an APIM container to become healthy.
   *
   * <p>Testcontainers defaults to 60s, which the management API and gateway routinely exceed on a
   * loaded machine or a cold image — producing a {@code ContainerLaunchException: Timed out
   * waiting for URL to be accessible} that looks like a product failure but is only a slow boot.
   */
  static final Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofSeconds(300);

  private TestVersions() {}
}
