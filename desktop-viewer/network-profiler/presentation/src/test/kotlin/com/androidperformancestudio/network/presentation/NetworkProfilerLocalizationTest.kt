package com.androidperformancestudio.network.presentation

import com.androidperformancestudio.network.presentation.generated.resources.Res
import com.androidperformancestudio.network.presentation.generated.resources.http_exchange
import com.androidperformancestudio.network.presentation.generated.resources.incomplete
import com.androidperformancestudio.network.presentation.generated.resources.phase_detail
import com.androidperformancestudio.network.presentation.generated.resources.request_details
import com.androidperformancestudio.network.presentation.generated.resources.reused_unknown
import com.androidperformancestudio.network.presentation.generated.resources.unavailable
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkProfilerLocalizationTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/network/presentation/NetworkProfilerScreen.kt"),
        )

    @Test
    fun `network profiler screen resolves labels with the application language`() {
        assertTrue(source.contains("localizedStringResource"))
        assertFalse(source.contains("stringResource("))
        assertTrue(source.contains("Res.string.calls, language"))
        assertTrue(source.contains("Res.string.request_details, language"))
        assertTrue(source.contains("Res.string.incomplete, language"))
        assertTrue(source.contains("coverage.instrumentationMode.displayName(language)"))
        assertTrue(source.contains("state.result.session.completeness.status.displayName(language)"))
        assertTrue(source.contains("call.outcome.displayName(language)"))
        assertTrue(source.contains("phase.kind.displayName(language)"))
        assertTrue(source.contains("phase.confidence.displayName(language)"))
    }

    @Test
    fun `network profiler presentation resources are translated to Chinese`() {
        assertEquals(
            "请求详情",
            localizedStringResource(Res.string.request_details, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "HTTP 200 · h2 · 连接 conn-1 · 复用",
            localizedStringResource(
                Res.string.http_exchange,
                UiLanguage.SIMPLIFIED_CHINESE,
                200,
                "h2",
                "conn-1",
                "复用",
            ),
        )
        assertEquals(
            "复用/未知",
            localizedStringResource(Res.string.reused_unknown, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "DNS：1.25 ms · HIGH",
            localizedStringResource(
                Res.string.phase_detail,
                UiLanguage.SIMPLIFIED_CHINESE,
                "DNS",
                "1.25 ms",
                "HIGH",
            ),
        )
        assertEquals(
            "不可用",
            localizedStringResource(Res.string.unavailable, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "未完成",
            localizedStringResource(Res.string.incomplete, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }
}
