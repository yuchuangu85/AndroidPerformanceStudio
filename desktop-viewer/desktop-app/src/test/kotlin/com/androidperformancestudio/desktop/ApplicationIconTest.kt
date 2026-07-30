package com.androidperformancestudio.desktop

import java.io.ByteArrayInputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ApplicationIconTest {
    private val buildScript = Files.readString(Path.of("build.gradle.kts"))
    private val mainSource = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/Main.kt"))

    @Test
    fun `desktop window loads the bundled application icon`() {
        assertTrue(mainSource.contains("painterResource(\"icons/app-icon.png\")"))
        assertTrue(mainSource.contains("icon = appIcon"))
    }

    @Test
    fun `native distributions use platform specific application icons`() {
        assertTrue(buildScript.contains("src/main/package/macos/app-icon.icns"))
        assertTrue(buildScript.contains("src/main/package/windows/app-icon.ico"))
        assertTrue(buildScript.contains("src/main/package/linux/app-icon.png"))
    }

    @Test
    fun `application icon assets are committed for runtime and packaging`() {
        listOf(
            "src/main/resources/icons/app-icon.png",
            "src/main/package/macos/app-icon.icns",
            "src/main/package/windows/app-icon.ico",
            "src/main/package/linux/app-icon.png",
        ).forEach { relativePath ->
            assertTrue(Files.size(Path.of(relativePath)) > 0, "Expected non-empty icon asset: $relativePath")
        }
    }

    @Test
    fun `runtime and linux icon pngs have transparent backgrounds`() {
        listOf(
            "src/main/resources/icons/app-icon.png",
            "src/main/package/linux/app-icon.png",
        ).forEach { relativePath ->
            val image = ImageIO.read(Path.of(relativePath).toFile())
            assertTrue(image.colorModel.hasAlpha(), "Expected alpha channel in $relativePath")
            assertTrue(image.isCornerTransparent(), "Expected transparent corners in $relativePath")
        }
    }

    @Test
    fun `runtime and linux icon pngs keep a white rounded square plate`() {
        listOf(
            "src/main/resources/icons/app-icon.png",
            "src/main/package/linux/app-icon.png",
        ).forEach { relativePath ->
            val image = ImageIO.read(Path.of(relativePath).toFile())
            assertTrue(image.hasWhitePlatePixelsInTopBand(), "Expected white top plate in $relativePath")
            assertTrue(image.hasWhitePlatePixelsInLeftBand(), "Expected white left plate in $relativePath")
        }
    }

    @Test
    fun `macOS icon keeps native safe area around the rounded square`() {
        val largestIcon = pngImagesInIcns(Path.of("src/main/package/macos/app-icon.icns"))
            .maxBy { it.width }
        val bounds = largestIcon.alphaBounds()
        val widthRatio = bounds.width.toDouble() / largestIcon.width
        val heightRatio = bounds.height.toDouble() / largestIcon.height

        assertTrue(bounds.left > 0, "Expected transparent left safe area in macOS icon")
        assertTrue(bounds.top > 0, "Expected transparent top safe area in macOS icon")
        assertTrue(bounds.right < largestIcon.width - 1, "Expected transparent right safe area in macOS icon")
        assertTrue(bounds.bottom < largestIcon.height - 1, "Expected transparent bottom safe area in macOS icon")
        assertTrue(widthRatio <= 0.9, "Expected macOS icon width to use native safe area, got $widthRatio")
        assertTrue(heightRatio <= 0.9, "Expected macOS icon height to use native safe area, got $heightRatio")
    }

    private fun BufferedImage.isCornerTransparent(): Boolean =
        listOf(
            getRGB(0, 0),
            getRGB(width - 1, 0),
            getRGB(0, height - 1),
            getRGB(width - 1, height - 1),
        ).all { pixel ->
            val alpha = pixel ushr 24
            alpha <= 8
        }

    private fun BufferedImage.isWhitePlatePixel(x: Int, y: Int): Boolean {
        val pixel = getRGB(x, y)
        val alpha = pixel ushr 24
        val red = pixel shr 16 and 0xff
        val green = pixel shr 8 and 0xff
        val blue = pixel and 0xff
        return alpha >= 240 && red >= 235 && green >= 235 && blue >= 235
    }

    private fun BufferedImage.hasWhitePlatePixelsInTopBand(): Boolean =
        (width / 3 until width * 2 / 3).any { x ->
            (height / 10 until height / 4).any { y -> isWhitePlatePixel(x, y) }
        }

    private fun BufferedImage.hasWhitePlatePixelsInLeftBand(): Boolean =
        (width / 10 until width / 4).any { x ->
            (height / 3 until height * 2 / 3).any { y -> isWhitePlatePixel(x, y) }
        }

    private fun pngImagesInIcns(path: Path): List<BufferedImage> {
        val bytes = Files.readAllBytes(path)
        require(bytes.size >= 8 && bytes.copyOfRange(0, 4).decodeToString() == "icns") {
            "Expected ICNS file: $path"
        }
        val images = mutableListOf<BufferedImage>()
        var offset = 8
        while (offset + 8 <= bytes.size) {
            val length = bytes.readBigEndianInt(offset + 4)
            if (length < 8 || offset + length > bytes.size) break
            val dataStart = offset + 8
            val dataEnd = offset + length
            val pngStart = bytes.indexOfPngSignature(dataStart, dataEnd)
            if (pngStart >= 0) {
                ImageIO.read(ByteArrayInputStream(bytes.copyOfRange(pngStart, dataEnd)))?.let(images::add)
            }
            offset += length
        }
        return images
    }

    private fun ByteArray.readBigEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff shl 24) or
            (this[offset + 1].toInt() and 0xff shl 16) or
            (this[offset + 2].toInt() and 0xff shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun ByteArray.indexOfPngSignature(start: Int, end: Int): Int {
        val signature = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
        for (index in start..(end - signature.size)) {
            if (signature.indices.all { signatureIndex -> this[index + signatureIndex] == signature[signatureIndex] }) {
                return index
            }
        }
        return -1
    }

    private fun BufferedImage.alphaBounds(): AlphaBounds {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha = getRGB(x, y) ushr 24
                if (alpha > 8) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return AlphaBounds(left = left, top = top, right = right, bottom = bottom)
    }

    private data class AlphaBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }
}
