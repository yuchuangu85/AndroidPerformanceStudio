# Debug-only Network Agent v2

Add the Android Agent to the debuggable App and attach
`NetworkProfiler.eventListenerFactory(existingFactory)` to each OkHttp client
that should be observed. AndroidX Startup starts the listener automatically.
This is not device-wide capture and does not cover uninstrumented clients,
WebView, Cronet, URLConnection or native sockets.

The listener binds IPv4 loopback port `49373` before atomically publishing the
private `files/aps-network/token`. The fixed port supports one listening App
process on the device at a time. Another App/process using that port produces
`NetworkProfilerStatus.BIND_FAILED`; it does not replace or delete another
process's token, and startup failure does not crash the profiled App.

Use `NetworkProfiler.status()` for a fixed non-sensitive diagnostic. After
resolving a port/storage/listener failure, explicitly call
`NetworkProfiler.start(context)` to retry. There is no implicit retry loop.
Startup/listener failures log only an allowlisted status code under
`APSNetworkAgent`, never tokens, paths, raw traffic or exception messages.

Client EOF, malformed frames, failed writes and a 10-second idle read timeout
close that client only; a later authenticated capture can reconnect. Listener
failure resets ownership and removes only its own token, while still holding
its port. Call IDs use a process-local atomic sequence rather than hash/time
collisions. Event buffering and v2 STOP boundaries remain bounded.

These lifecycle/error paths require follow-up Android/JVM and device validation;
compilation or source review alone does not establish runtime behavior.
