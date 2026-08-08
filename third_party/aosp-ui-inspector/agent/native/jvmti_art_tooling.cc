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
 * (app_inspection_service.cc).
 *
 * Purpose:
 * Implements the JVMTI event callbacks, class retransformation logic,
 * heap-walking traversals (supporting backward-compatible tags and Q+ API
 * iteration), and allocates instrumented bytecode.
 *
 * Key changes from app inspection:
 * - Renamed class from AppInspectionService to JvmtiArtTooling and modified
 * namespace.
 * - Removed socket transport, command parsing, and inspector loading. Exposes
 * JVMTI capabilities exclusively to JNI.
 */

#include "jvmti_art_tooling.h"

#include <algorithm>
#include <mutex>
#include <vector>
#include "array_params_entry_hook.h"
#include "slicer/reader.h"
#include "slicer/writer.h"
#include "tools/base/transport/native/agent/jni_wrappers.h"
#include "tools/base/transport/native/jvmti/hidden_api_silencer.h"
#include "tools/base/transport/native/jvmti/jvmti_helper.h"
#include "tools/base/transport/native/utils/device_info.h"
#include "tools/base/transport/native/utils/log.h"

namespace {
constexpr const char* kLogTag = "studio.ui-inspector";
}  // namespace

namespace ui_inspector {

static std::recursive_mutex g_transforms_mutex;
static std::unordered_map<std::string, UiInspectorTransform*> g_transformations;

void JvmtiArtTooling::Initialize() {
  profiler::SetAllCapabilities(jvmti_);

  jvmtiEventCallbacks callbacks;
  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.ClassFileLoadHook = OnClassFileLoaded;

  profiler::CheckJvmtiError(
      jvmti_, jvmti_->SetEventCallbacks(&callbacks, sizeof(callbacks)));

  bool filter_class_load_hook =
      profiler::DeviceInfo::feature_level() < profiler::DeviceInfo::P;
  profiler::SetEventNotification(
      jvmti_, filter_class_load_hook ? JVMTI_DISABLE : JVMTI_ENABLE,
      JVMTI_EVENT_CLASS_FILE_LOAD_HOOK);
}

static std::string ConvertClass(JNIEnv* env, jclass clazz) {
  jclass classClass = env->FindClass("java/lang/Class");
  jmethodID getName =
      env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
  jstring strObj = (jstring)env->CallObjectMethod(clazz, getName);
  profiler::JStringWrapper name_wrapped(env, strObj);

  std::string name = "L" + name_wrapped.get() + ";";
  std::replace(name.begin(), name.end(), '.', '/');
  return name;
}

void JvmtiArtTooling::AddTransform(JNIEnv* jni, const jclass& origin_class,
                                   const std::string& method_name,
                                   const std::string& signature,
                                   bool is_entry) {
  profiler::HiddenApiSilencer silencer(jvmti_);
  std::lock_guard<std::recursive_mutex> lock(g_transforms_mutex);
  std::string class_name = ConvertClass(jni, origin_class);
  auto transform_iter = g_transformations.find(class_name);
  UiInspectorTransform* app_transform;
  if (transform_iter == g_transformations.end()) {
    app_transform = new UiInspectorTransform(class_name.c_str());
    g_transformations.insert({class_name, app_transform});
  } else {
    app_transform = transform_iter->second;
  }
  app_transform->AddTransform(class_name.c_str(), method_name.c_str(),
                              signature.c_str(), is_entry);

  jthread thread = nullptr;
  jvmti_->GetCurrentThread(&thread);

  bool manually_toggle_load_hook =
      profiler::DeviceInfo::feature_level() < profiler::DeviceInfo::P;

  if (manually_toggle_load_hook) {
    profiler::CheckJvmtiError(
        jvmti_, jvmti_->SetEventNotificationMode(
                    JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, thread));
  }
  profiler::CheckJvmtiError(jvmti_,
                            jvmti_->RetransformClasses(1, &origin_class));
  if (manually_toggle_load_hook) {
    profiler::CheckJvmtiError(
        jvmti_, jvmti_->SetEventNotificationMode(
                    JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, thread));
  }

  if (thread != nullptr) {
    jni->DeleteLocalRef(thread);
  }
}

class JvmtiAllocator : public dex::Writer::Allocator {
 public:
  JvmtiAllocator(jvmtiEnv* jvmti_env) : jvmti_env_(jvmti_env) {}

  virtual void* Allocate(size_t size) {
    return profiler::Allocate(jvmti_env_, size);
  }

  virtual void Free(void* ptr) { profiler::Deallocate(jvmti_env_, ptr); }

