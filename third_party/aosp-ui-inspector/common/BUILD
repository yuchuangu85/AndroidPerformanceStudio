load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:kotlin.bzl", "kotlin_test")

package(default_visibility = ["//tools/base/ui-inspector:__subpackages__"])

java_library(
    name = "common",
    srcs = glob(["src/main/java/**/*.java"]),
    visibility = ["//visibility:public"],
)

kotlin_test(
    name = "common_tests",
    srcs = glob(["src/test/java/**/*.kt"]),
    jvm_flags = ["-Dtest.suite.jar=common_tests.jar"],
    test_class = "com.android.testutils.JarTestSuite",
    deps = [
        ":common",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/testutils:tools.testutils",
    ],
)
