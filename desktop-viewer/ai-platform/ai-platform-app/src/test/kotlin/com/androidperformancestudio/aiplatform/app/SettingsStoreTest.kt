package com.androidperformancestudio.aiplatform.app

import com.androidperformancestudio.ai.ReportLanguage
import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingsStoreTest {
    @Test
    fun `new settings and empty profiles default to HTML reports with automatic language`() {
        val preferences = MemoryPreferences().node(UUID.randomUUID().toString())
        val store = AiPlatformSettingsStore(preferences)

        assertEquals(ArtifactFormat.HTML, AiPlatformSettings().reportFormat)
        assertEquals(ReportLanguage.AUTO, AiPlatformSettings().reportLanguage)
        assertEquals(AiPlatformSettings(), store.load())
    }

    @Test
    fun `report languages persist enum names and invalid values fall back to automatic`() {
        for (language in ReportLanguage.entries) {
            val preferences = MemoryPreferences().node(UUID.randomUUID().toString())
            val expected = AiPlatformSettings("openai", "report-model", reportLanguage = language)
            AiPlatformSettingsStore(preferences).save(expected)

            assertEquals(language.name, preferences.get("reportLanguage", null))
            assertEquals(expected, AiPlatformSettingsStore(preferences).load())
        }
        for (invalid in listOf("CHINESE", "english", "")) {
            val preferences = MemoryPreferences().node(UUID.randomUUID().toString())
            preferences.put("provider", "codex")
            preferences.put("model", "retained-model")
            preferences.put("reportLanguage", invalid)

            assertEquals(AiPlatformSettings("codex", "retained-model"), AiPlatformSettingsStore(preferences).load())
        }
    }

    @Test
    fun `legacy profiles default to HTML without losing saved settings`() {
        val preferences = MemoryPreferences().node(UUID.randomUUID().toString())
        preferences.put("provider", "codex")
        preferences.put("model", "legacy-model")
        preferences.putBoolean("artifacts", true)
        preferences.put("agent", "aps.report")
        preferences.putBoolean("scripts", true)
        val expected = AiPlatformSettings("codex", "legacy-model", true, "aps.report", true)

        assertEquals(ArtifactFormat.HTML, expected.reportFormat)
        assertEquals(expected, AiPlatformSettingsStore(preferences).load())
    }

    @Test
    fun `report formats persist enum names and reload from preferences`() {
        for (format in ArtifactFormat.entries) {
            val preferences = MemoryPreferences().node(UUID.randomUUID().toString())
            val writer = AiPlatformSettingsStore(preferences)
            val expected = AiPlatformSettings("claude", "report-model", true, "aps.report", true, format)

            writer.save(expected)

            assertEquals(format.name, preferences.get("reportFormat", null))
            assertEquals(expected, writer.load())
            // A fresh path bypasses the process-wide shared state cache.
            val reloadedPreferences = MemoryPreferences().node(UUID.randomUUID().toString())
            preferences.keys().forEach { key -> reloadedPreferences.put(key, preferences.get(key, "")) }
            assertEquals(expected, AiPlatformSettingsStore(reloadedPreferences).load())
        }
    }

    @Test
    fun `invalid report formats fall back to HTML without losing other saved settings`() {
        for (format in listOf("PDF", "markdown", "")) {
            val preferences = MemoryPreferences().node(UUID.randomUUID().toString())
            preferences.put("provider", "claude")
            preferences.put("model", "retained-model")
            preferences.putBoolean("artifacts", true)
            preferences.put("agent", "aps.report")
            preferences.putBoolean("scripts", true)
            preferences.put("reportFormat", format)
            preferences.put("unrelated", "retained")
            val expected = AiPlatformSettings("claude", "retained-model", true, "aps.report", true)

            assertEquals(expected, AiPlatformSettingsStore(preferences).load())
            assertEquals("retained", preferences.get("unrelated", null))
        }
    }

    @Test
    fun `stores at the same preference path share saved settings without affecting other paths`() =
        runBlocking {
            val preferences = MemoryPreferences().node(UUID.randomUUID().toString())
            val first = AiPlatformSettingsStore(preferences)
            val second = AiPlatformSettingsStore(preferences.parent().node(preferences.name()))
            val isolated = AiPlatformSettingsStore(MemoryPreferences().node(UUID.randomUUID().toString()))
            val changed = AiPlatformSettings("codex", "shared-model", allowArtifacts = true, reportFormat = ArtifactFormat.MARKDOWN)
            val observed = async(start = CoroutineStart.UNDISPATCHED) { second.settings.first { it == changed } }

            first.save(changed)

            assertEquals(changed, withTimeout(5000) { observed.await() })
            assertEquals(changed, first.settings.value)
            assertEquals(changed, second.settings.value)
            assertEquals(changed, AiPlatformSettingsStore(preferences).settings.value)
            assertEquals(AiPlatformSettings(), isolated.settings.value)

            val next = changed.copy(model = "second-store-model")
            second.save(next)
            assertEquals(next, first.settings.value)
        }

    @Test
    fun `settings publish only after successful flush and failed saves preserve the shared value`() {
        val preferences = MemoryPreferences().node(UUID.randomUUID().toString()) as MemoryPreferences
        val writer = AiPlatformSettingsStore(preferences)
        val observer = AiPlatformSettingsStore(preferences)
        val original = AiPlatformSettings("openai", "original-model", reportFormat = ArtifactFormat.MARKDOWN)
        writer.save(original)
        val before = preferences.keys().associateWith { preferences.get(it, null) }
        preferences.onFlush = { assertEquals(original, observer.settings.value) }
        preferences.failFlush = true

        assertFailsWith<BackingStoreException> {
            writer.save(original.copy(model = "failed-model", reportFormat = ArtifactFormat.HTML))
        }
        assertEquals(before, preferences.keys().associateWith { preferences.get(it, null) })
        assertEquals(original, writer.settings.value)
        assertEquals(original, observer.settings.value)
        assertEquals(original, AiPlatformSettingsStore(preferences).settings.value)
        assertEquals(original, writer.load())
        assertEquals(original, observer.load())

        preferences.failFlush = false
        val saved = original.copy(model = "saved-model")
        writer.save(saved)
        assertEquals(saved, observer.settings.value)
        assertEquals("MARKDOWN", preferences.get("reportFormat", null))
    }

    @Test
    fun `failed update restores exact persisted strings and removes newly introduced keys`() {
        val preferences = MemoryPreferences().node(UUID.randomUUID().toString()) as MemoryPreferences
        preferences.put("provider", "openai")
        preferences.put("artifacts", "TRUE")
        preferences.put("unrelated", "retained")
        val before = preferences.keys().associateWith { preferences.get(it, null) }
        val writer = AiPlatformSettingsStore(preferences)
        val original = writer.settings.value
        preferences.failFlush = true

        assertFailsWith<BackingStoreException> {
            writer.update {
                it.copy(
                    providerId = "codex",
                    model = "not-saved",
                    agentId = "aps.report",
                    allowArtifacts = false,
                    allowScripts = true,
                    reportFormat = ArtifactFormat.MARKDOWN,
                )
            }
        }

        assertEquals(before, preferences.keys().associateWith { preferences.get(it, null) })
        assertEquals(original, writer.settings.value)
        assertEquals(original, writer.load())
    }

    @Test
    fun `partial put failure restores all existing values and never publishes failed settings`() {
        val preferences = MemoryPreferences().node(UUID.randomUUID().toString()) as MemoryPreferences
        val writer = AiPlatformSettingsStore(preferences)
        val original = AiPlatformSettings("openai", "original-model", agentId = "aps.report", allowScripts = true)
        writer.save(original)
        val before = preferences.keys().associateWith { preferences.get(it, null) }
        preferences.failPutKey = "agent"

        assertFailsWith<IllegalStateException> {
            writer.save(original.copy(providerId = "codex", model = "not-saved", allowArtifacts = true, agentId = "aps.main"))
        }

        assertEquals(before, preferences.keys().associateWith { preferences.get(it, null) })
        assertEquals(original, writer.settings.value)
        assertEquals(original, writer.load())
    }

    @Test
    fun `partial format write failure restores existing format or removes newly introduced key`() {
        for (format in listOf(null, ArtifactFormat.HTML, ArtifactFormat.MARKDOWN)) {
            val preferences = MemoryPreferences().node(UUID.randomUUID().toString()) as MemoryPreferences
            val writer = AiPlatformSettingsStore(preferences)
            val observer = AiPlatformSettingsStore(preferences)
            val original = AiPlatformSettings("openai", "original-model", reportFormat = format ?: ArtifactFormat.HTML)
            writer.save(original)
            if (format == null) preferences.remove("reportFormat")
            val before = preferences.keys().associateWith { preferences.get(it, null) }
            preferences.failAfterPutKey = "reportFormat"

            assertFailsWith<IllegalStateException> {
                writer.update {
                    it.copy(
                        providerId = "codex",
                        model = "not-saved",
                        allowArtifacts = true,
                        agentId = "aps.report",
                        allowScripts = true,
                        reportFormat = if (it.reportFormat == ArtifactFormat.HTML) ArtifactFormat.MARKDOWN else ArtifactFormat.HTML,
                    )
                }
            }

            assertEquals(before, preferences.keys().associateWith { preferences.get(it, null) })
            assertEquals(original, writer.settings.value)
            assertEquals(original, observer.settings.value)
            assertEquals(original, writer.load())
        }
    }

    @Test
    fun `throwing transform leaves preferences and shared state unchanged`() {
        val preferences = MemoryPreferences().node(UUID.randomUUID().toString()) as MemoryPreferences
        val writer = AiPlatformSettingsStore(preferences)
        val original = AiPlatformSettings("openai", "original-model", agentId = "aps.report")
        writer.save(original)
        val before = preferences.keys().associateWith { preferences.get(it, null) }

        assertFailsWith<IllegalArgumentException> { writer.update { throw IllegalArgumentException("fixture transform failure") } }

        assertEquals(before, preferences.keys().associateWith { preferences.get(it, null) })
        assertEquals(original, writer.settings.value)
        assertEquals(original, writer.load())
    }

    @Test
    fun `concurrent provider and agent transforms preserve unrelated saved changes`() {
        val preferences = MemoryPreferences().node(UUID.randomUUID().toString())
        val providerStore = AiPlatformSettingsStore(preferences)
        val agentStore = AiPlatformSettingsStore(preferences)
        val original =
            AiPlatformSettings(
                "openai",
                "original-model",
                allowArtifacts = true,
                agentId = "aps.main",
                allowScripts = true,
                reportFormat = ArtifactFormat.MARKDOWN,
            )
        providerStore.save(original)
        val start = CountDownLatch(1)
        val entered = CountDownLatch(2)
        val transforms = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val provider =
                executor.submit {
                    entered.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    providerStore.update {
                        transforms.incrementAndGet()
                        it.copy(providerId = "codex", model = "saved-model")
                    }
                }
            val agent =
                executor.submit {
                    entered.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    agentStore.update {
                        transforms.incrementAndGet()
                        it.copy(agentId = "aps.report")
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            start.countDown()
            provider.get(5, TimeUnit.SECONDS)
            agent.get(5, TimeUnit.SECONDS)
            val expected = original.copy(providerId = "codex", model = "saved-model", agentId = "aps.report")
            assertEquals(2, transforms.get())
            assertEquals(expected, providerStore.settings.value)
            assertEquals(expected, agentStore.settings.value)
            assertEquals(expected, providerStore.load())
            assertEquals("MARKDOWN", preferences.get("reportFormat", null))
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}

private class MemoryPreferences(
    parent: AbstractPreferences? = null,
    name: String = "",
) : AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    private val children = mutableMapOf<String, MemoryPreferences>()
    var failFlush = false
    var failPutKey: String? = null
    var failAfterPutKey: String? = null
    var onFlush: () -> Unit = {}

    override fun putSpi(
        key: String,
        value: String,
    ) {
        if (key == failPutKey) {
            failPutKey = null
            error("fixture put failure")
        }
        values[key] = value
        if (key == failAfterPutKey) {
            failAfterPutKey = null
            error("fixture put failure after write")
        }
    }

    override fun getSpi(key: String): String? = values[key]

    override fun removeSpi(key: String) {
        values.remove(key)
    }

    override fun removeNodeSpi() {
        values.clear()
    }

    override fun keysSpi(): Array<String> = values.keys.toTypedArray()

    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()

    override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { MemoryPreferences(this, name) }

    override fun syncSpi() = Unit

    override fun flushSpi() {
        onFlush()
        if (failFlush) throw BackingStoreException("fixture flush failure")
    }
}
