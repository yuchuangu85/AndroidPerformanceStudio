load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:android.bzl", "dex_library")
load("//tools/base/bazel:robolectric.bzl", "robolectric_test")

package(default_visibility = ["//tools/base/ui-inspector:__subpackages__"])

java_library(
    name = "ui_inspector_payload_src",
    srcs = glob([
        "src/main/java/**/*.java",
    ]),
    deps = [
        "//prebuilts/studio/sdk:platforms/latest_jar",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//tools/base/bazel:studio-proto",
        "//tools/base/ui-inspector/agent/service:ui_inspector_service_src",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
    ],
)

dex_library(
    name = "lib_ui_inspector_payload",
    aars = [
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
    ],
    jars = [
        ":ui_inspector_payload_src",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
        "//tools/base/bazel:studio-proto",
    ],
)

robolectric_test(
    name = "payload_robolectric_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    deps = [
        ":ui_inspector_payload_src",
        "//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/bazel:studio-proto",
        "//tools/base/testutils:tools.testutils",
        "//tools/base/ui-inspector/agent/service:ui_inspector_service_src",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto",
        "@maven//:org.mockito.mockito-core",
        "@maven//:org.robolectric.robolectric",
    ],
)
