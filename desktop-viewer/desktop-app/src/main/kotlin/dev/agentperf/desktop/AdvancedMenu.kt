package dev.agentperf.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class AdvancedMenuModel(
    val title: String,
    val exportLabel: String,
    val exportEnabled: Boolean,
) {
    constructor(
        strings: ViewerStrings,
        exportInProgress: Boolean = false,
    ) : this(
        title = strings.advanced,
        exportLabel = strings.exportVisibleWindowViews,
        exportEnabled = !exportInProgress,
    )
}

@Composable
internal fun AdvancedMenu(
    model: AdvancedMenuModel,
    onExport: () -> Unit,
) {
    val colors = LocalViewerColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .height(23.dp)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.title,
                color = colors.secondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(5.dp))
            Text("▾", color = colors.mutedText, fontSize = 10.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.panel).width(280.dp),
        ) {
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = model.exportLabel,
                            color = colors.primaryText,
                            fontSize = 11.sp,
                        )
                    }
                },
                enabled = model.exportEnabled,
                onClick = {
                    expanded = false
                    onExport()
                },
            )
        }
    }
}
