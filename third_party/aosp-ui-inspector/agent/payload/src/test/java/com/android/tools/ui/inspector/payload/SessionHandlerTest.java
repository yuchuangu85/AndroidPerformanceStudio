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

import com.android.tools.ui.inspector.common.FramingProtocol;
import com.android.tools.ui.inspector.common.ProtocolConstants;
import com.android.tools.ui.inspector.payload.appinspection.AppInspectionUtils;
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor;
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
public final class SessionHandlerTest {

  private final Connection mockConnection = new Connection() {
    @Override
    public void sendEvent(byte[] data) {}
  };

  @Test
  public void testHandle_shutdown_disposesInspector() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    UiInspectorProtocol.Command command = UiInspectorProtocol.Command.newBuilder()
        .setCommandId(1)
        .setShutdown(UiInspectorProtocol.ShutdownCommand.getDefaultInstance())
        .build();

    ByteArrayOutputStream inputStreamData = new ByteArrayOutputStream();
    FramingProtocol.writeMessage(inputStreamData, command.toByteArray());
    ByteArrayInputStream inputStream = new ByteArrayInputStream(inputStreamData.toByteArray());

    AtomicBoolean disposed = new AtomicBoolean(false);
    Inspector mockInspector = new Inspector(mockConnection) {
      @Override
      public void onReceiveCommand(byte[] data, CommandCallback callback) {}

      @Override
      public void onDispose() {
        disposed.set(true);
      }
    };

