/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.ui.inspector.payload.appinspection;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class HandlerThreadExecutorTest {

  @Test
  public void testExecute_sequential() throws InterruptedException {
    HandlerThreadExecutor executor = new HandlerThreadExecutor("test-thread", t -> {});
    List<Integer> list = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch latch = new CountDownLatch(2);

    executor.execute(() -> {
      list.add(1);
      latch.countDown();
    });
    executor.execute(() -> {
      list.add(2);
      latch.countDown();
    });

    latch.await(5, TimeUnit.SECONDS);
    assertThat(list).containsExactly(1, 2).inOrder();
    executor.quitSafely();
  }

  @Test
  public void testExecute_catchesException() throws InterruptedException {
    AtomicReference<Throwable> caughtThrowable = new AtomicReference<>();
    HandlerThreadExecutor executor = new HandlerThreadExecutor("test-thread", caughtThrowable::set);
    CountDownLatch latch = new CountDownLatch(1);
    RuntimeException exception = new RuntimeException("Test exception");

    executor.execute(() -> {
      throw exception;
    });

    executor.execute(latch::countDown);

    latch.await(5, TimeUnit.SECONDS);
    assertThat(caughtThrowable.get()).isEqualTo(exception);
    executor.quitSafely();
  }

  @Test
  public void testExecute_threadSurvivesException() throws InterruptedException {
    HandlerThreadExecutor executor = new HandlerThreadExecutor("test-thread", t -> {});
    CountDownLatch latch = new CountDownLatch(1);
    List<Integer> list = Collections.synchronizedList(new ArrayList<>());

    executor.execute(() -> {
      throw new RuntimeException("Test exception");
    });

    executor.execute(() -> {
      list.add(1);
      latch.countDown();
    });

    latch.await(5, TimeUnit.SECONDS);
    assertThat(list).containsExactly(1);
    executor.quitSafely();
  }
}
