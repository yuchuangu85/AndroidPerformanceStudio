package com.androidperformancestudio.export

import com.androidperformancestudio.storage.CallTreeNode
import com.androidperformancestudio.storage.TopFunction
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportExportServiceTest {
    @Test
    fun `exports top functions and call tree as valid escaped json and csv`() {
        val directory = Files.createTempDirectory("aps-report-export-")
        val top = listOf(TopFunction("render,\"frame\"", "/lib/app.so", 10, 7, 3, 1))
        val tree = listOf(CallTreeNode(1, null, 0, "root", "/lib/app.so", 10, 2, 3, 1))
        val service = ReportExportService()

        service.exportJson(top, tree, directory.resolve("report.json"))
        service.exportTopFunctionsCsv(top, directory.resolve("top.csv"))
        service.exportCallTreeCsv(tree, directory.resolve("tree.csv"))

        val json = directory.resolve("report.json").readText()
        assertTrue(json.contains("render,\\\"frame\\\""))
        assertTrue(json.contains("\"callTree\""))
        assertTrue(directory.resolve("top.csv").readText().contains("\"render,\"\"frame\"\"\""))
        assertTrue(directory.resolve("tree.csv").readText().startsWith("id,parent_id,depth"))
    }

    @Test
    fun `copies retained raw protobuf without mutating source`() {
        val directory = Files.createTempDirectory("aps-raw-export-")
        val source = directory.resolve("simpleperf.protobuf").also { it.writeText("trace") }
        val destination = directory.resolve("copy.protobuf")

        ReportExportService().exportRawProtobuf(source, destination)

        assertEquals("trace", destination.readText())
        assertEquals("trace", source.readText())
    }

    @Test
    fun `exports a readable png screenshot`() {
        val directory = Files.createTempDirectory("aps-screenshot-export-")
        val destination = directory.resolve("report.png")
        val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, Color.RED.rgb)

        ReportExportService().exportScreenshot(image, destination)

        val decoded = ImageIO.read(destination.toFile())
        assertEquals(2, decoded.width)
        assertEquals(1, decoded.height)
        assertEquals(Color.RED.rgb, decoded.getRGB(0, 0))
    }
}
