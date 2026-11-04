package com.androidperformancestudio.aiplatform.presentation

import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ReportLanguage
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.app.contextDigest
import com.androidperformancestudio.aiplatform.app.createBusinessContext
import com.androidperformancestudio.aiplatform.app.createChatContext
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatContextBindingTest {
    @Test
    fun `only exact data-free chat identity can restore without profiler evidence`() {
        val chat = createChatContext()
        val stored = record(chat.request)
        assertEquals(chat, matchingChatContext(stored, createChatContext()))
        assertNull(matchingChatContext(record(context().request), chat))
        assertNull(matchingChatContext(stored.copy(contextHash = "a".repeat(64)), chat))
        assertNull(matchingChatContext(stored.copy(analysisSessionId = "other-session"), chat))
    }

    @Test
    fun `same session and evidence ids cannot bind a different canonical payload`() {
        val context = context()
        val record = record(context.request)
        assertEquals(context, matchingChatContext(record, context))
        val changed =
            context.copy(
                request =
                    context.request.copy(
                        evidence =
                            listOf(
                                context.request.evidence
                                    .single()
                                    .copy(structuredPayload = "{\"durationNs\":999}"),
                            ),
                    ),
            )
        assertNull(matchingChatContext(record, changed))
        assertNull(matchingChatContext(record, null))
    }

    @Test
    fun `question and chat history do not change canonical context identity`() {
        val context = context()
        val withQuestion = context.copy(request = context.request.copy(userQuestion = "A different current question"))
        assertEquals(withQuestion, matchingChatContext(record(context.request), withQuestion))
    }

    @Test
    fun `report language does not change canonical evidence identity`() {
        val context = context()
        val chinese = context.copy(request = context.request.copy(outputLanguage = ReportLanguage.SIMPLIFIED_CHINESE))

        assertEquals(chinese, matchingChatContext(record(context.request), chinese))
    }

    @Test
    fun `binding a no source history never adds source candidates from current report`() {
        val context = context()
        val withSource =
            context.copy(
                request =
                    context.request.copy(
                        sourceCandidates =
                            listOf(
                                AiSourceCandidate("candidate-one", "Example.kt", null, "exact", emptyList(), "private snippet"),
                            ),
                    ),
            )
        val bound = matchingChatContext(record(context.request), withSource)
        assertEquals(emptyList(), bound!!.request.sourceCandidates)
        assertNull(matchingChatContext(record(withSource.request), context))
    }

    private fun record(request: AnalysisRequest) =
        ConversationRecord(
            id = "conversation",
            analysisSessionId = request.sessionId.value,
            contextHash =
                contextDigest(
                    request.copy(userQuestion = null, conversationContext = emptyList()).payloadText() +
                        "\n" + request.promptVersion + "\n" + request.payloadPolicyVersion,
                ),
        )

    private fun context() =
        createBusinessContext(
            "frame",
            "Selected frame",
            listOf(PerformanceEvidence("frame-one", "frame-selection", "Canonical frame", "{\"durationNs\":123}")),
            true,
        )
}
