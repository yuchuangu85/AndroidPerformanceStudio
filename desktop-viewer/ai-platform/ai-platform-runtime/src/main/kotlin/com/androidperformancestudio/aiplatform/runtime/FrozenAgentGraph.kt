package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionOrigin
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.registry.RegistryEntry
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

internal const val MAX_GRAPH_CALLS = 16

internal data class FrozenSkill(
    val entry: RegistryEntry,
    val permissions: Set<Permission>,
    val budget: ResourceBudget,
    val scripts: Map<String, String>,
)

internal data class FrozenAgent(
    val entry: RegistryEntry,
    val depth: Int,
    val permissions: Set<Permission>,
    val budget: ResourceBudget,
    val promptContext: String?,
    val skills: List<FrozenSkill>,
    val children: List<FrozenAgent>,
) {
    val agents: List<FrozenAgent> get() = listOf(this) + children.flatMap { it.agents }
    val scriptCount: Int get() = agents.sumOf { agent -> agent.skills.sumOf { it.scripts.size } }
    val callCount: Int
        get() =
            agents.sumOf { agent ->
                1 +
                    agent.skills.sumOf { skill ->
                        if (skill.scripts.isNotEmpty()) {
                            skill.scripts.size
                        } else {
                            1 + if (skill.entry.key.id == "aps.report-artifact" && Permission.WRITE_ARTIFACT in skill.permissions) 1 else 0
                        }
                    }
            }

    // Include edges and occurrences, not merely the set of package hashes (a DAG can repeat calls).
    fun approvalMaterial(): String =
        buildString {
            append("derived-transform:").append(DerivedAnalysisPolicy.APPROVAL_MATERIAL).append('\n')
            append("agent:")
                .append(entry.key)
                .append(':')
                .append(entry.contentHash)
                .append(':')
                .append(depth)
            append(':').append(permissions.map { it.name }.sorted()).append(':').append(budget)
            skills.forEach { skill ->
                append("\nskill:").append(skill.entry.key).append(':').append(skill.entry.contentHash)
                append(':').append(skill.permissions.map { it.name }.sorted()).append(':').append(skill.budget)
                skill.scripts.toSortedMap().forEach { (path, hash) -> append("\nscript:").append(path).append(':').append(hash) }
            }
            children.forEach { append("\nchild{\n").append(it.approvalMaterial()).append("\n}") }
        }
}

