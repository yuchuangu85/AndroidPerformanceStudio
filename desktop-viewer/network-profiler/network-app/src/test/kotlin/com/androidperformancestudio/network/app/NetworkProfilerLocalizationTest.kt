package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.network_app.generated.resources.Res
import com.androidperformancestudio.network.network_app.generated.resources.agent_session_started
import com.androidperformancestudio.network.network_app.generated.resources.capture_completed
import com.androidperformancestudio.network.network_app.generated.resources.captured_raw_events
import com.androidperformancestudio.network.network_app.generated.resources.http_archive
import com.androidperformancestudio.network.network_app.generated.resources.import_har
import com.androidperformancestudio.network.network_app.generated.resources.imported_redacted
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkProfilerLocalizationTest {
    @Test
    fun `network profiler application resources are translated to Chinese`() {
        assertEquals(
            "导入 HAR",
            localizedStringResource(Res.string.import_har, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "已启动并认证 Network Agent 会话。",
            localizedStringResource(Res.string.agent_session_started, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "已采集 12 个原始事件，不会采集正文内容。",
            localizedStringResource(
                Res.string.captured_raw_events,
                UiLanguage.SIMPLIFIED_CHINESE,
                12,
            ),
        )
        assertEquals(
            "在线采集完成，共观察到 3 个 OkHttp 请求。",
            localizedStringResource(
                Res.string.capture_completed,
                UiLanguage.SIMPLIFIED_CHINESE,
                3,
            ),
        )
        assertEquals(
            "已导入 capture.har；敏感请求头和查询参数值已脱敏。",
            localizedStringResource(
                Res.string.imported_redacted,
                UiLanguage.SIMPLIFIED_CHINESE,
                "capture.har",
            ),
        )
        assertEquals(
            "HTTP 归档",
            localizedStringResource(Res.string.http_archive, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }
}
