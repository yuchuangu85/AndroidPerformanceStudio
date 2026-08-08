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

import com.google.common.truth.Truth.assertThat
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.junit.Test

class TreeMergerTest {

  @Test
  fun testAttachComposeTreeGraftsNodesCorrectly() {
    // 1. Setup standard View tree:
    // Root (FrameLayout)
    //   -> AndroidComposeView (id = 123)
    //   -> OtherView (id = 456)
    val rootNode =
      UiNode.ViewNode(
        id = 1,
        className = "android.widget.FrameLayout",
        bounds = UiNode.Bounds(0, 0, 1080, 1920),
        idResource = "root",
        layoutResource = "main_layout",
        attributes = emptyList(),
        children =
          mutableListOf(
            UiNode.ViewNode(
              id = 123,
              className = "androidx.compose.ui.platform.AndroidComposeView",
              bounds = UiNode.Bounds(0, 0, 1080, 1000),
              idResource = null,
              layoutResource = null,
              attributes = emptyList(),
              children = mutableListOf(),
            ),
            UiNode.ViewNode(
              id = 456,
              className = "android.widget.Button",
              bounds = UiNode.Bounds(0, 1000, 1080, 1920),
              idResource = "submit_btn",
              layoutResource = null,
              attributes = emptyList(),
              children = mutableListOf(),
            ),
          ),
      )

    // 2. Setup Composable children nodes to graft:
    // Composable Column (id = 200)
    //   -> Text (id = 201)
    val stringTable = mapOf(1 to "Column", 2 to "Text", 3 to "MainActivity.kt")
    val composeNodes: List<LayoutInspectorComposeProtocol.ComposableNode> =
      listOf(
        LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
          .setId(200)
          .setName(1) // Column
          .setFilename(3) // MainActivity.kt
          .setLineNumber(10)
          .setBounds(
            LayoutInspectorComposeProtocol.Bounds.newBuilder()
              .setLayout(LayoutInspectorComposeProtocol.Rect.newBuilder().setX(0).setY(0).setW(1080).setH(500))
          )
          .addChildren(
            LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
              .setId(201)
              .setName(2) // Text
              .setBounds(
                LayoutInspectorComposeProtocol.Bounds.newBuilder()
                  .setLayout(LayoutInspectorComposeProtocol.Rect.newBuilder().setX(50).setY(50).setW(200).setH(50))
              )
          )
          .build()
      )

    // Build mock parameters response for Composable node ID 201 (Text)
    val mockAllParamsResponse =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(201)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(3) // index for "text"
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(4) // index for "Hello"
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("text"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("Hello"))
        .build()

    // 3. Execute grafting
    val wasAttached =
      attachComposeTree(
        viewNode = rootNode,
        targetViewId = 123,
        composeNodes = composeNodes,
        stringTable = stringTable,
        viewsToSkip = emptyList(),
        parameters = mockAllParamsResponse,
        includeParameters = true,
        includeSemantics = true,
      )

    // 4. Assertions
    assertThat(wasAttached).isTrue()

    // Verify that FrameLayout child count is still 2
    assertThat(rootNode.children).hasSize(2)

    // Verify that the target AndroidComposeView now has 1 grafted ComposeNode child
    val composeView = rootNode.children[0] as UiNode.ViewNode
    assertThat(composeView.children).hasSize(1)

    val graftedRoot = composeView.children[0] as UiNode.ComposeNode
    assertThat(graftedRoot.id).isEqualTo(200)
    assertThat(graftedRoot.className).isEqualTo("Column")
    assertThat(graftedRoot.bounds.width).isEqualTo(1080)
    assertThat(graftedRoot.sourceLocation?.filename).isEqualTo("MainActivity.kt")
    assertThat(graftedRoot.sourceLocation?.lineNumber).isEqualTo(10)

    // Verify recursive children grafting
    assertThat(graftedRoot.children).hasSize(1)
    val graftedText = graftedRoot.children[0] as UiNode.ComposeNode
    assertThat(graftedText.id).isEqualTo(201)
    assertThat(graftedText.className).isEqualTo("Text")

    // Verify parameters are successfully converted and grafted on Composable node
    assertThat(graftedText.parameters).hasSize(1)
    val param = graftedText.parameters[0] as UiNode.ComposeParameter.Single
    assertThat(param.name).isEqualTo("text")
    assertThat(param.value).isEqualTo(UiNode.ComposeParameter.Value.StringVal("Hello"))

    // Verify that Button (OtherView) has zero grafted children
    val buttonView = rootNode.children[1] as UiNode.ViewNode
    assertThat(buttonView.children).isEmpty()
  }