/** Only manifest edges select work. Imported text cannot add edges, tools, grants or instructions. */
internal class FrozenAgentGraph(
    private val registry: ExtensionRegistry,
) {
    fun freeze(
        root: RegistryEntry,
        analysis: AnalysisRequest,
        providerId: String,
        permissions: Set<Permission>,
        scriptsAvailable: Boolean,
    ): FrozenAgent {
        var calls = 0

        fun spendCall() {
            graphPolicy(++calls <= MAX_GRAPH_CALLS, "E_CALL_BUDGET")
        }

        fun visit(
            entry: RegistryEntry,
            depth: Int,
            ancestors: List<RegistryEntry>,
            inheritedPermissions: Set<Permission>,
            inheritedBudget: ResourceBudget,
        ): FrozenAgent {
            graphPolicy(ancestors.none { it.key.id == entry.key.id }, "E_CHILD_CYCLE")
            graphPolicy(depth <= root.manifest.budget.maxChildDepth, "E_CHILD_DEPTH")
            ancestors.forEachIndexed { index, ancestor ->
                graphPolicy(depth - index <= ancestor.manifest.budget.maxChildDepth, "E_CHILD_DEPTH")
            }
            graphPolicy(entry.usable, "E_AGENT_UNAVAILABLE")
            val manifest = entry.manifest
            graphPolicy(manifest.runtime == ScriptRuntime.NONE, "E_SANDBOX_UNAVAILABLE")
            graphPolicy(providerId in manifest.allowedProviders, "E_PROVIDER_NOT_ALLOWED")
            val grants = inheritedPermissions.intersect(manifest.permissions)
            graphPolicy(Permission.READ_EVIDENCE in grants, "E_CHILD_PERMISSION")
            val budget = intersectBudgets(inheritedBudget, manifest.budget)
            val selectedSkills =
                manifest.allowedSkills.sorted().mapNotNull { id ->
                    if (id == "aps.source-candidates" && analysis.sourceCandidates.isEmpty()) return@mapNotNull null
                    val skill = registry.resolve(ExtensionKind.SKILL, id)
                    graphPolicy(skill.usable && matches(skill, analysis), "E_SKILL_CONTEXT")
                    val skillGrants = grants.intersect(skill.manifest.permissions)
                    graphPolicy(Permission.READ_EVIDENCE in skillGrants, "E_SKILL_PERMISSION")
                    val scripts =
                        skill.manifest.scripts
                            .sorted()
                            .associateWith { skill.manifest.files.getValue(it) }
                    if (scripts.isEmpty()) {
                        graphPolicy(
                            skill.key.origin == ExtensionOrigin.BUILTIN && id in BuiltinSkillExecutor.BUILTIN_SKILLS,
                            "E_SKILL_UNSUPPORTED",
                        )
                        if (id == "aps.source-candidates") {
                            graphPolicy(Permission.READ_SOURCE_CANDIDATES in skillGrants, "E_SKILL_PERMISSION")
                        }
                        spendCall()
                        if (id == "aps.report-artifact" && Permission.WRITE_ARTIFACT in skillGrants) spendCall()
                    } else {
                        graphPolicy(Permission.EXECUTE_SCRIPT in skillGrants, "E_SCRIPT_PERMISSION")
                        graphPolicy(scriptsAvailable, "E_SCRIPT_UNAVAILABLE")
                        graphPolicy(skill.manifest.dependencies.isEmpty(), "E_SCRIPT_DEPENDENCIES_UNAVAILABLE")
                        scripts.forEach { _ -> spendCall() }
                    }
                    FrozenSkill(skill, skillGrants, intersectBudgets(budget, skill.manifest.budget), scripts)
                }
            spendCall()
            val children =
                manifest.allowedAgents.sorted().mapNotNull { id ->
                    val child = registry.resolve(ExtensionKind.AGENT, id)
                    // Context filtering preserves the builtin profiler/source lanes, without an ID allowlist.
                    if (!matches(child, analysis) || id == "aps.source-evidence" && analysis.sourceCandidates.isEmpty()) {
                        return@mapNotNull null
                    }
                    visit(child, depth + 1, ancestors + entry, grants, budget)
                }
            val prompt =
                if (entry.key.origin == ExtensionOrigin.IMPORTED) {
                    val bytes =
                        Files
                            .newInputStream(
                                entry.directory.resolve(manifest.entrypoint),
                                NOFOLLOW_LINKS,
                            ).use { it.readNBytes(262_145) }
                    graphPolicy(bytes.size <= 262_144 && bytes.sha256() == manifest.files.getValue(manifest.entrypoint), "E_AGENT_CHANGED")
                    bytes.decodeToString(throwOnInvalidSequence = true)
                } else {
                    null
                }
            return FrozenAgent(entry, depth, grants, budget, prompt, selectedSkills, children)
        }

        return visit(root, 0, emptyList(), permissions, root.manifest.budget)
    }

    fun verify(graph: FrozenAgent) {
        graph.agents.forEach { node ->
            verifyEntry(node.entry, "E_AGENT_CHANGED")
            node.skills.forEach { verifyEntry(it.entry, "E_EXTENSION_CHANGED") }
        }
    }

    private fun verifyEntry(
        frozen: RegistryEntry,
        code: String,
    ) {
        val current = registry.resolve(frozen.key.kind, frozen.key.id)
        graphPolicy(current.usable && current.key == frozen.key && current.contentHash == frozen.contentHash, code)
    }

    private fun matches(
        entry: RegistryEntry,
        analysis: AnalysisRequest,
    ): Boolean =
        analysis.originProfiler.name
            .lowercase()
            .replace('_', '-') in entry.manifest.contextKinds &&
            analysis.evidence.all { it.kind in entry.manifest.evidenceKinds }
}

internal fun intersectBudgets(
    a: ResourceBudget,
    b: ResourceBudget,
): ResourceBudget =
    ResourceBudget(
        timeoutMillis = minOf(a.timeoutMillis, b.timeoutMillis),
        maxInputBytes = minOf(a.maxInputBytes, b.maxInputBytes),
        maxOutputBytes = minOf(a.maxOutputBytes, b.maxOutputBytes),
        maxConcurrentRuns = minOf(a.maxConcurrentRuns, b.maxConcurrentRuns),
        maxChildDepth = minOf(a.maxChildDepth, b.maxChildDepth),
        maxCpuMillis = minOf(a.maxCpuMillis, b.maxCpuMillis),
        maxMemoryBytes = minOf(a.maxMemoryBytes, b.maxMemoryBytes),
    )

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun graphPolicy(
    condition: Boolean,
    code: String,
) {
    if (!condition) throw AgentPolicyException(code)
}
