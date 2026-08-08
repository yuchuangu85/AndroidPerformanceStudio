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

package com.android.tools.ui.inspector.printer.text

import com.android.tools.ui.inspector.AppContext
import com.android.tools.ui.inspector.DeviceConfiguration
import com.android.tools.ui.inspector.DeviceLocale
import com.android.tools.ui.inspector.Dimension
import java.io.PrintStream
import java.lang.reflect.Modifier

internal fun DeviceLocale.format(): String = listOfNotNull(language, country, variant, script).filter { it.isNotEmpty() }.joinToString("-")

/** Prints the device configuration to the target [PrintStream] in a human-readable format. */
internal fun printDeviceConfiguration(config: DeviceConfiguration, out: PrintStream) {
  out.println("Device Configuration:")
  DeviceConfiguration::class
    .java
    .declaredFields
    .filter { field -> !field.isSynthetic && !Modifier.isStatic(field.modifiers) }
    .sortedBy { it.name }
    .forEach { field ->
      field.isAccessible = true
      val name = formatPropertyName(field.name)
      val rawValue = field.get(config)
      val formattedValue = formatValueForPrinting(rawValue)
      if (formattedValue != null) {
        out.println(" $name: $formattedValue")
      }
    }
  out.println()
}

/** Converts a camelCase field name into a capitalized human-readable label (e.g. "fontScale" -> "Font Scale"). */
internal fun formatPropertyName(propertyName: String): String =
  propertyName.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun formatValueForPrinting(value: Any?): String? {
  if (value == null) return null
  return when (value) {
    is DeviceLocale -> {
      val str = value.format()
      if (str.isNotEmpty()) str else null
    }
    is Enum<*> -> getEnumDisplayValue(value)
    is Dimension.Dp -> "${value.value} dp"
    is Dimension.Dpi -> "${value.value} dpi"
    else -> value.toString()
  }
}

private fun getEnumDisplayValue(enumValue: Enum<*>): String {
  return enumValue.name.lowercase()
}

/** Prints the application context (theme and display info) to the target [PrintStream]. */
internal fun printAppContext(appContext: AppContext, out: PrintStream) {
  out.println("App Context:")
  appContext.theme?.let { out.println(" Theme: $it") }
  if (appContext.displays.isNotEmpty()) {
    out.println(" Displays:")
    appContext.displays.forEach { display ->
      out.println("  - Display ${display.id}: ${display.widthPx}x${display.heightPx} px, rotation ${display.orientation}°")
    }
  }
  out.println()
}
