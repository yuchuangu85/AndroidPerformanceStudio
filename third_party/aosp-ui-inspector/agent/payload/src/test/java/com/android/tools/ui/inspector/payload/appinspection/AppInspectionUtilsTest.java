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

import androidx.inspection.ArtTooling;
import androidx.inspection.Connection;
import androidx.inspection.InspectorEnvironment;
import androidx.inspection.InspectorExecutors;

import com.android.tools.ui.inspector.common.FramingProtocol;
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol;
import com.android.tools.ui.inspector.service.ArtToolingBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public final class AppInspectionUtilsTest {

  private final Connection mockConnection = new Connection() {
    @Override
    public void sendEvent(byte[] data) {}
  };

  @Test
  public void testCreateInspectorEnvironment_ioExecutorDelegates() throws InterruptedException {
    HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test-thread", t -> {});
        InspectorEnvironment environment =
                AppInspectionUtils.createInspectorEnvironment(
                        "test_inspector", primaryExecutor, t -> {});

    CountDownLatch latch = new CountDownLatch(1);
    environment.executors().io().execute(latch::countDown);

    boolean completed = latch.await(5, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    primaryExecutor.quitSafely();
  }

  @Test
  public void testCreateInspectorEnvironment_ioExecutorCatchesException() throws InterruptedException {
    HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test-thread", t -> {});
    AtomicReference<Throwable> caughtThrowable = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    RuntimeException exception = new RuntimeException("Test exception");

        InspectorEnvironment environment =
                AppInspectionUtils.createInspectorEnvironment(
                        "test_inspector",
                        primaryExecutor,
                        t -> {
                            caughtThrowable.set(t);
                            latch.countDown();
                        });

    environment.executors().io().execute(() -> {
      throw exception;
    });

    boolean completed = latch.await(5, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    assertThat(caughtThrowable.get()).isEqualTo(exception);
    primaryExecutor.quitSafely();
  }

  @Test
  public void testCreateAppInspectionConnection_wrapsEventWithId() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    String inspectorId = "test_inspector_id";
    Connection connection = AppInspectionUtils.createAppInspectionConnection(inspectorId, outputStream, t -> {});

    byte[] eventPayload = new byte[]{1, 2, 3};
    connection.sendEvent(eventPayload);

    byte[] writtenBytes = outputStream.toByteArray();
    byte[] responseBytes = FramingProtocol.readMessage(new ByteArrayInputStream(writtenBytes));
        UiInspectorProtocol.AgentMessage agentMessage =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes);
        assertThat(agentMessage.hasEvent()).isTrue();
        UiInspectorProtocol.Event event = agentMessage.getEvent();

    assertThat(event.getSpecializedCase())
        .isEqualTo(UiInspectorProtocol.Event.SpecializedCase.INSPECTOR_MESSAGE);
    assertThat(event.getInspectorMessage().getInspectorId()).isEqualTo(inspectorId);
    assertThat(event.getInspectorMessage().getPayload().toByteArray()).isEqualTo(eventPayload);
  }

  @Test
  public void testDelegatingConnection_delegates() {
    AtomicReference<byte[]> capturedEvent = new AtomicReference<>();
    Connection realConnection = new Connection() {
      @Override
      public void sendEvent(byte[] data) {
        capturedEvent.set(data);
      }
    };

    AppInspectionUtils.DelegatingConnection delegatingConnection = new AppInspectionUtils.DelegatingConnection();
    delegatingConnection.activeConnection = realConnection;

    byte[] eventPayload = new byte[]{4, 5};
    delegatingConnection.sendEvent(eventPayload);

    assertThat(capturedEvent.get()).isEqualTo(eventPayload);
  }

  @Test
  public void testLoadInspectorDynamically_throwsOnInvalidPath() {
    InspectorEnvironment mockEnvironment = new InspectorEnvironment() {
      @Override
      public InspectorExecutors executors() {
        throw new UnsupportedOperationException("Not implemented");
      }

      @Override
      public ArtTooling artTooling() {
        throw new UnsupportedOperationException("Not implemented");
      }
    };

    boolean exceptionThrown = false;
    try {
            AppInspectionUtils.loadInspectorDynamically(
                    "test_id", "/invalid/path.dex", mockConnection, mockEnvironment);
    } catch (Exception e) {
      exceptionThrown = true;
      assertThat(e.getMessage()).contains("Failed to find InspectorFactory");
    }
    assertThat(exceptionThrown).isTrue();
  }

    @Test
    public void testCreateInspectorEnvironment_artToolingSafety() {
        HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test-thread", t -> {});
        InspectorEnvironment environment =
                AppInspectionUtils.createInspectorEnvironment(
                        "test_inspector", primaryExecutor, t -> {});
        ArtTooling artTooling = environment.artTooling();
        assertThat(artTooling).isNotNull();

        // Verify calling methods is safe when sAgentPtr is 0
        assertThat(artTooling.findInstances(Object.class)).isEmpty();
        artTooling.registerEntryHook(
                Object.class, "toString()Ljava/lang/String;", (self, params) -> {});
        artTooling.registerExitHook(
                Object.class, "toString()Ljava/lang/String;", returnValue -> returnValue);

        primaryExecutor.quitSafely();
    }

    @Test
    public void testCreateInspectorEnvironment_delegatesToBridge() {
        HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test-thread", t -> {});
        InspectorEnvironment environment =
                AppInspectionUtils.createInspectorEnvironment(
                        "test_inspector", primaryExecutor, t -> {});
        ArtTooling artTooling = environment.artTooling();

        // Initialize with dummy agent pointer to trigger native calls
        ArtToolingBridge.initialize(1234L);

        boolean threwLinkError = false;
        try {
            artTooling.findInstances(Object.class);
        } catch (UnsatisfiedLinkError e) {
            threwLinkError = true;
        }
        assertThat(threwLinkError).isTrue();

        // Reset bridge state
        ArtToolingBridge.initialize(0L);
        primaryExecutor.quitSafely();
    }

    @Test
    public void testClearHooksReleasesReferences() {
        ArtToolingBridge.initialize(1234L);

        AtomicBoolean entryCalled = new AtomicBoolean(false);
        AtomicBoolean exitCalled = new AtomicBoolean(false);

        ArtToolingBridge.registerEntryHook(
                Object.class,
                "toString()Ljava/lang/String;",
                "test_inspector",
                (self, params) -> {
                    entryCalled.set(true);
                });
        ArtToolingBridge.registerExitHook(
                Object.class,
                "toString()Ljava/lang/String;",
                "test_inspector",
                returnValue -> {
                    exitCalled.set(true);
                    return returnValue;
                });

        String label = "Ljava/lang/Object;->toString()Ljava/lang/String;";

        // Trigger hooks and assert they are called
        ArtToolingBridge.onEntry(new Object[] {label, new Object()});
        ArtToolingBridge.onExit(label, new Object());
        assertThat(entryCalled.get()).isTrue();
        assertThat(exitCalled.get()).isTrue();

        // Reset flags
        entryCalled.set(false);
        exitCalled.set(false);

        // Clear the hooks
        ArtToolingBridge.clear();

        // Trigger hooks again and assert they are NOT called
        ArtToolingBridge.onEntry(new Object[] {label, new Object()});
        ArtToolingBridge.onExit(label, new Object());
        assertThat(entryCalled.get()).isFalse();
        assertThat(exitCalled.get()).isFalse();

        // Reset bridge state
        ArtToolingBridge.initialize(0L);
    }

    @Test
    public void testClearHooksByInspectorId() {
        ArtToolingBridge.initialize(1234L);

        String inspectorId1 = "inspector_1";
        String inspectorId2 = "inspector_2";

        AtomicBoolean entry1Called = new AtomicBoolean(false);
        AtomicBoolean entry2Called = new AtomicBoolean(false);

        ArtToolingBridge.EntryHookBridge hook1 =
                (thisObject, args) -> {
                    entry1Called.set(true);
                };

        ArtToolingBridge.EntryHookBridge hook2 =
                (thisObject, args) -> {
                    entry2Called.set(true);
                };

        ArtToolingBridge.registerEntryHook(
                Object.class, "toString()Ljava/lang/String;", inspectorId1, hook1);
        ArtToolingBridge.registerEntryHook(
                Object.class, "toString()Ljava/lang/String;", inspectorId2, hook2);

        String label = "Ljava/lang/Object;->toString()Ljava/lang/String;";

        // Trigger hooks and assert both are called
        ArtToolingBridge.onEntry(new Object[] {label, new Object()});
        assertThat(entry1Called.get()).isTrue();
        assertThat(entry2Called.get()).isTrue();

        // Reset
        entry1Called.set(false);
        entry2Called.set(false);

        // Clear only inspectorId1 hooks
        ArtToolingBridge.clear(inspectorId1);

        // Trigger hooks and assert only hook2 is called
        ArtToolingBridge.onEntry(new Object[] {label, new Object()});
        assertThat(entry1Called.get()).isFalse();
        assertThat(entry2Called.get()).isTrue();

        // Reset bridge state
        ArtToolingBridge.clear();
        ArtToolingBridge.initialize(0L);
    }
}
