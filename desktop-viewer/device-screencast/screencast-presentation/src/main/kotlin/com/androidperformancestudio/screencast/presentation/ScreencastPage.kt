package com.androidperformancestudio.screencast.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.screencast.protocol.ScrcpyControlWriter
import com.androidperformancestudio.screencast.server.ScreencastQuality
import com.androidperformancestudio.screencast.session.ScrcpySessionState
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactTextField
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import kotlin.math.roundToInt

/**
 * Device screencast page: device selection, session control, the mirrored
 * canvas with pointer control, navigation keys, and a send-to-device text bar.
 *
 * Closing the hosting window relies on the OS-provided close button; this page exposes no in-app
 * close control.
 */
@Composable
public fun ScreencastPage(
    controller: ScreencastPageController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val frame by controller.frame.collectAsState()
    val refreshError by controller.refreshError.collectAsState()
    val devices by controller.devices.collectAsState()
    val selectedSerial by controller.selectedSerial.collectAsState()
    val quality by controller.quality.collectAsState()
    val screenOff by controller.screenOff.collectAsState()
    val powerKeyActive by controller.powerKeyActive.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        ScreencastHeader(
            state = state,
            devices = devices,
            refreshError = refreshError,
            selectedSerial = selectedSerial,
            quality = quality,
            onQualitySelected = controller::selectQuality,
            onDeviceSelected = controller::selectDevice,
            onRefreshDevices = controller::refreshDevices,
            onNavigationKey = controller::tapNavigationKey,
            navEnabled = state is ScrcpySessionState.Running,
            screenOff = screenOff,
            powerKeyActive = powerKeyActive,
            onToggleScreenPower = controller::toggleScreenPower,
            onTogglePowerKey = controller::togglePowerKey,
        )

        if (controller.decoderFallbackMessage != null) {
            FallbackBanner("FFmpeg 解码器加载失败，已降级为兼容解码（流畅档）：${controller.decoderFallbackMessage}")
        }

        ScreencastStage(
            state = state,
            frame = frame,
            controller = controller,
            onStart = controller::start,
            canStart = selectedSerial != null,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        ControlBar(
            enabled = state is ScrcpySessionState.Running,
            onPaste = controller::pasteToDevice,
        )
    }
}

/**
 * Toolbar status text for [ScreencastHeader]: [Pair.first] is the operation
 * message, [Pair.second] the error (Failed reason), which takes precedence.
 * Idle yields an empty message so the status slot acts as a spacer that pins
 * the action button to the row tail.
 */
private fun screencastStatusText(state: ScrcpySessionState): Pair<String, String?> {
    val error = (state as? ScrcpySessionState.Failed)?.message
    val message = when (state) {
        is ScrcpySessionState.Idle -> ""
        is ScrcpySessionState.Connecting -> "正在连接设备…"
        is ScrcpySessionState.Running -> "正在投屏：${state.deviceName}（${state.width}×${state.height}）"
        is ScrcpySessionState.Disconnected -> "设备已断开"
        else -> ""
    }
    return message to error
}

