package com.androidperformancestudio.winscope.app

import org.jcodec.api.awt.AWTSequenceEncoder
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ScreenRecordingFrameSourceTest {
    @Test
    fun `different trace timestamps decode different recording frames`() {
        val file = createRecording(Color.RED, Color.GREEN, Color.BLUE)
        appendLegacyMetadata(file, 1_000_000L, 2_000_000L, 3_000_000L)

        ScreenRecordingFrameSource(file).use { source ->
            val first = source.frameAt(1_000_000_000L)!!.getRGB(8, 8)
            val second = source.frameAt(2_000_000_000L)!!.getRGB(8, 8)
            val third = source.frameAt(3_000_000_000L)!!.getRGB(8, 8)

            assertNotEquals(first, second)
            assertNotEquals(second, third)
            assertEquals(Color.BLUE.rgb, third)
        }
    }

    @Test
    fun `standard screen recording without Winscope metadata still displays a frame`() {
        val file = createRecording(Color.RED)

        ScreenRecordingFrameSource(file).use { source ->
            assertNotNull(source.frameAt(5_000_000_000L, 5_000_000_000L))
        }
    }

    @Test
    fun `cursor before the first recorded timestamp displays the first frame`() {
        val file = createRecording(Color.RED)
        val metadata =
            ByteBuffer
                .allocate(LEGACY_MAGIC.size + Int.SIZE_BYTES + Long.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(LEGACY_MAGIC)
                .putInt(1)
                .putLong(6_000_000L)
                .array()
        Files.write(file, metadata, StandardOpenOption.APPEND)

        ScreenRecordingFrameSource(file).use { source ->
            assertNotNull(source.frameAt(5_000_000_000L, 5_000_000_000L))
        }
    }

    private fun createRecording(vararg colors: Color) =
        Files.createTempFile("screen-recording", ".mp4").also { file ->
            AWTSequenceEncoder.createSequenceEncoder(file.toFile(), 1).run {
                colors.forEach { frameColor ->
                    encodeImage(
                        BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR).apply {
                            createGraphics().run {
                                color = frameColor
                                fillRect(0, 0, width, height)
                                dispose()
                            }
                        },
                    )
                }
                finish()
            }
        }

    private fun appendLegacyMetadata(
        file: java.nio.file.Path,
        vararg timestampsMicros: Long,
    ) {
        val metadata =
            ByteBuffer
                .allocate(LEGACY_MAGIC.size + Int.SIZE_BYTES + timestampsMicros.size * Long.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(LEGACY_MAGIC)
                .putInt(timestampsMicros.size)
        timestampsMicros.forEach(metadata::putLong)
        Files.write(file, metadata.array(), StandardOpenOption.APPEND)
    }

    private companion object {
        val LEGACY_MAGIC = "#VV1NSC0PET1ME!#".encodeToByteArray()
    }
}