 private:
  jvmtiEnv* jvmti_env_;
};

void JvmtiArtTooling::OnClassFileLoaded(
    jvmtiEnv* jvmti_env, JNIEnv* jni_env, jclass class_being_redefined,
    jobject loader, const char* name, jobject protection_domain,
    jint class_data_len, const unsigned char* class_data,
    jint* new_class_data_len, unsigned char** new_class_data) {
  std::string desc = "L" + std::string(name) + ";";
  std::lock_guard<std::recursive_mutex> lock(g_transforms_mutex);
  auto transform = g_transformations.find(desc);
  if (transform == g_transformations.end()) return;

  dex::Reader reader(class_data, class_data_len);
  auto class_index = reader.FindClassIndex(desc.c_str());
  if (class_index == dex::kNoIndex) {
    profiler::Log::E(kLogTag, "Could not find class index for %s", name);
    return;
  }

  reader.CreateClassIr(class_index);
  auto dex_ir = reader.GetIr();
  transform->second->Apply(dex_ir);

  size_t new_image_size = 0;
  dex::u1* new_image = nullptr;
  dex::Writer writer(dex_ir);

  JvmtiAllocator allocator(jvmti_env);
  new_image = writer.CreateImage(&allocator, &new_image_size);

  *new_class_data_len = new_image_size;
  *new_class_data = new_image;
}

jobjectArray JvmtiArtTooling::FindInstances(JNIEnv* jni, jclass clazz) {
  if (jni->IsSameObject(clazz, jni->FindClass("java/lang/Class"))) {
    jint count;
    jclass* classes;

    if (profiler::CheckJvmtiError(jvmti_,
                                  jvmti_->GetLoadedClasses(&count, &classes))) {
      return jni->NewObjectArray(0, clazz, NULL);
    }

    auto result = jni->NewObjectArray(count, clazz, NULL);
    for (int i = 0; i < count; ++i) {
      if (result != nullptr) {
        jni->SetObjectArrayElement(result, i, (jobject)classes[i]);
      }
      jni->DeleteLocalRef(classes[i]);
    }
    jvmti_->Deallocate((unsigned char*)classes);

    return result;
  }

  jlong tag = next_tag_++;

  bool error = profiler::DeviceInfo::feature_level() < profiler::DeviceInfo::Q
                   ? tagClassInstancesO(jni, clazz, tag)
                   : tagClassInstancesQ(clazz, tag);

  if (error) {
    return jni->NewObjectArray(0, clazz, NULL);
  }

  jint count;
  jobject* instances;
  if (profiler::CheckJvmtiError(
          jvmti_,
          jvmti_->GetObjectsWithTags(1, &tag, &count, &instances, NULL))) {
    return jni->NewObjectArray(0, clazz, NULL);
  }

  auto result = jni->NewObjectArray(count, clazz, NULL);
  for (int i = 0; i < count; ++i) {
    if (result != nullptr) {
      jni->SetObjectArrayElement(result, i, instances[i]);
    }
    jni->DeleteLocalRef(instances[i]);
  }
  jvmti_->Deallocate((unsigned char*)instances);

  return result;
}

static jint JNICALL HeapIterationCallback(jlong class_tag, jlong size,
                                          jlong* tag_ptr, jint length,
                                          void* user_data) {
  jlong tag = *(reinterpret_cast<jlong*>(user_data));
  *tag_ptr = tag;
  return 0;
}

bool JvmtiArtTooling::tagClassInstancesO(JNIEnv* jni, jclass clazz, jlong tag) {
  jvmtiHeapCallbacks heap_callbacks;
  jint count;
  jclass* classes;

  if (profiler::CheckJvmtiError(jvmti_,
                                jvmti_->GetLoadedClasses(&count, &classes))) {
    return true;
  }

  memset(&heap_callbacks, 0, sizeof(heap_callbacks));
  heap_callbacks.heap_iteration_callback =
      reinterpret_cast<decltype(heap_callbacks.heap_iteration_callback)>(
          HeapIterationCallback);
  bool error = false;
  for (int i = 0; i < count; ++i) {
    if (!error && jni->IsAssignableFrom(classes[i], clazz)) {
      error = profiler::CheckJvmtiError(
          jvmti_,
          jvmti_->IterateThroughHeap(0, classes[i], &heap_callbacks, &tag));
    }
    jni->DeleteLocalRef(classes[i]);
  }
  jvmti_->Deallocate((unsigned char*)classes);
  return error;
}

static jvmtiIterationControl JNICALL HeapObjectCallback(jlong class_tag,
                                                        jlong size,
                                                        jlong* tag_ptr,
                                                        void* user_data) {
  jlong tag = *(reinterpret_cast<jlong*>(user_data));
  *tag_ptr = tag;
  return JVMTI_ITERATION_CONTINUE;
}

bool JvmtiArtTooling::tagClassInstancesQ(jclass clazz, jlong tag) {
  return profiler::CheckJvmtiError(
      jvmti_, jvmti_->IterateOverInstancesOfClass(
                  clazz, JVMTI_HEAP_OBJECT_EITHER, HeapObjectCallback, &tag));
}

}  // namespace ui_inspector
