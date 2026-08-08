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

import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.inspection.ArtTooling;
import androidx.inspection.Connection;
import androidx.inspection.Inspector;
import androidx.inspection.InspectorEnvironment;
import androidx.inspection.InspectorExecutors;
import androidx.inspection.InspectorFactory;

import com.android.tools.idea.protobuf.ByteString;
import com.android.tools.ui.inspector.common.FramingProtocol;
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol;
import com.android.tools.ui.inspector.service.ArtToolingBridge;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class AppInspectionUtils {
  private AppInspectionUtils() {}

  private static final String TAG = "studio.AppInspectionUtils";
  /** Executor for disk I/O operations. We use a fixed thread pool of size 4, matching the behavior of AppInspectionService. */
  private static final Executor IoExecutor = Executors.newFixedThreadPool(4);

  /**
   * Creates a {@link Connection} that writes events to the given {@link OutputStream} using {@link FramingProtocol}.
   *
   * <p>Provides an interoperability bridge with the App Inspection framework, adapting its standard
   * interfaces to the UI Inspector's custom transport and routing protocol.
   */
  public static Connection createAppInspectionConnection(
      String inspectorId,
      OutputStream outputStream,
      HandlerThreadExecutor.CrashListener crashListener) {
        return new Connection() {
            @Override
            public void sendEvent(byte[] event) {
                try {
                    ByteString payload = ByteString.copyFrom(event);
                    UiInspectorProtocol.InspectorMessageEvent inspectorEvent =
                            UiInspectorProtocol.InspectorMessageEvent.newBuilder()
                                    .setInspectorId(inspectorId)
                                    .setPayload(payload)
                                    .build();
                    UiInspectorProtocol.Event eventWrapper =
                            UiInspectorProtocol.Event.newBuilder()
                                    .setInspectorMessage(inspectorEvent)
                                    .build();
                    UiInspectorProtocol.AgentMessage agentMessage =
                            UiInspectorProtocol.AgentMessage.newBuilder()
                                    .setEvent(eventWrapper)
                                    .build();
                    FramingProtocol.writeMessage(outputStream, agentMessage.toByteArray());
                } catch (IOException e) {
                    // Connection is broken, making it impossible to report the error back to the
                    // host.
                    Log.e(TAG, "IO error sending event", e);
                } catch (Exception e) {
                    // Internal inspector crash. Report it to the host to trigger a crash state.
                    Log.e(TAG, "Unexpected error sending event", e);
                    crashListener.onCrash(e);
                }
            }
        };
  }

  /**
   * A {@link Connection} that delegates to another {@link Connection}. Used to support reconnecting to
   * existing inspectors across different sessions.
   */
  public static final class DelegatingConnection extends Connection {
    public volatile Connection activeConnection = null;

    @Override
    public void sendEvent(byte[] event) {
      Connection connection = activeConnection;
      if (connection != null) {
        connection.sendEvent(event);
      } else {
        Log.w(TAG, "No active connection to send event");
      }
    }
  }

    public static InspectorEnvironment createInspectorEnvironment(
            String inspectorId,
            HandlerThreadExecutor primaryExecutor,
            HandlerThreadExecutor.CrashListener crashListener) {
        return new InspectorEnvironment() {
            @Override
            public ArtTooling artTooling() {
                return new ArtTooling() {
                    @Override
                    public <T> List<T> findInstances(Class<T> clazz) {
                        return ArtToolingBridge.findInstances(clazz);
                    }

                    @Override
                    public void registerEntryHook(
                            Class<?> originClass, String originMethod, EntryHook entryHook) {
                        ArtToolingBridge.registerEntryHook(
                                originClass,
                                originMethod,
                                inspectorId,
                                (thisObject, args) -> entryHook.onEntry(thisObject, args));
                    }

                    @Override
                    public <T> void registerExitHook(
                            Class<?> originClass, String originMethod, ExitHook<T> exitHook) {
                        ArtToolingBridge.registerExitHook(
                                originClass,
                                originMethod,
                                inspectorId,
                                returnValue -> exitHook.onExit((T) returnValue));
                    }
                };
            }

            @Override
            public InspectorExecutors executors() {
                return new InspectorExecutors() {
                    @Override
                    public Handler handler() {
                        return primaryExecutor.getHandler();
                    }

                    @Override
                    public Executor primary() {
                        return primaryExecutor;
                    }

                    @Override
                    public Executor io() {
                        return createDelegateExecutor(IoExecutor, crashListener);
                    }
                };
            }
        };
  }

  /** Creates an executor that delegates work to the given one and forwards all uncaught exceptions to {@code crashListener}. */
  private static Executor createDelegateExecutor(
      Executor delegate,
      HandlerThreadExecutor.CrashListener crashListener) {
    return command -> delegate.execute(() -> {
      try {
        command.run();
      } catch (Throwable t) {
        crashListener.onCrash(t);
      }
    });
  }

  /**
   * Dynamically loads an inspector from a dex file using {@link ServiceLoader}, matching the mechanism used
   * by App Inspection's {@code InspectorContext.java}.
   *
   * <p>Note that there are a few differences from App Inspection's implementation:
   * <ol>
   *   <li>It does not cache the {@link DexClassLoader}. App Inspection caches them to avoid native library loading
   *       conflicts (b/187342510) if the same jar is loaded multiple times. We rely on persisting
   *       {@link InspectorBridge}s instead.
   *   <li>It uses {@code SessionHandler.class.getClassLoader()} as the parent class loader, whereas App
   *       Inspection uses the application's class loader. Since the session handler's loader is already a
   *       child of the application's class loader, app classes remain visible through delegation.
   *   <li>It does not support native pointers in {@link InspectorEnvironment}.
   * </ol>
   */
  public static Inspector loadInspectorDynamically(
      String inspectorId,
      String dexPath,
      Connection connection,
      InspectorEnvironment environment) throws Exception {
    String optimizedDir = System.getProperty("java.io.tmpdir");
    String nativePath = prepareNativeLibraries(dexPath);
    DexClassLoader classLoader = new DexClassLoader(
        dexPath, optimizedDir, nativePath, AppInspectionUtils.class.getClassLoader());
    ServiceLoader<InspectorFactory> loader = ServiceLoader.load(InspectorFactory.class, classLoader);
    Inspector inspector = null;
    for (InspectorFactory factory : loader) {
      if (factory.getInspectorId().equals(inspectorId)) {
        inspector = factory.createInspector(connection, environment);
        break;
      }
    }
    if (inspector == null) {
      throw new Exception("Failed to find InspectorFactory with id " + inspectorId);
    }
    return inspector;
  }

  /**
   * Dynamically extracts JNI native libraries from the inspector's jar if present, matching the mechanism
   * used by App Inspection's {@code InspectorContext.java}.
   */
  private static String prepareNativeLibraries(String dexPath) {
    try {
      String abi = Build.SUPPORTED_ABIS[0];
      try (JarFile jarFile = new JarFile(dexPath)) {
        JarEntry libEntry = jarFile.getJarEntry("lib/");
        if (libEntry == null || !libEntry.isDirectory()) {
          return null;
        }
        File dexFile = new File(dexPath);
        String tmpDir = System.getProperty("java.io.tmpdir");
        File workingDir = new File(tmpDir, dexFile.getName() + "_unpacked_lib");
        if (!workingDir.exists() && !workingDir.mkdir()) {
          throw new IOException("Failed to create working dir: " + workingDir);
        }
        java.util.Enumeration<JarEntry> entries = jarFile.entries();
        String targetFolder = "lib/" + abi + "/";
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith(targetFolder)
                            && !entry.isDirectory()
                            && entry.getName().endsWith(".so")) {
            String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
            File file = new File(workingDir, name);
            try (InputStream inputStream = jarFile.getInputStream(entry)) {
              Files.copy(inputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            file.setReadable(true, false);
            file.setWritable(true, false);
            file.setExecutable(true, false);
            file.deleteOnExit();
          }
        }
        return workingDir.getAbsolutePath();
      }
    } catch (Exception e) {
      Log.e(TAG, "Failed to prepare native libraries", e);
      return null;
    }
  }
}
