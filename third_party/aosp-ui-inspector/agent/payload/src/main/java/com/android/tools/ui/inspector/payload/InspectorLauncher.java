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
import com.android.tools.ui.inspector.common.ProtocolConstants;
import java.util.function.Consumer;

/** Entry point for the UI Inspector payload. Starts a Unix domain socket server to listen for commands from the host. */
public final class InspectorLauncher {
  private static final String TAG = ProtocolConstants.LOG_TAG_PREFIX + ".InspectorLauncher";
  private static Thread serverThread = null;

  private InspectorLauncher() {}

  public static synchronized void start(String pid) {
    start(pid, Server::startServer);
  }

  public static synchronized void start(String pid, Consumer<String> serverStarter) {
    if (serverThread != null && serverThread.isAlive()) {
      Log.i(TAG, "Inspector server is already running.");
      return;
    }
    serverThread = new Thread(() -> {
      try {
        serverStarter.accept(pid);
      } catch (Throwable t) {
        // Catching Throwable prevents any unhandled exception or error in the agent
        // from bringing down the entire application process.
        Log.e(TAG, "Uncaught exception in inspector", t);
      }
    }, "ui-inspector-server");
    serverThread.start();
  }
}
