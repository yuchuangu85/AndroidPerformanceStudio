package com.androidperformancestudio.platform.toolchain

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.system.exitProcess

object HostProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.firstOrNull()) {
            "binary" -> System.out.write(byteArrayOf(0xff.toByte(), 0, 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
            "context" -> print("${System.getenv("APS_TEST_VALUE")}|${Path.of("").toAbsolutePath()}")
            "exit" -> exitProcess(arguments.getOrNull(1)?.toIntOrNull() ?: 1)
            "flood" -> repeat(10_000) { print("output-$it\n") }
            "sleep" -> Thread.sleep(30_000)
            "spawn-child" -> spawnChild(Path.of(requireNotNull(arguments.getOrNull(1))))
            "streams" -> {
                print("stdout")
                System.err.print("stderr")
            }
            else -> error("Unknown fixture command")
        }
    }

    private fun spawnChild(pidFile: Path) {
        val child =
            ProcessBuilder(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                HostProcessFixture::class.qualifiedName,
                "sleep",
            ).start()
        pidFile.writeText(child.pid().toString())
        child.waitFor()
    }

    private fun javaExecutable(): Path {
        val name = if (System.getProperty("os.name").contains("windows", true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", name)
    }
}
