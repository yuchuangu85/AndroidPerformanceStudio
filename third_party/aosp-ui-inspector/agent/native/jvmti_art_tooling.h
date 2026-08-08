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
 * This file is an adapted copy of the app-inspection implementation
 * (app_inspection_service.h).
 *
 * Purpose:
 * Declares the main JVMTI agent controller interface (`JvmtiArtTooling`) which
 * manages bytecode transformations for method hook placement, registers the
 * JVMTI ClassFileLoadHook callback, and implements heap-walking to search for
 * JVM class instances on demand.
 *
 * Key changes from app inspection:
 * - Renamed class from AppInspectionService to JvmtiArtTooling and modified
 * namespace.
 * - Narrowed down interface to only expose raw JVMTI primitives (no
 * transport/message dispatcher).
 */

#ifndef JVMTI_ART_TOOLING_H
#define JVMTI_ART_TOOLING_H

#include <jni.h>
#include <jvmti.h>
#include <atomic>
#include <mutex>
#include <string>
#include <unordered_map>
#include "ui_inspector_transform.h"

namespace ui_inspector {

// Explicitly registers JNI native methods for the ArtToolingBridge class.
int RegisterArtToolingBridgeNatives(JNIEnv* env);

class JvmtiArtTooling {
 public:
  JvmtiArtTooling(jvmtiEnv* jvmti) : jvmti_(jvmti), next_tag_(1) {
    Initialize();
  }

  jvmtiEnv* jvmti() const { return jvmti_; }

  jobjectArray FindInstances(JNIEnv* jni, jclass clazz);

  void AddEntryTransform(JNIEnv* jni, const jclass& origin_class,
                         const std::string& method_name,
                         const std::string& signature) {
    AddTransform(jni, origin_class, method_name, signature, true);
  }

  void AddExitTransform(JNIEnv* jni, const jclass& origin_class,
                        const std::string& method_name,
                        const std::string& signature) {
    AddTransform(jni, origin_class, method_name, signature, false);
  }

 private:
  void Initialize();
  void AddTransform(JNIEnv* jni, const jclass& origin_class,
                    const std::string& method_name,
                    const std::string& signature, bool is_entry);

  bool tagClassInstancesO(JNIEnv* jni, jclass clazz, jlong tag);
  bool tagClassInstancesQ(jclass clazz, jlong tag);

  static void OnClassFileLoaded(jvmtiEnv* jvmti_env, JNIEnv* jni_env,
                                jclass class_being_redefined, jobject loader,
                                const char* name, jobject protection_domain,
                                jint class_data_len,
                                const unsigned char* class_data,
                                jint* new_class_data_len,
                                unsigned char** new_class_data);

  jvmtiEnv* jvmti_;
  std::atomic<long> next_tag_;
};

}  // namespace ui_inspector

#endif  // JVMTI_ART_TOOLING_H
