# Use installed NDK symbolization with graceful degradation

Native source resolution discovers `llvm-symbolizer` from configured Android SDK/NDK installations or an explicit user path and combines it with Build IDs and debug symbols. The application does not bundle a complete LLVM/NDK toolchain in the first release; when tools or symbols are missing it may still produce function or file candidates, but never labels an unverified line as exact. This keeps packaging manageable while preserving an evidence-based upgrade path to precise C/C++ locations.
