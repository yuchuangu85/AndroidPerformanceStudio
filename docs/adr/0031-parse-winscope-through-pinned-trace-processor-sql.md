# Parse Winscope through pinned Trace Processor SQL

Android 15+ Winscope traces are parsed through the repository's version-pinned Trace Processor and mapped from its Winscope SQL tables and modules into Kotlin inspection models. We will not maintain a second raw protobuf parser: schema compatibility stays tied to the pinned Trace Processor version, so exposing newer platform fields requires a deliberate tool and query-schema upgrade instead of parallel parser maintenance.