@Suppress("LongParameterList")
@Composable
private fun ScreencastHeader(
    state: ScrcpySessionState,
    devices: List<DeviceOption>,
    refreshError: String?,
    selectedSerial: String?,
    quality: ScreencastQuality,
    onQualitySelected: (ScreencastQuality) -> Unit,
    onDeviceSelected: (String) -> Unit,
    onRefreshDevices: () -> Unit,
    onNavigationKey: (Int) -> Unit,
    navEnabled: Boolean,
    screenOff: Boolean,
    powerKeyActive: Boolean,
    onToggleScreenPower: () -> Unit,
    onTogglePowerKey: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val deviceSelectable = state is ScrcpySessionState.Idle ||
        state is ScrcpySessionState.Failed ||
        state is ScrcpySessionState.Disconnected
    val selectedDevice = devices.firstOrNull { it.serial == selectedSerial }
    val (statusMessage, failedMessage) = screencastStatusText(state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.toolbar)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1: Android navigation-key icons (back / home / recent) lead the toolbar, followed by
        // device selection, refresh, and quality — all on one row in the narrow screencast window.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavigationKey.entries.forEach { key ->
                ScreencastIconButton(
                    label = key.label,
                    enabled = navEnabled,
                    onClick = { onNavigationKey(key.keycode) },
                ) { color ->
                    NavKeyGlyph(key = key, color = color, modifier = Modifier.size(18.dp))
                }
            }
            ScreencastIconButton(
                label = if (screenOff) "亮屏" else "熄屏",
                enabled = navEnabled,
                onClick = onToggleScreenPower,
            ) { color ->
                ScreenPowerGlyph(off = screenOff, color = color, modifier = Modifier.size(18.dp))
            }
            // POWER key injection — like pressing the device's physical power button. Available only
            // while a session is running (same as the nav keys, since it goes through the control channel).
            ScreencastIconButton(
                label = if (powerKeyActive) "唤醒" else "电源键",
                enabled = navEnabled,
                onClick = onTogglePowerKey,
            ) { color ->
                PowerKeyGlyph(active = powerKeyActive, color = color, modifier = Modifier.size(18.dp))
            }
            DropdownSelector(
                items = devices,
                selectedItem = selectedDevice,
                onItemSelected = { onDeviceSelected(it.serial) },
                itemLabel = { it.displayName },
                placeholder = refreshError ?: "选择设备",
                enabled = deviceSelectable && devices.isNotEmpty(),
                modifier = Modifier.weight(1f),
                controlFontSize = ViewerTypography.bodyCompact.fontSize,
                controlHeight = SCREENCEAST_TOOL_HEIGHT,
            )
            ScreencastToolButton(
                text = "刷新",
                onClick = onRefreshDevices,
                enabled = state !is ScrcpySessionState.Connecting,
            )
            DropdownSelector(
                items = ScreencastQuality.entries,
                selectedItem = quality,
                onItemSelected = onQualitySelected,
                itemLabel = { "${it.label} · ${it.maxFps}fps" },
                placeholder = "画质",
                enabled = state !is ScrcpySessionState.Connecting,
                controlFontSize = ViewerTypography.bodyCompact.fontSize,
                controlHeight = SCREENCEAST_TOOL_HEIGHT,
            )
        }
        // Row 2: session status only. There is no in-app stop/close control: stopping the screencast
        // is done by closing the window (which disposes the controller and tears down the session),
        // and the connection/running/error state is already conveyed by the status line below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfilerToolbarStatus(message = statusMessage, error = failedMessage)
        }
    }
}

/**
 * Canvas stage: the mirrored frame plus state overlays. Caches the last
 * known device size so a frozen frame (disconnect) keeps its aspect ratio
 * instead of collapsing to the 1×1 fallback, and layers a dim overlay +
 * status card over non-running states.
 */
