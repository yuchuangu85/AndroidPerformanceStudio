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

import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.inspection.Connection;

import com.android.tools.idea.protobuf.ByteString;
import com.android.tools.ui.inspector.common.FramingProtocol;
import com.android.tools.ui.inspector.common.ProtocolConstants;
import com.android.tools.ui.inspector.payload.appinspection.AppInspectionUtils;
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor;
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Handles a single connection session with the host. */
public final class SessionHandler {
  private static final String TAG = ProtocolConstants.LOG_TAG_PREFIX + ".SessionHandler";

  private final InputStream inputStream;
  private final OutputStream outputStream;
  private final HandlerThreadExecutor.CrashListener crashListener;
  private final CountDownLatch shutdownLatch;
  private final Map<String, InspectorBridge> inspectorBridges;
  private final ClassLoader classLoader;

  public SessionHandler(
      InputStream inputStream,
      OutputStream outputStream,
      HandlerThreadExecutor.CrashListener crashListener,
      CountDownLatch shutdownLatch,
      Map<String, InspectorBridge> inspectorBridges) {
    this(inputStream, outputStream, crashListener, shutdownLatch, inspectorBridges, SessionHandler.class.getClassLoader());
  }

  @VisibleForTesting
  public SessionHandler(
      InputStream inputStream,
      OutputStream outputStream,
      HandlerThreadExecutor.CrashListener crashListener,
      CountDownLatch shutdownLatch,
      Map<String, InspectorBridge> inspectorBridges,
      ClassLoader classLoader) {
    this.inputStream = inputStream;
    this.outputStream = outputStream;
    this.crashListener = crashListener;
    this.shutdownLatch = shutdownLatch;
    this.inspectorBridges = inspectorBridges;
    this.classLoader = classLoader;
  }

  /**
   * Reads and executes a continuous stream of commands from the host. Loops until the connection is broken or a shutdown command is
   * received.
   */
  public void processCommands() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        byte[] commandBytes = FramingProtocol.readMessage(inputStream);
        UiInspectorProtocol.Command command = UiInspectorProtocol.Command.parseFrom(commandBytes);
        int commandId = command.getCommandId();

