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

package com.android.tools.ui.inspector.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Helper for reading and writing framed messages over a stream. Used by both the host and the agent
 * to ensure consistent message framing.
 *
 * <p>The protocol format is: 1. Header: 8 bytes ("UIINSPCT") 2. Payload length: 4 bytes (Int,
 * big-endian) 3. Payload: 'length' bytes
 */
public final class FramingProtocol {
    private FramingProtocol() {}

    private static final String HEADER_STRING = "UIINSPCT";
    private static final byte[] HEADER = HEADER_STRING.getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 8;

    public static void writeMessage(OutputStream outputStream, byte[] payload) throws IOException {
        synchronized (outputStream) {
            DataOutputStream dataOutput = new DataOutputStream(outputStream);
            dataOutput.write(HEADER);
            dataOutput.writeInt(payload.length);
            dataOutput.write(payload);
            dataOutput.flush();
        }
    }

    public static byte[] readMessage(InputStream inputStream) throws IOException {
        DataInputStream dataInput = new DataInputStream(inputStream);
        byte[] header = new byte[HEADER_SIZE];
        dataInput.readFully(header);
        if (!Arrays.equals(header, HEADER)) {
            throw new IllegalStateException("Invalid header in framing protocol");
        }
        int length = dataInput.readInt();
        byte[] payload = new byte[length];
        dataInput.readFully(payload);
        return payload;
    }
}
