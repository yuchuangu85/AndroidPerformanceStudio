/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.ui.inspector

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MavenArtifactResolverTest {

  @get:Rule val tempFolder = TemporaryFolder()

  /** A fake downloader that creates a valid ZIP file representing the AAR, containing "inspector.jar". */
  private class FakeArtifactDownloader(private val jarContent: String) : ArtifactDownloader {
    var recordedUrl: String? = null
    var downloadCalls = 0

    override fun download(url: String, outputFile: File) {
      recordedUrl = url
      downloadCalls++
      ZipOutputStream(outputFile.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry("inspector.jar"))
        zos.write(jarContent.toByteArray())
        zos.closeEntry()
      }
    }
  }

  @Test(timeout = 5_000)
  fun testHttpArtifactDownloader_timesOutOnStalledServer() {
    // A server that accepts the connection but never writes a byte: without a read timeout the download hangs forever.
    ServerSocket(0).use { server ->
      val downloader = HttpArtifactDownloader(connectTimeout = 5.seconds, readTimeout = 200.milliseconds)
      val output = tempFolder.newFile("stalled.aar")

      assertThrows(SocketTimeoutException::class.java) { downloader.download("http://127.0.0.1:${server.localPort}/x.aar", output) }
    }
  }

  @Test
  fun testResolve_extractsInspectorJarSuccessfully() {
    val fakeDownloader = FakeArtifactDownloader("fake pre-compiled dex classes")
    val resolver = MavenArtifactResolver(fakeDownloader, tempFolder.newFolder())

    val jarFile = resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4")

    assertThat(jarFile.exists()).isTrue()
    assertThat(jarFile.readText()).isEqualTo("fake pre-compiled dex classes")
    assertThat(fakeDownloader.recordedUrl).isEqualTo("https://maven.google.com/androidx/compose/ui/ui-android/1.5.4/ui-android-1.5.4.aar")
  }

  @Test
  fun testResolve_throwsIfInspectorJarMissing() {
    val emptyDownloader =
      object : ArtifactDownloader {
        override fun download(url: String, outputFile: File) {
          // Create an empty zip archive with NO "inspector.jar"
          ZipOutputStream(outputFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
          }
        }
      }
    val resolver = MavenArtifactResolver(emptyDownloader, tempFolder.newFolder())

    val exception = assertThrows(IllegalStateException::class.java) { resolver.resolve("androidx.compose.ui", "ui", "1.5.4") }
    assertThat(exception.message).contains("inspector.jar not found in ui-1.5.4.aar")
  }

  @Test
  fun testResolve_servesFromCacheOnSecondCall() {
    val fakeDownloader = FakeArtifactDownloader("fake pre-compiled dex classes")
    val resolver = MavenArtifactResolver(fakeDownloader, tempFolder.newFolder())

    val jarFile1 = resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4")
    val jarFile2 = resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4")

    assertThat(jarFile1).isEqualTo(jarFile2)
    assertThat(jarFile2.exists()).isTrue()
    assertThat(jarFile2.readText()).isEqualTo("fake pre-compiled dex classes")
    assertThat(fakeDownloader.downloadCalls).isEqualTo(1)
  }

  @Test
  fun testResolve_concurrentResolutions() {
    val jarContent = "fake pre-compiled dex classes"
    val threadCount = 10
    val barrier = CyclicBarrier(threadCount)
    val fakeDownloader =
      object : ArtifactDownloader {
        override fun download(url: String, outputFile: File) {
          // Synchronize all threads here so they run the download and resolution concurrently
          try {
            barrier.await(10, TimeUnit.SECONDS)
          } catch (e: Exception) {
            throw RuntimeException(e)
          }
          ZipOutputStream(outputFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("inspector.jar"))
            zos.write(jarContent.toByteArray())
            zos.closeEntry()
          }
        }
      }
    val resolver = MavenArtifactResolver(fakeDownloader, tempFolder.newFolder())

    val executor = Executors.newFixedThreadPool(threadCount)
    val futures = (1..threadCount).map { executor.submit(Callable { resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4") }) }

    futures.forEach { future ->
      val jarFile = future.get()
      assertThat(jarFile.exists()).isTrue()
      assertThat(jarFile.readText()).isEqualTo(jarContent)
    }
    executor.shutdown()
  }

  @Test
  fun testResolve_ignoresMoveExceptionIfDestinationExists() {
    val fakeDownloader = FakeArtifactDownloader("fake jar content")
    val cacheDir = tempFolder.newFolder()

    var moveAttempted = false
    val resolver =
      MavenArtifactResolver(
        fakeDownloader,
        cacheDir,
        fileMover = { _, destination ->
          moveAttempted = true
          // Simulate Thread A creating the destination file concurrently
          destination.writeText("concurrently written correct jar content")
          throw IOException("Simulated file system lock / access denied error")
        },
      )

    // Call resolve. Since the destination file is concurrently written and exists,
    // this should return successfully despite the move exception.
    val jarFile = resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4")

    assertThat(moveAttempted).isTrue()
    assertThat(jarFile.exists()).isTrue()
    assertThat(jarFile.isFile).isTrue()
    assertThat(jarFile.readText()).isEqualTo("concurrently written correct jar content")
  }
}
