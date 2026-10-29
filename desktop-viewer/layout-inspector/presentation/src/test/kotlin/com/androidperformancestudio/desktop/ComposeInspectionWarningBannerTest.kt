package com.androidperformancestudio.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_unavailable
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ComposeInspectionWarningBannerTest {
    @Test
    fun `fallback reason remains visible without the details pane and clears after recovery`() {
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            runComposeUiTest {
                val reason = mutableStateOf<String?>("Compose tree unavailable")
                setContent {
                    ViewerTheme(darkTheme = false) {
                        reason.value?.let { ComposeInspectionWarningBanner(it, language) }
                    }
                }

                onNodeWithTag("layout-compose-fallback-warning").assertIsDisplayed()
                onNodeWithText(
                    localizedStringResource(Res.string.compose_inspection_unavailable, language) + ": " + reason.value,
                ).assertIsDisplayed()

                runOnIdle { reason.value = null }
                onNodeWithTag("layout-compose-fallback-warning").assertDoesNotExist()
            }
        }
    }
}
