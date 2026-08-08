load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:android.bzl", "dex_library")

package(default_visibility = ["//visibility:public"])

java_library(
    name = "ui_inspector_service_src",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = [
        "//prebuilts/studio/sdk:platforms/latest_jar",
    ],
)

dex_library(
    name = "lib_ui_inspector_service",
    jars = [
        ":ui_inspector_service_src",
    ],
)
