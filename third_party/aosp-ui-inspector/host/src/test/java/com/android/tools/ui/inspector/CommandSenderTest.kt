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

import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.AgentMessage
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Command
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.InspectorMessageResponse
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Response
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.ShutdownCommand
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test

class CommandSenderTest {

  private val executor = Executors.newSingleThreadExecutor()
  private val dispatcher = executor.asCoroutineDispatcher()

  @After
  fun tearDown() {
    executor.shutdown()
  }

  @Test
  fun testSendMessage() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort

    coroutineScope {
      val serverTask =
        async(dispatcher) {
          try {
            serverSocket.accept().use { clientSocket ->
              val input = clientSocket.getInputStream()
              val output = clientSocket.getOutputStream()

              val requestBytes = FramingProtocol.readMessage(input)
              val request = Command.parseFrom(requestBytes)

              val response = Response.newBuilder().setCommandId(request.commandId).setStatus(Response.Status.SUCCESS).build()
              val agentMessage = AgentMessage.newBuilder().setResponse(response).build()

              FramingProtocol.writeMessage(output, agentMessage.toByteArray())
            }
          } catch (e: Exception) {
            // Silence socket exceptions during close/cancellation
          }
        }

      try {
        CommandSender.connect("127.0.0.1", port, this).use { sender ->
          val command = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()
          val response = sender.sendMessage(command)

          assertThat(response.status).isEqualTo(Response.Status.SUCCESS)
          assertThat(response.commandId).isEqualTo(1) // First command should have ID 1
        }

        serverTask.await()
      } finally {
        serverSocket.close()
      }
    }
  }

  @Test
  fun testSendMessage_Concurrent() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort

    coroutineScope {
      val serverTask =
        async(dispatcher) {
          try {
            serverSocket.accept().use { clientSocket ->
              val input = clientSocket.getInputStream()
              val output = clientSocket.getOutputStream()

              // Read 3 requests
              val req1 = Command.parseFrom(FramingProtocol.readMessage(input))
              val req2 = Command.parseFrom(FramingProtocol.readMessage(input))
              val req3 = Command.parseFrom(FramingProtocol.readMessage(input))

              // Respond in reverse order: 3, 2, 1
              val resp3 = Response.newBuilder().setCommandId(req3.commandId).setStatus(Response.Status.SUCCESS).build()
              val resp2 = Response.newBuilder().setCommandId(req2.commandId).setStatus(Response.Status.SUCCESS).build()
              val resp1 = Response.newBuilder().setCommandId(req1.commandId).setStatus(Response.Status.SUCCESS).build()

              FramingProtocol.writeMessage(output, AgentMessage.newBuilder().setResponse(resp3).build().toByteArray())
              FramingProtocol.writeMessage(output, AgentMessage.newBuilder().setResponse(resp2).build().toByteArray())
              FramingProtocol.writeMessage(output, AgentMessage.newBuilder().setResponse(resp1).build().toByteArray())
            }
          } catch (e: Exception) {
            // Silence socket exceptions during close/cancellation
          }
        }

      try {
        CommandSender.connect("127.0.0.1", port, this).use { sender ->
          val cmd = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()

          val deferred1 = async { sender.sendMessage(cmd) }
          val deferred2 = async { sender.sendMessage(cmd) }
          val deferred3 = async { sender.sendMessage(cmd) }

          val r1 = deferred1.await()
          val r2 = deferred2.await()
          val r3 = deferred3.await()

          assertThat(r1.commandId).isEqualTo(1)
          assertThat(r2.commandId).isEqualTo(2)
          assertThat(r3.commandId).isEqualTo(3)
        }
        serverTask.await()
      } finally {
        serverSocket.close()
      }
    }
  }

  @Test
  fun testEvents() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort

    coroutineScope {
      val serverTask =
        async(dispatcher) {
          try {
            serverSocket.accept().use { clientSocket ->
              val input = clientSocket.getInputStream()
              val output = clientSocket.getOutputStream()

              val req = Command.parseFrom(FramingProtocol.readMessage(input))

              // Send an event first
              val inspectorEvent =
                UiInspectorProtocol.InspectorMessageEvent.newBuilder()
                  .setInspectorId("test-inspector")
                  .setPayload(ByteString.copyFromUtf8("event-payload"))
                  .build()
              val event = UiInspectorProtocol.Event.newBuilder().setInspectorMessage(inspectorEvent).build()
              FramingProtocol.writeMessage(output, AgentMessage.newBuilder().setEvent(event).build().toByteArray())

              // Then send the response
              val resp = Response.newBuilder().setCommandId(req.commandId).setStatus(Response.Status.SUCCESS).build()
              FramingProtocol.writeMessage(output, AgentMessage.newBuilder().setResponse(resp).build().toByteArray())
            }
          } catch (e: Exception) {
            // Silence socket exceptions during close/cancellation
          }
        }

      try {
        CommandSender.connect("127.0.0.1", port, this).use { sender ->
          val eventDeferred = CompletableDeferred<UiInspectorProtocol.Event>()
          val collectJob = launch { sender.events.collect { eventDeferred.complete(it) } }
          runCurrent()

          val cmd = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()
          val response = sender.sendMessage(cmd)

          assertThat(response.status).isEqualTo(Response.Status.SUCCESS)

          // Reactively wait for the event to be collected
          val recEvent = eventDeferred.await()
          collectJob.cancel()

          assertThat(recEvent.inspectorMessage.inspectorId).isEqualTo("test-inspector")
          assertThat(recEvent.inspectorMessage.payload.toStringUtf8()).isEqualTo("event-payload")
        }
        serverTask.await()
      } finally {
        serverSocket.close()
      }
    }
  }

  @Test
  fun testAgentCrash() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort

    coroutineScope {
      val serverTask =
        async(dispatcher) {
          try {
            serverSocket.accept().use { clientSocket ->
              val input = clientSocket.getInputStream()
              val output = clientSocket.getOutputStream()

              Command.parseFrom(FramingProtocol.readMessage(input))

              // Send crash event
              val crash =
                UiInspectorProtocol.CrashEvent.newBuilder().setErrorMessage("Agent exploded").setStackTrace("at some.Class.method").build()
              val event = UiInspectorProtocol.Event.newBuilder().setCrash(crash).build()
              FramingProtocol.writeMessage(output, AgentMessage.newBuilder().setEvent(event).build().toByteArray())
            }
          } catch (e: Exception) {
            // Silence socket exceptions during close/cancellation
          }
        }

      try {
        CommandSender.connect("127.0.0.1", port, this).use { sender ->
          val cmd = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()
          try {
            sender.sendMessage(cmd)
            fail("Expected InspectorCrashException")
          } catch (e: InspectorCrashException) {
            assertThat(e.message).contains("Agent exploded")
            assertThat(e.stackTrace).isEqualTo("at some.Class.method")
          }
        }
        serverTask.await()
      } finally {
        serverSocket.close()
      }
    }
  }

  @Test
  fun testDisconnection() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort

    coroutineScope {
      val serverTask =
        async(dispatcher) {
          try {
            serverSocket.accept().use { clientSocket ->
              val input = clientSocket.getInputStream()
              Command.parseFrom(FramingProtocol.readMessage(input))
              // Close connection immediately without sending response
            }
          } catch (e: Exception) {
            // Silence socket exceptions during close/cancellation
          }
        }

      try {
        CommandSender.connect("127.0.0.1", port, this).use { sender ->
          val cmd = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()
          try {
            sender.sendMessage(cmd)
            fail("Expected IOException due to disconnection")
          } catch (e: IOException) {
            // Success
          }
        }
        serverTask.await()
      } finally {
        serverSocket.close()
      }
    }
  }

  @Test
  fun testClose() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort
    val writeCompleted = CompletableDeferred<Unit>()

    supervisorScope {
      val serverTask =
        async(dispatcher) {
          try {
            serverSocket.accept().use { clientSocket ->
              val input = clientSocket.getInputStream()
              Command.parseFrom(FramingProtocol.readMessage(input))
              writeCompleted.complete(Unit)
              // Keep connection alive by blocking on read until client closes
              input.read()
            }
          } catch (e: Exception) {
            // Silence socket exceptions during close/cancellation
          }
        }

      try {
        val sender = CommandSender.connect("127.0.0.1", port, this)
        val cmd = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()
        val deferred = async { sender.sendMessage(cmd) }

        writeCompleted.await()

        sender.close()

        try {
          deferred.await()
          fail("Expected IOException because sender was closed")
        } catch (e: IOException) {
          assertThat(e.message).contains("CommandSender closed")
        }

        // Subsequent messages should fail immediately
        try {
          sender.sendMessage(cmd)
          fail("Expected IOException on closed sender")
        } catch (e: IOException) {
          assertThat(e.message).contains("CommandSender closed")
        }

        serverTask.await()
      } finally {
        serverSocket.close()
      }
    }
  }

  @Test
  fun testSendInspectorCommand() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort

    coroutineScope {
      val serverTask =
        async(dispatcher) {
          try {
            serverSocket.accept().use { clientSocket ->
              val input = clientSocket.getInputStream()
              val output = clientSocket.getOutputStream()

              val requestBytes = FramingProtocol.readMessage(input)
              val request = Command.parseFrom(requestBytes)

              val inspectorMsg = request.inspectorMessage
              val responsePayload = inspectorMsg.payload.toStringUtf8().reversed()

              val responseEnvelope =
                InspectorMessageResponse.newBuilder()
                  .setInspectorId(inspectorMsg.inspectorId)
                  .setPayload(ByteString.copyFromUtf8(responsePayload))
                  .build()

              val response =
                Response.newBuilder()
                  .setCommandId(request.commandId)
                  .setStatus(Response.Status.SUCCESS)
                  .setInspectorMessage(responseEnvelope)
                  .build()
              val agentMessage = AgentMessage.newBuilder().setResponse(response).build()

              FramingProtocol.writeMessage(output, agentMessage.toByteArray())
            }
          } catch (e: Exception) {
            // Silence socket exceptions during close/cancellation
          }
        }

      try {
        CommandSender.connect("127.0.0.1", port, this).use { sender ->
          val payload = "hello".toByteArray()
          val responseBytes = sender.sendInspectorCommand("my-inspector", payload)

          assertThat(String(responseBytes)).isEqualTo("olleh")
        }

        serverTask.await()
      } finally {
        serverSocket.close()
      }
    }
  }

  @Test
  fun testParentScopeCancellation() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort
    val connectionAccepted = CompletableDeferred<Unit>()

    val serverTask =
      async(dispatcher) {
        try {
          serverSocket.accept().use { clientSocket ->
            connectionAccepted.complete(Unit)
            val input = clientSocket.getInputStream()
            val buf = ByteArray(1024)
            while (input.read(buf) != -1) {}
          }
        } catch (_: Exception) {}
      }

    try {
      val childJob = Job()
      val childScope = CoroutineScope(coroutineContext + childJob)

      val senderReady = CompletableDeferred<CommandSender>()
      val sendJob =
        childScope.async {
          try {
            CommandSender.connect("127.0.0.1", port, this).use { sender ->
              senderReady.complete(sender)
              val cmd = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()
              sender.sendMessage(cmd)
            }
          } catch (e: Throwable) {
            senderReady.completeExceptionally(e)
            throw e
          }
        }

      connectionAccepted.await()
      val sender = senderReady.await()

      // Cancel the parent scope of the CommandSender
      childJob.cancel()

      try {
        sendJob.await()
        fail("Expected CancellationException or IOException due to socket closure")
      } catch (_: CancellationException) {
        // Expected if coroutine cancellation interrupts before socket write
      } catch (_: IOException) {
        // Expected if socket closure during cancellation interrupts ongoing write
      }

      // Verify that the sender is closed after its parent scope was cancelled
      try {
        sender.sendMessage(Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build())
        fail("Expected IOException because sender should be closed")
      } catch (e: IOException) {
        assertThat(e.message).contains("CommandSender scope cancelled")
      }

      serverTask.await()
    } finally {
      serverSocket.close()
    }
  }

  @Test
  fun testIndividualCallCancellation() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort
    val connectionAccepted = CompletableDeferred<Unit>()

    val serverTask =
      async(dispatcher) {
        try {
          serverSocket.accept().use { clientSocket ->
            connectionAccepted.complete(Unit)
            val input = clientSocket.getInputStream()
            val buf = ByteArray(1024)
            while (input.read(buf) != -1) {}
          }
        } catch (_: Exception) {}
      }

    try {
      CommandSender.connect("127.0.0.1", port, this).use { sender ->
        connectionAccepted.await()

        val cmd = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()
        val job = launch {
          try {
            sender.sendMessage(cmd)
            fail("Expected cancellation")
          } catch (e: kotlinx.coroutines.CancellationException) {
            // Expected
          }
        }

        // Wait for the message to be registered in pendingCommands
        while (sender.pendingCommandCount == 0) {
          runCurrent()
        }
        assertThat(sender.pendingCommandCount).isEqualTo(1)

        // Cancel the individual sendMessage coroutine
        job.cancel()
        job.join()

        // Verify that the pending command was cleaned up
        assertThat(sender.pendingCommandCount).isEqualTo(0)
      }
      serverTask.await()
    } finally {
      serverSocket.close()
    }
  }

  @Test
  fun testConnectCallCancellationLeak() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort
    val connectionAccepted = CompletableDeferred<Unit>()
    val serverSocketClosed = CompletableDeferred<Unit>()

    val serverTask =
      async(dispatcher) {
        try {
          serverSocket.accept().use { clientSocket ->
            connectionAccepted.complete(Unit)
            try {
              // Wait for EOF or close from client
              clientSocket.getInputStream().read()
            } catch (e: Exception) {
              // Expected when client socket closes
            } finally {
              serverSocketClosed.complete(Unit)
            }
          }
        } catch (_: Exception) {}
      }

    try {
      val persistentScope = CoroutineScope(coroutineContext + Job())

      val connectJob = async { CommandSender.connect("127.0.0.1", port, persistentScope) }

      connectionAccepted.await()

      // The socket has connected.
      // Cancel the connect call coroutine. This is running the `connect` function.
      connectJob.cancel()

      try {
        val sender = connectJob.await()
        // If we reached here, the connect call completed before the cancellation was processed.
        // Close the sender to prevent leaking the socket.
        sender.close()
      } catch (e: CancellationException) {
        // Expected if cancelled in flight
      }

      // The calling coroutine is cancelled. If the socket leaked, the server-side client socket
      // will not see EOF or close because the client-side socket is still held open.
      // Let's verify if the server-side socket is closed.
      serverSocketClosed.await()

      // Verify that the server socket has closed (meaning the client socket was closed as well)
      assertThat(serverSocketClosed.isCompleted).isTrue()

      // Clean up the persistent scope if it wasn't cancelled
      persistentScope.coroutineContext[Job]?.cancel()
      serverTask.await()
    } finally {
      serverSocket.close()
    }
  }

  @Test
  fun testParentScopeCancellationWithoutManualClose() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort
    val connectionAccepted = CompletableDeferred<Unit>()

    val serverTask =
      async(dispatcher) {
        try {
          serverSocket.accept().use { clientSocket ->
            connectionAccepted.complete(Unit)
            val input = clientSocket.getInputStream()
            val buf = ByteArray(1024)
            while (input.read(buf) != -1) {}
          }
        } catch (_: Exception) {}
      }

    try {
      val childJob = Job()
      val childScope = CoroutineScope(coroutineContext + childJob)

      val senderReady = CompletableDeferred<CommandSender>()
      childScope.launch {
        try {
          val sender = CommandSender.connect("127.0.0.1", port, this)
          senderReady.complete(sender)
        } catch (e: Throwable) {
          senderReady.completeExceptionally(e)
        }
      }

      connectionAccepted.await()
      val sender = senderReady.await()

      // Ensure background cleanup job has had a chance to start before cancelling
      yield()
      runCurrent()

      // Cancel the parent scope of the CommandSender
      childJob.cancel()

      // Wait for childJob to finish. If there is a deadlock, this will hang (timeout after 10s of runTest).
      childJob.join()

      // Verify that the sender is closed after its parent scope was cancelled
      try {
        sender.sendMessage(Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build())
        fail("Expected IOException because sender should be closed")
      } catch (e: IOException) {
        assertThat(e.message).contains("CommandSender scope cancelled")
      }

      serverTask.await()
    } finally {
      serverSocket.close()
    }
  }
}
