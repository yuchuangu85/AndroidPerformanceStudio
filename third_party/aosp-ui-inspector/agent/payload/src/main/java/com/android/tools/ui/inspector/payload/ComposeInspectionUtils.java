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

package com.android.tools.ui.inspector.payload;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ComposeInspectionUtils {
  private ComposeInspectionUtils() {}

  private static final String COMPOSE_UI_VERSION_RESOURCE_PATH = "META-INF/androidx.compose.ui_ui.version";

  public static String detectComposeVersion(ClassLoader classLoader) {
    try {
      classLoader.loadClass("androidx.compose.ui.Modifier");
    } catch (ClassNotFoundException e) {
      return null;
    }
    try (InputStream is = classLoader.getResourceAsStream(COMPOSE_UI_VERSION_RESOURCE_PATH)) {
      if (is == null) {
        return null;
      }
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          sb.append(line);
        }
        return sb.toString().trim();
      }
    } catch (Exception e) {
      return null;
    }
  }
}
