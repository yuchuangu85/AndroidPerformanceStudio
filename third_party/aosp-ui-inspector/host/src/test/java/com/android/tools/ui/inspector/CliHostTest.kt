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

import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.testing.FakeAdbSession
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import picocli.CommandLine

class CliHostTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun parseDumpUi(vararg args: String): DumpUiCommand {
    val parseResult = createCommandLine().parseArgs("dump-ui", *args)
    return parseResult.subcommand().commandSpec().userObject() as DumpUiCommand
  }

  @Test
  fun testNoArgsReturnsError() {
    val exitCode = createCommandLine().execute()
    assertThat(exitCode).isEqualTo(1)
  }

  @Test
  fun testDumpUiParsesWithNoOptions() {
    val dumpCmd = parseDumpUi()
    assertThat(dumpCmd.device).isNull()
    assertThat(dumpCmd.packageName).isNull()
  }

  @Test
  fun testCommandLineOptionsDefaults() {
    val dumpCmd = parseDumpUi("--device", "123", "--package", "com.example")
    assertThat(dumpCmd.include).isEmpty()
    assertThat(dumpCmd.composeInspectorJarPath).isNull()
    assertThat(dumpCmd.output).isNull()
  }

  @Test
  fun testIncludeParsesSingleFacet() {
    val dumpCmd = parseDumpUi("--include", "system-composables")
    assertThat(dumpCmd.include).containsExactly(IncludeFacet.SYSTEM_COMPOSABLES)
  }

  @Test
  fun testIncludeParsesCommaSeparatedAndRepeatedForms() {
    val dumpCmd = parseDumpUi("--include", "attributes,semantics", "--include", "resolution-stack")
    assertThat(dumpCmd.include).containsExactly(IncludeFacet.ATTRIBUTES, IncludeFacet.SEMANTICS, IncludeFacet.RESOLUTION_STACK).inOrder()
  }

  @Test
  fun testIncludeRejectsUnknownFacet() {
    val exception =
      assertThrows(CommandLine.ParameterException::class.java) { createCommandLine().parseArgs("dump-ui", "--include", "everything") }
    assertThat(exception).hasMessageThat().contains("Invalid value for --include: 'everything'")
    assertThat(exception).hasMessageThat().contains("attributes, semantics, resolution-stack, system-composables, all")
  }

  @Test
  fun testOldIncludeFlagsRemoved() {
    for (oldFlag in listOf("--include-attributes", "--include-resolution-stack", "--include-system-composables", "--include-semantics")) {
      assertThrows(CommandLine.UnmatchedArgumentException::class.java) { createCommandLine().parseArgs("dump-ui", oldFlag) }
    }
  }

  @Test
  fun testCommandLineOptionsComposeInspector() {
    val dumpCmd = parseDumpUi("--device", "123", "--package", "com.example", "--compose-inspector", "local/path/to/inspector.jar")
    assertThat(dumpCmd.composeInspectorJarPath).isEqualTo("local/path/to/inspector.jar")
  }

  @Test
  fun testCommandLineOptionsOutput() {
    val dumpCmd = parseDumpUi("--device", "123", "--package", "com.example", "--output", "out/dump.json")
    assertThat(dumpCmd.output).isEqualTo(Paths.get("out/dump.json"))
  }

  @Test
  fun testCommandLineOptionsOutputShortName() {
    val dumpCmd = parseDumpUi("--device", "123", "--package", "com.example", "-o", "dump.json")
    assertThat(dumpCmd.output).isEqualTo(Paths.get("dump.json"))
  }

  @Test
  fun testListPackagesDeviceOption() {
    val parseResult = createCommandLine().parseArgs("list-packages", "--device", "123")
    val listCmd = parseResult.subcommand().commandSpec().userObject() as ListPackagesCommand
    assertThat(listCmd.device).isEqualTo("123")
  }

  @Test
  fun testDeviceOptionIsOptional() {
    assertThat(parseDumpUi("--package", "com.example").device).isNull()

    val listParse = createCommandLine().parseArgs("list-packages")
    assertThat((listParse.subcommand().commandSpec().userObject() as ListPackagesCommand).device).isNull()
  }

  @Test
  fun testSerialOptionNoLongerSupported() {
    assertThrows(CommandLine.UnmatchedArgumentException::class.java) {
      createCommandLine().parseArgs("dump-ui", "--device", "123", "--serial", "456", "--package", "com.example")
    }
    assertThrows(CommandLine.UnmatchedArgumentException::class.java) {
      createCommandLine().parseArgs("list-packages", "--device", "123", "--serial", "456")
    }
  }

  @Test
  fun testTrackChangesCommandRemoved() {
    assertThrows(CommandLine.UnmatchedArgumentException::class.java) { createCommandLine().parseArgs("track-changes") }
  }

  @Test
  fun testRecordOptionsRemoved() {
    assertThrows(CommandLine.UnmatchedArgumentException::class.java) {
      createCommandLine().parseArgs("dump-ui", "--record", "--duration", "5s")
    }
  }

  @Test
  fun testDumpUiDeviceResolutionFailureLeavesOutputFileUntouched() {
    val outputFile = tempFolder.newFile("dump.json").toPath()
    Files.write(outputFile, "existing content".toByteArray(Charsets.UTF_8))
    val noDevicesSession = FakeAdbSession().apply { hostServices.devices = DeviceList(emptyList(), emptyList()) }
    val originalFactory = sessionFactory
    sessionFactory = { noDevicesSession }
    try {
      val exitCode = createCommandLine().execute("dump-ui", "--package", "com.example", "-o", outputFile.toString())

      assertThat(exitCode).isEqualTo(1)
      assertThat(String(Files.readAllBytes(outputFile), Charsets.UTF_8)).isEqualTo("existing content")
    } finally {
      sessionFactory = originalFactory
    }
  }

  @Test
  fun testDumpUiPackageResolutionFailureLeavesOutputFileUntouched() {
    val outputFile = tempFolder.newFile("dump.json").toPath()
    Files.write(outputFile, "existing content".toByteArray(Charsets.UTF_8))
    val session =
      FakeAdbSession().apply {
        hostServices.devices = DeviceList(listOf(DeviceInfo("abc", DeviceState.ONLINE)), emptyList())
        deviceServices.configureShellCommand(DeviceSelector.fromSerialNumber("abc"), TOP_ACTIVITY_SHELL_COMMAND, "", exitCode = 1)
      }
    val originalFactory = sessionFactory
    sessionFactory = { session }
    try {
      val exitCode = createCommandLine().execute("dump-ui", "-o", outputFile.toString())

      assertThat(exitCode).isEqualTo(1)
      assertThat(String(Files.readAllBytes(outputFile), Charsets.UTF_8)).isEqualTo("existing content")
    } finally {
      sessionFactory = originalFactory
    }
  }
}
