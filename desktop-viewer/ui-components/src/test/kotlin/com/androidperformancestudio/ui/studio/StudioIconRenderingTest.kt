package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_home
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_source_workspaces
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_layout_inspector
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_cpu_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_trace_analyzer
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_memory_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_frame_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_startup_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_battery
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_network
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_gpu_inspector
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_benchmark
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_method_recording
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_rail_collapse
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_rail_expand
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_artifact
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_attention
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_capability
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_device
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_history
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_verified
import com.androidperformancestudio.ui_components.generated.resources.ic_action_play
import com.androidperformancestudio.ui_components.generated.resources.ic_action_import
import com.androidperformancestudio.ui_components.generated.resources.ic_settings_ai
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders every bundled studio vector through the production painter path so a malformed SVG or a
 * missing resource fails here instead of showing a blank icon at runtime.
 */
@OptIn(ExperimentalTestApi::class)
class StudioIconRenderingTest {
    private val icons: List<Pair<String, DrawableResource>> =
        listOf(
            "nav_home" to Res.drawable.ic_nav_home,
            "nav_source_workspaces" to Res.drawable.ic_nav_source_workspaces,
            "nav_layout_inspector" to Res.drawable.ic_nav_layout_inspector,
            "nav_cpu_profiler" to Res.drawable.ic_nav_cpu_profiler,
            "nav_trace_analyzer" to Res.drawable.ic_nav_trace_analyzer,
            "nav_memory_profiler" to Res.drawable.ic_nav_memory_profiler,
            "nav_frame_profiler" to Res.drawable.ic_nav_frame_profiler,
            "nav_startup_profiler" to Res.drawable.ic_nav_startup_profiler,
            "nav_battery" to Res.drawable.ic_nav_battery,
            "nav_network" to Res.drawable.ic_nav_network,
            "nav_gpu_inspector" to Res.drawable.ic_nav_gpu_inspector,
            "nav_benchmark" to Res.drawable.ic_nav_benchmark,
            "nav_method_recording" to Res.drawable.ic_nav_method_recording,
            "nav_rail_collapse" to Res.drawable.ic_nav_rail_collapse,
            "nav_rail_expand" to Res.drawable.ic_nav_rail_expand,
            "ai_analysis" to Res.drawable.ic_settings_ai,
            "stat_device" to Res.drawable.ic_stat_device,
            "stat_history" to Res.drawable.ic_stat_history,
            "stat_artifact" to Res.drawable.ic_stat_artifact,
            "stat_verified" to Res.drawable.ic_stat_verified,
            "stat_attention" to Res.drawable.ic_stat_attention,
            "stat_capability" to Res.drawable.ic_stat_capability,
            "action_play" to Res.drawable.ic_action_play,
            "action_import" to Res.drawable.ic_action_import,
        )

    @Test
    fun `every bundled studio vector decodes and paints visible pixels`() =
        runDesktopComposeUiTest(width = 720, height = 160) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    Row {
                        icons.forEach { (name, resource) ->
                            Icon(
                                painter = painterResource(resource),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp).testTag("studio-icon-$name"),
                                tint = Color.Red,
                            )
                        }
                    }
                }
            }

            icons.forEach { (name, _) ->
                val pixels = onNodeWithTag("studio-icon-$name").captureToImage().toPixelMap()
                var tinted = 0
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        val pixel = pixels[x, y]
                        if (pixel.red > 0.5f && pixel.green < 0.3f && pixel.blue < 0.3f) {
                            tinted++
                        }
                    }
                }
                assertTrue(tinted > 0, "studio icon '$name' painted no vector pixels")
            }
        }
}
