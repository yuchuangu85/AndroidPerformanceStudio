package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.gpu.artifact.AgiArtifactIndexer
import com.androidperformancestudio.gpu.artifact.JsonAgiArtifactStore
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.presentation.ArtifactPrimaryAction
import com.androidperformancestudio.gpu.toolchain.AgiExecutablePreferenceStore
import com.androidperformancestudio.gpu.toolchain.AgiLocator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuIntegrationControllerTest {
    @Test
    fun `loads indexed artifacts and inspects selected devices`() = runBlocking {
        val directory = Files.createTempDirectory("gpu-controller-test")
        try {
            val artifactFile = directory.resolve("capture.pftrace")
            Files.writeString(artifactFile, "trace")
            val indexer = AgiArtifactIndexer()
            val artifact = indexer.import(artifactFile)
            val store = JsonAgiArtifactStore(directory.resolve("artifacts.json"))
            store.save(listOf(artifact))
            val devices = listOf(device("serial-1"), device("serial-2"))
            val controller = controller(directory, store, devices)

            controller.initialize()
            assertFalse(controller.state.value.isBusy)
            assertEquals(artifact.id, controller.state.value.selectedArtifactId)
            assertTrue(assertNotNull(controller.state.value.completedDurationSeconds) >= 0.0)
            controller.importArtifact(artifactFile)
            assertTrue(assertNotNull(controller.state.value.completedDurationSeconds) >= 0.0)
            controller.importArtifact(directory.resolve("missing.pftrace"))
            assertNull(controller.state.value.completedDurationSeconds)

            controller.refreshDevices()
            assertEquals(null, controller.state.value.selectedDeviceSerial)
            assertEquals(null, controller.state.value.devices.first().gpuRenderer)

            controller.selectDevice("serial-1")
            controller.inspectSelectedDevice()
            assertEquals("serial-1", controller.state.value.selectedDeviceSerial)
            assertEquals("Inspected serial-1", controller.state.value.devices.first().gpuRenderer)

            controller.selectDevice("serial-2")
            controller.inspectSelectedDevice()
            assertEquals("serial-2", controller.state.value.selectedDeviceSerial)
            assertEquals("Inspected serial-2", controller.state.value.devices.last().gpuRenderer)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `does not overwrite an unreadable artifact index`() = runBlocking {
        val directory = Files.createTempDirectory("gpu-controller-test")
        try {
            val indexFile = directory.resolve("artifacts.json")
            Files.writeString(indexFile, "{invalid index")
            val original = Files.readString(indexFile)
            val artifactFile = directory.resolve("new.pftrace")
            Files.writeString(artifactFile, "trace")
            val store = JsonAgiArtifactStore(indexFile)
            val controller = controller(directory, store, emptyList())

            controller.initialize()
            assertNotNull(controller.state.value.error)

            controller.importArtifact(artifactFile)

            assertTrue(controller.state.value.error.orEmpty().contains("will not be overwritten"))
            assertEquals(original, Files.readString(indexFile))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `open revalidates cached available content before dispatching a viewer`() = runBlocking {
        val directory = Files.createTempDirectory("gpu-controller-test")
        try {
            val file = directory.resolve("capture.pftrace")
            Files.writeString(file, "trace")
            val artifact = AgiArtifactIndexer().import(file)
            val store = JsonAgiArtifactStore(directory.resolve("artifacts.json"))
            store.save(listOf(artifact))
            val controller = controller(directory, store, emptyList())
            controller.initialize()
            assertEquals(ArtifactPrimaryAction.OPEN_PERFETTO, controller.state.value.availabilityByArtifactId[artifact.id]?.primaryAction)
            Files.writeString(file, "other")
            var opened = false

            controller.openArtifact(artifact, "changed", "no viewer") { _, _ -> opened = true }

            assertFalse(opened)
            val availability = requireNotNull(controller.state.value.availabilityByArtifactId[artifact.id])
            assertEquals(ArtifactLocationStatus.CONTENT_CHANGED, availability.locationStatus)
            assertEquals(ArtifactPrimaryAction.NONE, availability.primaryAction)
            assertNull(availability.resolvedPath)
            assertEquals("changed", controller.state.value.error)
            assertFalse(controller.state.value.isBusy)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `open uses a verified alternative instead of changed primary content`() = runBlocking {
        val directory = Files.createTempDirectory("gpu-controller-test")
        try {
            val primary = directory.resolve("capture.pftrace")
            val alternative = directory.resolve("copy.pftrace")
            Files.writeString(primary, "trace")
            Files.writeString(alternative, "trace")
            val artifact = AgiArtifactIndexer().import(primary).copy(alternativePaths = listOf(alternative))
            val store = JsonAgiArtifactStore(directory.resolve("artifacts.json"))
            store.save(listOf(artifact))
            val controller = controller(directory, store, emptyList())
            controller.initialize()
            Files.writeString(primary, "other")
            var openedPath: Path? = null

            controller.openArtifact(artifact, "changed", "no viewer") { availability, _ ->
                assertEquals(ArtifactPrimaryAction.OPEN_PERFETTO, availability.primaryAction)
                assertTrue(controller.state.value.isBusy)
                openedPath = availability.resolvedPath
            }

            assertEquals(alternative, openedPath)
            assertEquals(alternative, controller.state.value.availabilityByArtifactId[artifact.id]?.resolvedPath)
            assertNull(controller.state.value.error)
            assertFalse(controller.state.value.isBusy)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `open rejects missing changed size unreadable and unsupported artifacts`() = runBlocking {
        val directory = Files.createTempDirectory("gpu-controller-test")
        try {
            listOf("missing.pftrace", "size.pftrace", "unreadable.pftrace", "opaque.bin", "capture.gfxtrace").forEach { name ->
                val file = directory.resolve(name)
                Files.writeString(file, "trace")
                val artifact = AgiArtifactIndexer().import(file)
                val store = JsonAgiArtifactStore(directory.resolve("artifacts.json"))
                store.save(listOf(artifact))
                val controller = controller(directory, store, emptyList())
                controller.initialize()
                when (name) {
                    "missing.pftrace" -> Files.delete(file)
                    "size.pftrace" -> Files.writeString(file, "changed size")
                    "unreadable.pftrace" -> {
                        Files.delete(file)
                        Files.createSymbolicLink(file, file.fileName)
                    }
                }
                var opened = false

                controller.openArtifact(artifact, "changed", "no viewer") { _, _ -> opened = true }

                assertFalse(opened, name)
                val availability = requireNotNull(controller.state.value.availabilityByArtifactId[artifact.id])
                assertEquals(ArtifactPrimaryAction.NONE, availability.primaryAction, name)
                assertEquals(if (name.endsWith("pftrace")) "changed" else "no viewer", controller.state.value.error, name)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `open uses indexed identity rather than a stale or forged caller path`() = runBlocking {
        val directory = Files.createTempDirectory("gpu-controller-test")
        try {
            val indexedFile = directory.resolve("capture.pftrace")
            val callerFile = directory.resolve("other.pftrace")
            Files.writeString(indexedFile, "trace")
            Files.writeString(callerFile, "other")
            val artifact = AgiArtifactIndexer().import(indexedFile)
            val store = JsonAgiArtifactStore(directory.resolve("artifacts.json"))
            store.save(listOf(artifact))
            val controller = controller(directory, store, emptyList())
            controller.initialize()
            var openedPath: Path? = null

            controller.openArtifact(artifact.copy(path = callerFile), "changed", "no viewer") { availability, _ ->
                openedPath = availability.resolvedPath
            }

            assertEquals(indexedFile, openedPath)
            var unknownOpened = false
            controller.openArtifact(artifact.copy(id = "unknown"), "changed", "no viewer") { _, _ -> unknownOpened = true }
            assertFalse(unknownOpened)
            assertEquals("changed", controller.state.value.error)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun controller(
        directory: Path,
        store: JsonAgiArtifactStore,
        devices: List<GpuDeviceContext>,
    ) = GpuIntegrationController(
        store = store,
        preferences = AgiExecutablePreferenceStore(directory.resolve("agi-executable.txt")),
        locator = AgiLocator(environment = emptyMap(), osName = "Linux"),
        discoverDevices = { devices },
        inspectDevice = { device -> device.copy(gpuRenderer = "Inspected ${device.serial}") },
    )

    private fun device(serial: String) = GpuDeviceContext(serial, "Test device", null, null, null, null)
}