        boolean shouldTerminate = handleCommand(command, commandId);
        if (shouldTerminate) {
          break;
        }
      }
    } catch (EOFException e) {
      Log.i(TAG, "Client disconnected (EOF)");
    } catch (IOException e) {
      Log.i(TAG, "Client connection lost: " + e.getMessage());
    } catch (Exception e) {
      Log.e(TAG, "Error handling client session", e);
      crashListener.onCrash(e);
    } finally {
      // Clear active connections in each inspector
      for (InspectorBridge bridge : inspectorBridges.values()) {
        bridge.updateConnection(null);
      }
    }
  }

  /** Handles a single command and returns whether to terminate the session. Every command must receive a response. */
  private boolean handleCommand(UiInspectorProtocol.Command command, int commandId) throws IOException {
    try {
      switch (command.getSpecializedCase()) {
        case INSPECTOR_MESSAGE:
          handleInspectorMessage(command.getInspectorMessage(), commandId);
          return false;
        case CREATE_INSPECTOR:
          handleCreateInspector(command.getCreateInspector(), commandId);
          return false;
        case SHUTDOWN:
          handleShutdown(commandId);
          return true;
        case GET_VERSION:
          handleGetVersion(command.getGetVersion(), commandId);
          return false;
        default:
                    throw new IllegalStateException(
                            "Unhandled top-level command: " + command.getSpecializedCase());
            }
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      Log.e(TAG, "Error handling command", e);
      replyError(commandId, "Error handling command: " + e.getMessage());
      return false;
    }
  }

  private void handleInspectorMessage(UiInspectorProtocol.InspectorMessageCommand message, int commandId) throws Exception {
    InspectorBridge inspectorBridge = inspectorBridges.get(message.getInspectorId());
    if (inspectorBridge == null) {
      throw new IllegalStateException("Unknown inspector ID: " + message.getInspectorId());
    }
    byte[] responseBytes = inspectorBridge.sendCommand(message.getPayload().toByteArray());

    UiInspectorProtocol.Response.Builder responseBuilder = UiInspectorProtocol.Response.newBuilder();
    responseBuilder.setInspectorMessage(
        UiInspectorProtocol.InspectorMessageResponse.newBuilder()
            .setInspectorId(message.getInspectorId())
            .setPayload(ByteString.copyFrom(responseBytes))
            .build());
    reply(commandId, responseBuilder);
  }

  private void handleCreateInspector(UiInspectorProtocol.CreateInspectorCommand command, int commandId) throws Exception {
    String inspectorId = command.getInspectorId();
    String dexPath = command.getDexPath();

    InspectorBridge bridge = inspectorBridges.get(inspectorId);
    if (bridge != null) {
      // Update the bridge with the new session's connection to resume event sending.
      Connection newRealConnection = AppInspectionUtils.createAppInspectionConnection(inspectorId, outputStream, crashListener);
      bridge.updateConnection(newRealConnection);
    } else {
      AppInspectionUtils.DelegatingConnection delegatingConnection = new AppInspectionUtils.DelegatingConnection();
      Connection realConnection = AppInspectionUtils.createAppInspectionConnection(inspectorId, outputStream, crashListener);
      delegatingConnection.activeConnection = realConnection;
      InspectorBridge newBridge = InspectorBridge.create(inspectorId, dexPath, delegatingConnection, crashListener);

      inspectorBridges.put(inspectorId, newBridge);
    }

    UiInspectorProtocol.Response.Builder responseBuilder = UiInspectorProtocol.Response.newBuilder();
    responseBuilder.setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.newBuilder().build());
    reply(commandId, responseBuilder);
  }

  private void handleShutdown(int commandId) throws IOException {
    UiInspectorProtocol.Response.Builder responseBuilder = UiInspectorProtocol.Response.newBuilder();
    responseBuilder.setShutdown(UiInspectorProtocol.ShutdownResponse.newBuilder().build());
    reply(commandId, responseBuilder);
    shutdownLatch.countDown();
  }

  private void handleGetVersion(UiInspectorProtocol.GetVersionCommand command, int commandId) throws IOException {
    Map<String, String> versions = new HashMap<>();
    for (String libraryId : command.getLibraryIdsList()) {
      String version = performVersionDetection(libraryId);
      if (version != null) {
        versions.put(libraryId, version);
      }
    }

    UiInspectorProtocol.Response.Builder responseBuilder = UiInspectorProtocol.Response.newBuilder();
    responseBuilder.setGetVersion(UiInspectorProtocol.GetVersionResponse.newBuilder().putAllVersions(versions).build());
    reply(commandId, responseBuilder);
  }

  private String performVersionDetection(String libraryId) {
    if (ProtocolConstants.COMPOSE_UI_LIBRARY_ID.equals(libraryId)) {
      return ComposeInspectionUtils.detectComposeVersion(classLoader);
    } else {
      throw new IllegalArgumentException("Unsupported library ID: " + libraryId);
    }
  }

  private void reply(int commandId, UiInspectorProtocol.Response.Builder responseBuilder) throws IOException {
    writeResponse(commandId, UiInspectorProtocol.Response.Status.SUCCESS, null, responseBuilder);
  }

  private void replyError(int commandId, String errorMessage) throws IOException {
    writeResponse(commandId, UiInspectorProtocol.Response.Status.ERROR, errorMessage, UiInspectorProtocol.Response.newBuilder());
  }

  private void writeResponse(
      int commandId,
      UiInspectorProtocol.Response.Status status,
      String errorMessage,
      UiInspectorProtocol.Response.Builder responseBuilder) throws IOException {
    responseBuilder.setCommandId(commandId).setStatus(status);
    if (errorMessage != null) {
      responseBuilder.setErrorMessage(errorMessage);
    }
        UiInspectorProtocol.AgentMessage agentMessage =
                UiInspectorProtocol.AgentMessage.newBuilder()
                        .setResponse(responseBuilder.build())
                        .build();
        FramingProtocol.writeMessage(outputStream, agentMessage.toByteArray());
  }
}
