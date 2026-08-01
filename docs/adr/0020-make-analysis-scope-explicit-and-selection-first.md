# Make analysis scope explicit and selection-first

AI analysis records an explicit scope and defaults to the current Layout Inspector node or Simpleperf function, stack, thread, and time range when one exists; users may switch to a bounded report summary. Profiler evidence adapters aggregate and budget data locally, report omissions rather than silently truncating them, and persist the exact evidence IDs used. This keeps requests focused and repeatable while retaining an intentional whole-report workflow.
