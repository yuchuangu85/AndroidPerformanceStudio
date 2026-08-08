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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A classloader-safe bridge loaded in the bootstrap classloader. Provides entry points for JNI
 * callbacks and delegates finding instances and setting hooks to the native JvmtiArtTooling
 * implementation.
 *
 * <p>This file is an adapted, simplified subset of the app-inspection implementation
 * (AppInspectionService.java). Key differences:
 *
 * <ul>
 *   <li>Only contains JNI routing, heap instance walking, and bytecode callback hooks.
 *   <li>Tracks hook ownership via ClassLoader keys instead of string inspector IDs to prevent
 *       memory leaks on disposal.
 *   <li>Removed socket transport, command processing, and version checking.
 * </ul>
 */
public final class ArtToolingBridge {

    /** Interface for listening to method entry events. */
    public interface EntryHookBridge {
        /**
         * Invoked when the hooked method enters.
         *
         * @param thisObject the instance on which the method was called (null for static methods)
         * @param args the list of method parameters passed to the method (boxed if primitive)
         */
        void onEntry(Object thisObject, List<Object> args);
    }

    /** Interface for listening to and optionally modifying method return values. */
    public interface ExitHookBridge {
        /**
         * Invoked when the hooked method exits.
         *
         * @param returnValue the value returned by the method (or null if void)
         * @return the value that will actually be returned by the instrumented method
         */
        Object onExit(Object returnValue);
    }

    /**
     * Container holding registration info for a method entry hook callback, including the owner key
     * (typically the ClassLoader) used for scoped cleanup.
     */
    private static final class EntryHookInfo {
        final String inspectorId;
        final EntryHookBridge hook;

        EntryHookInfo(String inspectorId, EntryHookBridge hook) {
            this.inspectorId = inspectorId;
            this.hook = hook;
        }
    }

    /**
     * Container holding registration info for a method exit hook callback, including the owner key
     * (typically the ClassLoader) used for scoped cleanup.
     */
    private static final class ExitHookInfo {
        final String inspectorId;
        final ExitHookBridge hook;

        ExitHookInfo(String inspectorId, ExitHookBridge hook) {
            this.inspectorId = inspectorId;
            this.hook = hook;
        }
    }

    /**
     * Native pointer to the JvmtiArtTooling C++ instance. Used by JNI methods to delegate tooling
     * actions back to the native agent.
     */
    private static long sArtToolingPtr = 0;

    private static final Map<String, List<EntryHookInfo>> mEntryHooks = new ConcurrentHashMap<>();
    private static final Map<String, List<ExitHookInfo>> mExitHooks = new ConcurrentHashMap<>();

    /**
     * Initializes the bridge with the pointer to the native JvmtiArtTooling C++ instance. Called by
     * the agent attach entrypoint.
     */
    public static void initialize(long artToolingPtr) {
        sArtToolingPtr = artToolingPtr;
    }

    /** Clears all registered entry and exit bytecode hooks. */
    public static void clear() {
        mEntryHooks.clear();
        mExitHooks.clear();
    }

    /**
     * Clears registered hooks belonging to a specific owner. Used during inspector session disposal
     * to prevent ClassLoader memory leaks.
     *
     * @param inspectorId the inspector ID of the disposed inspector session.
     */
    public static void clear(String inspectorId) {
        if (inspectorId == null) {
            return;
        }
        for (List<EntryHookInfo> list : mEntryHooks.values()) {
            list.removeIf(info -> inspectorId.equals(info.inspectorId));
        }
        for (List<ExitHookInfo> list : mExitHooks.values()) {
            list.removeIf(info -> inspectorId.equals(info.inspectorId));
        }
    }

    /**
     * Walks the JVM heap to find all active instances of the specified class. Special-cases
     * java.lang.Class itself by returning all loaded classes.
     *
     * @param clazz the target class type to search for
     * @return a list of matching JVM instances, or an empty list if none found or agent is not
     *     initialized
     */
    public static <T> List<T> findInstances(Class<T> clazz) {
        if (sArtToolingPtr == 0) {
            return Collections.emptyList();
        }
        T[] instances = nativeFindInstances(sArtToolingPtr, clazz);
        return instances != null ? Arrays.asList(instances) : Collections.emptyList();
    }

    /**
     * Registers a callback to be executed when the targeted method begins execution (entry point).
     *
     * @param originClass the target class containing the method to hook
     * @param originMethod the method signature in format: method_name(param_sig...)return_sig
     * @param hook the callback implementation to execute on method entry
     */
    public static void registerEntryHook(
            Class<?> originClass, String originMethod, String inspectorId, EntryHookBridge hook) {
        if (sArtToolingPtr == 0) {
            return;
        }
        String label = createLabel(originClass, originMethod);
        mEntryHooks
                .computeIfAbsent(
                        label,
                        k -> {
                            try {
                                nativeRegisterEntryHook(sArtToolingPtr, originClass, originMethod);
                            } catch (UnsatisfiedLinkError e) {
                                // Safe fall-through for Robolectric tests where JNI agent is
                                // stubbed
                            }
                            return new CopyOnWriteArrayList<>();
                        })
                .add(new EntryHookInfo(inspectorId, hook));
    }

