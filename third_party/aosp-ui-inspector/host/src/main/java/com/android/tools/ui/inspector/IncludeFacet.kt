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

package com.android.tools.ui.inspector

import picocli.CommandLine

/** Optional data facets of a dump, selected via `--include`. */
internal enum class IncludeFacet(val cliName: String) {
  ATTRIBUTES("attributes"),
  SEMANTICS("semantics"),
  RESOLUTION_STACK("resolution-stack"),
  SYSTEM_COMPOSABLES("system-composables"),
  ALL("all"),
}

/**
 * Expands [facets] to the concrete facets to include: `all` becomes every concrete facet, and `resolution-stack` implies `attributes` since
 * resolution stacks are per-attribute data.
 */
internal fun expandIncludeFacets(facets: Collection<IncludeFacet>): Set<IncludeFacet> {
  val expanded = mutableSetOf<IncludeFacet>()
  for (facet in facets) {
    if (facet == IncludeFacet.ALL) {
      expanded += IncludeFacet.entries.filter { it != IncludeFacet.ALL }
    } else {
      expanded += facet
    }
  }
  if (IncludeFacet.RESOLUTION_STACK in expanded) {
    expanded += IncludeFacet.ATTRIBUTES
  }
  return expanded
}

/** Converts kebab-case `--include` values into [IncludeFacet]s. */
internal class IncludeFacetConverter : CommandLine.ITypeConverter<IncludeFacet> {
  override fun convert(value: String): IncludeFacet {
    val trimmedValue = value.trim()
    return IncludeFacet.entries.firstOrNull { it.cliName == trimmedValue }
      ?: throw CommandLine.TypeConversionException(
        "Invalid value for --include: '$value'. Expected one of: ${IncludeFacet.entries.joinToString { it.cliName }}."
      )
  }
}
