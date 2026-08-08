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

package com.android.tools.ui.inspector.payload;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class InspectorLauncherTest {

  @Test
  public void testStart_doesNotStartNewServerIfAlreadyRunning() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(1);

    Consumer<String> starter = pid -> {
      callCount.incrementAndGet();
      try {
        latch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };

    InspectorLauncher.start("1234", starter);

    Thread.sleep(100);

    InspectorLauncher.start("1234", starter);

    latch.countDown();

    assertThat(callCount.get()).isEqualTo(1);
  }
}
