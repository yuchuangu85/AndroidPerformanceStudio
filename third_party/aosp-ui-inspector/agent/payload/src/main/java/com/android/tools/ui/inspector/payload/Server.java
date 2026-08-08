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

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import com.android.tools.ui.inspector.common.FramingProtocol;
import com.android.tools.ui.inspector.common.ProtocolConstants;
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor;
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Handles the server socket listener and connection lifecycle for the UI Inspector. */
public final class Server {
  private static final String TAG = ProtocolConstants.LOG_TAG_PREFIX + ".Server";
  private static final long INACTIVITY_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

  private Server() {}

  public static void startServer(String pid) {
    new ServerInstance(pid).run();
  }

  private static final class ServerInstance {
    private final String socketName;
    // Completes when a request to shutdown the server is received.
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    // Flags whether the server stopped due to an inactivity timeout.
    private final AtomicBoolean timedOut = new AtomicBoolean(false);
    // Holds the bridges to active inspectors. This map is shared across connection sessions to
    // allow inspectors to maintain state across host reconnections.
    private final Map<String, InspectorBridge> inspectorBridges = new ConcurrentHashMap<>();

        /** The output stream of the active connection session. Null if no connection is active. */
        private volatile OutputStream activeOutputStream;

        private final HandlerThreadExecutor.CrashListener crashListener =
                throwable -> {
                    Log.e(TAG, "Uncaught exception in inspector", throwable);
                    reportCrashToHost(throwable);
                };

        private void reportCrashToHost(Throwable throwable) {
            OutputStream output = activeOutputStream;
            if (output == null) {
                Log.w(TAG, "Cannot report crash to host: no active connection");
                return;
            }
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = sw.toString();
                String message =
                        throwable.getMessage() != null
                                ? throwable.getMessage()
                                : throwable.getClass().getName();

                UiInspectorProtocol.CrashEvent crashEvent =
                        UiInspectorProtocol.CrashEvent.newBuilder()
                                .setErrorMessage(message)
                                .setStackTrace(stackTrace)
                                .build();

                UiInspectorProtocol.Event eventWrapper =
                        UiInspectorProtocol.Event.newBuilder().setCrash(crashEvent).build();

                UiInspectorProtocol.AgentMessage agentMessage =
                        UiInspectorProtocol.AgentMessage.newBuilder()
                                .setEvent(eventWrapper)
                                .build();

                FramingProtocol.writeMessage(output, agentMessage.toByteArray());
                Log.i(TAG, "Sent crash event to host");
            } catch (IOException e) {
                Log.e(TAG, "Failed to send crash event to host", e);
            }
        }

    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();

    private LocalServerSocket serverSocket;
    private ScheduledFuture<?> timeoutFuture;

    ServerInstance(String pid) {
      this.socketName = ProtocolConstants.getSocketName(pid);
    }

    void run() {
      try (LocalServerSocket socket = new LocalServerSocket(socketName)) {
        this.serverSocket = socket;
        Log.i(TAG, "Server listening on " + socketName);

        // Centralized cleanup thread. Terminates the server and disposes of resources when
        // shutdownLatch fires.
        Thread janitorThread = new Thread(() -> {
          try {
            shutdownLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            timeoutExecutor.shutdownNow();
            closeServerSocket();
            for (InspectorBridge bridge : inspectorBridges.values()) {
              try {
                bridge.dispose();
              } catch (Throwable t) {
                Log.e(TAG, "Error disposing inspector bridge", t);
              }
            }
            inspectorBridges.clear();
          }
        }, "ui-inspector-janitor");
        janitorThread.start();

        scheduleTimeout();

        // To prevent the thread from re-entering accept() during shutdown, we check the shutdown
        // state directly here.
        while (shutdownLatch.getCount() > 0) {
          LocalSocket clientSocket;
          try {
            clientSocket = serverSocket.accept();
          } catch (IOException e) {
            if (timedOut.get() || shutdownLatch.getCount() == 0) {
              break;
            }
            throw e;
          }

          if (shutdownLatch.getCount() == 0) {
            try {
              clientSocket.close();
            } catch (IOException ignored) {}
            break;
          }

          cancelTimeout();

          try {
                        activeOutputStream = clientSocket.getOutputStream();
                        SessionHandler sessionHandler =
                                new SessionHandler(
                                        clientSocket.getInputStream(),
                                        activeOutputStream,
                                        crashListener,
                                        shutdownLatch,
                                        inspectorBridges);
            sessionHandler.processCommands();
          } finally {
                        activeOutputStream = null;
            try {
              clientSocket.close();
            } catch (IOException e) {
              Log.e(TAG, "Error closing client socket", e);
            }
          }

          if (shutdownLatch.getCount() > 0) {
            scheduleTimeout();
          }
        }
      } catch (IOException e) {
        if (timedOut.get()) {
          Log.i(TAG, "Server stopped due to inactivity timeout");
        } else if (shutdownLatch.getCount() == 0) {
          Log.i(TAG, "Server stopped due to shutdown signal");
        } else if (e.getMessage() != null && e.getMessage().contains("Address already in use")) {
          Log.i(TAG, "Server is already running on " + socketName);
        } else {
          Log.e(TAG, "Error in server loop", e);
        }
      } finally {
        shutdownLatch.countDown();
      }
    }

    // Helper task to schedule (or reset) the inactivity timeout.
    // If no client connects within the timeout period, the server shuts down automatically.
    private synchronized void scheduleTimeout() {
      if (timeoutFuture != null) {
        timeoutFuture.cancel(false);
      }
      timeoutFuture = timeoutExecutor.schedule(() -> {
        Log.i(TAG, "Inactivity timeout reached, stopping server");
        timedOut.set(true);
        shutdownLatch.countDown();
      }, INACTIVITY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelTimeout() {
      if (timeoutFuture != null) {
        timeoutFuture.cancel(false);
        timeoutFuture = null;
      }
    }

    private void closeServerSocket() {
      if (serverSocket != null) {
        forceWakeUp(socketName);
        try {
          serverSocket.close();
        } catch (IOException e) {
          Log.e(TAG, "Error closing server socket", e);
        }
      }
    }

    private static void forceWakeUp(String socketName) {
      // The main server loop is stuck in a native layer during serverSocket.accept()
      // FORCE WAKEUP: Connect a dummy client to the socket to unblock the stuck thread.
      try (LocalSocket dummySocket = new LocalSocket()) {
        dummySocket.connect(new LocalSocketAddress(socketName));
      } catch (IOException ignored) {
        // Ignored. Connection failure is expected if the socket is already fully closed or gone.
      }
    }
  }
}
