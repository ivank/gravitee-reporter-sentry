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
package io.gravitee.reporter.sentry.config;

import org.springframework.beans.factory.annotation.Value;

/**
 * Configuration for the Sentry reporter plugin.
 * All values are bound from {@code gravitee.yml} under the {@code reporters.sentry.*} prefix.
 *
 * <p>Example gravitee.yml block:
 * <pre>{@code
 * reporters:
 *   sentry:
 *     enabled: true
 *     dsn: https://key@o123456.ingest.sentry.io/789
 *     environment: production
 *     release: 1.0.0
 *     serverName: gw-node-1
 *     tracesSampleRate: 0.1
 *     captureErrors: true
 *     reportHealthChecks: true
 *     reportLogs: false
 *     reportMessageMetrics: true
 *     debug: false
 * }</pre>
 */
public class SentryReporterConfiguration {

  /** Whether the Sentry reporter is active at all. */
  @Value("${reporters.sentry.enabled:true}")
  private boolean enabled;

  /**
   * Sentry project DSN URL.
   * If blank or absent, the reporter starts but sends nothing (safe no-op).
   */
  @Value("${reporters.sentry.dsn:}")
  private String dsn;

  /** Sentry environment tag (e.g. production, staging, development). */
  @Value("${reporters.sentry.environment:production}")
  private String environment;

  /** Application release version attached to all Sentry events. */
  @Value("${reporters.sentry.release:unknown}")
  private String release;

  /**
   * Override the server name reported to Sentry.
   * Defaults to {@code null}, which lets the Sentry SDK detect the hostname automatically.
   */
  @Value("${reporters.sentry.serverName:#{null}}")
  private String serverName;

  /**
   * Fraction of HTTP requests to send as Sentry Performance transactions (0.0–1.0).
   * 1.0 = 100% sampling (use in dev/test); lower in production to control volume.
   * 0.0 disables performance monitoring while keeping error capture active.
   */
  @Value("${reporters.sentry.tracesSampleRate:1.0}")
  private double tracesSampleRate;

  /**
   * When true, HTTP 5xx responses are captured as Sentry error events
   * in addition to the performance transaction.
   */
  @Value("${reporters.sentry.captureErrors:true}")
  private boolean captureErrors;

  /**
   * When true, endpoint health-check failures are captured as Sentry issues.
   */
  @Value("${reporters.sentry.reportHealthChecks:true}")
  private boolean reportHealthChecks;

  /**
   * When true, full request/response body logs (v4.log.Log, v4.log.MessageLog)
   * are attached as Sentry breadcrumbs. May generate high volume — disable in production.
   */
  @Value("${reporters.sentry.reportLogs:false}")
  private boolean reportLogs;

  /**
   * When true, async message metrics from Event-native APIs are reported
   * as Sentry Performance transactions.
   */
  @Value("${reporters.sentry.reportMessageMetrics:true}")
  private boolean reportMessageMetrics;

  /** Enable Sentry SDK internal debug logging. Use only for troubleshooting. */
  @Value("${reporters.sentry.debug:false}")
  private boolean debug;

  public boolean isEnabled() {
    return enabled;
  }

  public String getDsn() {
    return dsn;
  }

  public String getEnvironment() {
    return environment;
  }

  public String getRelease() {
    return release;
  }

  public String getServerName() {
    return serverName;
  }

  public double getTracesSampleRate() {
    return tracesSampleRate;
  }

  public boolean isCaptureErrors() {
    return captureErrors;
  }

  public boolean isReportHealthChecks() {
    return reportHealthChecks;
  }

  public boolean isReportLogs() {
    return reportLogs;
  }

  public boolean isReportMessageMetrics() {
    return reportMessageMetrics;
  }

  public boolean isDebug() {
    return debug;
  }
}
