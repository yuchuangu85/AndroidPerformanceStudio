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

package com.android.tools.ui.inspector.common

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.fail
import org.junit.Test

class FramingProtocolTest {

  @Test
  fun testWriteAndReadMessage() {
    val payload = "Hello, World!".toByteArray(Charsets.UTF_8)
    val outputStream = ByteArrayOutputStream()

    FramingProtocol.writeMessage(outputStream, payload)

    val inputStream = ByteArrayInputStream(outputStream.toByteArray())
    val readPayload = FramingProtocol.readMessage(inputStream)

    assertThat(readPayload).isEqualTo(payload)
  }

  @Test
  fun testReadMessageInvalidHeader() {
    val invalidData = "INVALID_HEADER".toByteArray(Charsets.US_ASCII) + ByteArray(4)
    val inputStream = ByteArrayInputStream(invalidData)

    try {
      FramingProtocol.readMessage(inputStream)
      fail("Expected IllegalStateException")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Invalid header")
    }
  }
}
