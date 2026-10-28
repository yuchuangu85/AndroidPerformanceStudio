package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.references
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MemoryObjectEvidenceTest {
    @Test
    fun `field metric cells distinguish measured zero from unknown and use grouped raw bytes`() =
        runEvidenceCases { language, dark ->
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(fixture(), MemoryProfilerActions(), language, Modifier.fillMaxSize())
                }
            }
            assertMetrics("memory-inspector-row-0", "0", "0", "0", "0", "0")
            assertMetrics("memory-inspector-row-1", "-", "-", "-", "-", "-")
            assertMetrics("memory-inspector-row-2", "12", "4,096", "1,234,567", "8,192", "9,876,543")
            onNodeWithText("4.0 KB", substring = true, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("0 B", substring = true, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("memory-inspector-facts", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun `one label cell renders primitive null string object and represented class evidence`() =
        runEvidenceCases { language, dark ->
            val fields =
                listOf(
                    field("count", "7", HeapValueKind.PRIMITIVE),
                    field("missing", "null", HeapValueKind.NULL),
                    field("text", "java.lang.String #142", HeapValueKind.STRING).copy(
                        targetObjectId = 142L,
                        targetClassName = "java.lang.String",
                        stringPreview = "\"hello\"",
                    ),
                    field("child", "sample.Child #143", HeapValueKind.OBJECT).copy(
                        targetObjectId = 143L,
                        targetClassName = "sample.Child",
                        childCount = 2,
                    ),
                    field("type", "java.lang.Class #144", HeapValueKind.CLASS).copy(
                        targetObjectId = 144L,
                        targetClassName = "java.lang.Class",
                        representedClassName = "sample.Target",
                    ),
                )
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(
                        fixture().copy(selectedInstanceDetail = detail().copy(fields = fields)),
                        MemoryProfilerActions(),
                        language,
                        Modifier.fillMaxSize(),
                    )
                }
            }
            listOf("count = 7", "missing = null", "text = {String} \"hello\"", "child = {Child}", "type = {Target}")
                .forEachIndexed { index, label ->
                    onNodeWithTag("memory-inspector-row-$index-name", useUnmergedTree = true).assertTextEquals(label)
                    onNodeWithTag("memory-inspector-row-$index-value", useUnmergedTree = true).assertDoesNotExist()
                }
        }

    @Test
    fun `reference root retains unknown shallow evidence and zero metrics without byte units`() =
        runEvidenceCases { language, dark ->
            var state by mutableStateOf(fixture())
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(state, MemoryProfilerActions(), language, Modifier.fillMaxSize())
                }
            }
            onNodeWithText(localizedStringResource(Res.string.references, language)).performClick()
            assertMetrics("memory-inspector-reference-root", "-", "-", "-", "4,096", "0")
            onNodeWithTag("memory-inspector-row-0-name", useUnmergedTree = true)
                .assertTextEquals("owner in Owner@4 (0x4)")
            runOnIdle {
                state =
                    state.copy(
                        selectedInstanceDetail =
                            state.selectedInstanceDetail!!.copy(
                                shallowSizeKnown = true,
                                retainedSize = 0,
                                depth = 0,
                                nativeSize = null,
                                retainedNativeSize = 0,
                            ),
                    )
            }
            assertMetrics("memory-inspector-reference-root", "0", "0", "0", "-", "0")
        }

    @Test
    fun `native columns require capability and retained native requires metric evidence`() =
        runEvidenceCases { language, dark ->
            var state by mutableStateOf(fixture().copy(snapshotSummary = HeapSnapshotSummary("evidence")))
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(state, MemoryProfilerActions(), language, Modifier.fillMaxSize())
                }
            }
            listOf("LABEL", "DEPTH", "SHALLOW_SIZE", "RETAINED_SIZE").forEach {
                onNodeWithTag("memory-inspector-header-$it", useUnmergedTree = true).assertExists()
            }
            onNodeWithTag("memory-inspector-header-NATIVE_SIZE", useUnmergedTree = true).assertDoesNotExist()
            runOnIdle {
                state =
                    fixture().copy(
                        selectedInstanceDetail =
                            detail().copy(
                                nativeSize = null,
                                retainedNativeSize = null,
                                fields = listOf(field("unknown", "null", HeapValueKind.NULL)),
                                references = emptyList(),
                            ),
                    )
            }
            onNodeWithTag("memory-inspector-header-NATIVE_SIZE", useUnmergedTree = true).assertExists()
            onNodeWithTag("memory-inspector-row-0-NATIVE_SIZE", useUnmergedTree = true).assertTextEquals("-")
            onNodeWithTag("memory-inspector-header-RETAINED_NATIVE_SIZE", useUnmergedTree = true).assertDoesNotExist()
            runOnIdle {
                state =
                    state.copy(
                        selectedInstanceDetail = state.selectedInstanceDetail!!.copy(retainedNativeSize = 0),
                    )
            }
            onNodeWithTag("memory-inspector-header-RETAINED_NATIVE_SIZE", useUnmergedTree = true).assertExists()
            onNodeWithTag("memory-inspector-row-0-RETAINED_NATIVE_SIZE", useUnmergedTree = true).assertTextEquals("-")
        }

    private fun DesktopComposeUiTest.assertMetrics(
        tag: String,
        vararg expectedValues: String,
    ) {
        val columns = listOf("DEPTH", "SHALLOW_SIZE", "RETAINED_SIZE", "NATIVE_SIZE", "RETAINED_NATIVE_SIZE")
        require(expectedValues.size == columns.size)
        columns.zip(expectedValues.toList()).forEach { (column, expected) ->
            onNodeWithTag("$tag-$column", useUnmergedTree = true).assertTextEquals(expected)
        }
    }

    private fun runEvidenceCases(block: DesktopComposeUiTest.(UiLanguage, Boolean) -> Unit) {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 600, height = 520) { block(language, dark) }
            }
        }
    }

    private fun fixture(): MemoryProfilerState =
        MemoryProfilerState(
            activeSnapshotId = "evidence",
            snapshotSummary = HeapSnapshotSummary("evidence", capabilities = setOf(HeapCapability.NATIVE_SIZE)),
            selectedInstanceDetail = detail(),
        )

    private fun detail(): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = 42L,
            className = "sample.Target",
            shallowSize = 0,
            shallowSizeKnown = false,
            retainedSize = null,
            nativeSize = 4096,
            retainedNativeSize = 0,
            depth = null,
            isArray = false,
            elementCount = null,
            fields =
                listOf(
                    field("zero", "0", HeapValueKind.PRIMITIVE).copy(
                        depth = 0,
                        shallowSize = 0,
                        retainedSize = 0,
                        nativeSize = 0,
                        retainedNativeSize = 0,
                    ),
                    field("unknown", "null", HeapValueKind.NULL),
                    field("large", "sample.Large #142", HeapValueKind.OBJECT).copy(
                        targetObjectId = 142,
                        targetClassName = "sample.Large",
                        depth = 12,
                        shallowSize = 4096,
                        retainedSize = 1_234_567,
                        nativeSize = 8192,
                        retainedNativeSize = 9_876_543,
                    ),
                ),
            references =
                listOf(
                    field("owner", "sample.Owner #4", HeapValueKind.OBJECT).copy(
                        targetObjectId = 4,
                        targetClassName = "sample.Owner",
                    ),
                ),
            referenceChain = emptyList(),
            valueKind = HeapValueKind.OBJECT,
        )

    private fun field(
        name: String,
        display: String,
        kind: HeapValueKind,
    ): MemoryInstanceField = MemoryInstanceField(name, display, null, null, valueKind = kind)
}
