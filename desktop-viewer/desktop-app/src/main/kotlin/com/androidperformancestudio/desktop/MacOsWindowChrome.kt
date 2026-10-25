package com.androidperformancestudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.ViewerTypography
import java.util.Locale
import javax.swing.RootPaneContainer

/**
 * macOS draws the close/minimize/maximize buttons inside a native title bar that owns its own
 * background and title colour, so neither can be themed directly. Extending the window content
 * underneath a transparent title bar lets the app paint that strip itself while the buttons stay
 * native: dragging, double-click zoom and the green full-screen button keep working.
 */
internal object MacOsWindowChrome {
    /** App-painted title strip, 2 dp taller than the original 28 dp layout. */
    internal val titleBarHeight: Dp = 30.dp

    /** Hairline separating the title bar from the window body. */
    internal val titleBarDividerHeight: Dp = 1.dp

    internal const val FULL_WINDOW_CONTENT = "apple.awt.fullWindowContent"
    internal const val TRANSPARENT_TITLE_BAR = "apple.awt.transparentTitleBar"
    internal const val WINDOW_TITLE_VISIBLE = "apple.awt.windowTitleVisible"

    /** Only macOS embeds the title bar; Windows and Linux keep their native window frame. */
    internal fun embedsTitleBar(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
        osName.lowercase(Locale.ROOT).contains("mac")

    /**
     * Makes the native title bar transparent and lets the content view cover it. The native title
     * text is hidden because it cannot be recoloured; [MacOsTitleBar] draws it instead. The macOS
     * peer applies all three client properties live, and this is a no-op on any other platform.
     */
    internal fun install(window: RootPaneContainer) {
        if (!embedsTitleBar()) return
        window.rootPane.putClientProperty(FULL_WINDOW_CONTENT, true)
        window.rootPane.putClientProperty(TRANSPARENT_TITLE_BAR, true)
        window.rootPane.putClientProperty(WINDOW_TITLE_VISIBLE, false)
    }
}

/**
 * Paints the native title bar strip: theme background, contrasting title text and a bottom hairline.
 *
 * Compose it outside the viewer theme so the fixed point height keeps matching the real window
 * buttons instead of following the user's display scale.
 */
@Composable
internal fun MacOsTitleBar(
    title: String,
    background: Color,
    contentColor: Color,
    dividerColor: Color,
    modifier: Modifier = Modifier,
    targetControls: (@Composable () -> Unit)? = null,
) {
    if (!MacOsWindowChrome.embedsTitleBar()) return
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MacOsWindowChrome.titleBarHeight)
                .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (targetControls == null) {
            Text(
                text = title,
                color = contentColor,
                style = ViewerTypography.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The traffic lights own the leading edge; keep the title clear of them.
                modifier = Modifier.padding(horizontal = 80.dp),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 80.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = contentColor,
                    style = ViewerTypography.body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Keep selectors in the content area, away from the native window controls.
                Box(Modifier.widthIn(max = 484.dp).clipToBounds()) { targetControls() }
            }
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(MacOsWindowChrome.titleBarDividerHeight)
                    .background(dividerColor),
        )
    }
}
