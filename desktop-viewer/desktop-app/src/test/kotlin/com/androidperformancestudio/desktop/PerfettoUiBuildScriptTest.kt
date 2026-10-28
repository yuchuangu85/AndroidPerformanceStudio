package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PerfettoUiBuildScriptTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `source build replaces an existing release bundle in the APS consumed directory`() {
        assumeFalse(System.getProperty("os.name").contains("Windows", ignoreCase = true))
        val script = directory.resolve("scripts/build-perfetto-ui.sh")
        Files.createDirectories(script.parent)
        Files.copy(Path.of("../../scripts/build-perfetto-ui.sh"), script)
        val perfetto = directory.resolve("third_party/perfetto")
        Files.createDirectories(perfetto.resolve("ui/node_modules"))
        Files.writeString(perfetto.resolve("ui/package.json"), "{}")
        val bundle = perfetto.resolve("out/ui/dist/index.html")
        Files.createDirectories(bundle.parent)
        Files.writeString(bundle, "stale-release")
        val bin = directory.resolve("bin")
        writeExecutable(
            bin.resolve("git"),
            """
            #!/usr/bin/env bash
            case "${'$'}*" in
              *"ls-files --stage"*) printf '160000 fixture 0\tthird_party/perfetto\n' ;;
              *"rev-parse HEAD"*) printf 'fixture\n' ;;
              *) exit 1 ;;
            esac
            """.trimIndent(),
        )
        writeExecutable(
            perfetto.resolve("buildtools/mac/nodejs/bin/node"),
            "#!/usr/bin/env bash\nprintf 'v22.23.1\\n'\n",
        )
        // Reproduce upstream's default nesting: <out>/ui/dist, not <out>/dist.
        writeExecutable(
            perfetto.resolve("ui/build"),
            """
            #!/usr/bin/env bash
            set -euo pipefail
            output="out/ui"
            while [[ ${'$'}# -gt 0 ]]; do
              case "${'$'}1" in
                --out) output="${'$'}2"; shift 2 ;;
                *) shift ;;
              esac
            done
            mkdir -p "${'$'}output/ui/dist"
            printf 'rebuilt-release' > "${'$'}output/ui/dist/index.html"
            """.trimIndent(),
        )
        val builder = ProcessBuilder("bash", script.toString(), "build").redirectErrorStream(true)
        builder.environment()["PATH"] = "$bin:${System.getenv("PATH")}"
        val process = builder.start()
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue(completed, "Perfetto build script did not finish")
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), output)
        assertEquals("rebuilt-release", Files.readString(bundle), output)
    }

    private fun writeExecutable(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        assertTrue(path.toFile().setExecutable(true), "Unable to make fixture executable: $path")
    }
}
