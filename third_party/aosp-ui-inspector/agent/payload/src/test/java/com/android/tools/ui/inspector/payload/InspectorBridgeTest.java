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

import androidx.inspection.Connection;
import androidx.inspection.Inspector;

import com.android.tools.ui.inspector.payload.appinspection.AppInspectionUtils;
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public final class InspectorBridgeTest {

  private final Connection mockConnection = new Connection() {
    @Override
    public void sendEvent(byte[] data) {}
  };

  private HandlerThreadExecutor primaryExecutor;

  @Before
  public void setUp() {
    primaryExecutor = new HandlerThreadExecutor("test_bridge_thread", t -> {
      throw new RuntimeException(t);
    });
  }

  @After
  public void tearDown() {
    primaryExecutor.quitSafely();
  }

  @Test
  public void testSendCommand_sequentialProcessing() throws Exception {
    AtomicBoolean inProgress = new AtomicBoolean(false);
    Inspector mockInspectorSeq = new Inspector(mockConnection) {
      @Override
      public void onReceiveCommand(byte[] data, CommandCallback callback) {
        assertThat(inProgress.get()).isFalse();
        inProgress.set(true);
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        inProgress.set(false);
        callback.reply(data);
      }

      @Override
      public void onDispose() {}
    };

        InspectorBridge bridgeSeq =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspectorSeq,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor);

    CountDownLatch latch = new CountDownLatch(2);
    List<Throwable> errors = new CopyOnWriteArrayList<>();

    new Thread(() -> {
      try {
        bridgeSeq.sendCommand(new byte[]{1});
      } catch (Throwable t) {
        errors.add(t);
      } finally {
        latch.countDown();
      }
    }).start();

    new Thread(() -> {
      try {
        bridgeSeq.sendCommand(new byte[]{2});
      } catch (Throwable t) {
        errors.add(t);
      } finally {
        latch.countDown();
      }
    }).start();

    latch.await(5, TimeUnit.SECONDS);
    assertThat(errors).isEmpty();
  }

  @Test
  public void testSendCommand_executesOnPrimaryExecutorThread() throws Exception {
    AtomicReference<String> executionThreadName = new AtomicReference<>();
    Inspector mockInspector = new Inspector(mockConnection) {
      @Override
      public void onReceiveCommand(byte[] data, CommandCallback callback) {
        executionThreadName.set(Thread.currentThread().getName());
        callback.reply(data);
      }

      @Override
      public void onDispose() {}
    };

        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor);

    bridge.sendCommand(new byte[]{1});

    assertThat(executionThreadName.get()).startsWith("test_bridge_thread");
  }

  @Test
  public void testSendCommand_errorPropagation() {
    Inspector mockInspector = new Inspector(mockConnection) {
      @Override
      public void onReceiveCommand(byte[] data, CommandCallback callback) {
        throw new RuntimeException("Test exception");
      }

      @Override
      public void onDispose() {}
    };

        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor);

    boolean exceptionThrown = false;
    try {
      bridge.sendCommand(new byte[]{1});
    } catch (Exception e) {
      exceptionThrown = true;
      assertThat(e.getMessage()).contains("Test exception");
    }
    assertThat(exceptionThrown).isTrue();
  }

  @Test
  public void testUpdateConnection_updatesDelegatingConnection() {
    AppInspectionUtils.DelegatingConnection delegatingConnection = new AppInspectionUtils.DelegatingConnection();
    Inspector mockInspector = new Inspector(mockConnection) {
      @Override
      public void onReceiveCommand(byte[] data, CommandCallback callback) {}

      @Override
      public void onDispose() {}
    };

        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector", mockInspector, delegatingConnection, primaryExecutor);

    Connection newConnection = new Connection() {
      @Override
      public void sendEvent(byte[] data) {}
    };

    bridge.updateConnection(newConnection);

    assertThat(delegatingConnection.activeConnection).isSameAs(newConnection);
  }

  @Test
  public void testDispose_callsOnDisposeAndQuitsExecutor() throws Exception {
    AtomicBoolean disposed = new AtomicBoolean(false);
    Inspector mockInspector = new Inspector(mockConnection) {
      @Override
      public void onReceiveCommand(byte[] data, CommandCallback callback) {}

      @Override
      public void onDispose() {
        disposed.set(true);
      }
    };

        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor);

    bridge.dispose();

    primaryExecutor.awaitTermination(5, TimeUnit.SECONDS);

    assertThat(disposed.get()).isTrue();

    boolean exceptionThrown = false;
    try {
      bridge.sendCommand(new byte[]{1});
    } catch (Exception e) {
      exceptionThrown = true;
    }
    assertThat(exceptionThrown).isTrue();
  }

  @Test
  public void testDispose_preventsCommandRaceCondition() throws Exception {
    AtomicBoolean disposed = new AtomicBoolean(false);
    AtomicBoolean commandExecutedAfterDispose = new AtomicBoolean(false);

    Inspector mockInspector = new Inspector(mockConnection) {
      @Override
      public void onReceiveCommand(byte[] data, CommandCallback callback) {
        if (disposed.get()) {
          commandExecutedAfterDispose.set(true);
        }
        callback.reply(data);
      }

      @Override
      public void onDispose() {
        disposed.set(true);
      }
    };

    InspectorBridge bridge =
        InspectorBridge.createForTesting(
            "test_inspector",
            mockInspector,
            new AppInspectionUtils.DelegatingConnection(),
            primaryExecutor);

    // Call dispose
    bridge.dispose();

    // Immediately try to send a command.
    // Under the race condition (current code), this succeeds to queue and executes after disposal.
    // Under the fixed code, this should fail immediately with a RejectedExecutionException.
    boolean rejected = false;
    try {
      bridge.sendCommand(new byte[]{1});
    } catch (RejectedExecutionException e) {
      rejected = true;
    }

    primaryExecutor.awaitTermination(5, TimeUnit.SECONDS);

    assertThat(rejected).isTrue();
    assertThat(commandExecutedAfterDispose.get()).isFalse();
  }
}
