package com.androidperformancestudio.gpu.presentation

import com.androidperformancestudio.gpu.presentation.generated.resources.Res
import com.androidperformancestudio.gpu.presentation.generated.resources.artifact_kind_perfetto_trace
import com.androidperformancestudio.gpu.presentation.generated.resources.launch_mode_gui_only
import com.androidperformancestudio.gpu.presentation.generated.resources.unavailable
import com.androidperformancestudio.gpu.presentation.generated.resources.unknown_version
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuIntegrationLocalizationTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/gpu/presentation/GpuIntegrationScreen.kt"),
        )

    @Test
    fun `gpu inspector screen resolves labels with the application language`() {
        assertTrue(source.contains("localizedStringResource"))
        assertFalse(source.contains("stringResource("))
        assertTrue(source.contains("Res.string.android_gpu_inspector, language"))
        assertTrue(source.contains("Res.string.recent_gpu_artifacts, language"))
        assertTrue(source.contains("artifact.kind.displayName(language)"))
        assertTrue(source.contains("state.capability?.launchMode?.displayName(language)"))
        assertTrue(source.contains("localizedWarning(warning, language)"))
    }

    @Test
    fun `gpu inspector presentation resources are translated to Chinese`() {
        assertEquals(
            "版本未知",
            localizedStringResource(Res.string.unknown_version, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "不可用",
            localizedStringResource(Res.string.unavailable, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "Perfetto 轨迹",
            localizedStringResource(Res.string.artifact_kind_perfetto_trace, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "仅 GUI",
            localizedStringResource(Res.string.launch_mode_gui_only, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }
}
