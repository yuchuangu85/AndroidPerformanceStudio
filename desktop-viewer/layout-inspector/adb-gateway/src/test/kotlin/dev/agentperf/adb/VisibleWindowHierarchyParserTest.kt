package dev.agentperf.adb

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.EdgeInsets
import dev.agentperf.protocol.ViewNode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisibleWindowHierarchyParserTest {
    @Test
    fun `decodes actual runtime classes and screen bounds`() {
        val root = VisibleWindowHierarchyParser.parse(
            zipBytes = EncodedHierarchyFixture.zip("com.codemx.anrdemo"),
            packageName = "com.codemx.anrdemo",
        )
        val title = root.children.single() as ViewNode

        assertEquals("com.codemx.ui.RealRootLayout", root.className)
        assertEquals("root", root.id)
        assertEquals(Bounds(left = 10, top = 20, right = 1090, bottom = 2420), root.bounds)
        assertEquals("VISIBLE", root.attributes.visibility)
        assertEquals(Bounds(left = 0, top = 0, right = 1080, bottom = 2400), root.attributes.layoutBounds)
        assertEquals(8f, root.attributes.elevation)
        assertEquals(10f, root.attributes.z)
        assertEquals(EdgeInsets(16, 24, 16, 24), root.attributes.padding)
        assertEquals(EdgeInsets(8, 12, 8, 12), root.attributes.margin)
        assertEquals(-1, root.attributes.layoutWidth)
        assertEquals(-2, root.attributes.layoutHeight)
        assertEquals("android.widget.FrameLayout.LayoutParams", root.attributes.layoutParamsClass)
        assertEquals(Bounds(0, 0, 1080, 2300), root.attributes.clipBounds)
        assertEquals(true, root.attributes.clipChildren)
        assertEquals(false, root.attributes.clipToPadding)
        assertEquals("HARDWARE", root.attributes.layerType)
        assertEquals(true, root.attributes.hardwareAccelerated)
        assertEquals(true, root.attributes.clickable)
        assertEquals(true, root.attributes.longClickable)
        assertEquals("Root container", root.attributes.contentDescription)
        assertEquals("0", root.attributes.rawProperties["layout:left"])
        assertEquals("1080", root.attributes.rawProperties["layout:right"])
        assertEquals("8.0", root.attributes.rawProperties["drawing:elevation"])
        assertEquals(
            "android.widget.FrameLayout.LayoutParams",
            root.attributes.rawProperties["layoutParams:class"],
        )
        assertEquals("com.codemx.ui.RealTitleView", title.className)
        assertEquals("root/0", title.id)
        assertEquals("com.codemx.anrdemo:id/title", title.resourceName)
        assertEquals("Title", title.text)
        assertEquals(Bounds(left = 50, top = 100, right = 610, bottom = 180), title.bounds)
        assertEquals(Bounds(left = 40, top = 80, right = 600, bottom = 160), title.attributes.layoutBounds)
    }

    @Test
    fun `text renderer includes every window and complete view properties`() {
        val text = VisibleWindowViewsTextRenderer.render(
            EncodedHierarchyFixture.multiWindowZip(),
        )

        assertTrue(text.contains("Window count: 3"))
        assertTrue(text.contains("ImageWallpaper"))
        assertTrue(text.contains("No hierarchy payload was supplied"))
        assertTrue(text.contains("com.codemx.ui.RealRootLayout"))
        assertTrue(text.contains("drawing:elevation: 8"))
        assertTrue(text.contains("com.codemx.ui.RealTitleView"))
        assertTrue(text.contains("Parse error:"))
        assertTrue(text.contains("SUMMARY: parsed 1 of 3 windows"))
    }

    @Test
    fun `text renderer rejects non zip input`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisibleWindowViewsTextRenderer.render("not a zip".toByteArray())
        }
    }

    @Test
    fun `parses every decodable window for the target package`() {
        val windows = VisibleWindowHierarchyParser.parseWindows(
            zipBytes = EncodedHierarchyFixture.twoAppWindowsZip(),
            packageName = "com.codemx.anrdemo",
        )

        assertEquals(2, windows.size)
        assertEquals(listOf("MainActivity", "ConfirmDialog"), windows.map { it.title })
        assertTrue(windows.all { window -> window.root.id.startsWith("${window.id}/root") })
    }

    @Test
    fun `parses systemui windows whose dump entries omit the package name`() {
        val windows = VisibleWindowHierarchyParser.parseWindows(
            zipBytes = EncodedHierarchyFixture.systemUiZip(),
            packageName = "com.android.systemui",
        )

        assertEquals(listOf("StatusBar", "NavigationBar"), windows.map { it.title })
        assertTrue(windows.all { window -> window.root.id.startsWith("${window.id}/root") })
    }

    @Test
    fun `parses launcher taskbar window as systemui navigation on Android 16`() {
        val windows = VisibleWindowHierarchyParser.parseWindows(
            zipBytes = EncodedHierarchyFixture.systemUiWithLauncherTaskbarZip(),
            packageName = "com.android.systemui",
        )

        assertEquals(listOf("StatusBar", "Taskbar"), windows.map { it.title })
        assertTrue(windows.all { window -> window.root.id.startsWith("${window.id}/root") })
    }
}