  @Test
  fun testAttachComposeTreeSkipsNonMatchingId() {
    val rootNode =
      UiNode.ViewNode(
        id = 1,
        className = "android.widget.FrameLayout",
        bounds = UiNode.Bounds(0, 0, 1080, 1920),
        idResource = "root",
        layoutResource = null,
        attributes = emptyList(),
        children = mutableListOf(),
      )

    val composeNodes = listOf(LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(200).setName(1).build())

    // Try grafting to a non-existent target view id 999
    val wasAttached =
      attachComposeTree(
        viewNode = rootNode,
        targetViewId = 999,
        composeNodes = composeNodes,
        stringTable = mapOf(1 to "Column"),
        viewsToSkip = emptyList(),
        parameters = null,
        includeParameters = true,
        includeSemantics = true,
      )

    // Must skip grafting because the view id 999 does not exist in the View tree!
    assertThat(wasAttached).isFalse()
    assertThat(rootNode.children).isEmpty()
  }

  @Test
  fun testAttachComposeTreeFiltersOutViewsToSkip() {
    // 1. Setup standard View tree:
    // Root (FrameLayout)
    //   -> AndroidComposeView (id = 123)
    //        -> ShadowView (id = 777) -- should be skipped
    //        -> NormalView (id = 888) -- should keep
    val rootNode =
      UiNode.ViewNode(
        id = 1,
        className = "android.widget.FrameLayout",
        bounds = UiNode.Bounds(0, 0, 1080, 1920),
        idResource = "root",
        layoutResource = "main_layout",
        attributes = emptyList(),
        children =
          mutableListOf(
            UiNode.ViewNode(
              id = 123,
              className = "androidx.compose.ui.platform.AndroidComposeView",
              bounds = UiNode.Bounds(0, 0, 1080, 1000),
              idResource = null,
              layoutResource = null,
              attributes = emptyList(),
              children =
                mutableListOf(
                  UiNode.ViewNode(
                    id = 777,
                    className = "android.view.View", // e.g., paint shadow
                    bounds = UiNode.Bounds(0, 0, 1080, 1000),
                    idResource = null,
                    layoutResource = null,
                    attributes = emptyList(),
                    children = mutableListOf(),
                  ),
                  UiNode.ViewNode(
                    id = 888,
                    className = "android.widget.TextView",
                    bounds = UiNode.Bounds(100, 100, 500, 200),
                    idResource = null,
                    layoutResource = null,
                    attributes = emptyList(),
                    children = mutableListOf(),
                  ),
                ),
            )
          ),
      )

    // 2. Setup Composable children nodes to graft:
    val stringTable = mapOf(1 to "Column")
    val composeNodes =
      listOf(
        LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
          .setId(200)
          .setName(1) // Column
          .setBounds(
            LayoutInspectorComposeProtocol.Bounds.newBuilder()
              .setLayout(LayoutInspectorComposeProtocol.Rect.newBuilder().setX(0).setY(0).setW(1080).setH(500))
          )
          .build()
      )

    // 3. Execute grafting with viewsToSkip list containing 777
    val wasAttached =
      attachComposeTree(
        viewNode = rootNode,
        targetViewId = 123,
        composeNodes = composeNodes,
        stringTable = stringTable,
        viewsToSkip = listOf(777L),
        parameters = null,
        includeParameters = true,
        includeSemantics = true,
      )

    // 4. Assertions
    assertThat(wasAttached).isTrue()

    val composeView = rootNode.children[0] as UiNode.ViewNode
    // The children of AndroidComposeView should be:
    // - NormalView (id = 888)
    // - Grafted ComposeNode (id = 200)
    // The ShadowView (id = 777) must be skipped (removed)
    assertThat(composeView.children).hasSize(2)

    val child1 = composeView.children[0] as UiNode.ViewNode
    assertThat(child1.id).isEqualTo(888)

    val child2 = composeView.children[1] as UiNode.ComposeNode
    assertThat(child2.id).isEqualTo(200)
  }

