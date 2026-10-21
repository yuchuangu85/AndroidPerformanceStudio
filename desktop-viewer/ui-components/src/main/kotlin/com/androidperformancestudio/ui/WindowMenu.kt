package com.androidperformancestudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.menu_clear_recent
import com.androidperformancestudio.ui_components.generated.resources.menu_export
import com.androidperformancestudio.ui_components.generated.resources.menu_file
import com.androidperformancestudio.ui_components.generated.resources.menu_help
import com.androidperformancestudio.ui_components.generated.resources.menu_import
import com.androidperformancestudio.ui_components.generated.resources.menu_no_recent
import com.androidperformancestudio.ui_components.generated.resources.menu_open
import com.androidperformancestudio.ui_components.generated.resources.menu_open_file
import com.androidperformancestudio.ui_components.generated.resources.menu_open_folder
import com.androidperformancestudio.ui_components.generated.resources.menu_open_recent
import com.androidperformancestudio.ui_components.generated.resources.menu_settings
import com.androidperformancestudio.ui_components.generated.resources.menu_tools
import com.androidperformancestudio.ui_components.generated.resources.menu_view

/** One clickable native menu item. */
public data class MenuCommand(
    val label: String,
    val enabled: Boolean = true,
    val shortcut: KeyShortcut? = null,
    val onClick: () -> Unit,
)

/** Recent artifacts of a page; the host always renders the "No recent files" and "Clear" items. */
public data class RecentMenu(
    val items: List<MenuCommand>,
    val enabled: Boolean = true,
    val onClear: (() -> Unit)? = null,
)

/**
 * Items a page contributes to the fixed window menu skeleton.
 *
 * The page never names a top-level menu or the primary "Open…" item: the host owns those titles
 * and shortcuts so every page reads the same. "Open" replaces the page's current session, while
 * [imports] add data to the session that is already loaded.
 */
public data class PageMenu(
    val onOpen: (() -> Unit)? = null,
    val openEnabled: Boolean = true,
    /** Opens a folder; when set, File shows "Open File…" and "Open Folder…" instead of "Open…". */
    val onOpenFolder: (() -> Unit)? = null,
    val imports: List<MenuCommand> = emptyList(),
    val recent: RecentMenu? = null,
    /** Export formats; each inner list is a group separated from the next one. */
    val exportGroups: List<List<MenuCommand>> = emptyList(),
    /** Replaces the global Settings action, e.g. to open this page's own settings section. */
    val onOpenSettings: (() -> Unit)? = null,
    val view: (@Composable MenuScope.() -> Unit)? = null,
    val tools: (@Composable MenuScope.() -> Unit)? = null,
)

/** Shortcuts reserved by the window menu; pages use these instead of defining their own keys. */
public object MenuShortcuts {
    public val isMacOs: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    /** Cmd on macOS, Ctrl elsewhere. */
    public fun primary(
        key: Key,
        isMacOs: Boolean = this.isMacOs,
    ): KeyShortcut = KeyShortcut(key, ctrl = !isMacOs, meta = isMacOs)

    public fun open(isMacOs: Boolean = this.isMacOs): KeyShortcut = primary(Key.O, isMacOs)

    public fun openFolder(isMacOs: Boolean = this.isMacOs): KeyShortcut =
        KeyShortcut(Key.O, ctrl = !isMacOs, meta = isMacOs, shift = true)

    /** Assigned by a page to its most common export format. */
    public fun export(isMacOs: Boolean = this.isMacOs): KeyShortcut = primary(Key.E, isMacOs)

    public fun settings(isMacOs: Boolean = this.isMacOs): KeyShortcut = primary(Key.Comma, isMacOs)
}

/** Holds the menu published by the active page. */
@Stable
public class PageMenuHost internal constructor() {
    public var menu: PageMenu? by mutableStateOf(null)
        internal set
}

@Composable
public fun rememberPageMenuHost(): PageMenuHost = remember { PageMenuHost() }

/** Host the active page publishes into; null when a page runs without the application shell. */
public val LocalPageMenuHost = staticCompositionLocalOf<PageMenuHost?> { null }

/** False for retained pages that are composed but not the visible destination. */
public val LocalWindowMenuBarActive = staticCompositionLocalOf { true }

/** Publishes [menu] into the window menu while the calling page is the active destination. */
@Composable
public fun ActivePageMenu(menu: PageMenu) {
    if (!LocalWindowMenuBarActive.current) return
    val host = LocalPageMenuHost.current ?: return
    val current = rememberUpdatedState(menu)
    SideEffect {
        if (host.menu != current.value) host.menu = current.value
    }
    DisposableEffect(host) {
        onDispose {
            if (host.menu == current.value) host.menu = null
        }
    }
}

/** Top-level menu titles, in order; shared by the window bar and the macOS default bar. */
public fun windowMenuTitles(language: UiLanguage): List<String> =
    listOf(Res.string.menu_file, Res.string.menu_view, Res.string.menu_tools, Res.string.menu_help)
        .map { localizedStringResource(it, language) }

/**
 * Installs the macOS application-wide default menu bar with the same top-level titles.
 *
 * macOS shows the default bar whenever the focused window has no `JMenuBar`: while the JVM starts
 * and the first Compose frame has not yet attached the window bar, and while a Swing dialog is up.
 * Without it the screen menu bar shows only the Apple and application menus during those gaps.
 * The menus are empty; the real items arrive with the window's own bar a moment later.
 */
