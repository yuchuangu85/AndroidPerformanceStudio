load("//tools/base/bazel:proto.bzl", "android_java_proto_library", "java_proto_library")

package(default_visibility = ["//visibility:public"])

java_proto_library(
    name = "ui_inspector_protocol_java_proto-nojarjar",
    srcs = glob(["*.proto"]),
)

android_java_proto_library(
    name = "ui_inspector_protocol_java_proto",
    srcs = glob(["*.proto"]),
)