    CountDownLatch shutdownLatch = new CountDownLatch(1);
    Map<String, InspectorBridge> bridges = new HashMap<>();
    HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test_thread_shutdown", t -> {
      throw new RuntimeException(t);
    });

        bridges.put(
                ProtocolConstants.VIEW_INSPECTOR_ID,
                InspectorBridge.createForTesting(
                        ProtocolConstants.VIEW_INSPECTOR_ID,
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor));

    SessionHandler sessionHandler = new SessionHandler(
        inputStream,
        outputStream,
        t -> {
          throw new RuntimeException(t);
        },
        shutdownLatch,
        bridges
    );

    sessionHandler.processCommands();
    if (shutdownLatch.getCount() == 0) {
      for (InspectorBridge bridge : bridges.values()) {
        bridge.dispose();
      }
    }

    primaryExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(disposed.get()).isTrue();
    assertThat(shutdownLatch.getCount()).isEqualTo(0);

    byte[] writtenBytes = outputStream.toByteArray();
    byte[] responseBytes = FramingProtocol.readMessage(new ByteArrayInputStream(writtenBytes));
        UiInspectorProtocol.AgentMessage agentMessage =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes);
        assertThat(agentMessage.hasResponse()).isTrue();
        UiInspectorProtocol.Response response = agentMessage.getResponse();

    assertThat(response.getCommandId()).isEqualTo(1);
    assertThat(response.getStatus()).isEqualTo(UiInspectorProtocol.Response.Status.SUCCESS);
    assertThat(response.getSpecializedCase()).isEqualTo(UiInspectorProtocol.Response.SpecializedCase.SHUTDOWN);
  }

  @Test
  public void testHandle_eof_doesNotDisposeInspector() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(new byte[0]);

    AtomicBoolean disposed = new AtomicBoolean(false);
    Inspector mockInspector = new Inspector(mockConnection) {
      @Override
      public void onReceiveCommand(byte[] data, CommandCallback callback) {}

      @Override
      public void onDispose() {
        disposed.set(true);
      }
    };

    CountDownLatch shutdownLatch = new CountDownLatch(1);
    Map<String, InspectorBridge> bridges = new HashMap<>();
    HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test_thread_eof", t -> {
      throw new RuntimeException(t);
    });
        bridges.put(
                ProtocolConstants.VIEW_INSPECTOR_ID,
                InspectorBridge.createForTesting(
                        ProtocolConstants.VIEW_INSPECTOR_ID,
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor));

    SessionHandler sessionHandler = new SessionHandler(
        inputStream,
        outputStream,
        t -> {
          throw new RuntimeException(t);
        },
        shutdownLatch,
        bridges
    );

    sessionHandler.processCommands();
    primaryExecutor.quitSafely();

    assertThat(disposed.get()).isFalse();
    assertThat(shutdownLatch.getCount()).isEqualTo(1);
  }

  @Test
  public void testHandle_createInspector_failsWithInvalidPath() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    UiInspectorProtocol.Command command = UiInspectorProtocol.Command.newBuilder()
        .setCommandId(1)
        .setCreateInspector(UiInspectorProtocol.CreateInspectorCommand.newBuilder()
            .setInspectorId("test_inspector")
            .setDexPath("/path/to/test.dex")
            .build())
        .build();

    ByteArrayOutputStream inputStreamData = new ByteArrayOutputStream();
    FramingProtocol.writeMessage(inputStreamData, command.toByteArray());
    ByteArrayInputStream inputStream = new ByteArrayInputStream(inputStreamData.toByteArray());

    CountDownLatch shutdownLatch = new CountDownLatch(1);
    SessionHandler sessionHandler = new SessionHandler(
        inputStream,
        outputStream,
        t -> {
          throw new RuntimeException(t);
        },
        shutdownLatch,
        new HashMap<>()
    );

    sessionHandler.processCommands();

    byte[] writtenBytes = outputStream.toByteArray();
    byte[] responseBytes = FramingProtocol.readMessage(new ByteArrayInputStream(writtenBytes));
        UiInspectorProtocol.AgentMessage agentMessage =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes);
        assertThat(agentMessage.hasResponse()).isTrue();
        UiInspectorProtocol.Response response = agentMessage.getResponse();

    assertThat(response.getCommandId()).isEqualTo(1);
    assertThat(response.getStatus()).isEqualTo(UiInspectorProtocol.Response.Status.ERROR);
    assertThat(response.getErrorMessage()).contains("Failed to find InspectorFactory");
  }

  @Test
  public void testHandle_unknownInspector_fails() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    UiInspectorProtocol.Command command = UiInspectorProtocol.Command.newBuilder()
        .setCommandId(1)
        .setInspectorMessage(UiInspectorProtocol.InspectorMessageCommand.newBuilder()
            .setInspectorId("unknown_inspector")
            .setPayload(com.android.tools.idea.protobuf.ByteString.copyFrom(new byte[0]))
            .build())
        .build();

    ByteArrayOutputStream inputStreamData = new ByteArrayOutputStream();
    FramingProtocol.writeMessage(inputStreamData, command.toByteArray());
    ByteArrayInputStream inputStream = new ByteArrayInputStream(inputStreamData.toByteArray());

    CountDownLatch shutdownLatch = new CountDownLatch(1);
    SessionHandler sessionHandler = new SessionHandler(
        inputStream,
        outputStream,
        t -> {
          throw new RuntimeException(t);
        },
        shutdownLatch,
        new HashMap<>()
    );

    sessionHandler.processCommands();

    byte[] writtenBytes = outputStream.toByteArray();
    byte[] responseBytes = FramingProtocol.readMessage(new ByteArrayInputStream(writtenBytes));
        UiInspectorProtocol.AgentMessage agentMessage =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes);
        assertThat(agentMessage.hasResponse()).isTrue();
        UiInspectorProtocol.Response response = agentMessage.getResponse();

    assertThat(response.getCommandId()).isEqualTo(1);
    assertThat(response.getStatus()).isEqualTo(UiInspectorProtocol.Response.Status.ERROR);
    assertThat(response.getErrorMessage()).contains("Unknown inspector ID");
  }

  @Test
  public void testHandle_multipleCommands_processesAll() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    UiInspectorProtocol.Command command1 = UiInspectorProtocol.Command.newBuilder()
        .setCommandId(1)
        .setInspectorMessage(UiInspectorProtocol.InspectorMessageCommand.newBuilder()
            .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
            .setPayload(com.android.tools.idea.protobuf.ByteString.copyFrom(new byte[]{10}))
            .build())
        .build();

    UiInspectorProtocol.Command command2 = UiInspectorProtocol.Command.newBuilder()
        .setCommandId(2)
        .setInspectorMessage(UiInspectorProtocol.InspectorMessageCommand.newBuilder()
            .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
            .setPayload(com.android.tools.idea.protobuf.ByteString.copyFrom(new byte[]{20}))
            .build())
        .build();

    ByteArrayOutputStream inputStreamData = new ByteArrayOutputStream();
    FramingProtocol.writeMessage(inputStreamData, command1.toByteArray());
    FramingProtocol.writeMessage(inputStreamData, command2.toByteArray());
    ByteArrayInputStream inputStream = new ByteArrayInputStream(inputStreamData.toByteArray());

    List<byte[]> receivedPayloads = new ArrayList<>();
    Inspector mockInspector = new Inspector(mockConnection) {
      @Override
      public void onReceiveCommand(byte[] data, CommandCallback callback) {
        receivedPayloads.add(data);
        callback.reply(new byte[]{30});
      }

      @Override
      public void onDispose() {}
    };

    CountDownLatch shutdownLatch = new CountDownLatch(1);
    Map<String, InspectorBridge> bridges = new HashMap<>();
    HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test_thread_multiple", t -> {
      throw new RuntimeException(t);
    });
        bridges.put(
                ProtocolConstants.VIEW_INSPECTOR_ID,
                InspectorBridge.createForTesting(
                        ProtocolConstants.VIEW_INSPECTOR_ID,
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor));

    SessionHandler sessionHandler = new SessionHandler(
        inputStream,
        outputStream,
        t -> {
          throw new RuntimeException(t);
        },
        shutdownLatch,
        bridges
    );

    sessionHandler.processCommands();
    primaryExecutor.quitSafely();

    assertThat(receivedPayloads).hasSize(2);
    assertThat(receivedPayloads.get(0)).isEqualTo(new byte[]{10});
    assertThat(receivedPayloads.get(1)).isEqualTo(new byte[]{20});

    byte[] writtenBytes = outputStream.toByteArray();
    ByteArrayInputStream responseInputStream = new ByteArrayInputStream(writtenBytes);

    byte[] responseBytes1 = FramingProtocol.readMessage(responseInputStream);
        UiInspectorProtocol.AgentMessage agentMessage1 =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes1);
        assertThat(agentMessage1.hasResponse()).isTrue();
        UiInspectorProtocol.Response response1 = agentMessage1.getResponse();
    assertThat(response1.getCommandId()).isEqualTo(1);

    byte[] responseBytes2 = FramingProtocol.readMessage(responseInputStream);
        UiInspectorProtocol.AgentMessage agentMessage2 =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes2);
        assertThat(agentMessage2.hasResponse()).isTrue();
        UiInspectorProtocol.Response response2 = agentMessage2.getResponse();
    assertThat(response2.getCommandId()).isEqualTo(2);
  }

  @Test
  public void testHandle_getVersion_composeAbsent() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    UiInspectorProtocol.Command command = UiInspectorProtocol.Command.newBuilder()
        .setCommandId(1)
        .setGetVersion(UiInspectorProtocol.GetVersionCommand.newBuilder()
            .addLibraryIds(ProtocolConstants.COMPOSE_UI_LIBRARY_ID)
            .build())
        .build();

    ByteArrayOutputStream inputStreamData = new ByteArrayOutputStream();
    FramingProtocol.writeMessage(inputStreamData, command.toByteArray());
    ByteArrayInputStream inputStream = new ByteArrayInputStream(inputStreamData.toByteArray());

    ClassLoader mockAbsentClassLoader = new ClassLoader(SessionHandlerTest.class.getClassLoader()) {
      @Override
      public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if ("androidx.compose.ui.Modifier".equals(name)) {
          throw new ClassNotFoundException("androidx.compose.ui.Modifier");
        }
        return super.loadClass(name, resolve);
      }
    };

    CountDownLatch shutdownLatch = new CountDownLatch(1);
    SessionHandler sessionHandler = new SessionHandler(
        inputStream,
        outputStream,
        t -> {
          throw new RuntimeException(t);
        },
        shutdownLatch,
        new HashMap<>(),
        mockAbsentClassLoader
    );

    sessionHandler.processCommands();

    byte[] writtenBytes = outputStream.toByteArray();
    byte[] responseBytes = FramingProtocol.readMessage(new ByteArrayInputStream(writtenBytes));
        UiInspectorProtocol.AgentMessage agentMessage =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes);
        assertThat(agentMessage.hasResponse()).isTrue();
        UiInspectorProtocol.Response response = agentMessage.getResponse();

    assertThat(response.getCommandId()).isEqualTo(1);
    assertThat(response.getStatus()).isEqualTo(UiInspectorProtocol.Response.Status.SUCCESS);
    assertThat(response.getSpecializedCase()).isEqualTo(UiInspectorProtocol.Response.SpecializedCase.GET_VERSION);

    UiInspectorProtocol.GetVersionResponse versionResponse = response.getGetVersion();
    assertThat(versionResponse.getVersionsMap()).isEmpty();
  }

  @Test
  public void testHandle_getVersion_composePresent() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    UiInspectorProtocol.Command command = UiInspectorProtocol.Command.newBuilder()
        .setCommandId(1)
        .setGetVersion(UiInspectorProtocol.GetVersionCommand.newBuilder()
            .addLibraryIds(ProtocolConstants.COMPOSE_UI_LIBRARY_ID)
            .build())
        .build();

    ByteArrayOutputStream inputStreamData = new ByteArrayOutputStream();
    FramingProtocol.writeMessage(inputStreamData, command.toByteArray());
    ByteArrayInputStream inputStream = new ByteArrayInputStream(inputStreamData.toByteArray());

    ClassLoader mockClassLoader = new ClassLoader(SessionHandlerTest.class.getClassLoader()) {
      @Override
      public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if ("androidx.compose.ui.Modifier".equals(name)) {
          return Object.class;
        }
        return super.loadClass(name, resolve);
      }

      @Override
      public InputStream getResourceAsStream(String name) {
        if ("META-INF/androidx.compose.ui_ui.version".equals(name)) {
          return new ByteArrayInputStream("1.5.4".getBytes());
        }
        return super.getResourceAsStream(name);
      }
    };

    CountDownLatch shutdownLatch = new CountDownLatch(1);
    SessionHandler sessionHandler = new SessionHandler(
        inputStream,
        outputStream,
        t -> {
          throw new RuntimeException(t);
        },
        shutdownLatch,
        new HashMap<>(),
        mockClassLoader
    );

    sessionHandler.processCommands();

    byte[] writtenBytes = outputStream.toByteArray();
    byte[] responseBytes = FramingProtocol.readMessage(new ByteArrayInputStream(writtenBytes));
        UiInspectorProtocol.AgentMessage agentMessage =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes);
        assertThat(agentMessage.hasResponse()).isTrue();
        UiInspectorProtocol.Response response = agentMessage.getResponse();

    assertThat(response.getCommandId()).isEqualTo(1);
    assertThat(response.getStatus()).isEqualTo(UiInspectorProtocol.Response.Status.SUCCESS);
    assertThat(response.getSpecializedCase()).isEqualTo(UiInspectorProtocol.Response.SpecializedCase.GET_VERSION);

    UiInspectorProtocol.GetVersionResponse versionResponse = response.getGetVersion();
        assertThat(versionResponse.getVersionsMap())
                .containsExactly(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.5.4");
  }

  @Test
  public void testHandle_getVersion_unknownLibrary_fails() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    UiInspectorProtocol.Command command = UiInspectorProtocol.Command.newBuilder()
        .setCommandId(1)
        .setGetVersion(UiInspectorProtocol.GetVersionCommand.newBuilder()
            .addLibraryIds("invalid:lib")
            .build())
        .build();

    ByteArrayOutputStream inputStreamData = new ByteArrayOutputStream();
    FramingProtocol.writeMessage(inputStreamData, command.toByteArray());
    ByteArrayInputStream inputStream = new ByteArrayInputStream(inputStreamData.toByteArray());

    CountDownLatch shutdownLatch = new CountDownLatch(1);
    SessionHandler sessionHandler = new SessionHandler(
        inputStream,
        outputStream,
        t -> {
          throw new RuntimeException(t);
        },
        shutdownLatch,
        new HashMap<>()
    );

    sessionHandler.processCommands();

    byte[] writtenBytes = outputStream.toByteArray();
    byte[] responseBytes = FramingProtocol.readMessage(new ByteArrayInputStream(writtenBytes));
        UiInspectorProtocol.AgentMessage agentMessage =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes);
        assertThat(agentMessage.hasResponse()).isTrue();
        UiInspectorProtocol.Response response = agentMessage.getResponse();

    assertThat(response.getCommandId()).isEqualTo(1);
    assertThat(response.getStatus()).isEqualTo(UiInspectorProtocol.Response.Status.ERROR);
    assertThat(response.getErrorMessage()).contains("Unsupported library ID: invalid:lib");
  }
}
