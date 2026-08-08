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

package com.android.tools.ui.inspector.inspectors.view

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** An executor that runs tasks on the app's main thread */
internal class MainThreadExecutor : Executor {
  private val handler = Handler(Looper.getMainLooper())

  override fun execute(command: Runnable) {
    handler.post(command)
  }
}
