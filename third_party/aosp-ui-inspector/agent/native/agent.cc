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

#include <jni.h>
#include <jvmti.h>
#include <optional>
#include <string>
#include <vector>
#include "jvmti_art_tooling.h"
#include "tools/base/transport/native/agent/jni_wrappers.h"
#include "tools/base/transport/native/jvmti/hidden_api_silencer.h"
#include "tools/base/transport/native/jvmti/jvmti_helper.h"
#include "tools/base/transport/native/utils/log.h"
#include "tools/base/transport/native/utils/tokenizer.h"

namespace {
constexpr const char* kLogTag = "studio.ui-inspector.native";
constexpr const char* kInspectorServiceClassName =
    "com/android/tools/ui/inspector/service/InspectorService";
constexpr const char* kInitializeMethodName = "initialize";
constexpr const char* kInitializeMethodSignature =
    "(Ljava/lang/String;Ljava/lang/String;J)I";

// Options passed to the agent on attach.
struct AgentOptions {
  std::string service_jar;
  std::string payload_jar;
  std::string pid;
};

// Parses options in format: service_jar;payload_jar;pid
std::optional<AgentOptions> parseOptions(const char* options) {
  if (options == nullptr || strlen(options) == 0) return std::nullopt;

  std::vector<std::string> args = profiler::Tokenizer::GetTokens(options, ";");
  if (args.size() != 3) return std::nullopt;

  return AgentOptions{args[0], args[1], args[2]};
}

// Process-wide singleton representing the JVMTI agent tooling.
// Since the native agent library is loaded into the app process and never
// unloaded, we keep a single instance alive to handle all attachment sessions
// and prevent memory leaks on re-attach.
ui_inspector::JvmtiArtTooling* g_art_tooling = nullptr;
}  // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options,
                                                 void* reserved) {
  profiler::Log::I(kLogTag, "UI Inspector Agent attaching...");

  // Parse options
  auto options_opt = parseOptions(options);
  if (!options_opt) {
    profiler::Log::E(
        kLogTag,
        "Invalid options format. Expected: service_jar;payload_jar;pid");
    return JNI_OK;
  }

  std::string service_jar_path = options_opt->service_jar;
  std::string payload_jar_path = options_opt->payload_jar;
  std::string pid = options_opt->pid;

  JNIEnv* env = profiler::GetThreadLocalJNI(vm);
  if (env == nullptr) {
    profiler::Log::E(kLogTag, "Could not attach to current thread.");
    return JNI_OK;
  }

  jvmtiEnv* jvmti = profiler::CreateJvmtiEnv(vm);
  if (jvmti == nullptr) {
    profiler::Log::E(kLogTag, "Could not get JVMTI env.");
    return JNI_OK;
  }

  // Bypass hidden API enforcement policy to access internal framework classes
  // for UI inspection
  static profiler::HiddenApiSilencer hiddenApiSilencer(jvmti);

  profiler::Log::I(kLogTag, "Adding jar to bootstrap search path: %s",
                   service_jar_path.c_str());
  if (jvmti->AddToBootstrapClassLoaderSearch(service_jar_path.c_str()) !=
      JVMTI_ERROR_NONE) {
    profiler::Log::E(kLogTag,
                     "Failed to add jar to bootstrap classloader search");
    return JNI_OK;
  }

  // Register the ArtToolingBridge JNI methods explicitly. Since we invoke the
  // Java service's initialize() method synchronously during Agent_OnAttach, the
  // JVMTI agent loading process is not yet complete. During this attachment
  // window, the JVM JNI symbol linker has not yet registered this agent library
  // for automatic dynamic resolution. Explicitly calling RegisterNatives here
  // binds the native methods immediately, preventing UnsatisfiedLinkError.
  if (ui_inspector::RegisterArtToolingBridgeNatives(env) != JNI_OK) {
    profiler::Log::E(kLogTag,
                     "Failed to register ArtToolingBridge native methods");
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
    return JNI_OK;
  }

  // Find InspectorService
  jclass serviceClass = env->FindClass(kInspectorServiceClassName);
  if (env->ExceptionCheck() || serviceClass == nullptr) {
    profiler::Log::E(kLogTag, "Failed to find %s", kInspectorServiceClassName);
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
    return JNI_OK;
  }

  // Get initialize method
  jmethodID initMethod = env->GetStaticMethodID(
      serviceClass, kInitializeMethodName, kInitializeMethodSignature);
  if (env->ExceptionCheck() || initMethod == nullptr) {
    profiler::Log::E(kLogTag, "Failed to find %s method",
                     kInitializeMethodName);
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
    return JNI_OK;
  }

  // Call initialize
  jstring arg1 = env->NewStringUTF(payload_jar_path.c_str());
  jstring arg2 = env->NewStringUTF(pid.c_str());

  // Get or instantiate the singleton JvmtiArtTooling engine, then cast its
  // pointer to a jlong so it can be passed to and stored by the Java service
  // layer for subsequent JNI callbacks.
  if (g_art_tooling == nullptr) {
    g_art_tooling = new ui_inspector::JvmtiArtTooling(jvmti);
  }
  jlong artToolingPtr = reinterpret_cast<jlong>(g_art_tooling);

  jint result = env->CallStaticIntMethod(serviceClass, initMethod, arg1, arg2,
                                         artToolingPtr);

  if (env->ExceptionCheck()) {
    profiler::Log::E(kLogTag, "Exception in %s.%s", kInspectorServiceClassName,
                     kInitializeMethodName);
    env->ExceptionDescribe();
    env->ExceptionClear();
  } else if (result != 0) {
    profiler::Log::E(kLogTag, "%s.%s failed with code %d",
                     kInspectorServiceClassName, kInitializeMethodName, result);
  } else {
    profiler::Log::I(kLogTag, "Agent attached successfully!");
  }

  return JNI_OK;
}
