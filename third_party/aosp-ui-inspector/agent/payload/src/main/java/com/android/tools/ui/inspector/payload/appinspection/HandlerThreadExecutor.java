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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * An executor that runs tasks on a dedicated [HandlerThread]. It replicates the behavior of App Inspection's HandlerThreadExecutor to
 * ensure sequential execution and safe exception handling.
 */
public final class HandlerThreadExecutor implements Executor {
  private final HandlerThread thread;
  private final Handler handler;

  public interface CrashListener {
    void onCrash(Throwable t);
  }

  public HandlerThreadExecutor(String name, CrashListener crashListener) {
    this.thread = new HandlerThread(name);
    this.thread.start();
    this.handler = new Handler(thread.getLooper()) {
      @Override
      public void dispatchMessage(Message msg) {
        if (msg.getCallback() != null) {
          try {
            msg.getCallback().run();
          } catch (Throwable t) {
            crashListener.onCrash(t);
          }
        } else {
          super.dispatchMessage(msg);
        }
      }
    };
  }

  public Handler getHandler() {
    return handler;
  }

  @Override
  public void execute(Runnable command) {
    if (!handler.post(command)) {
      throw new RejectedExecutionException("Handler thread has quit");
    }
  }

  public void quitSafely() {
    thread.quitSafely();
  }

  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    thread.join(unit.toMillis(timeout));
    return !thread.isAlive();
  }
}
