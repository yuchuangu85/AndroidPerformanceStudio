package com.androidperformancestudio.ai

public interface CredentialStore {
    public fun read(key: String): String?

    public fun write(
        key: String,
        value: String,
    )

    public fun delete(key: String)
}

public class InMemoryCredentialStore : CredentialStore {
    private val values: MutableMap<String, String> = mutableMapOf()

    override fun read(key: String): String? = values[key]

    override fun write(
        key: String,
        value: String,
    ) {
        require(key.isNotBlank() && value.isNotBlank())
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}

public class MacOsKeychainCredentialStore(
    private val service: String = "AndroidPerformanceStudio",
) : CredentialStore {
    override fun read(key: String): String? =
        command("security", "find-generic-password", "-a", key, "-s", service, "-w")
            .takeIf { it.exitCode == 0 }
            ?.output
            ?.trim()

    override fun write(
        key: String,
        value: String,
    ) {
        val result = command("security", "add-generic-password", "-U", "-a", key, "-s", service, "-w", value)
        check(result.exitCode == 0) { "Unable to store credential in macOS Keychain" }
    }

    override fun delete(key: String) {
        command("security", "delete-generic-password", "-a", key, "-s", service)
    }

    private fun command(vararg arguments: String): CommandResult =
        runCatching {
            val process = ProcessBuilder(arguments.toList()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            CommandResult(process.waitFor(), output)
        }.getOrElse { CommandResult(-1, "") }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
    )
}
