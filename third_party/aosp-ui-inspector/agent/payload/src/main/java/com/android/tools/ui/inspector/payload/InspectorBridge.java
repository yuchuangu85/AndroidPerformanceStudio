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
import androidx.inspection.Inspector;
import androidx.inspection.InspectorEnvironment;

import com.android.tools.ui.inspector.payload.appinspection.AppInspectionUtils;
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor;
import com.android.tools.ui.inspector.service.ArtToolingBridge;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bridges communication between the server and a specific [Inspector], enabling persistence across host reconnections and ensuring
 * sequential command processing.
 */
public final class InspectorBridge {
  private static final String THREAD_NAME_PREFIX = "ui_inspector_";

    private final String inspectorId;
  private final Inspector inspector;
  private final AppInspectionUtils.DelegatingConnection connection;
  private final HandlerThreadExecutor primaryExecutor;

    private InspectorBridge(
            String inspectorId,
            Inspector inspector,
            AppInspectionUtils.DelegatingConnection connection,
            HandlerThreadExecutor primaryExecutor) {
        this.inspectorId = inspectorId;
    this.inspector = inspector;
    this.connection = connection;
    this.primaryExecutor = primaryExecutor;
  }

  /**
   * Updates the connection used by the inspector to send events. Since we re-use the inspectors across different connections, this is
   * required when a host reconnects and a new socket session is established.
   */
  public void updateConnection(Connection newConnection) {
    connection.activeConnection = newConnection;
  }

  /** Sends a command to the inspector and waits for the response. */
  public byte[] sendCommand(byte[] command) throws Exception {
    CompletableFuture<byte[]> future = new CompletableFuture<>();
    primaryExecutor.execute(() -> {
      try {
        inspector.onReceiveCommand(command, new Inspector.CommandCallback() {
          @Override
          public void reply(byte[] responseBytes) {
            future.complete(responseBytes);
          }

          @Override
          public void addCancellationListener(Executor executor, Runnable runnable) {
            future.whenComplete((result, exception) -> {
              if (future.isCancelled()) {
                executor.execute(runnable);
              }
            });
          }
        });
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
    });
    try {
      return future.get();
    } catch (InterruptedException e) {
      // Cancel the future to trigger the cancellation listener on the inspector.
      future.cancel(true);
      // Restore the interrupted status so that calling threads/frameworks are aware of the interrupt.
      Thread.currentThread().interrupt();
      throw e;
    } catch (ExecutionException e) {
      // Unwrap ExecutionException to propagate the inspector's original failure cause directly,
      // avoiding leaking the internal CompletableFuture/threading wrapper to the caller.
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw new RuntimeException(cause);
    }
  }

  /** Disposes the bridge and the underlying inspector. */
  public void dispose() {
    try {
      primaryExecutor.execute(() -> {
        try {
          inspector.onDispose();
        } catch (Throwable t) {
          Log.e("InspectorBridge", "Error during inspector disposal", t);
        } finally {
          // Clear all bytecode hooks registered by this inspector session to prevent ClassLoader
          // memory leaks.
          ArtToolingBridge.clear(inspectorId);
        }
      });
    } catch (RejectedExecutionException e) {
      // The bridge is already disposed.
    }
    primaryExecutor.quitSafely();
  }

  /** Creates and initializes a new [InspectorBridge] containing a dynamically loaded [Inspector] instance. */
  public static InspectorBridge create(
      String inspectorId,
      String dexPath,
      AppInspectionUtils.DelegatingConnection connection,
      HandlerThreadExecutor.CrashListener crashListener) throws Exception {
    HandlerThreadExecutor primaryExecutor =
        new HandlerThreadExecutor(THREAD_NAME_PREFIX + inspectorId, crashListener);
        InspectorEnvironment inspectorEnvironment =
                AppInspectionUtils.createInspectorEnvironment(
                        inspectorId, primaryExecutor, crashListener);

    CompletableFuture<Inspector> future = new CompletableFuture<>();
    primaryExecutor.execute(() -> {
      try {
        // Instantiate the inspector on the primary executor thread so that its internally captured
        // Thread.currentThread() matches the thread used for executing subsequent commands.
        // This respects the convention from AppInspection.
        Inspector inspector = AppInspectionUtils.loadInspectorDynamically(
            inspectorId, dexPath, connection, inspectorEnvironment);
        future.complete(inspector);
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
    });

    Inspector inspector;
    try {
      inspector = future.get();
    } catch (InterruptedException e) {
      // In case of interruption, clean up resources to prevent background thread leaks.
      primaryExecutor.quitSafely();
      Thread.currentThread().interrupt();
      throw e;
    } catch (ExecutionException e) {
      // Ensure the executor is closed to clean up the HandlerThread if initialization fails.
      primaryExecutor.quitSafely();
      // Unwrap ExecutionException to propagate the original initialization failure cause directly.
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw new RuntimeException(cause);
    }

        return new InspectorBridge(inspectorId, inspector, connection, primaryExecutor);
  }

    /**
     * Creates a new [InspectorBridge] for testing with a mocked or stubbed [Inspector] instance.
     */
    @VisibleForTesting
    public static InspectorBridge createForTesting(
            String inspectorId,
            Inspector inspector,
            AppInspectionUtils.DelegatingConnection connection,
            HandlerThreadExecutor primaryExecutor) {
        return new InspectorBridge(inspectorId, inspector, connection, primaryExecutor);
  }
}
