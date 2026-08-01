# Use a common analysis envelope with profiler evidence adapters

Layout Inspector and Simpleperf produce profiler-specific performance evidence through adapters but persist AI output in a common analysis-session and finding envelope. Findings identify their origin profiler and reference evidence IDs and resolver-created source candidate IDs alongside analysis confidence and model/prompt provenance. Initial requests remain profiler-scoped, while the shared envelope preserves a path to later cross-profiler correlation without changing historical result formats.
