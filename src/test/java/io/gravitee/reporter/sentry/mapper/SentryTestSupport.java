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
