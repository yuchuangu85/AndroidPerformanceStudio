package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryInstanceInspectionTest {
    @Test
    fun `legacy entry shares flat selection reference filtering snapshot reset and array pagination`() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            var state by mutableStateOf(fixture().copy(selectedInstanceDetail = null))
            val loadedRanges = mutableListOf<Pair<Long, Int>>()
            val copy = labels(UiLanguage.ENGLISH)
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerClassListPane(
                        MemoryProfilerPresenter.present(state),
                        MemoryProfilerActions(
                            onSelectInstance = { id -> state = state.copy(selectedInstanceDetail = detail(id)) },
                            onLoadArrayRange = { id, start -> loadedRanges += id to start },
                        ),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("memory-instance-row-42").performClick().assertHeightIsEqualTo(28.dp).assertIsSelected()
            inspectorTab(copy.references).performClick()
            rootFilterLabel(copy).performClick()
            assertInboundReferences(42L, nearestOnly = true)
            onNodeWithTag("memory-instance-row-43").performClick().assertHeightIsEqualTo(28.dp).assertIsSelected()
            assertInspectorTabs(copy, fieldsSelected = false)
            rootFilter().assertIsOn()
            assertInboundReferences(43L, nearestOnly = true)

            runOnIdle {
                state =
                    fixture(snapshotId = "legacy-array").copy(
                        selectedInstanceDetail =
                            detail(42L).copy(
                                className = "sample.Target[]",
                                isArray = true,
                                elementCount = 450,
                                arrayStart = 200,
                                fields = List(200) { MemoryInstanceField("[${200 + it}]", "value", null, null) },
                            ),
                    )
            }
            assertInspectorTabs(copy, fieldsSelected = true)
            rootFilter().assertDoesNotExist()
            onNodeWithText("Elements 201\u2013400 of 450").assertExists()
            onNodeWithContentDescription("Previous elements").performClick()
            onNodeWithContentDescription("Next elements").performClick()
            assertEquals(listOf(42L to 0, 42L to 400), loadedRanges)
            inspectorTab(copy.references).performClick()
            rootFilter().assertIsOff()
        }

    @Test
    fun `instance rows stay flat and select the object shown in Instance Details`() =
        runInspectionTest { language, dark ->
            var state by mutableStateOf(fixture().copy(selectedInstanceDetail = null))
            val selectedIds = mutableListOf<Long>()
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryProfilerScreen(
                        state,
                        MemoryProfilerActions(
                            onSelectInstance = { id ->
                                selectedIds += id
                                state = state.copy(selectedInstanceDetail = detail(id))
                            },
                        ),
                        language,
                    )
                }
            }

            assertFlatInstanceRows()
            listOf(42L, 43L, 43L, 42L).forEach { id ->
                onNodeWithTag("memory-instance-row-$id").performScrollTo().performClick()
                runOnIdle { assertEquals(id, state.selectedInstanceDetail?.objectId) }
                onNodeWithTag("memory-instance-row-$id").assertIsSelected()
                onNodeWithTag("memory-instance-row-${if (id == 42L) 43L else 42L}").assertIsNotSelected()
                assertFlatInstanceRows()
                onNodeWithTag("memory-inspector-card").performScrollTo()
                inspectorText(labels(language).title).assertIsDisplayed()
                inspectorText("ID 0x${id.toString(16)}").assertIsDisplayed()
                visibleContentText("child$id").assertIsDisplayed()
                contentText("child${if (id == 42L) 43L else 42L}").assertDoesNotExist()
            }
            runOnIdle { assertEquals(listOf(42L, 43L, 43L, 42L), selectedIds) }
        }

    @Test
    fun `only Fields and References tabs exist and label toggles depth filtered inbound references with root path`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(
                        fixture(),
                        MemoryProfilerActions(),
                        language,
                        Modifier.width(330.dp).fillMaxHeight(),
                    )
                }
            }

            inspectorText(copy.title).assertIsDisplayed()
            assertInspectorTabs(copy, fieldsSelected = true)
            rootFilter().assertDoesNotExist()
            visibleContentText("child42").assertIsDisplayed()
            contentText("sample.Root.target42").assertDoesNotExist()
            contentText("sample.Owner42.target").assertDoesNotExist()
            assertRootPathAbsent()

            inspectorTab(copy.references).performClick()
            assertInspectorTabs(copy, fieldsSelected = false)
            rootFilter().assertIsOff()
            assertInboundReferences(42L, nearestOnly = false)
            assertRootPathAbsent()

            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOn()
            assertInboundReferences(42L, nearestOnly = true)
            assertRootPath(42L)
            assertInspectorTabs(copy, fieldsSelected = false)

            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOff()
            assertInboundReferences(42L, nearestOnly = false)
            assertRootPathAbsent()
            inspectorTab(copy.fields).performClick()
            assertInspectorTabs(copy, fieldsSelected = true)
            rootFilter().assertDoesNotExist()
            visibleContentText("child42").assertIsDisplayed()
            contentText("sample.Root.target42").assertDoesNotExist()
            assertRootPathAbsent()
        }

    @Test
    fun `same snapshot object changes preserve tab and nearest root filter while replacing displayed data`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            var state by mutableStateOf(fixture())
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(
                        state,
                        MemoryProfilerActions(),
                        language,
                        Modifier.width(330.dp).fillMaxHeight(),
                    )
                }
            }

            inspectorTab(copy.references).performClick()
            rootFilterLabel(copy).performClick()
            assertRootPath(42L)
            runOnIdle { state = state.copy(selectedInstanceDetail = detail(43L)) }
            assertInspectorTabs(copy, fieldsSelected = false)
            rootFilter().assertIsOn()
            assertInboundReferences(43L, nearestOnly = true)
            assertRootPath(43L)
            contentText("sample.Root.target42").assertDoesNotExist()
            contentText("target42", substring = true).assertDoesNotExist()

            rootFilterLabel(copy).performClick()
            assertInboundReferences(43L, nearestOnly = false)
            runOnIdle { state = state.copy(selectedInstanceDetail = detail(42L)) }
            assertInspectorTabs(copy, fieldsSelected = false)
            rootFilter().assertIsOff()
            assertInboundReferences(42L, nearestOnly = false)
            contentText("sample.Root.target43").assertDoesNotExist()
            contentText("sample.Owner43.target").assertDoesNotExist()
            assertRootPathAbsent()

            rootFilterLabel(copy).performClick()
            inspectorTab(copy.fields).performClick()
            rootFilter().assertDoesNotExist()
            runOnIdle { state = state.copy(selectedInstanceDetail = detail(43L)) }
            assertInspectorTabs(copy, fieldsSelected = true)
            visibleContentText("child43").assertIsDisplayed()
            contentText("child42").assertDoesNotExist()
            inspectorTab(copy.references).performClick()
            rootFilter().assertIsOn()
            assertInboundReferences(43L, nearestOnly = true)
            assertRootPath(43L)
        }

    @Test
    fun `snapshot changes reset tab and nearest root filter even when selected object id is reused`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            var state by mutableStateOf(fixture())
            val nextDetail = newSnapshotDetail()
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(
                        state,
                        MemoryProfilerActions(),
                        language,
                        Modifier.width(330.dp).fillMaxHeight(),
                    )
                }
            }

            inspectorTab(copy.references).performClick()
            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOn()
            assertRootPath(42L)
            runOnIdle {
                state = fixture(snapshotId = "inspection-b").copy(selectedInstanceDetail = nextDetail)
            }
            assertInspectorTabs(copy, fieldsSelected = true)
            rootFilter().assertDoesNotExist()
            visibleContentText("newSnapshotChild").assertIsDisplayed()
            contentText("child42").assertDoesNotExist()
            assertRootPathAbsent()

            inspectorTab(copy.references).performClick()
            rootFilter().assertIsOff()
            visibleContentText("sample.Root.newSnapshotTarget").assertIsDisplayed()
            visibleContentText("sample.NewOwner.target").assertIsDisplayed()
            contentText("sample.Root.target42").assertDoesNotExist()
            contentText("sample.Owner42.target").assertDoesNotExist()
            assertRootPathAbsent()
            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOn()
            visibleContentText("sample.Root.newSnapshotTarget").assertIsDisplayed()
            contentText("sample.NewOwner.target").assertDoesNotExist()
            visibleContentText("newSnapshotTarget  0x2a").assertIsDisplayed()
            contentText("target42  0x2a").assertDoesNotExist()
        }

    @Test
    fun `array values and missing inbound references or root paths have distinct empty states`() =
        runInspectionTest { language, dark ->
            val copy = labels(language)
            val array =
                detail(42L).copy(
                    className = "sample.Target[]",
                    depth = null,
                    isArray = true,
                    elementCount = 1,
                    fields = emptyList(),
                    references = emptyList(),
                    referenceChain = emptyList(),
                )
            var state by mutableStateOf(fixture().copy(selectedInstanceDetail = array))
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryObjectInspector(
                        state,
                        MemoryProfilerActions(),
                        language,
                        Modifier.width(330.dp).fillMaxHeight(),
                    )
                }
            }

            visibleContentText(copy.noArrayValues).assertIsDisplayed()
            contentText(copy.noFields).assertDoesNotExist()
            contentText(copy.noReferences).assertDoesNotExist()
            inspectorTab(copy.references).performClick()
            rootFilter().assertIsOff()
            visibleContentText(copy.noReferences).assertIsDisplayed()
            contentText(copy.noArrayValues).assertDoesNotExist()
            contentText(copy.noFields).assertDoesNotExist()
            contentText(copy.noRootPath).assertDoesNotExist()
            rootFilterLabel(copy).performClick()
            rootFilter().assertIsOn()
            visibleContentText(copy.noRootPath).assertIsDisplayed()
            contentText(copy.noArrayValues).assertDoesNotExist()
            assertRootPathAbsent()

            inspectorTab(copy.fields).performClick()
            runOnIdle {
                state =
                    state.copy(
                        selectedInstanceDetail =
                            array.copy(
                                fields = listOf(MemoryInstanceField("[0]", "sample.Child #142", 142L, "sample.Child")),
                            ),
                    )
            }
            visibleContentText("[0]").assertIsDisplayed()
            visibleContentText("sample.Child #142").assertIsDisplayed()
            contentText(copy.noArrayValues).assertDoesNotExist()
            inspectorTab(copy.references).performClick()
            rootFilter().assertIsOn()
            rootFilterLabel(copy).performClick()
            visibleContentText(copy.noReferences).assertIsDisplayed()
            contentText("[0]").assertDoesNotExist()
            contentText(copy.noArrayValues).assertDoesNotExist()
            contentText(copy.noRootPath).assertDoesNotExist()
        }

    @Test
    fun `details capture fields inbound references and nearest root layouts`() =
        runInspectionTest { language, dark ->
            setContent {
                ViewerTheme(darkTheme = dark) {
                    MemoryProfilerScreen(fixture(), MemoryProfilerActions(), language)
                }
            }
            onNodeWithTag("memory-inspector-card").performScrollTo()
            val width = onRoot().captureToImage().width
            val locale = if (language == UiLanguage.ENGLISH) "en" else "zh"
            val theme = if (dark) "dark" else "light"
            val name = "memory-instance-details-$width-$locale-$theme"
            saveCapture(onRoot().captureToImage(), "$name-fields")
            inspectorTab(labels(language).references).performClick()
            saveCapture(onRoot().captureToImage(), "$name-references")
            rootFilterLabel(labels(language)).performClick()
            saveCapture(onRoot().captureToImage(), "$name-nearest-root")
        }

    private fun saveCapture(
        image: ImageBitmap,
        name: String,
    ) {
        val output = Path.of("build/screenshots/$name.png")
        Files.createDirectories(output.parent)
        val pixels = image.toPixelMap()
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                bitmap.setRGB(x, y, pixels[x, y].toArgb())
            }
        }
        assertTrue(ImageIO.write(bitmap, "png", output.toFile()))
    }

    private fun newSnapshotDetail(): MemoryInstanceDetail =
        detail(42L).copy(
            fields = listOf(MemoryInstanceField("newSnapshotChild", "sample.NewChild #142", 142L, "sample.NewChild")),
            references =
                listOf(
                    MemoryInstanceField(
                        "sample.Root.newSnapshotTarget",
                        "sample.Root #4",
                        4L,
                        "sample.Root",
                        depth = 0,
                    ),
                    MemoryInstanceField(
                        "sample.NewOwner.target",
                        "sample.NewOwner #342",
                        342L,
                        "sample.NewOwner",
                        depth = 1,
                    ),
                ),
            referenceChain =
                listOf(
                    ObjectReference("GC Root (JNI_GLOBAL)", 4L, "sample.Root"),
                    ObjectReference("newSnapshotTarget", 42L, "sample.Target"),
                ),
        )

    private fun runInspectionTest(block: DesktopComposeUiTest.(UiLanguage, Boolean) -> Unit) {
        listOf(1280, 900).forEach { width ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                listOf(false, true).forEach { dark ->
                    runDesktopComposeUiTest(width = width, height = 800) { block(language, dark) }
                }
            }
        }
    }

    private fun DesktopComposeUiTest.assertFlatInstanceRows() {
        onNodeWithTag("memory-instances-card").performScrollTo()
        val inInstances = hasAnyAncestor(hasTestTag("memory-instances-card"))
        onAllNodes(hasClickAction() and inInstances).assertCountEquals(2)
        listOf(42L, 43L).forEach { id ->
            onNodeWithTag("memory-instance-row-$id").assertHeightIsEqualTo(28.dp)
        }
        listOf("\u25B6", "\u25BC", "\u25B8", "\u25BE", "GC Root", "sample.Root").forEach { text ->
            onNode(hasText(text, substring = true) and inInstances, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun DesktopComposeUiTest.assertInspectorTabs(
        copy: InspectorLabels,
        fieldsSelected: Boolean,
    ) {
        val tabs =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                hasAnyAncestor(hasTestTag("memory-object-inspector"))
        onAllNodes(tabs).assertCountEquals(2)
        if (fieldsSelected) {
            inspectorTab(copy.fields).assertIsSelected()
            inspectorTab(copy.references).assertIsNotSelected()
        } else {
            inspectorTab(copy.fields).assertIsNotSelected()
            inspectorTab(copy.references).assertIsSelected()
        }
        listOf("Path", "\u8DEF\u5F84", "GC Root path").forEach { label ->
            onNode(hasText(label) and tabs).assertDoesNotExist()
        }
    }

    private fun DesktopComposeUiTest.assertInboundReferences(
        objectId: Long,
        nearestOnly: Boolean,
    ) {
        visibleContentText("sample.Root.target$objectId").assertIsDisplayed()
        if (nearestOnly) {
            contentText("sample.Owner$objectId.target").assertDoesNotExist()
        } else {
            visibleContentText("sample.Owner$objectId.target").assertIsDisplayed()
        }
        contentText("child$objectId").assertDoesNotExist()
    }

    private fun DesktopComposeUiTest.assertRootPath(objectId: Long) {
        visibleContentText("GC Root (JNI_GLOBAL)", substring = true).assertIsDisplayed()
        visibleContentText("1. sample.Root").assertIsDisplayed()
        visibleContentText("2. sample.Target").assertIsDisplayed()
        visibleContentText("target$objectId  0x${objectId.toString(16)}").assertIsDisplayed()
    }

    private fun DesktopComposeUiTest.assertRootPathAbsent() {
        contentText("GC Root (JNI_GLOBAL)", substring = true).assertDoesNotExist()
        contentText("1. sample.Root").assertDoesNotExist()
        contentText("2. sample.Target").assertDoesNotExist()
    }

    private fun DesktopComposeUiTest.inspectorTab(label: String): SemanticsNodeInteraction =
        onNode(
            hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                hasAnyAncestor(hasTestTag("memory-object-inspector")),
        )

    private fun DesktopComposeUiTest.rootFilter(): SemanticsNodeInteraction =
        onNode(isToggleable() and hasAnyAncestor(hasTestTag("memory-object-inspector")))

    private fun DesktopComposeUiTest.rootFilterLabel(copy: InspectorLabels): SemanticsNodeInteraction =
        onNode(hasText(copy.nearestRootOnly) and hasAnyAncestor(hasTestTag("memory-object-inspector")))

    private fun DesktopComposeUiTest.inspectorText(text: String): SemanticsNodeInteraction =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag("memory-object-inspector")), useUnmergedTree = true)

    private fun DesktopComposeUiTest.visibleContentText(
        text: String,
        substring: Boolean = false,
    ): SemanticsNodeInteraction {
        onNodeWithTag("memory-inspector-content").performScrollToNode(hasText(text, substring = substring))
        return contentText(text, substring)
    }

    private fun DesktopComposeUiTest.contentText(
        text: String,
        substring: Boolean = false,
    ): SemanticsNodeInteraction =
        onNode(
            hasText(text, substring = substring) and hasAnyAncestor(hasTestTag("memory-inspector-content")),
            useUnmergedTree = true,
        )

    private fun fixture(snapshotId: String = "inspection-a"): MemoryProfilerState {
        val snapshot =
            HeapSnapshotSummary(
                snapshotId,
                capabilities = setOf(HeapCapability.HEAP_GRAPH, HeapCapability.GC_ROOTS),
            )
        val classes = listOf(ClassStats("sample.Target", instanceCount = 2, shallowSize = 64, retainedSize = 96))
        return MemoryProfilerState(
            activeSnapshotId = snapshotId,
            snapshotSummary = snapshot,
            snapshotSummaries = listOf(snapshot),
            classes = classes,
            heapBaseClasses = classes,
            viewMode = MemoryProfilerViewMode.ClassList,
            selectedClassName = "sample.Target",
            selectedClassifierId = "class:sample.Target",
            selectedClassInstances =
                listOf(
                    MemoryInstanceRow(42L, 1, 32L, 48L, 1, true),
                    MemoryInstanceRow(43L, 2, 32L, 48L, 1, true),
                ),
            selectedInstanceDetail = detail(42L),
        )
    }

    private fun detail(objectId: Long): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = objectId,
            className = "sample.Target",
            shallowSize = 32L,
            retainedSize = 48L,
            depth = 1,
            isArray = false,
            elementCount = null,
            fields =
                listOf(
                    MemoryInstanceField(
                        "child$objectId",
                        "sample.Child #${objectId + 100L}",
                        objectId + 100L,
                        "sample.Child",
                    ),
                ),
            references =
                listOf(
                    MemoryInstanceField("sample.Root.target$objectId", "sample.Root #4", 4L, "sample.Root", depth = 0),
                    MemoryInstanceField(
                        "sample.Owner$objectId.target",
                        "sample.Owner$objectId #${objectId + 200L}",
                        objectId + 200L,
                        "sample.Owner$objectId",
                        depth = if (objectId == 42L) 1 else 2,
                    ),
                ),
            referenceChain =
                listOf(
                    ObjectReference("GC Root (JNI_GLOBAL)", 4L, "sample.Root"),
                    ObjectReference("target$objectId", objectId, "sample.Target"),
                ),
        )

    private fun labels(language: UiLanguage): InspectorLabels =
        if (language == UiLanguage.ENGLISH) {
            InspectorLabels(
                title = "Instance Details",
                fields = "Fields",
                references = "References",
                nearestRootOnly = "Show nearest GC root only",
                noFields = "No fields in this parsed object",
                noReferences = "No inbound references.",
                noRootPath = "No strong GC Root path available",
                noArrayValues = "Array values are unavailable in this parsed evidence",
            )
        } else {
            InspectorLabels(
                title = "\u5B9E\u4F8B\u8BE6\u60C5",
                fields = "\u5B57\u6BB5",
                references = "\u5F15\u7528",
                nearestRootOnly = "\u4EC5\u663E\u793A\u6700\u8FD1\u7684 GC Root",
                noFields = "\u5F53\u524D\u89E3\u6790\u5BF9\u8C61\u6CA1\u6709\u5B57\u6BB5",
                noReferences = "\u65E0\u5165\u5411\u5F15\u7528\u3002",
                noRootPath = "\u6CA1\u6709\u53EF\u7528\u7684\u5F3A\u5F15\u7528 GC Root \u8DEF\u5F84",
                noArrayValues =
                    "\u5F53\u524D\u89E3\u6790\u8BC1\u636E\u4E2D\u6CA1\u6709" +
                        "\u53EF\u67E5\u770B\u7684\u6570\u7EC4\u503C",
            )
        }

    private data class InspectorLabels(
        val title: String,
        val fields: String,
        val references: String,
        val nearestRootOnly: String,
        val noFields: String,
        val noReferences: String,
        val noRootPath: String,
        val noArrayValues: String,
    )
}
