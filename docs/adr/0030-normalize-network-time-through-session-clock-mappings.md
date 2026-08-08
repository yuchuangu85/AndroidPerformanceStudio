# Normalize network time through session clock mappings

Network evidence preserves its source time domain and raw timestamp while analysis normalizes Call and Exchange time to a session-relative monotonic origin. Wall-clock display and cross-profiler correlation are derived only through a measured Clock Mapping with an explicit error bound; this adds mapping metadata but prevents device monotonic timestamps, HAR-relative offsets, and host wall time from being silently combined into unreproducible timelines.
