package dev.agentperf.desktop

internal object ViewDisplayProjection {
    fun hierarchyRows(
        rows: List<TreeRowModel>,
        hideInvisible: Boolean,
    ): List<TreeRowModel> {
        if (!hideInvisible) return rows

        return buildList {
            var hiddenAncestorDepth: Int? = null
            rows.forEach { row ->
                val hiddenDepth = hiddenAncestorDepth
                if (hiddenDepth != null) {
                    if (row.depth > hiddenDepth) {
                        return@forEach
                    }
                    hiddenAncestorDepth = null
                }
                if (row.visible) {
                    add(row)
                } else {
                    hiddenAncestorDepth = row.depth
                }
            }
        }
    }

    fun findings(
        findings: List<FindingRowModel>,
        rows: List<TreeRowModel>,
        hideInvisible: Boolean,
    ): List<FindingRowModel> {
        if (!hideInvisible) return findings

        val rowIds = rows.mapTo(mutableSetOf(), TreeRowModel::id)
        val displayedRowIds = hierarchyRows(rows, hideInvisible = true)
            .mapTo(mutableSetOf(), TreeRowModel::id)
        val hiddenRowIds = rowIds - displayedRowIds
        return findings.filter { it.nodeId !in hiddenRowIds }
    }

    fun severitySummary(findings: List<FindingRowModel>): SeveritySummary = SeveritySummary(
        info = findings.count { it.tone == FindingTone.INFO },
        warning = findings.count { it.tone == FindingTone.WARNING },
        error = findings.count { it.tone == FindingTone.ERROR },
    )

    fun hierarchyLabel(
        row: TreeRowModel,
        hideIndex: Boolean,
        showId: Boolean,
    ): String = listOfNotNull(
        row.number.takeUnless { hideIndex },
        row.resourceLabel.takeIf { showId },
        row.label,
    ).joinToString("  ")
}
