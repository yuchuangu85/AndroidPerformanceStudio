# Require an explicit user trigger for cloud analysis

The first release starts cloud AI only from an explicit user action followed by a preflight that identifies the performance evidence, bound source snapshots, build evidence, model, and source payload scope. Importing data, changing tabs, hovering, or selecting frames never sends a request; running analysis supports cancellation, timeout, and retry. Automatic analysis may be added only as a separately authorized workspace policy.
