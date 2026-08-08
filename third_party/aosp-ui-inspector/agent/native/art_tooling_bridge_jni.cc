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

/*
 * JNI export bindings for the Java ArtToolingBridge class.
 * This file routes Java calls through to the native JvmtiArtTooling
 * implementation.
 */

#include <jni.h>
#include "jvmti_art_tooling.h"
#include "tools/base/transport/native/agent/jni_wrappers.h"
#include "tools/base/transport/native/utils/log.h"

namespace {
constexpr const char* kLogTag = "studio.ui-inspector";
}

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_com_android_tools_ui_inspector_service_ArtToolingBridge_nativeFindInstances(
    JNIEnv* env, jclass callerClass, jlong artToolingPtr, jclass clazz) {
  auto* art_tooling =
      reinterpret_cast<ui_inspector::JvmtiArtTooling*>(artToolingPtr);
  return art_tooling->FindInstances(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_android_tools_ui_inspector_service_ArtToolingBridge_nativeRegisterEntryHook(
    JNIEnv* env, jclass callerClass, jlong artToolingPtr, jclass originClass,
    jstring originMethod) {
  auto* art_tooling =
      reinterpret_cast<ui_inspector::JvmtiArtTooling*>(artToolingPtr);
  profiler::JStringWrapper method_str(env, originMethod);
  std::size_t found = method_str.get().find("(");
  if (found == std::string::npos) {
    profiler::Log::E(kLogTag, "Invalid method signature for entry hook: %s",
                     method_str.get().c_str());
    return;
  }
  art_tooling->AddEntryTransform(env, originClass,
                                 method_str.get().substr(0, found),
                                 method_str.get().substr(found));
}

JNIEXPORT void JNICALL
Java_com_android_tools_ui_inspector_service_ArtToolingBridge_nativeRegisterExitHook(
    JNIEnv* env, jclass callerClass, jlong artToolingPtr, jclass originClass,
    jstring originMethod) {
  auto* art_tooling =
      reinterpret_cast<ui_inspector::JvmtiArtTooling*>(artToolingPtr);
  profiler::JStringWrapper method_str(env, originMethod);
  std::size_t found = method_str.get().find("(");
  if (found == std::string::npos) {
    profiler::Log::E(kLogTag, "Invalid method signature for exit hook: %s",
                     method_str.get().c_str());
    return;
  }
  art_tooling->AddExitTransform(env, originClass,
                                method_str.get().substr(0, found),
                                method_str.get().substr(found));
}

}  // extern "C"

namespace ui_inspector {

// Explicitly registers JNI native methods for the ArtToolingBridge class.
// This is required because the agent is loaded dynamically during
// Agent_OnAttach, before the JVM registers JNI symbols for dynamic lookup.
//
// IMPORTANT: The method names and signatures in the JNINativeMethod array below
// MUST match the native declarations in ArtToolingBridge.java EXACTLY. If any
// native method in ArtToolingBridge.java is updated, this mapping must be
// updated accordingly.
int RegisterArtToolingBridgeNatives(JNIEnv* env) {
  jclass clazz =
      env->FindClass("com/android/tools/ui/inspector/service/ArtToolingBridge");
  if (clazz == nullptr) {
    return JNI_ERR;
  }
  JNINativeMethod methods[] = {
      {(char*)"nativeFindInstances",
       (char*)"(JLjava/lang/Class;)[Ljava/lang/Object;",
       (void*)&Java_com_android_tools_ui_inspector_service_ArtToolingBridge_nativeFindInstances},
      {(char*)"nativeRegisterEntryHook",
       (char*)"(JLjava/lang/Class;Ljava/lang/String;)V",
       (void*)&Java_com_android_tools_ui_inspector_service_ArtToolingBridge_nativeRegisterEntryHook},
      {(char*)"nativeRegisterExitHook",
       (char*)"(JLjava/lang/Class;Ljava/lang/String;)V",
       (void*)&Java_com_android_tools_ui_inspector_service_ArtToolingBridge_nativeRegisterExitHook},
  };
  return env->RegisterNatives(clazz, methods,
                              sizeof(methods) / sizeof(methods[0]));
}

}  // namespace ui_inspector