internal object EncodedHierarchyFixture {
    fun zip(packageName: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("$packageName/$packageName.MainActivity"))
            zip.write(hierarchy())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    fun multiWindowZip(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("f714f6 com.android.systemui.wallpapers.ImageWallpaper"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("42177c9 com.codemx.anrdemo/com.codemx.anrdemo.MainActivity"))
            zip.write(hierarchy())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("a3db2af StatusBar"))
            zip.write("broken hierarchy".toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    fun twoAppWindowsZip(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(
                ZipEntry("42177c9 com.codemx.anrdemo/com.codemx.anrdemo.MainActivity"),
            )
            zip.write(hierarchy())
            zip.closeEntry()
            zip.putNextEntry(
                ZipEntry("51aa71 com.codemx.anrdemo/com.codemx.anrdemo.ConfirmDialog"),
            )
            zip.write(hierarchy())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("9911 other.app/other.app.MainActivity"))
            zip.write(hierarchy())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    fun systemUiZip(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("a3db2af StatusBar"))
            zip.write(hierarchy())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("b473c9d NavigationBar"))
            zip.write(hierarchy())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("42177c9 com.codemx.anrdemo/com.codemx.anrdemo.MainActivity"))
            zip.write(hierarchy())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    fun systemUiWithLauncherTaskbarZip(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("a3db2af StatusBar"))
            zip.write(hierarchy())
            zip.closeEntry()
            zip.putNextEntry(
                ZipEntry("b473c9d com.google.android.apps.nexuslauncher/com.android.launcher3.taskbar.Taskbar"),
            )
            zip.write(hierarchy())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("42177c9 com.codemx.anrdemo/com.codemx.anrdemo.MainActivity"))
            zip.write(hierarchy())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun hierarchy(): ByteArray {
        val output = ByteArrayOutputStream()
        val encoder = FixtureEncoder(DataOutputStream(output))

        encoder.property("window:left", 10)
        encoder.property("window:top", 20)
        encoder.map {
            property("meta:__name__", "com.codemx.ui.RealRootLayout")
            property("id", "NO_ID")
            property("layout:left", 0)
            property("layout:top", 0)
            property("layout:right", 1080)
            property("layout:bottom", 2400)
            property("scrolling:scrollX", 0)
            property("scrolling:scrollY", 0)
            property("drawing:translationX", 0f)
            property("drawing:translationY", 0f)
            property("drawing:translationZ", 2f)
            property("drawing:elevation", 8f)
            property("drawing:rotation", 5f)
            property("drawing:rotationX", 1f)
            property("drawing:rotationY", 2f)
            property("drawing:scaleX", 1f)
            property("drawing:scaleY", 1f)
            property("drawing:pivotX", 540f)
            property("drawing:pivotY", 1200f)
            property("drawing:alpha", 1f)
            property("drawing:clipBounds", "Rect(0, 0 - 1080, 2300)")
            property("drawing:opaque", false)
            property("drawing:willNotDraw", false)
            property("drawing:hardwareAccelerated", true)
            property("drawing:layerType", 2)
            property("misc:visibility", 0)
            property("misc:enabled", true)
            property("misc:clickable", true)
            property("misc:longClickable", true)
            property("misc:selected", false)
            property("accessibility:getContentDescription()", "Root container")
            property("focus:isFocusable", true)
            property("focus:isFocused", false)
            property("padding:paddingLeft", 16)
            property("padding:paddingTop", 24)
            property("padding:paddingRight", 16)
            property("padding:paddingBottom", 24)
            property("measurement:minWidth", 0)
            property("measurement:minHeight", 0)
            property("measurement:measuredWidth", 1080)
            property("measurement:measuredHeight", 2400)
            property("drawing:clipChildren", true)
            property("drawing:clipToPadding", false)
            nestedMap("layoutParams") {
                property("class", "android.widget.FrameLayout.LayoutParams")
                property("width", -1)
                property("height", -2)
                property("leftMargin", 8)
                property("topMargin", 12)
                property("rightMargin", 8)
                property("bottomMargin", 12)
            }
            property("meta:__childCount__", 1.toShort())
            nestedMap("meta:__child__0") {
                property("meta:__name__", "com.codemx.ui.RealTitleView")
                property("id", "com.codemx.anrdemo:id/title")
                property("text:mText", "Title")
                property("layout:left", 40)
                property("layout:top", 80)
                property("layout:right", 600)
                property("layout:bottom", 160)
                property("scrolling:scrollX", 0)
                property("scrolling:scrollY", 0)
                property("drawing:translationX", 0f)
                property("drawing:translationY", 0f)
                property("drawing:alpha", 1f)
                property("misc:visibility", 0)
                property("meta:__childCount__", 0.toShort())
            }
        }
        encoder.writePropertyIndex()
        return output.toByteArray()
    }

    private class FixtureEncoder(
        private val output: DataOutputStream,
    ) {
        private val propertyIds = linkedMapOf<String, Short>()

        fun property(name: String, value: Any) {
            writeShort(propertyId(name))
            writeValue(value)
        }

        fun map(block: FixtureEncoder.() -> Unit) {
            output.writeByte(MAP)
            block()
            writeShort(0)
        }

        fun nestedMap(name: String, block: FixtureEncoder.() -> Unit) {
            writeShort(propertyId(name))
            map(block)
        }

        fun writePropertyIndex() {
            val nameKey = propertyId("__name__")
            output.writeByte(MAP)
            writeShort(nameKey)
            writeString("propertyIndex")
            propertyIds.toList().forEach { (name, id) ->
                writeShort(id)
                writeString(name)
            }
            writeShort(0)
        }

        private fun propertyId(name: String): Short =
            propertyIds.getOrPut(name) { (propertyIds.size + 1).toShort() }

        private fun writeValue(value: Any) {
            when (value) {
                is Int -> {
                    output.writeByte(INT)
                    output.writeInt(value)
                }
                is Short -> writeShort(value)
                is Float -> {
                    output.writeByte(FLOAT)
                    output.writeFloat(value)
                }
                is Boolean -> {
                    output.writeByte(BOOLEAN)
                    output.writeBoolean(value)
                }
                is String -> writeString(value)
                else -> error("Unsupported fixture value: $value")
            }
        }

        private fun writeShort(value: Short) {
            output.writeByte(SHORT)
            output.writeShort(value.toInt())
        }

        private fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            output.writeByte(STRING)
            output.writeShort(bytes.size)
            output.write(bytes)
        }

        private companion object {
            const val SHORT = 'S'.code
            const val INT = 'I'.code
            const val FLOAT = 'F'.code
            const val BOOLEAN = 'Z'.code
            const val STRING = 'R'.code
            const val MAP = 'M'.code
        }
    }
}
