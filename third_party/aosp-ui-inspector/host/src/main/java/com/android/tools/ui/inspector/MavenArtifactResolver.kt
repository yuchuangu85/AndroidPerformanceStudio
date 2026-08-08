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

import com.android.prefs.AndroidLocationsSingleton
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Abstraction for downloading remote artifacts. */
interface ArtifactDownloader {

  /**
   * Downloads the artifact payload from the specified [url] and writes it to the [outputFile].
   *
   * @param url The URL of the remote artifact.
   * @param outputFile The local file where the downloaded bytes will be written.
   */
  fun download(url: String, outputFile: File)
}

/** Default connect timeout for artifact downloads. */
private val DEFAULT_CONNECT_TIMEOUT = 30.seconds

/** Default read timeout for artifact downloads. Bounds each read stall, not the total transfer time. */
private val DEFAULT_READ_TIMEOUT = 30.seconds

/**
 * Default implementation of [ArtifactDownloader] that downloads files over standard HTTP. Connect and read timeouts prevent an unresponsive
 * server from hanging the CLI forever; a read timeout bounds each stall rather than the total transfer, so large artifacts on slow
 * connections still complete.
 */
class HttpArtifactDownloader(
  private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
  private val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
) : ArtifactDownloader {
  override fun download(url: String, outputFile: File) {
    val connection = URL(url).openConnection()
    connection.connectTimeout = connectTimeout.inWholeMilliseconds.toInt()
    connection.readTimeout = readTimeout.inWholeMilliseconds.toInt()
    try {
      connection.getInputStream().use { input -> outputFile.outputStream().use { output -> input.copyTo(output) } }
    } finally {
      (connection as? HttpURLConnection)?.disconnect()
    }
  }
}

private const val DEFAULT_CACHE_PATH = "ui-inspector/cache"

/**
 * Resolves and downloads library artifacts from Google's Maven repository and extracts their nested payload jars.
 *
 * @param downloader The downloader used to download remote AAR artifacts.
 * @param cacheDir The local directory where resolved payload JARs are cached.
 * @param fileMover The function used to move the temporary extracted JAR to its final cached destination path.
 */
class MavenArtifactResolver(
  private val downloader: ArtifactDownloader = HttpArtifactDownloader(),
  // TODO: Integrate with the Android CLI's SysInfoService once UI Inspector is moved there.
  private val cacheDir: File = AndroidLocationsSingleton.prefsLocation.resolve(DEFAULT_CACHE_PATH).toFile(),
  private val fileMover: (File, File) -> Unit = ::defaultMoveFile,
) {

  companion object {
    private const val GOOGLE_MAVEN_BASE_URL = "https://maven.google.com"
    private const val PAYLOAD_JAR_ENTRY_NAME = "inspector.jar"
  }

  /**
   * Resolves the given artifact (e.g. "androidx.compose.ui", "ui-android", "1.5.4") from Google Maven, downloads its AAR, and extracts the
   * nested "inspector.jar".
   *
   * @return The local cached file containing the extracted "inspector.jar".
   */
  fun resolve(groupId: String, artifactId: String, version: String): File {
    val cachedJarName = "$groupId-$artifactId-$version-inspector.jar"
    val cachedJar = File(cacheDir, cachedJarName)
    if (cachedJar.isFile) {
      return cachedJar
    }

    val fileName = "$artifactId-$version.aar"
    val tempAar = downloadFromMaven(groupId, artifactId, version, fileName)
    var tempInspectorJar: File? = null
    try {
      Files.createDirectories(cacheDir.toPath())
      tempInspectorJar =
        extractInspectorJar(tempAar, cacheDir) ?: throw IllegalStateException("$PAYLOAD_JAR_ENTRY_NAME not found in $fileName")

      try {
        fileMover(tempInspectorJar, cachedJar)
      } catch (e: Exception) {
        if (!cachedJar.isFile) {
          // If the destination file was successfully written by another thread, we can safely ignore the exception if the file now exists.
          throw e
        }
      }
      return cachedJar
    } finally {
      tempInspectorJar?.delete()
      tempAar.delete()
    }
  }

  /** Downloads the target AAR artifact from Google Maven to a local temporary file. */
  private fun downloadFromMaven(groupId: String, artifactId: String, version: String, fileName: String): File {
    val groupPath = groupId.replace('.', '/')
    val url = "$GOOGLE_MAVEN_BASE_URL/$groupPath/$artifactId/$version/$fileName"

    val tempAar = File.createTempFile("$groupId-$artifactId-$version-", ".aar")
    try {
      downloader.download(url, tempAar)
      return tempAar
    } catch (e: Exception) {
      tempAar.delete()
      throw e
    }
  }

  /** Extracts the nested "inspector.jar" from the downloaded [aarFile] to the [destinationDir]. */
  private fun extractInspectorJar(aarFile: File, destinationDir: File): File? {
    val prefix = "${aarFile.nameWithoutExtension}-extracted-"
    val inspectorJar = File.createTempFile(prefix, ".jar", destinationDir)

    try {
      ZipInputStream(aarFile.inputStream()).use { zipInputStream ->
        var entry = zipInputStream.nextEntry
        while (entry != null) {
          if (entry.name == PAYLOAD_JAR_ENTRY_NAME) {
            inspectorJar.outputStream().use { output -> zipInputStream.copyTo(output) }
            return inspectorJar
          }
          entry = zipInputStream.nextEntry
        }
      }
      inspectorJar.delete()
      return null
    } catch (t: Throwable) {
      inspectorJar.delete()
      throw t
    }
  }
}

private fun defaultMoveFile(source: File, destination: File) {
  try {
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  } catch (e: AtomicMoveNotSupportedException) {
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}