@Suppress("LongParameterList")
@Composable
private fun ScreencastStage(
    state: ScrcpySessionState,
    frame: ImageBitmap?,
    controller: ScreencastPageController,
    onStart: () -> Unit,
    canStart: Boolean,
    modifier: Modifier = Modifier,
) {
    val running = state is ScrcpySessionState.Running
    var lastSize by remember { mutableStateOf<IntSize?>(null) }
    if (state is ScrcpySessionState.Running) {
        lastSize = IntSize(state.width, state.height)
    }
    Box(modifier = modifier) {
        ScreencastCanvas(
            frame = frame,
            controller = controller,
            enabled = running,
            deviceSize = lastSize,
            modifier = Modifier.fillMaxSize(),
        )
        val failed = state as? ScrcpySessionState.Failed
        when (state) {
            is ScrcpySessionState.Disconnected, is ScrcpySessionState.Failed -> DimOverlay()
            else -> Unit
        }
        when (state) {
            is ScrcpySessionState.Idle ->
                StateCard(
                    title = "设备投屏",
                    subtitle = if (canStart) "选择画质，点击此处开始" else "请先选择设备",
                    action = StateCardAction("开始投屏", onStart, canStart),
                )
            is ScrcpySessionState.Connecting ->
                StateCard(loading = true, title = "正在连接设备…", subtitle = "正在建立反向隧道与协议握手")
            is ScrcpySessionState.Disconnected ->
                StateCard(
                    symbol = "⚡",
                    title = "设备已断开",
                    subtitle = "点击此处重新连接",
                    action = StateCardAction("重新连接", onStart, canStart),
                )
            is ScrcpySessionState.Failed ->
                StateCard(
                    symbol = "⚠",
                    title = "连接失败",
                    subtitle = failed?.message,
                    tone = StateTone.ERROR,
                    action = StateCardAction("重试", onStart, canStart),
                )
            else -> Unit
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun ScreencastCanvas(
    frame: ImageBitmap?,
    controller: ScreencastPageController,
    enabled: Boolean,
    deviceSize: IntSize?,
    modifier: Modifier = Modifier,
) {
    val screenWidth = deviceSize?.width ?: 0
    val screenHeight = deviceSize?.height ?: 0

    Canvas(
        modifier = modifier
            .onSizeChanged { controller.canvasSize = it }
            .pointerInput(enabled, screenWidth, screenHeight) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val viewport = containViewport(
                            screenWidth,
                            screenHeight,
                            size.width.toFloat(),
                            size.height.toFloat(),
                        )
                        val change = event.changes.firstOrNull() ?: continue
                        val screen =
                            mapToScreen(
                                change.position.x,
                                change.position.y,
                                viewport,
                                screenWidth,
                                screenHeight,
                            )
                        when (event.type) {
                            PointerEventType.Press -> {
                                screen?.let { (x, y) ->
                                    controller.sendTouch(
                                        ScrcpyControlWriter.ACTION_DOWN, x, y,
                                        pressure = 1f,
                                        buttons = ScrcpyControlWriter.BUTTON_PRIMARY,
                                    )
                                }
                            }
                            PointerEventType.Release -> {
                                screen?.let { (x, y) ->
                                    controller.sendTouch(
                                        ScrcpyControlWriter.ACTION_UP, x, y,
                                        pressure = 0f,
                                        buttons = 0,
                                    )
                                }
                            }
                            PointerEventType.Move -> {
                                screen?.let { (x, y) ->
                                    controller.sendTouch(
                                        if (change.pressed) {
                                            ScrcpyControlWriter.ACTION_MOVE
                                        } else {
                                            ScrcpyControlWriter.ACTION_HOVER_MOVE
                                        },
                                        x, y,
                                        pressure = if (change.pressed) 1f else 0f,
                                        buttons = if (change.pressed) ScrcpyControlWriter.BUTTON_PRIMARY else 0,
                                    )
                                }
                            }
                            PointerEventType.Scroll -> {
                                screen?.let { (x, y) ->
                                    // One wheel notch (delta ±1) equals one full
                                    // scrcpy scroll unit (1.0); invert to match
                                    // scrcpy's positive = scroll-up semantics.
                                    controller.sendScroll(
                                        x, y,
                                        scrollX = -change.scrollDelta.x,
                                        scrollY = -change.scrollDelta.y,
                                    )
                                }
                            }
                        }
                    }
                }
            },
    ) {
        val bitmap = frame
        if (bitmap != null && screenWidth > 0 && screenHeight > 0) {
            val viewport = containViewport(screenWidth, screenHeight, size.width, size.height)
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(viewport.offsetX.roundToInt(), viewport.offsetY.roundToInt()),
                dstSize = IntSize(viewport.width.roundToInt(), viewport.height.roundToInt()),
            )
        }
    }
}

/** Visual tone for [StateCard]: drives the symbol color. */
private enum class StateTone { NEUTRAL, ERROR, WARNING }

/** Optional clickable action rendered at the bottom of a [StateCard]. */
private data class StateCardAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * Centered status card for the canvas area's non-running states
 * (idle / connecting / disconnected / failed). Same shell, different symbol
 * and copy; [loading] swaps the symbol for a spinner. When [action] is set
 * the whole card is clickable and [StateCardAction.label] renders as an
 * accent hint line; there is no separate button — the card itself is the
 * entry point.
 */
