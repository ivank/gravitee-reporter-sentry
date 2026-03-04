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
package io.gravitee.reporter.sentry.mapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;
import org.mockito.MockedStatic;

/**
 * Shared test utilities for mapper unit tests.
 */
final class SentryTestSupport {

  private SentryTestSupport() {}

  /**
   * Stubs {@link Sentry#startTransaction} to return a mock {@link ITransaction} whose
   * {@link ITransaction#getStatus()} returns the given {@code status}.
   *
   * @param sentryMock active {@link MockedStatic} scope for {@link Sentry}
   * @param status     the status the mock transaction will report
   * @return the configured mock transaction
   */
  static ITransaction mockTransaction(MockedStatic<Sentry> sentryMock, SpanStatus status) {
    ITransaction mockTx = mock(ITransaction.class);
    when(mockTx.getStatus()).thenReturn(status);
    sentryMock.when(() -> Sentry.startTransaction(anyString(), anyString(), any())).thenReturn(mockTx);
    return mockTx;
  }
}
