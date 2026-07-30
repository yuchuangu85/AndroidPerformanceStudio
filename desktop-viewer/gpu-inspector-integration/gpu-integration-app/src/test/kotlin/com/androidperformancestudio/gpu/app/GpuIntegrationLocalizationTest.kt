package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.Res
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.imported_file
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.select_android_gpu_inspector_executable
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.test.Test
import kotlin.test.assertEquals

class GpuIntegrationLocalizationTest {
    @Test
    fun `gpu inspector application messages are translated to Chinese`() {
        assertEquals(
            "选择 Android GPU Inspector 可执行文件",
            localizedStringResource(
                Res.string.select_android_gpu_inspector_executable,
                UiLanguage.SIMPLIFIED_CHINESE,
            ),
        )
        assertEquals(
            "已导入 capture.gfxtrace",
            localizedStringResource(
                Res.string.imported_file,
                UiLanguage.SIMPLIFIED_CHINESE,
                "capture.gfxtrace",
            ),
        )
    }
}