@Suppress("LongParameterList")
@Composable
private fun StateCard(
    title: String,
    subtitle: String? = null,
    symbol: String? = null,
    loading: Boolean = false,
    tone: StateTone = StateTone.NEUTRAL,
    action: StateCardAction? = null,
) {
    val colors = LocalViewerColors.current
    val symbolColor = when (tone) {
        StateTone.ERROR -> colors.error
        StateTone.WARNING -> colors.warning
        StateTone.NEUTRAL -> colors.accent
    }
    val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(ViewerDimensions.controlRadius * 2)
    val clickAction = action?.takeIf { it.enabled }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = colors.panel,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            shape = cardShape,
            modifier = Modifier
                .widthIn(min = 240.dp)
                .then(if (clickAction != null) Modifier.clickable(onClick = clickAction.onClick) else Modifier),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StateCardSymbol(loading = loading, symbol = symbol, color = symbolColor)
                Text(
                    text = title,
                    color = colors.text,
                    style = ViewerTypography.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.secondaryText,
                        style = ViewerTypography.bodyCompact,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (clickAction != null) {
                    Text(
                        text = clickAction.label,
                        color = colors.accent,
                        style = ViewerTypography.bodyCompact,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateCardSymbol(loading: Boolean, symbol: String?, color: androidx.compose.ui.graphics.Color) {
    if (loading) {
        CircularProgressIndicator(
            color = LocalViewerColors.current.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    } else {
        symbol?.let {
            Text(text = it, color = color, style = ViewerTypography.pageTitle)
        }
    }
}

/** Translucent dim layer over the frozen frame on disconnect/failure. */
@Composable
private fun DimOverlay(alpha: Float = 0.45f) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalViewerColors.current.text.copy(alpha = alpha)),
    )
}

@Composable
private fun FallbackBanner(message: String) {
    val colors = LocalViewerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warning.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "⚠ $message",
            color = colors.warning,
            style = ViewerTypography.secondary,
        )
    }
}

@Composable
private fun ControlBar(
    enabled: Boolean,
    onPaste: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val colors = LocalViewerColors.current
    val send = {
        if (text.isNotEmpty()) {
            onPaste(text)
            text = ""
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.toolbar)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProfilerCompactTextField(
            label = "",
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            placeholder = "输入文本发送到设备（回车发送）",
            modifier = Modifier.weight(1f),
            onSubmit = send,
        )
        ProfilerCompactButton(
            text = "发送到设备",
            onClick = send,
            enabled = enabled && text.isNotEmpty(),
        )
    }
}

/** The three Android navigation keys, drawn as the classic 3-button-nav glyphs. */
private enum class NavigationKey(
    val keycode: Int,
    val label: String,
) {
    BACK(ScrcpyControlWriter.KEYCODE_BACK, "返回"),
    HOME(ScrcpyControlWriter.KEYCODE_HOME, "主页"),
    RECENT(ScrcpyControlWriter.KEYCODE_APP_SWITCH, "最近任务"),
}

private const val NAV_KEY_DISABLED_ALPHA: Float = 0.38f
private val SCREENCEAST_TOOL_HEIGHT: Dp = 32.dp

/**
 * A toolbar text button sized to match the screencast window's larger nav-key buttons (32dp tall,
 * bodyCompact text) so the whole first toolbar row — nav glyphs, device dropdown, refresh, quality
 * — reads as one coordinated, legible set instead of the cramped 24dp compact control size.
 */