  @Test
  fun testAttachComposeTreeGraftsHostedViews() {
    // 1. Setup standard View tree:
    // Root (FrameLayout)
    //   -> AndroidComposeView (id = 123)
    //        -> HostedView (id = 999) -- this view is hosted by Composable id 201
    val rootNode =
      UiNode.ViewNode(
        id = 1,
        className = "android.widget.FrameLayout",
        bounds = UiNode.Bounds(0, 0, 1080, 1920),
        idResource = "root",
        layoutResource = "main_layout",
        attributes = emptyList(),
        children =
          mutableListOf(
            UiNode.ViewNode(
              id = 123,
              className = "androidx.compose.ui.platform.AndroidComposeView",
              bounds = UiNode.Bounds(0, 0, 1080, 1000),
              idResource = null,
              layoutResource = null,
              attributes = emptyList(),
              children =
                mutableListOf(
                  UiNode.ViewNode(
                    id = 999,
                    className = "android.widget.Button",
                    bounds = UiNode.Bounds(50, 50, 200, 50),
                    idResource = "button_in_compose",
                    layoutResource = null,
                    attributes = emptyList(),
                    children = mutableListOf(),
                  )
                ),
            )
          ),
      )

    // 2. Setup Composable children nodes to graft:
    // Column (id = 200)
    //   -> AndroidView (id = 201, viewId = 999)
    val stringTable = mapOf(1 to "Column", 2 to "AndroidView")
    val composeNodes =
      listOf(
        LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
          .setId(200)
          .setName(1) // Column
          .addChildren(
            LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
              .setId(201)
              .setName(2) // AndroidView
              .setViewId(999) // References the View 999
          )
          .build()
      )

    // 3. Execute grafting
    val wasAttached =
      attachComposeTree(
        viewNode = rootNode,
        targetViewId = 123,
        composeNodes = composeNodes,
        stringTable = stringTable,
        viewsToSkip = emptyList(),
        parameters = null,
        includeParameters = true,
        includeSemantics = true,
      )

    // 4. Assertions
    assertThat(wasAttached).isTrue()

    val composeView = rootNode.children[0] as UiNode.ViewNode

    // The HostedView with id 999 should no longer be a child of AndroidComposeView
    assertThat(composeView.children).hasSize(1)

    // The grafted ComposeNode Column is the only child of AndroidComposeView
    val columnNode = composeView.children[0] as UiNode.ComposeNode
    assertThat(columnNode.id).isEqualTo(200)
    assertThat(columnNode.children).hasSize(1)

    // AndroidView is a child of Column
    val androidViewNode = columnNode.children[0] as UiNode.ComposeNode
    assertThat(androidViewNode.id).isEqualTo(201)

    // The HostedView 999 is now grafted as a child of the AndroidView ComposableNode!
    assertThat(androidViewNode.children).hasSize(1)
    val graftedView = androidViewNode.children[0] as UiNode.ViewNode
    assertThat(graftedView.id).isEqualTo(999)
    assertThat(graftedView.className).isEqualTo("android.widget.Button")
  }
}