    public static void registerExitHook(
            Class<?> originClass, String originMethod, String inspectorId, ExitHookBridge hook) {
        if (sArtToolingPtr == 0) {
            return;
        }
        String label = createLabel(originClass, originMethod);
        mExitHooks
                .computeIfAbsent(
                        label,
                        k -> {
                            try {
                                nativeRegisterExitHook(sArtToolingPtr, originClass, originMethod);
                            } catch (UnsatisfiedLinkError e) {
                                // Safe fall-through for Robolectric tests where JNI agent is
                                // stubbed
                            }
                            return new CopyOnWriteArrayList<>();
                        })
                .add(new ExitHookInfo(inspectorId, hook));
    }

    /**
     * Creates a signature label identifying the hooked method. Combines the origin class name and
     * method signature into a unified descriptor.
     */
    private static String createLabel(Class<?> origin, String method) {
        return normalizeSignature(origin.getName() + "->" + method);
    }

    /**
     * Normalizes a method signature descriptor to JVM format. Converts class signatures containing
     * dot-notation package paths (e.g. "com.example.Foo") into JVM slash-notation package paths
     * prefixed with 'L' and suffixed with ';' (e.g. "Lcom/example/Foo;").
     */
    private static String normalizeSignature(String signature) {
        if (signature == null) {
            return "";
        }
        if (signature.startsWith("L") && signature.contains(";->")) {
            return signature;
        }
        int index = signature.indexOf("->");
        if (index != -1) {
            String className = signature.substring(0, index);
            String methodAndSig = signature.substring(index);
            String jvmClassName = "L" + className.replace('.', '/') + ";";
            return jvmClassName + methodAndSig;
        }
        return signature;
    }

    /**
     * Entry callback invoked dynamically by slicer-instrumented bytecode at method start. Extracts
     * parameters, resolves matching hooks, and notifies all registered entry listeners.
     *
     * @param signatureThisParams packaging structure: [0]=methodSignature (String), [1]=thisObject,
     *     [2...]=parameters
     */
    public static void onEntry(Object[] signatureThisParams) {
        if (signatureThisParams == null || signatureThisParams.length < 2) {
            return;
        }
        String signature = normalizeSignature((String) signatureThisParams[0]);
        List<EntryHookInfo> hooks = mEntryHooks.get(signature);
        if (hooks == null) {
            return;
        }
        Object thisObject = signatureThisParams[1];
        List<Object> params = Collections.emptyList();
        if (signatureThisParams.length > 2) {
            params =
                    Arrays.asList(
                            Arrays.copyOfRange(signatureThisParams, 2, signatureThisParams.length));
        }
        for (EntryHookInfo info : hooks) {
            try {
                info.hook.onEntry(thisObject, params);
            } catch (Throwable t) {
                // Prevent hook exceptions from crashing the app process
            }
        }
    }

    /**
     * Internal exit callback. Iterates through all registered exit listeners and routes the return
     * value sequentially, allowing listeners to observe or alter the return object.
     *
     * @param label the normalized method signature descriptor
     * @param returnObject the value returned by the instrumented method (or null if void)
     * @return the potentially modified return object
     */
    private static <T> T onExitInternal(String label, T returnObject) {
        String signature = normalizeSignature(label);
        List<ExitHookInfo> hooks = mExitHooks.get(signature);
        if (hooks == null) {
            return returnObject;
        }
        for (ExitHookInfo info : hooks) {
            try {
                //noinspection unchecked
                returnObject = (T) info.hook.onExit(returnObject);
            } catch (Throwable t) {
                // Prevent hook exceptions from crashing the app process
            }
        }
        return returnObject;
    }

    /**
     * Exit callback invoked dynamically by slicer-instrumented bytecode at method exit. Routes the
     * return value through all registered exit hook listeners.
     *
     * @param methodSignature the instrumented method signature descriptor
     * @param returnObject the value returned by the method
     * @return the potentially modified return object
     */
    public static Object onExit(String methodSignature, Object returnObject) {
        return onExitInternal(methodSignature, returnObject);
    }

    /**
     * Exit callback invoked dynamically by slicer-instrumented bytecode at void method exit.
     *
     * @param methodSignature the instrumented method signature descriptor
     */
    public static void onExit(String methodSignature) {
        onExitInternal(methodSignature, null);
    }

    public static boolean onExit(String methodSignature, boolean result) {
        return onExitInternal(methodSignature, result);
    }

    public static byte onExit(String methodSignature, byte result) {
        return onExitInternal(methodSignature, result);
    }

    public static char onExit(String methodSignature, char result) {
        return onExitInternal(methodSignature, result);
    }

    public static short onExit(String methodSignature, short result) {
        return onExitInternal(methodSignature, result);
    }

    public static int onExit(String methodSignature, int result) {
        return onExitInternal(methodSignature, result);
    }

    public static float onExit(String methodSignature, float result) {
        return onExitInternal(methodSignature, result);
    }

    public static long onExit(String methodSignature, long result) {
        return onExitInternal(methodSignature, result);
    }

    public static double onExit(String methodSignature, double result) {
        return onExitInternal(methodSignature, result);
    }

    // JNI Declarations
    private static native <T> T[] nativeFindInstances(long artToolingPtr, Class<T> clazz);

    private static native void nativeRegisterEntryHook(
            long artToolingPtr, Class<?> originClass, String originMethod);

    private static native void nativeRegisterExitHook(
            long artToolingPtr, Class<?> originClass, String originMethod);
}
