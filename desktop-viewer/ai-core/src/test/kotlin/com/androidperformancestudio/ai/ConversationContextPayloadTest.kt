package com.androidperformancestudio.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConversationContextPayloadTest {
    @Test
    fun `empty history keeps the original payload shape`() {
        val payload = Json.parseToJsonElement(request().payloadText()).jsonObject
        assertFalse("untrustedConversationContext" in payload)
    }

    @Test
    fun `previous messages are separate from current question and performance evidence`() {
        val request =
            request().copy(
                userQuestion = "Why is that slow?",
                conversationContext = listOf(
                    AiConversationContextTurn("run-one", "Which frame?", "Frame one was slow."),
                ),
            )
        val payload = Json.parseToJsonElement(request.payloadText()).jsonObject
        val prior =
            payload
                .getValue("untrustedConversationContext")
                .jsonArray
                .single()
                .jsonObject

        assertEquals("Why is that slow?", payload.getValue("untrustedUserQuestion").jsonPrimitive.content)
        assertEquals("Which frame?", prior.getValue("question").jsonPrimitive.content)
        assertEquals("Frame one was slow.", prior.getValue("answer").jsonPrimitive.content)
        assertEquals(1, payload.getValue("performanceEvidence").jsonArray.size)
    }

    @Test
    fun `conversation history has explicit size and identity bounds`() {
        assertFailsWith<IllegalArgumentException> { AiConversationContextTurn("run-one", null, "x".repeat(2049)) }
        val turn = AiConversationContextTurn("run-one", null, "answer")
        assertFailsWith<IllegalArgumentException> { request().copy(conversationContext = listOf(turn, turn)) }
        assertFailsWith<IllegalArgumentException> {
            request().copy(conversationContext = (1..5).map { AiConversationContextTurn("run-$it", null, "answer") })
        }
    }

    private fun request(): AnalysisRequest =
        AnalysisRequest(
            AnalysisSessionId("fixture-session"),
            ProfilerKind.FRAME,
            AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Selected frame"),
            listOf(PerformanceEvidence("frame-one", "frame", "One frame", "{}")),
            emptyList(),
            "prompt-v1",
            "payload-v1",
        )
}
