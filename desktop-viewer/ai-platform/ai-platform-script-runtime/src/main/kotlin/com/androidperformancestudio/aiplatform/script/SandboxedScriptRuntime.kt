package com.androidperformancestudio.aiplatform.script

import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import com.androidperformancestudio.aiplatform.model.requireBundlePath
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** No direct process fallback: a supported OS isolation backend is a prerequisite for every run. */
public class SandboxedScriptRuntime(
    private val registry: ExtensionRegistry,
    workRoot: Path,
    private val tools: SandboxToolchain = SandboxToolchain(),
) {
    private val workRoot = workRoot.toAbsolutePath().normalize()
    private val backend = SandboxBackend(tools)
    private val executionPermit = Semaphore(1)

    public suspend fun discover(runtime: ScriptRuntime): SandboxAvailability =
        try {
            backend.requireSupported()
            scriptRequire(runtime != ScriptRuntime.NONE, "E_SCRIPT_RUNTIME_UNAVAILABLE")
            val executable = backend.executable(runtime)
            // Even version discovery goes through the native boundary, never a bare interpreter.
            SandboxAvailability.Available(probeIsolation(executable, runtime), backend.name)
        } catch (failure: ScriptException) {
            SandboxAvailability.Unavailable(failure.code)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            SandboxAvailability.Unavailable("E_SANDBOX_DISCOVERY")
        }

    /** Exercise the actual backend, including forbidden host read/write/network, before advertising it. */
    private suspend fun probeIsolation(
        executable: Path,
        runtime: ScriptRuntime,
    ): String {
        privateDirectory(workRoot)
        val run = Files.createTempDirectory(workRoot, "probe-")
        privateDirectory(run)
        try {
            val script = run.resolve("probe")
            val sentinel = run.resolve("host-private")
            privateWrite(sentinel, "not mounted into sandbox".toByteArray())
            val pathLiteral = PlatformJson.codec.encodeToString(sentinel.toString())
            val probe =
                if (runtime == ScriptRuntime.PYTHON) {
                    """
                    import errno,os,pathlib,socket,subprocess,sys
                    path=pathlib.Path($pathLiteral)
                    try:
                        path.read_bytes()
                        sys.exit(71)
                    except OSError:
                        pass
                    try:
                        path.write_text('escape')
                        sys.exit(72)
                    except OSError:
                        pass
                    try:
                        pathlib.Path('/input/skill').write_text('escape')
                        sys.exit(73)
                    except OSError:
                        pass
                    for family in (socket.AF_INET,socket.AF_INET6,socket.AF_UNIX):
                        try:
                            socket.socket(family,socket.SOCK_STREAM).close()
                            sys.exit(74)
                        except OSError as e:
                            if e.errno not in (errno.EPERM,errno.EACCES): sys.exit(75)
                    try:
                        child=os.fork()
                        if child == 0: os._exit(0)
                        os.waitpid(child,0)
                        sys.exit(76)
                    except OSError as e:
                        if e.errno not in (errno.EPERM,errno.EACCES): sys.exit(77)
                    try:
                        subprocess.run([sys.executable,'-I','-B','-c','pass'],check=True)
                        sys.exit(78)
                    except OSError as e:
                        if e.errno not in (errno.EPERM,errno.EACCES): sys.exit(79)
                    for directory in ('/tmp','/work'):
                        scratch=pathlib.Path(directory)/'positive-control'
                        scratch.write_text('scratch')
                        assert scratch.read_text() == 'scratch'
                        scratch.unlink()
                    print('APS_SANDBOX_OK '+sys.version.split()[0])
                    """.trimIndent()
                } else {
                    """
                    const fs=require('fs'),net=require('net'),cp=require('child_process');
                    const path=$pathLiteral;
                    try{fs.readFileSync(path);process.exit(71)}catch(e){}
                    for(const f of [path,'/input/skill']) { try{fs.writeFileSync(f,'escape');process.exit(72)}catch(e){} }
                    for(const d of ['/tmp','/work']) {
                        const f=d+'/positive-control';fs.writeFileSync(f,'scratch');
                        if(fs.readFileSync(f,'utf8')!=='scratch')process.exit(73);fs.unlinkSync(f);
                    }
                    const child=cp.spawnSync(process.execPath,['--version']);
                    if(!child.error || !['EPERM','EACCES'].includes(child.error.code))process.exit(76);
                    const socket=net.connect({host:'127.0.0.1',port:9});
                    socket.on('connect',()=>process.exit(74));
                    socket.on('error',e=>{
                        if(!['EPERM','EACCES'].includes(e.code))process.exit(75);
                        console.log('APS_SANDBOX_OK '+process.versions.node);
                    });
                    socket.setTimeout(300,()=>{socket.destroy();process.exit(77)});
                    """.trimIndent()
                }
            privateWrite(script, probe.toByteArray())
            val budget =
                com.androidperformancestudio.aiplatform.model
                    .ResourceBudget(timeoutMillis = 5000)
            val process =
                ProcessBuilder(backend.command(executable, runtime, script, budget))
                    .apply {
                        directory(run.toFile())
                        environment().clear()
                    }.start()
            val result =
                try {
                    capture(process, ByteArray(0), 4096, 5000)
                } finally {
                    terminate(process)
                }
            val response = result.stdout.decodeToString(throwOnInvalidSequence = true).trim()
            scriptRequire(result.exitCode == 0 && response.startsWith("APS_SANDBOX_OK "), "E_SANDBOX_PROBE_FAILED")
            scriptRequire(Files.readString(sentinel) == "not mounted into sandbox", "E_SANDBOX_PROBE_FAILED")
            val version = response.removePrefix("APS_SANDBOX_OK ")
            scriptRequire(version.length in 1..128 && version.all { it.isLetterOrDigit() || it in ". -_" }, "E_RUNTIME_VERSION")
            return version
        } finally {
            deleteTree(run)
        }
    }

    public suspend fun run(request: ScriptRunRequest): ScriptRunResult = execute(request)

    public suspend fun execute(request: ScriptRunRequest): ScriptRunResult =
        executionPermit.withPermit {
            try {
                backend.requireSupported()
                val entry = registry.resolve(request.key)
                val manifest = entry.manifest
                scriptRequire(request.key.kind == ExtensionKind.SKILL && entry.usable, "E_SKILL_UNAVAILABLE")
                scriptRequire(
                    Permission.EXECUTE_SCRIPT in request.grantedPermissions && Permission.EXECUTE_SCRIPT in manifest.permissions,
                    "E_SCRIPT_PERMISSION",
                )
                if (request.input.evidenceIds.isNotEmpty()) {
                    scriptRequire(
                        Permission.READ_EVIDENCE in request.grantedPermissions && Permission.READ_EVIDENCE in manifest.permissions,
                        "E_SCRIPT_PERMISSION",
                    )
                }
                if (request.input.candidateIds.isNotEmpty()) {
                    scriptRequire(
                        Permission.READ_SOURCE_CANDIDATES in request.grantedPermissions &&
                            Permission.READ_SOURCE_CANDIDATES in manifest.permissions,
                        "E_SCRIPT_PERMISSION",
                    )
                }
                // Scripts remain read-only/no-network/no-device even if a caller supplies broader grants.
                scriptRequire(manifest.dependencies.isEmpty(), "E_SCRIPT_DEPENDENCIES_UNAVAILABLE")
                scriptRequire(manifest.runtime != ScriptRuntime.NONE, "E_SCRIPT_RUNTIME_UNAVAILABLE")
                scriptRequire(request.input.schema == manifest.inputSchema, "E_SCRIPT_INPUT_SCHEMA")
                requireBundlePath(request.scriptPath)
                scriptRequire(request.scriptPath in manifest.scripts, "E_SCRIPT_UNDECLARED")
                val input = PlatformJson.codec.encodeToString(request.input).toByteArray()
                val budget = intersectBudget(request.budget, manifest.budget)
                scriptRequire(input.size <= budget.maxInputBytes, "E_SCRIPT_INPUT_LIMIT")
                val executable = backend.executable(manifest.runtime)
                val availability = discover(manifest.runtime)
                scriptRequire(
                    availability is SandboxAvailability.Available,
                    (availability as? SandboxAvailability.Unavailable)?.code ?: "E_SANDBOX_UNAVAILABLE",
                )
                privateDirectory(workRoot)
                val run = Files.createTempDirectory(workRoot, "script-")
                privateDirectory(run)
                try {
                    val script = run.resolve("skill")
                    val scriptBytes = safeRead(entry.directory.resolve(request.scriptPath), 65_536)
                    scriptRequire(digest(scriptBytes) == manifest.files.getValue(request.scriptPath), "E_SCRIPT_CONTENT_CHANGED")
                    privateWrite(script, scriptBytes)
                    val process =
                        ProcessBuilder(backend.command(executable, manifest.runtime, script, budget))
                            .apply {
                                directory(run.toFile())
                                environment().clear()
                            }.start()
                    val captured =
                        try {
                            capture(process, input, budget.maxOutputBytes, budget.timeoutMillis)
                        } finally {
                            terminate(process)
                        }
                    scriptRequire(captured.exitCode == 0, "E_SCRIPT_EXIT")
                    val output = validateOutput(captured.stdout, manifest.outputSchema, request.input)
                    ScriptRunResult(
                        output,
                        ScriptAudit(
                            request.key.id,
                            request.key.version,
                            digest(input),
                            digest(captured.stdout),
                            digest(captured.stderr),
                            captured.stdout.size,
                            captured.stderr.size,
                            manifest.runtime,
                            (availability as SandboxAvailability.Available).runtimeVersion,
                            backend.name,
                            captured.exitCode,
                        ),
                    )
                } finally {
                    // The sandbox cannot replace the outer run directory; never follow links in scratch.
                    deleteTree(run)
                }
            } catch (failure: ScriptException) {
                throw failure
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                throw ScriptException("E_SCRIPT_EXECUTION")
            }
        }
}

