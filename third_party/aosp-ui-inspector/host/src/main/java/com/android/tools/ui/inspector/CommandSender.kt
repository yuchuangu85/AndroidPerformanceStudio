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

import com.android.annotations.concurrency.GuardedBy
import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.AgentMessage
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Command
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Event
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.InspectorMessageCommand
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Response
import com.google.protobuf.ByteString
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly

/** Exception thrown when the UI Inspector agent crashes on the device. */
class InspectorCrashException(errorMessage: String, val stackTrace: String) : IOException("Agent crashed: $errorMessage\n$stackTrace")

/**
 * Sends messages to the UI Inspector agent running on the device and receives responses. Uses the shared FramingProtocol for message
 * framing.
 */
class CommandSender private constructor(private val socket: Socket, private val scope: CoroutineScope) : AutoCloseable {

  companion object {
    /**
     * Establishes a connection to the layout inspector agent on the device.
     *
     * @param host The host address (typically 127.0.0.1).
     * @param port The port number.
     * @param scope The [CoroutineScope] whose lifetime dictates the connection.
     */
    suspend fun connect(host: String, port: Int, scope: CoroutineScope): CommandSender {
      var socket: Socket? = null
      var commandSender: CommandSender? = null
      try {
        return withContext(Dispatchers.IO) {
          val s = Socket(host, port)
          socket = s
          val sender = CommandSender(s, scope)
          commandSender = sender
          sender
        }
      } catch (e: Throwable) {
        commandSender?.close() ?: socket?.close()
        throw e
      }
    }
  }

  private val outputStream = socket.getOutputStream()
  private val inputStream = socket.getInputStream()

  /** Monotonically increasing ID generator for commands. */
  private val nextCommandId = AtomicInteger(1)

  private val stateLock = Any()
  /** Map of command ID to its deferred response, representing active outgoing requests. */
  @GuardedBy("stateLock") private val pendingCommands = HashMap<Int, CompletableDeferred<Response>>()
  /** Stored exception representing the reason for disconnection, to be rethrown on subsequent usage attempts. */
  @GuardedBy("stateLock") private var disconnectException: Throwable? = null

  @GuardedBy("stateLock") private var _isClosed = false
  /** Flag indicating whether the connection has been closed. Once closed, the sender cannot be reused. */
  private val isClosed: Boolean
    get() = synchronized(stateLock) { _isClosed }

  @get:TestOnly
  internal val pendingCommandCount: Int
    get() = synchronized(stateLock) { pendingCommands.size }

  private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  /** A flow of asynchronous events received from the agent. */
  val events: SharedFlow<Event> = _events.asSharedFlow()

  /** Background job running the continuous socket message reader loop. */
  private val readerJob: Job
  /** Background job listening for scope cancellation to clean up resources. */
  private val cleanupJob: Job

  init {
    readerJob = scope.launch(Dispatchers.IO) { runReaderLoop() }
    cleanupJob = scope.launch { awaitScopeCancellation() }
  }

  /** Continuous loop reading messages from the socket and dispatching them to pending commands or the events flow. */
  private suspend fun runReaderLoop() {
    try {
      while (coroutineContext.isActive && !isClosed) {
        val responseBytes = FramingProtocol.readMessage(inputStream)
        val agentMessage = AgentMessage.parseFrom(responseBytes)
        when (agentMessage.specializedCase) {
          AgentMessage.SpecializedCase.RESPONSE -> {
            val response = agentMessage.response
            val deferred = synchronized(stateLock) { pendingCommands.remove(response.commandId) }
            deferred?.complete(response)
          }
          AgentMessage.SpecializedCase.EVENT -> {
            val event = agentMessage.event
            if (event.hasCrash()) {
              val crash = event.crash
              _events.tryEmit(event)
              throw InspectorCrashException(crash.errorMessage, crash.stackTrace)
            }
            _events.tryEmit(event)
          }
          else -> {
            throw IOException("Received invalid or empty message from agent")
          }
        }
      }
    } catch (e: Exception) {
      if (!isClosed) {
        val exception =
          when (e) {
            is InspectorCrashException -> e
            is CancellationException -> IOException("CommandSender scope cancelled", e)
            else -> IOException("Connection lost", e)
          }
        closeInternal(exception)
      }
      if (e is CancellationException) throw e
    } finally {
      closeInternal(IOException("Connection closed"))
    }
  }

  /** Listens to parent scope cancellation to close the connection and free resources. */
  private suspend fun awaitScopeCancellation() {
    try {
      awaitCancellation()
    } catch (cause: CancellationException) {
      val disconnectException = IOException("CommandSender scope cancelled", cause)
      closeInternal(disconnectException)
      throw cause
    }
  }

  private fun failAllPendingCommands(exception: Throwable) {
    val pending =
      synchronized(stateLock) {
        val list = pendingCommands.values.toList()
        pendingCommands.clear()
        list
      }
    for (deferred in pending) {
      deferred.completeExceptionally(exception)
    }
  }

  suspend fun sendMessage(command: Command): Response {
    val commandId = nextCommandId.getAndIncrement()
    val commandWithId = command.toBuilder().setCommandId(commandId).build()
    val deferred = CompletableDeferred<Response>()

    synchronized(stateLock) {
      if (_isClosed) {
        throw disconnectException ?: IllegalStateException("CommandSender is closed")
      }
      pendingCommands[commandId] = deferred
    }

    try {
      withContext(Dispatchers.IO) {
        val payload = commandWithId.toByteArray()
        // writeMessage is already thread-safe
        FramingProtocol.writeMessage(outputStream, payload)
      }
      return deferred.await()
    } catch (e: Throwable) {
      synchronized(stateLock) { pendingCommands.remove(commandId) }
      throw e
    }
  }

  /** Sends a command targeted at a specific inspector and returns the unwrapped response payload. */
  suspend fun sendInspectorCommand(inspectorId: String, payload: ByteArray): ByteArray =
    withContext(Dispatchers.IO) {
      val envelope = InspectorMessageCommand.newBuilder().setInspectorId(inspectorId).setPayload(ByteString.copyFrom(payload)).build()
      val command = Command.newBuilder().setInspectorMessage(envelope).build()
      val response = sendMessage(command)

      if (response.status != Response.Status.SUCCESS) {
        error("Agent Command Failed: ${response.errorMessage}")
      }

      val responseEnvelope = response.inspectorMessage
      if (responseEnvelope.inspectorId != inspectorId) {
        error("Received response for wrong inspector. Expected: $inspectorId, Got: ${responseEnvelope.inspectorId}")
      }
      responseEnvelope.payload.toByteArray()
    }

  override fun close() {
    val disconnectException =
      if (scope.isActive) {
        IOException("CommandSender closed")
      } else {
        IOException("CommandSender scope cancelled")
      }
    closeInternal(disconnectException)
  }

  /**
   * Closes all resources, cancels background jobs, and fails any pending commands.
   *
   * @param disconnectException The exception representing the reason for disconnection. This exception will be thrown by any subsequent or
   *   active [sendMessage] calls.
   */
  private fun closeInternal(disconnectException: Throwable) {
    synchronized(stateLock) {
      if (_isClosed) return
      _isClosed = true
      this.disconnectException = disconnectException
    }
    // Cancel the cleanup job to prevent it from leaking.
    cleanupJob.cancel()
    readerJob.cancel()
    try {
      socket.close()
    } catch (e: IOException) {
      // ignore
    }
    failAllPendingCommands(disconnectException)
  }
}
