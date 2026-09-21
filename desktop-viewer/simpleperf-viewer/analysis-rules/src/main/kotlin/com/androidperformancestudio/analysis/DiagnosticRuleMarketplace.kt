package com.androidperformancestudio.analysis

data class DiagnosticRulePack(
    val id: String,
    val version: String,
    val rules: List<DiagnosticRule>,
    val source: String,
) {
    init {
        require(id.isNotBlank()) { "rule pack id must not be blank" }
        require(version.isNotBlank()) { "rule pack version must not be blank" }
        require(rules.isNotEmpty()) { "rule pack must contain at least one rule" }
    }
}

class DiagnosticRuleMarketplace(
    builtInRules: List<DiagnosticRule> = emptyList(),
) {
    private val builtIns = builtInRules.toList()
    private val installed = linkedMapOf<String, DiagnosticRulePack>()

    fun install(pack: DiagnosticRulePack) {
        installed[pack.id] = pack
    }

    fun uninstall(id: String): Boolean = installed.remove(id) != null

    fun installedPacks(): List<DiagnosticRulePack> = installed.values.toList()

    fun engine(): DiagnosticEngine = DiagnosticEngine(builtIns + installed.values.flatMap(DiagnosticRulePack::rules))
}