internal data class Captured(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int,
)

internal suspend fun capture(
    process: Process,
    input: ByteArray,
    limit: Int,
    timeoutMillis: Long,
): Captured =
    coroutineScope {
        val remaining = AtomicInteger(limit)
        val descendants = mutableSetOf<ProcessHandle>()
        val stdout = async(Dispatchers.IO) { readLimited(process.inputStream, remaining) }
        val stderr = async(Dispatchers.IO) { readLimited(process.errorStream, remaining) }
        val writer = async(Dispatchers.IO) { process.outputStream.use { it.write(input) } }
        try {
            val started = System.nanoTime()
            while (process.isAlive || !stdout.isCompleted || !stderr.isCompleted || !writer.isCompleted) {
                coroutineContext.ensureActive()
                process.descendants().use { children -> children.forEach { descendants.add(it) } }
                if (stdout.isCompleted) stdout.await()
                if (stderr.isCompleted) stderr.await()
                scriptRequire((System.nanoTime() - started) / 1_000_000 < timeoutMillis, "E_SCRIPT_TIMEOUT")
                delay(10)
            }
            writer.await()
            Captured(stdout.await(), stderr.await(), process.exitValue())
        } finally {
            terminate(process, descendants)
            process.outputStream.close()
            process.inputStream.close()
            process.errorStream.close()
            stdout.cancel()
            stderr.cancel()
            writer.cancel()
        }
    }