@Composable
private fun ScreencastToolButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val containerColor = MaterialTheme.colorScheme.surface
    val contentColor = MaterialTheme.colorScheme.primary
    val alpha = if (enabled) 1f else NAV_KEY_DISABLED_ALPHA
    val shape = RoundedCornerShape(ViewerDimensions.compactControlRadius)
    Box(
        modifier =
            Modifier
                .height(SCREENCEAST_TOOL_HEIGHT)
                .clip(shape)
                .background(containerColor.copy(alpha = alpha))
                .border(1.dp, contentColor, shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor.copy(alpha = alpha),
            fontSize = ViewerTypography.bodyCompact.fontSize,
            lineHeight = ViewerTypography.bodyCompact.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A square 32dp toolbar icon button (nav keys, power, brightness …) sharing one look: surface
 * background, primary border, centered glyph. The glyph is provided by [content] and should size
 * itself (≈18dp).
 */
@Composable
private fun ScreencastIconButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable (color: Color) -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surface
    val contentColor = MaterialTheme.colorScheme.primary
    val alpha = if (enabled) 1f else NAV_KEY_DISABLED_ALPHA
    val shape = RoundedCornerShape(ViewerDimensions.compactControlRadius)
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(shape)
                .background(containerColor.copy(alpha = alpha))
                .border(1.dp, contentColor, shape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content(contentColor.copy(alpha = alpha))
    }
}

/** Draws the classic Android 3-button-nav glyph for [key]: back triangle, home circle, recent square. */
@Composable
private fun NavKeyGlyph(
    key: NavigationKey,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.16f
        when (key) {
            NavigationKey.BACK -> {
                val path = Path().apply {
                    moveTo(w * 0.72f, h * 0.18f)
                    lineTo(w * 0.72f, h * 0.82f)
                    lineTo(w * 0.24f, h * 0.5f)
                    close()
                }
                drawPath(path, color)
            }
            NavigationKey.HOME -> {
                drawCircle(
                    color = color,
                    radius = size.minDimension * 0.28f,
                    style = Stroke(width = stroke),
                )
            }
            NavigationKey.RECENT -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.24f, h * 0.24f),
                    size = Size(w * 0.52f, h * 0.52f),
                    cornerRadius = CornerRadius(size.minDimension * 0.1f),
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

/**
 * Screen-power glyph: a small display/monitor outline. When the screen is off ([off] = true) a
 * diagonal "off" line is drawn across it; when on, only the outline stands — same convention as a
 * standby power button.
 */
@Composable
private fun ScreenPowerGlyph(
    off: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.16f
        // Monitor outline.
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.2f, h * 0.16f),
            size = Size(w * 0.6f, h * 0.52f),
            cornerRadius = CornerRadius(size.minDimension * 0.08f),
            style = Stroke(width = stroke),
        )
        // Stand.
        drawLine(
            color = color,
            start = Offset(w * 0.4f, h * 0.72f),
            end = Offset(w * 0.4f, h * 0.84f),
            strokeWidth = stroke,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.6f, h * 0.72f),
            end = Offset(w * 0.6f, h * 0.84f),
            strokeWidth = stroke,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.3f, h * 0.84f),
            end = Offset(w * 0.7f, h * 0.84f),
            strokeWidth = stroke,
        )
        if (off) {
            // Diagonal "off" slash across the screen face.
            drawLine(
                color = color,
                start = Offset(w * 0.24f, h * 0.18f),
                end = Offset(w * 0.76f, h * 0.66f),
                strokeWidth = stroke,
            )
        }
    }
}

/**
 * Power-key glyph: the standard standby power symbol — an open circle with a vertical stem rising
 * from its top, matching a device's physical power button. When [active] (a POWER press has been
 * injected, screen presumably toggled off) the symbol fills to read as "pressed/inactive".
 */
@Composable
private fun PowerKeyGlyph(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val stroke = size.minDimension * 0.16f
        val radius = size.minDimension * 0.28f
        // Open-arc circle (gap at top for the stem), spanning ~270°.
        val startAngle = 135f
        val sweep = 270f
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke),
        )
        // Vertical stem from the top of the arc gap down to the circle's upper edge.
        drawLine(
            color = color,
            start = Offset(cx, cy - radius * 1.5f),
            end = Offset(cx, cy - radius * 0.2f),
            strokeWidth = stroke,
        )
        // When a POWER press is active, dot the center to signal "screen off / pressed in".
        if (active) {
            drawCircle(color = color, radius = radius * 0.28f, center = Offset(cx, cy))
        }
    }
}