public fun installDefaultWindowMenuBar(language: UiLanguage) {
    if (!MenuShortcuts.isMacOs) return
    val desktop = runCatching { java.awt.Desktop.getDesktop() }.getOrNull() ?: return
    if (!desktop.isSupported(java.awt.Desktop.Action.APP_MENU_BAR)) return
    java.awt.EventQueue.invokeLater {
        val bar = javax.swing.JMenuBar()
        windowMenuTitles(language).forEach { title -> bar.add(javax.swing.JMenu(title)) }
        runCatching { desktop.setDefaultMenuBar(bar) }
    }
}

/** Window-level entries that do not depend on the active page. */
public data class WindowMenuChrome(
    val language: UiLanguage,
    /** Always-available entries appended to File, e.g. "Open Trace File…". */
    val globalOpenItems: List<MenuCommand> = emptyList(),
    /** Null hides File > Settings, which is how macOS defers to the application menu. */
    val onOpenSettings: (() -> Unit)? = null,
    val helpItems: List<MenuCommand> = emptyList(),
)

/**
 * Renders the window's single native menu bar: File, View, Tools and Help.
 *
 * Compose's [MenuBar] and every top-level `Menu` capture their content once, so the content reads
 * [chrome] and the page menu through state instead of closing over parameters. Because the set of
 * top-level menus is fixed, navigation only toggles items and enabled state; the macOS screen menu
 * bar never has to delete and re-add native menus, which is what used to make it flash.
 */
@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
public fun FrameWindowScope.WindowMenuBar(
    host: PageMenuHost,
    chrome: WindowMenuChrome,
) {
    val currentChrome = rememberUpdatedState(chrome)
    // Keep the macOS fallback bar's titles in the current language.
    LaunchedEffect(chrome.language) { installDefaultWindowMenuBar(chrome.language) }
    MenuBar {
        val language = currentChrome.value.language
        val page = host.menu
        val (fileTitle, viewTitle, toolsTitle, helpTitle) = windowMenuTitles(language)
        Menu(fileTitle) {
            FileMenuItems(currentChrome.value, host.menu ?: PageMenu())
        }
        Menu(viewTitle, enabled = page?.view != null) {
            host.menu?.view?.invoke(this)
        }
        Menu(toolsTitle, enabled = page?.tools != null) {
            host.menu?.tools?.invoke(this)
        }
        Menu(
            helpTitle,
            enabled = currentChrome.value.helpItems.isNotEmpty(),
        ) {
            currentChrome.value.helpItems.forEach { CommandItem(it) }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MenuScope.FileMenuItems(
    chrome: WindowMenuChrome,
    page: PageMenu,
) {
    val language = chrome.language
    val openFolder = page.onOpenFolder
    Item(
        localizedStringResource(if (openFolder == null) Res.string.menu_open else Res.string.menu_open_file, language),
        enabled = page.onOpen != null && page.openEnabled,
        shortcut = MenuShortcuts.open(),
        onClick = { page.onOpen?.invoke() },
    )
    if (openFolder != null) {
        Item(
            localizedStringResource(Res.string.menu_open_folder, language),
            enabled = page.openEnabled,
            shortcut = MenuShortcuts.openFolder(),
            onClick = openFolder,
        )
    }
    Menu(localizedStringResource(Res.string.menu_import, language), enabled = page.imports.isNotEmpty()) {
        page.imports.forEach { CommandItem(it) }
    }
    Menu(
        localizedStringResource(Res.string.menu_open_recent, language),
        enabled = page.recent?.enabled ?: false,
    ) {
        val recent = page.recent
        if (recent == null || recent.items.isEmpty()) {
            Item(localizedStringResource(Res.string.menu_no_recent, language), enabled = false, onClick = {})
        } else {
            recent.items.forEach { CommandItem(it) }
        }
        if (recent?.onClear != null) {
            Separator()
            Item(
                localizedStringResource(Res.string.menu_clear_recent, language),
                enabled = recent.items.isNotEmpty(),
                onClick = recent.onClear,
            )
        }
    }
    Separator()
    Menu(localizedStringResource(Res.string.menu_export, language), enabled = page.hasExports()) {
        page.exportGroups.filter { it.isNotEmpty() }.forEachIndexed { index, group ->
            if (index > 0) Separator()
            group.forEach { CommandItem(it) }
        }
    }
    if (chrome.globalOpenItems.isNotEmpty()) {
        Separator()
        chrome.globalOpenItems.forEach { CommandItem(it) }
    }
    val openSettings = chrome.onOpenSettings?.let { global -> page.onOpenSettings ?: global }
    if (openSettings != null) {
        Separator()
        Item(
            localizedStringResource(Res.string.menu_settings, language),
            shortcut = MenuShortcuts.settings(),
            onClick = openSettings,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MenuScope.CommandItem(command: MenuCommand) {
    Item(command.label, enabled = command.enabled, shortcut = command.shortcut, onClick = command.onClick)
}

internal fun PageMenu.hasExports(): Boolean = exportGroups.any { it.isNotEmpty() }
