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

package com.android.tools.ui.inspector.common;

/** Shared constants and identifiers for the UI Inspector communication protocol. */
public final class ProtocolConstants {
    private ProtocolConstants() {}

    public static final String SOCKET_NAME_PREFIX = "ui_inspector_";
    public static final String LOG_TAG_PREFIX = "studio.ui-inspector";
    public static final String VIEW_INSPECTOR_ID = "ui.inspector.inspectors.view.inspector";
    public static final String COMPOSE_INSPECTOR_ID = "layoutinspector.compose.inspection";
    public static final String COMPOSE_UI_LIBRARY_ID = "androidx.compose.ui:ui";
    public static final int MIN_SUPPORTED_API_LEVEL = 29;

    /* Returns the unique socket name for a given process ID. */
    public static String getSocketName(String pid) {
        return SOCKET_NAME_PREFIX + pid;
    }
}
