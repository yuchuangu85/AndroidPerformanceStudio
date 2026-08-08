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
 * (app_inspection_transform.h).
 *
 * Purpose:
 * Manages class bytecode instrumentation lists using slicer. It defines how
 * entry/exit hooks on targeted classes are applied to route control flow into
 * Java callbacks (onEntry/onExit) via the `ArtToolingBridge`.
 *
 * Key changes from app inspection:
 * - Renamed class from AppInspectionTransform to UiInspectorTransform and
 * modified namespace.
 * - Routes callback hooks to ArtToolingBridge instead of AppInspectionService.
 */

#ifndef UI_INSPECTOR_TRANSFORM_H
#define UI_INSPECTOR_TRANSFORM_H

#include <list>
#include <mutex>
#include <string>
#include "array_params_entry_hook.h"
#include "slicer/dex_ir.h"
#include "slicer/dex_ir_builder.h"
#include "slicer/instrumentation.h"
#include "tools/base/transport/native/utils/log.h"

namespace ui_inspector {

class UiInspectorTransform {
 public:
  UiInspectorTransform(const char* class_name) : class_name_(class_name) {}

  void AddTransform(const char* class_name, const char* method_name,
                    const char* signature, bool isEntry) {
    std::lock_guard<std::mutex> lock(transforms_mutex_);
    transforms.push_back(
        TransformDescription(class_name, method_name, signature, isEntry));
  }

  void Apply(std::shared_ptr<ir::DexFile> dex_ir) {
    std::lock_guard<std::mutex> lock(transforms_mutex_);
    for (auto transform : transforms) {
      slicer::MethodInstrumenter mi(dex_ir);
      if (transform.isEntry()) {
        mi.AddTransformation<ArrayParamsEntryHook>(ir::MethodId(
            "Lcom/android/tools/ui/inspector/service/ArtToolingBridge;",
            "onEntry"));
      } else {
        auto tweak = transform.HasPrimitiveOrVoidReturnType()
                         ? slicer::ExitHook::Tweak::None
                         : slicer::ExitHook::Tweak::ReturnAsObject;
        tweak = tweak | slicer::ExitHook::Tweak::PassMethodSignature;
        mi.AddTransformation<slicer::ExitHook>(
            ir::MethodId(
                "Lcom/android/tools/ui/inspector/service/ArtToolingBridge;",
                "onExit"),
            tweak);
      }

      if (!mi.InstrumentMethod(ir::MethodId(transform.GetClassName(),
                                            transform.GetMethod(),
                                            transform.GetSignature()))) {
        profiler::Log::E(
            "studio.ui-inspector", "Error instrumenting %s %s->%s%s\n",
            transform.isEntry() ? "entry hook for" : "exit hook for",
            transform.GetClassName(), transform.GetMethod(),
            transform.GetSignature());
      }
    }
  }

  const char* GetClassName() { return class_name_.c_str(); }

 private:
  class TransformDescription {
   public:
    TransformDescription(const char* class_name, const char* method_name,
                         const char* signature, bool isEntry)
        : class_name_(class_name),
          method_name_(method_name),
          signature_(signature),
          isEntry_(isEntry) {}

    const char* GetClassName() { return class_name_.c_str(); }

    const char* GetMethod() { return method_name_.c_str(); }

    const char* GetSignature() { return signature_.c_str(); }

    bool HasPrimitiveOrVoidReturnType() {
      size_t pos = signature_.find(')');
      if (pos != std::string::npos && pos + 1 < signature_.length()) {
        char ret = signature_[pos + 1];
        return ret != 'L' && ret != '[';
      }
      return true;  // fallback
    }
    bool isEntry() { return isEntry_; }

   private:
    std::string class_name_;
    std::string method_name_;
    std::string signature_;
    bool isEntry_;
  };

  std::string class_name_;
  std::list<TransformDescription> transforms;
  std::mutex transforms_mutex_;
};

}  // namespace ui_inspector

#endif  // UI_INSPECTOR_TRANSFORM_H
