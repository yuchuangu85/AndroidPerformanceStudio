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

package com.android.tools.ui.inspector.service;

import android.app.Application;
import android.os.Looper;
import android.util.Log;

import dalvik.system.DexClassLoader;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Service loaded into the app process by the native agent.
 * Responsible for loading the inspector payload and invoking its entry point.
 * This service is expected to start the inspector and return immediately.
 */
public class InspectorService {

    public static final int RESULT_OK = 0;
    public static final int RESULT_ERROR = 1;
    public static final int RESULT_EXCEPTION = -1;

    private static final String TAG = "studio.ui-inspector.InspectorService";
    private static final String INSPECTOR_LAUNCHER_CLASS_NAME =
            "com.android.tools.ui.inspector.payload.InspectorLauncher";
    private static final String START_METHOD_NAME = "start";

    /**
     * Initializes the inspector service, loads the payload JAR, and launches the inspector payload.
     *
     * @param payloadJarPath absolute path to the payload jar/dex file to load dynamically
     * @param pid the target process ID of this application
     * @param artToolingPtr pointer to the native JvmtiArtTooling C++ instance
     * @return RESULT_OK on success, or an error/exception code on failure
     */
    public static int initialize(String payloadJarPath, String pid, long artToolingPtr) {
        try {
            // Register the native JvmtiArtTooling pointer in the bootstrap bridge so JNI callbacks
            // can delegate hooks and heap-walking queries to the JVMTI agent.
            ArtToolingBridge.initialize(artToolingPtr);

            if (pid == null || pid.isEmpty()) {
                Log.e(TAG, "PID is required for initialization");
                return RESULT_ERROR;
            }

            ClassLoader appClassLoader = getAppClassLoader();
            if (appClassLoader == null) {
                Log.e(TAG, "Could not find app ClassLoader");
                return RESULT_ERROR;
            }

            DexClassLoader dexClassLoader = new DexClassLoader(
                    payloadJarPath,
                    null,
                    null,
                    appClassLoader
            );

            Class<?> launcherClass = Class.forName(
                    INSPECTOR_LAUNCHER_CLASS_NAME,
                    true,
                    dexClassLoader
            );

            Method startMethod = launcherClass.getMethod(START_METHOD_NAME, String.class);

            startMethod.invoke(null, pid);

            return RESULT_OK;
        } catch (Throwable e) {
            Log.e(TAG, "Error in InspectorService initialization", e);
            return RESULT_EXCEPTION;
        }
    }

    private static ClassLoader getAppClassLoader() {
        List<Application> applications = ArtToolingBridge.findInstances(Application.class);

        for (Application application : applications) {
            if (application != null) {
                try {
                    ClassLoader classLoader = application.getClassLoader();
                    if (classLoader != null) {
                        return classLoader;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to get application classloader via JVMTI", e);
                }
            }
        }

        // Fallback strategy: Only executed if JVMTI strategy fails or returns null
        Looper looper = Looper.getMainLooper();
        if (looper != null && looper.getThread() != null) {
            return looper.getThread().getContextClassLoader();
        }
        return null;
    }
}
