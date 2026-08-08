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

package com.android.tools.ui.inspector.inspectors.view

import android.app.Activity
import android.content.Context
import android.content.res.Configuration as AndroidResConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.inspection.ArtTooling
import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import androidx.inspection.InspectorExecutors
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Command
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsCommand
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// We need API 29+ because WindowInspector.getGlobalWindowViews() is used in RootsDetector.
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class ViewInspectorTest {

  /**
   * Dispatcher used for testing coroutines.
   *
   * We use [UnconfinedTestDispatcher] to ensure that coroutines launched in tests execute immediately on the current thread, avoiding the
   * need for complex clock manipulation in simple tests.
   */
  private val testDispatcher = UnconfinedTestDispatcher()

  private val mockEnvironment =
    object : InspectorEnvironment {
      override fun executors(): InspectorExecutors {
        return object : InspectorExecutors {
          override fun primary() = Executor { it.run() }

          override fun io() = Executor { it.run() }

          override fun handler() = Handler(Looper.getMainLooper())
        }
      }

      override fun artTooling(): ArtTooling {
        throw UnsupportedOperationException("Not implemented")
      }
    }

  @Test
  fun testOnReceiveCommand_unknown() {
    val mockConnection =
      object : Connection() {
        override fun sendEvent(data: ByteArray) {
          // Not used
        }
      }
    val inspector = ViewInspector(mockConnection, mockEnvironment)

    var replyData: ByteArray? = null
    val callback =
      object : Inspector.CommandCallback {
        override fun reply(response: ByteArray) {
          replyData = response
        }

        override fun addCancellationListener(executor: Executor, runnable: Runnable) {
          // Not used
        }
      }

    val command = Command.getDefaultInstance()
    var exceptionThrown = false
    try {
      inspector.onReceiveCommand(command.toByteArray(), callback)
    } catch (e: IllegalStateException) {
      exceptionThrown = true
      assertThat(e.message).contains("Unknown command")
    }
    assertThat(exceptionThrown).isTrue()
  }

  @Test
  fun testDumpViews_hierarchyStructure() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      activity.setContentView(setupViews(activity))

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      assertThat(response.specializedCase).isEqualTo(Response.SpecializedCase.DUMP_VIEWS_RESPONSE)
      val dumpResponse = response.dumpViewsResponse
      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }

      val testRoot = findNodeByClassName(dumpResponse.getNodes(0), "LinearLayout", stringTable)
      assertThat(testRoot).isNotNull()
      assertThat(testRoot!!.childrenCount).isEqualTo(3)
    }

  @Test
  fun testDumpViews_layoutParams() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      val textView =
        TextView(activity).apply {
          id = 101
          layoutParams = ViewGroup.LayoutParams(120, 80)
        }
      val layout =
        LinearLayout(activity).apply {
          id = 100
          addView(textView)
        }
      activity.setContentView(layout)

      val mockConnection =
        object : Connection() {
          override fun sendEvent(data: ByteArray) {}
        }
      val inspector = ViewInspector(mockConnection, mockEnvironment)
      val response = runDumpCommand(inspector, includeAttributes = true)
      val dumpResponse = response.dumpViewsResponse
      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }
      val root = dumpResponse.nodesList.first()
      val textNode = findNodeByClassName(root, "TextView", stringTable)!!

      val widthAttr = textNode.attributesList.find { stringTable[it.name] == "layout_width" }!!
      assertThat(widthAttr.type).isEqualTo(ViewInspectorProtocol.ViewNode.Attribute.Type.DIMENSION)
      assertThat(widthAttr.floatValue).isEqualTo(120f)

      val heightAttr = textNode.attributesList.find { stringTable[it.name] == "layout_height" }!!
      assertThat(heightAttr.type).isEqualTo(ViewInspectorProtocol.ViewNode.Attribute.Type.DIMENSION)
      assertThat(heightAttr.floatValue).isEqualTo(80f)
    }

  @Test
  fun testDumpViews_visibility() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      activity.setContentView(setupViews(activity))

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      val dumpResponse = response.dumpViewsResponse
      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }
      val testRoot = findNodeByClassName(dumpResponse.getNodes(0), "LinearLayout", stringTable)

      assertThat(testRoot).isNotNull()
      assertThat(stringTable[testRoot!!.attributesList.find { stringTable[it.name] == "visibility" }!!.int32Value]).isEqualTo("visible")
      assertThat(stringTable[testRoot.getChildren(0).attributesList.find { stringTable[it.name] == "visibility" }!!.int32Value])
        .isEqualTo("visible")
      assertThat(stringTable[testRoot.getChildren(1).attributesList.find { stringTable[it.name] == "visibility" }!!.int32Value])
        .isEqualTo("invisible")
      assertThat(stringTable[testRoot.getChildren(2).attributesList.find { stringTable[it.name] == "visibility" }!!.int32Value])
        .isEqualTo("gone")
    }

  @Test
  fun testDumpViews_stringTableDeduplication() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      activity.setContentView(setupViews(activity))

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      val dumpResponse = response.dumpViewsResponse

      val textViewEntries = dumpResponse.stringsList.filter { it.value == "TextView" }
      assertThat(textViewEntries).hasSize(1)
    }

  @Test
  fun testDumpViews_bounds() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      val root = setupViews(activity)
      activity.setContentView(root)

      root.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
      )
      root.layout(0, 0, 100, 100)

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      val dumpResponse = response.dumpViewsResponse
      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }
      val testRoot = findNodeByClassName(dumpResponse.getNodes(0), "LinearLayout", stringTable)

      assertThat(testRoot).isNotNull()
      val child1 = testRoot!!.getChildren(0)
      assertThat(child1.bounds.width).isGreaterThan(0)
    }

  @Test
  fun testDumpViews_resources() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      val root = LinearLayout(activity).apply { id = android.R.id.content }
      activity.setContentView(root)

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      val dumpResponse = response.dumpViewsResponse
      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }

      val testRoot = findNodeByClassName(dumpResponse.getNodes(0), "LinearLayout", stringTable)
      assertThat(testRoot).isNotNull()

      val resource = testRoot!!.idResource
      assertThat(resource).isNotEqualTo(0)
      assertThat(stringTable[resource]).isEqualTo("@android:id/content")
    }

  @Test
  fun testDumpViews_layoutResource() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      val root = TestView(activity)
      activity.setContentView(root)

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      val dumpResponse = response.dumpViewsResponse
      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }

      val testViewNode = findNodeByClassName(dumpResponse.getNodes(0), "TestView", stringTable)
      assertThat(testViewNode).isNotNull()

      val layoutResource = testViewNode!!.layoutResource
      assertThat(layoutResource).isNotEqualTo(0)
      assertThat(stringTable[layoutResource]).isEqualTo("@android:layout/simple_list_item_1")
    }

  @Test
  fun testDumpViews_nestedHierarchy() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      val root =
        LinearLayout(activity).apply { addView(LinearLayout(activity).apply { addView(TextView(activity).apply { text = "Nested" }) }) }
      activity.setContentView(root)

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      val dumpResponse = response.dumpViewsResponse
      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }

      val topLinearLayout = findNodeByClassName(dumpResponse.getNodes(0), "LinearLayout", stringTable)
      assertThat(topLinearLayout).isNotNull()
      assertThat(topLinearLayout!!.childrenCount).isEqualTo(1)

      val nestedLinearLayout = topLinearLayout.getChildren(0)
      assertThat(stringTable[nestedLinearLayout.className]).isEqualTo("LinearLayout")
      assertThat(nestedLinearLayout.childrenCount).isEqualTo(1)

      val textView = nestedLinearLayout.getChildren(0)
      assertThat(stringTable[textView.className]).isEqualTo("TextView")
    }

  @Test
  fun testDumpViews_multipleRoots() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      activity.setContentView(setupViews(activity))

      // Create a second window (Dialog)
      val dialog = android.app.Dialog(activity)
      dialog.setContentView(TextView(activity).apply { text = "Dialog Text" })
      dialog.show()

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      val dumpResponse = response.dumpViewsResponse

      // We expect at least 2 roots now (Activity DecorView and Dialog DecorView)
      assertThat(dumpResponse.nodesCount).isAtLeast(2)

      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }

      // Verify we can find elements from both roots
      val dialogTextView = findNodeByClassName(dumpResponse.getNodes(1), "TextView", stringTable)
      assertThat(dialogTextView).isNotNull()

      dialog.dismiss()
    }

  private fun setupViews(activity: Activity): View {
    return LinearLayout(activity).apply {
      id = 100
      addView(
        TextView(activity).apply {
          id = 101
          text = "Hello"
          visibility = View.VISIBLE
        }
      )
      addView(
        TextView(activity).apply {
          id = 102
          text = "World"
          visibility = View.INVISIBLE
        }
      )
      addView(
        View(activity).apply {
          id = 103
          visibility = View.GONE
        }
      )
    }
  }

  private fun runDumpCommand(inspector: ViewInspector, includeAttributes: Boolean = true): Response {
    var replyData: ByteArray? = null
    val callback =
      object : Inspector.CommandCallback {
        override fun reply(response: ByteArray) {
          replyData = response
        }

        override fun addCancellationListener(executor: Executor, runnable: Runnable) {}
      }
    val command =
      Command.newBuilder()
        .setDumpViewsCommand(ViewInspectorProtocol.DumpViewsCommand.newBuilder().setIncludeAttributes(includeAttributes).build())
        .build()
    inspector.onReceiveCommand(command.toByteArray(), callback)
    // Idle the main looper to ensure tasks posted to the main thread (e.g., by MainThreadExecutor)
    // are executed before we parse the reply.
    shadowOf(Looper.getMainLooper()).idle()
    return Response.parseFrom(replyData!!)
  }

  private fun findNodeByClassName(node: ViewNode, className: String, stringTable: Map<Int, String>): ViewNode? {
    if (stringTable[node.className] == className) return node
    for (child in node.childrenList) {
      val found = findNodeByClassName(child, className, stringTable)
      if (found != null) return found
    }
    return null
  }

  @Test
  fun testDumpViews_gravity() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val textView = TextView(activity).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
    activity.setContentView(textView)

    val inspector =
      ViewInspector(
        object : Connection() {
          override fun sendEvent(data: ByteArray) {}
        },
        mockEnvironment,
      )
    val response = runDumpCommand(inspector)

    val dumpResponse = response.dumpViewsResponse
    val stringTable = dumpResponse.stringsList.associate { it.id to it.value }

    val textViewNode = findNodeByClassName(dumpResponse.getNodes(0), "TextView", stringTable)
    assertThat(textViewNode).isNotNull()

    // Verify that we have attributes
    assertThat(textViewNode!!.attributesCount).isAtLeast(1)

    // Verify that we can find the "gravity" attribute
    val gravityAttr = textViewNode.attributesList.find { stringTable[it.name] == "gravity" }
    assertThat(gravityAttr).isNotNull()

    // Verify that the value is resolved correctly as flags joined by "|"
    assertThat(stringTable[gravityAttr!!.int32Value]).isEqualTo("top|start")
  }

  private class TestView(context: Context) : View(context) {
    override fun getSourceLayoutResId(): Int = android.R.layout.simple_list_item_1
  }

  @Test
  fun testDumpViews_excludeAttributes() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      val textView = TextView(activity).apply { gravity = android.view.Gravity.TOP }
      activity.setContentView(textView)

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector, includeAttributes = false)

      val dumpResponse = response.dumpViewsResponse
      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }

      val textViewNode = findNodeByClassName(dumpResponse.getNodes(0), "TextView", stringTable)
      assertThat(textViewNode).isNotNull()

      // Verify that we have NO attributes collected
      assertThat(textViewNode!!.attributesCount).isEqualTo(0)
    }

  @Test
  fun testDumpViews_configuration() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      val config = activity.resources.configuration
      config.densityDpi = 320
      config.orientation = AndroidResConfiguration.ORIENTATION_LANDSCAPE
      config.screenLayout =
        AndroidResConfiguration.SCREENLAYOUT_SIZE_LARGE or
          AndroidResConfiguration.SCREENLAYOUT_LONG_YES or
          AndroidResConfiguration.SCREENLAYOUT_LAYOUTDIR_RTL or
          AndroidResConfiguration.SCREENLAYOUT_ROUND_YES
      config.colorMode = AndroidResConfiguration.COLOR_MODE_WIDE_COLOR_GAMUT_YES or AndroidResConfiguration.COLOR_MODE_HDR_YES
      config.touchscreen = AndroidResConfiguration.TOUCHSCREEN_FINGER
      config.keyboard = AndroidResConfiguration.KEYBOARD_QWERTY
      config.keyboardHidden = AndroidResConfiguration.KEYBOARDHIDDEN_YES
      config.hardKeyboardHidden = AndroidResConfiguration.HARDKEYBOARDHIDDEN_YES
      config.navigation = AndroidResConfiguration.NAVIGATION_DPAD
      config.navigationHidden = AndroidResConfiguration.NAVIGATIONHIDDEN_YES
      config.uiMode = AndroidResConfiguration.UI_MODE_TYPE_CAR or AndroidResConfiguration.UI_MODE_NIGHT_YES
      config.screenWidthDp = 1024
      config.screenHeightDp = 768
      config.smallestScreenWidthDp = 768

      if (Build.VERSION.SDK_INT >= 24) {
        config.setLocales(android.os.LocaleList(Locale("ar")))
      } else {
        @Suppress("DEPRECATION")
        config.locale = Locale("ar")
      }
      config.setLayoutDirection(Locale("ar"))

      if (Build.VERSION.SDK_INT >= 34) {
        try {
          // Use reflection to bypass read-only limitations
          val field = AndroidResConfiguration::class.java.getDeclaredField("grammaticalGender")
          field.isAccessible = true
          field.set(config, AndroidResConfiguration.GRAMMATICAL_GENDER_FEMININE)
        } catch (_: Exception) {}
      }

      activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
      activity.setContentView(setupViews(activity))

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      assertThat(response.specializedCase).isEqualTo(Response.SpecializedCase.DUMP_VIEWS_RESPONSE)
      val dumpResponse = response.dumpViewsResponse
      assertThat(dumpResponse.hasConfiguration()).isTrue()

      val configuration = dumpResponse.configuration
      assertThat(configuration.density).isEqualTo(320)
      assertThat(configuration.orientation).isEqualTo(ViewInspectorProtocol.Orientation.ORIENTATION_LANDSCAPE)
      assertThat(configuration.screenLayoutSize).isEqualTo(ViewInspectorProtocol.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_LARGE)
      assertThat(configuration.screenLayoutLong).isEqualTo(ViewInspectorProtocol.ScreenLayoutLong.SCREEN_LAYOUT_LONG_YES)
      assertThat(configuration.layoutDirection).isEqualTo(ViewInspectorProtocol.LayoutDirection.LAYOUT_DIRECTION_RTL)
      assertThat(configuration.screenLayoutRound).isEqualTo(ViewInspectorProtocol.ScreenLayoutRound.SCREEN_LAYOUT_ROUND_YES)
      assertThat(configuration.colorModeWideGamut).isEqualTo(ViewInspectorProtocol.ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_YES)
      assertThat(configuration.colorModeHdr).isEqualTo(ViewInspectorProtocol.ColorModeHdr.COLOR_MODE_HDR_YES)
      assertThat(configuration.touchScreen).isEqualTo(ViewInspectorProtocol.TouchScreen.TOUCH_SCREEN_FINGER)
      assertThat(configuration.keyboard).isEqualTo(ViewInspectorProtocol.Keyboard.KEYBOARD_QWERTY)
      assertThat(configuration.keyboardHidden).isEqualTo(ViewInspectorProtocol.KeyboardHidden.KEYBOARD_HIDDEN_YES)
      assertThat(configuration.hardKeyboardHidden).isEqualTo(ViewInspectorProtocol.HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_YES)
      assertThat(configuration.navigation).isEqualTo(ViewInspectorProtocol.Navigation.NAVIGATION_DPAD)
      assertThat(configuration.navigationHidden).isEqualTo(ViewInspectorProtocol.NavigationHidden.NAVIGATION_HIDDEN_YES)
      assertThat(configuration.uiModeType).isEqualTo(ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_CAR)
      assertThat(configuration.uiModeNight).isEqualTo(ViewInspectorProtocol.UiModeNight.UI_MODE_NIGHT_YES)
      assertThat(configuration.screenWidthDp).isEqualTo(1024)
      assertThat(configuration.screenHeightDp).isEqualTo(768)
      assertThat(configuration.smallestScreenWidthDp).isEqualTo(768)

      if (Build.VERSION.SDK_INT >= 34) {
        assertThat(configuration.grammaticalGender).isEqualTo(ViewInspectorProtocol.GrammaticalGender.GRAMMATICAL_GENDER_FEMININE)
      }

      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }
      assertThat(stringTable[configuration.locale.language]).isEqualTo("ar")
      assertThat(configuration.locale.country).isEqualTo(0)
    }

  @Test
  fun testDumpViews_appContext() =
    runTest(testDispatcher) {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      activity.setTheme(android.R.style.Theme_Material)
      activity.setContentView(setupViews(activity))

      val inspector =
        ViewInspector(
          object : Connection() {
            override fun sendEvent(data: ByteArray) {}
          },
          mockEnvironment,
        )
      val response = runDumpCommand(inspector)

      assertThat(response.specializedCase).isEqualTo(Response.SpecializedCase.DUMP_VIEWS_RESPONSE)
      val dumpResponse = response.dumpViewsResponse
      assertThat(dumpResponse.hasAppContext()).isTrue()

      val appContext = dumpResponse.appContext
      val stringTable = dumpResponse.stringsList.associate { it.id to it.value }

      // Verify theme string resolution
      assertThat(stringTable[appContext.theme]).isEqualTo("@android:style/Theme.Material")

      // Verify display info presence
      assertThat(appContext.displayInfoCount).isAtLeast(1)
      val display = appContext.getDisplayInfo(0)
      assertThat(display.widthPx).isGreaterThan(0)
      assertThat(display.heightPx).isGreaterThan(0)
    }
}
