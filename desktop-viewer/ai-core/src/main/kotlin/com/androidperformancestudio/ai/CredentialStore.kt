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

public class MacOsKeychainCredentialStore private constructor(
    private val service: String,
    private val execute: (List<String>) -> Pair<Int, String>,
) : CredentialStore {
    public constructor(service: String = "AndroidPerformanceStudio") : this(service, ::executeKeychainCommand)

    internal constructor(execute: (List<String>) -> Pair<Int, String>) : this("AndroidPerformanceStudio", execute)

    override fun read(key: String): String? {
        val result = execute(listOf("security", "find-generic-password", "-a", key, "-s", service, "-w"))
        if (result.first == KEYCHAIN_ITEM_NOT_FOUND) return null
        check(result.first == 0) { "E_CREDENTIAL_READ" }
        return result.second.trim()
    }

    override fun write(
        key: String,
        value: String,
    ) {
        val result = execute(listOf("security", "add-generic-password", "-U", "-a", key, "-s", service, "-w", value))
        check(result.first == 0) { "E_CREDENTIAL_WRITE" }
    }

    override fun delete(key: String) {
        val result = execute(listOf("security", "delete-generic-password", "-a", key, "-s", service))
        check(result.first == 0 || result.first == KEYCHAIN_ITEM_NOT_FOUND) { "E_CREDENTIAL_DELETE" }
    }
}

private const val KEYCHAIN_ITEM_NOT_FOUND = 44

private fun executeKeychainCommand(arguments: List<String>): Pair<Int, String> =
    runCatching {
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() to output
    }.getOrElse { -1 to "" }