private fun readLimited(
    stream: InputStream,
    remaining: AtomicInteger,
): ByteArray =
    stream.use {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val count = it.read(chunk)
            if (count < 0) break
            scriptRequire(remaining.addAndGet(-count) >= 0, "E_SCRIPT_OUTPUT_LIMIT")
            bytes.write(chunk, 0, count)
        }
        bytes.toByteArray()
    }

private suspend fun terminate(
    process: Process,
    observedDescendants: Set<ProcessHandle> = emptySet(),
) {
    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        val children = (observedDescendants + process.descendants().use { it.toList() }).toList().reversed()
        children.forEach { it.destroy() }

        fun awaitChildren() {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150)
            while (children.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(5)
        }
        // Keep the waiting parent alive long enough to reap children, especially under a non-reaping container PID 1.
        awaitChildren()
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        awaitChildren()
        if (process.isAlive) process.destroy()
        process.waitFor(150, TimeUnit.MILLISECONDS)
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(1000, TimeUnit.MILLISECONDS)
    }
}

internal fun validateOutput(
    bytes: ByteArray,
    schema: String,
    input: ScriptInput,
): JsonObject {
    val output =
        try {
            PlatformJson.codec.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
        } catch (_: Exception) {
            throw ScriptException("E_SCRIPT_OUTPUT_SCHEMA")
        }
    scriptRequire(output != null, "E_SCRIPT_OUTPUT_SCHEMA")
    val result = output!!
    scriptRequire(result.keys == setOf("schema", "evidenceIds", "candidateIds", "result"), "E_SCRIPT_OUTPUT_SCHEMA")
    scriptRequire(result["schema"]?.jsonPrimitive?.content == schema, "E_SCRIPT_OUTPUT_SCHEMA")

    fun references(
        name: String,
        allowed: Set<String>,
    ) {
        val array = result[name] as? JsonArray
        scriptRequire(array != null && array.size <= 100, "E_SCRIPT_REFERENCE")
        val ids = array!!.map { it.jsonPrimitive.content }
        scriptRequire(ids.distinct().size == ids.size && ids.all { it in allowed }, "E_SCRIPT_REFERENCE")
    }
    references("evidenceIds", input.evidenceIds)
    references("candidateIds", input.candidateIds)
    scriptRequire(result["result"] is JsonObject, "E_SCRIPT_OUTPUT_SCHEMA")
    return result
}

private fun intersectBudget(
    a: com.androidperformancestudio.aiplatform.model.ResourceBudget,
    b: com.androidperformancestudio.aiplatform.model.ResourceBudget,
) = com.androidperformancestudio.aiplatform.model.ResourceBudget(
    timeoutMillis = minOf(a.timeoutMillis, b.timeoutMillis),
    maxInputBytes = minOf(a.maxInputBytes, b.maxInputBytes),
    maxOutputBytes = minOf(a.maxOutputBytes, b.maxOutputBytes),
    maxConcurrentRuns = 1,
    maxChildDepth = 0,
    maxCpuMillis = minOf(a.maxCpuMillis, b.maxCpuMillis),
    maxMemoryBytes = minOf(a.maxMemoryBytes, b.maxMemoryBytes),
)

private fun deleteTree(path: Path) {
    Files.walkFileTree(
        path,
        object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: java.nio.file.attribute.BasicFileAttributes,
            ): java.nio.file.FileVisitResult {
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                dir: Path,
                exc: java.io.IOException?,
            ): java.nio.file.FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        },
    )
}
