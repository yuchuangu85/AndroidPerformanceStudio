load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:android.bzl", "dex_library")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel:merge_archives.bzl", "merge_jars")
load("//tools/base/bazel:robolectric.bzl", "robolectric_test")
load("//tools/base/bazel:utils.bzl", "java_jarjar")

package(default_visibility = ["//tools/base/ui-inspector:__subpackages__"])

kotlin_library(
    name = "view_inspector",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    resource_strip_prefix = "tools/base/ui-inspector/agent/inspectors/view/src/main/resources",
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "//prebuilts/studio/sdk:platforms/latest_jar",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//tools/base/bazel:studio-proto",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core-jvm",
    ],
)

robolectric_test(
    name = "view_inspector_tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    friends = [":view_inspector"],
    deps = [
        ":view_inspector",
        "//prebuilts/studio/sdk:platforms/latest_jar",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/bazel:studio-proto",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core-jvm",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-test-jvm",
        "@maven//:org.robolectric.robolectric",
    ],
)

java_library(
    name = "view_inspector_resources",
    resource_strip_prefix = "tools/base/ui-inspector/agent/inspectors/view/src/main/resources",
    resources = glob(["src/main/resources/**/*"]),
    visibility = ["//tools/base/ui-inspector:__subpackages__"],
)

merge_jars(
    name = "view_inspector_unjarjared",
    out = "view_inspector_unjarjared.jar",
    jars = [
        ":view_inspector",
        "//tools/base/ui-inspector/common",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core-jvm",
    ],
)

java_jarjar(
    name = "view_inspector_jarjared",
    srcs = [":view_inspector_unjarjared"],
    rules = "jarjar_rules.txt",
)

dex_library(
    name = "view_inspector_dexed",
    jars = [":view_inspector_jarjared"],
)

merge_jars(
    name = "view_inspector_jar",
    out = "view-inspector.jar",
    jars = [
        ":view_inspector_dexed",
        ":view_inspector_resources",
    ],
)
