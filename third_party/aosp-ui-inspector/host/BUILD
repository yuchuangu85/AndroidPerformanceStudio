load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

# managed by go/iml_to_build
iml_module(
    name = "studio.android.sdktools.ui-inspector",
    srcs = ["src/main/java"],
    iml_files = ["android.sdktools.ui-inspector.iml"],
    test_deps = [
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
    deps = [
        # Add Compose Inspector protobuf Java classes
        "//prebuilts/tools/common/app-inspection/androidx/compose/ui:layout_inspector_compose_java_proto-nojarjar",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/adblib-tools:studio.android.sdktools.adblib.tools",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto-nojarjar",
        "@maven//:com.google.code.gson.gson",
        "@maven//:com.google.guava.guava",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:info.picocli.picocli",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

kotlin_library(
    name = "ui-inspector-host",
    srcs = glob([
        "src/main/java/**/*.kt",
        "src/main/java/**/*.java",
    ]),
    resources = [
        "//tools/base/ui-inspector/agent/inspectors/view:view_inspector_jar",
        "//tools/base/ui-inspector/agent/native:lib_ui_inspector_agent.so",
        "//tools/base/ui-inspector/agent/payload:lib_ui_inspector_payload",
        "//tools/base/ui-inspector/agent/service:lib_ui_inspector_service",
    ],
    visibility = ["//visibility:public"],
    deps = [
        "//prebuilts/tools/common/app-inspection/androidx/compose/ui:layout_inspector_compose_java_proto-nojarjar",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/base/adblib-tools:studio.android.sdktools.adblib.tools",
        "//tools/base/common:tools.common",
        "//tools/base/ui-inspector/common",
        "//tools/base/ui-inspector/common/proto:ui_inspector_protocol_java_proto-nojarjar",
        "@maven//:com.google.code.gson.gson",
        "@maven//:com.google.guava.guava",
        "@maven//:com.google.protobuf.protobuf-java",
        "@maven//:info.picocli.picocli",
        "@maven//:org.jetbrains.kotlin.kotlin-stdlib",
        "@maven//:org.jetbrains.kotlinx.kotlinx-coroutines-core",
    ],
)

java_binary(
    name = "cli",
    # Needed during development to make the agent available in runfiles when running via 'bazel run'
    data = [
        "//tools/base/ui-inspector/agent/inspectors/view:view_inspector_jar",
        "//tools/base/ui-inspector/agent/native:lib_ui_inspector_agent.so",
        "//tools/base/ui-inspector/agent/payload:lib_ui_inspector_payload",
        "//tools/base/ui-inspector/agent/service:lib_ui_inspector_service",
    ],
    main_class = "com.android.tools.ui.inspector.CliHostKt",
    visibility = ["//visibility:public"],
    runtime_deps = [":ui-inspector-host"],
)
