# Separate platform jank signals from frame deadline misses

Frame analysis preserves Platform Jank Signals and Frame Deadline Misses as separate evidence and reports separate rates instead of collapsing them into one verdict. Platform signals depend on Android or JankStats version and heuristic configuration, while deadline misses depend on an explicit per-frame budget; keeping both identities prevents source changes from silently changing historical metric meaning, and Perfetto FrameTimeline remains the required evidence for attributing missed frames to app, SurfaceFlinger, or GPU work.
