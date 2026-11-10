package com.androidperformancestudio.desktop

import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionManifest
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.registry.PlatformDirectories
import com.androidperformancestudio.aiplatform.script.SandboxAvailability
import com.androidperformancestudio.aiplatform.script.SandboxedScriptRuntime
import com.androidperformancestudio.aiplatform.script.ScriptException
import com.androidperformancestudio.aiplatform.script.ScriptInput
import com.androidperformancestudio.aiplatform.script.ScriptRunRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

class AiReleaseQualificationTest {
    private val repository = Path.of("../..").toAbsolutePath().normalize()

    @TempDir
    lateinit var temporary: Path

    @Test
    fun nativeSandboxQualification() =
        runBlocking {
            // Default unit tests confer no native qualification and perform no script execution.
            if (System.getenv("APS_REQUIRE_NATIVE_SANDBOX") != "true") return@runBlocking
            if (System.getenv("APS_REQUIRE_LINUX_SANDBOX") == "true") {
                assertTrue(System.getProperty("os.name").startsWith("Linux"), "Linux qualification needs a native Linux host")
            }
            val root = temporary.toRealPath()
            val sentinel = Files.writeString(root.resolve("host-private"), "host-only-sentinel")
            val registry = ExtensionRegistry(PlatformDirectories(root.resolve("profile")))
            val work = root.resolve("work")
            val runtime = SandboxedScriptRuntime(registry, work)
            for (language in listOf(ScriptRuntime.PYTHON, ScriptRuntime.NODE)) {
                val availability = runtime.discover(language)
                assertTrue(availability is SandboxAvailability.Available, "A real production sandbox is required for $language")
                val hostPath = JsonPrimitive(sentinel.toString()).toString()
                val probe =
                    if (language == ScriptRuntime.PYTHON) {
                        """
                        import json,os,pathlib,socket,subprocess
                        for name in [$hostPath,'/etc/passwd']:
                            try: pathlib.Path(name).read_bytes()
                            except OSError: pass
                            else: raise RuntimeError('host read escape')
                        try: pathlib.Path($hostPath).write_text('escape')
                        except OSError: pass
                        else: raise RuntimeError('host write escape')
                        try: socket.socket()
                        except OSError: pass
                        else: raise RuntimeError('socket escape')
                        try: subprocess.run(['/usr/bin/true'],check=True)
                        except OSError: pass
                        else: raise RuntimeError('child escape')
                        assert 'APS_NATIVE_FIXTURE_SECRET' not in os.environ
                        assert json.load(__import__('sys').stdin)['evidenceIds']==['native-evidence']
                        print(json.dumps({'schema':'output.v1','evidenceIds':['native-evidence'],'candidateIds':[],'result':{}}))
                        """.trimIndent()
                    } else {
                        """
                        const fs=require('fs'),net=require('net'),cp=require('child_process');
                        for(const name of [$hostPath,'/etc/passwd']) {
                          let denied=false;try{fs.readFileSync(name)}catch(e){denied=true}
                          if(!denied)throw Error('host read escape');
                        }
                        let denied=false;try{fs.writeFileSync($hostPath,'escape')}catch(e){denied=true}
                        if(!denied)throw Error('host write escape');
                        if(process.env.APS_NATIVE_FIXTURE_SECRET)throw Error('environment escape');
                        if(!cp.spawnSync('/usr/bin/true').error)throw Error('child escape');
                        if(JSON.parse(fs.readFileSync(0,'utf8')).evidenceIds[0]!=='native-evidence')throw Error('input');
                        const socket=net.connect({host:'1.1.1.1',port:443});
                        socket.on('connect',()=>{process.exitCode=71;socket.destroy()});
                        socket.on('error',()=>console.log(JSON.stringify({schema:'output.v1',evidenceIds:['native-evidence'],candidateIds:[],result:{}})));
                        socket.setTimeout(500,()=>{process.exitCode=72;socket.destroy()});
                        """.trimIndent()
                    }
                val request = installNativeProbe(registry, language, "probe", probe)
                val result = runtime.execute(request)
                assertEquals(0, result.audit.exitCode)
                assertEquals(language, result.audit.runtime)
                assertTrue(result.audit.stdoutBytes > 0)
                assertEquals("host-only-sentinel", Files.readString(sentinel))
                try {
                    runtime.execute(request.copy(grantedPermissions = emptySet()))
                    error("Script execution without a host grant succeeded")
                } catch (failure: ScriptException) {
                    assertEquals("E_SCRIPT_PERMISSION", failure.code)
                }
                val overflow = if (language == ScriptRuntime.PYTHON) "print('x'*100000)" else "console.log('x'.repeat(100000))"
                val spin = if (language == ScriptRuntime.PYTHON) "while True: pass" else "for(;;){}"
                val allocation = if (language == ScriptRuntime.PYTHON) "bytearray(512*1024*1024)" else "Buffer.alloc(512*1024*1024)"
                for ((name, script, codes) in listOf(
                    Triple("overflow", overflow, setOf("E_SCRIPT_OUTPUT_LIMIT", "E_SCRIPT_EXIT")),
                    Triple("budget", spin, setOf("E_SCRIPT_TIMEOUT", "E_SCRIPT_EXIT")),
                    Triple("memory", allocation, setOf("E_SCRIPT_EXIT")),
                )) {
                    val bounded = installNativeProbe(registry, language, name, script)
                    try {
                        runtime.execute(bounded.copy(budget = ResourceBudget(timeoutMillis = 1500, maxOutputBytes = 1024, maxCpuMillis = 1000)))
                        error("Native resource enforcement did not stop $name")
                    } catch (failure: ScriptException) {
                        assertTrue(failure.code in codes, "Resource enforcement must not fail as unavailable: ${failure.code}")
                    }
                }
                Files.list(work).use { assertEquals(0L, it.count(), "Per-run sandbox scratch must be removed") }
            }
        }

    private fun installNativeProbe(
        registry: ExtensionRegistry,
        runtime: ScriptRuntime,
        name: String,
        script: String,
    ): ScriptRunRequest {
        val id = "qualification.${runtime.name.lowercase()}.$name"
        val source = Files.createDirectory(temporary.toRealPath().resolve(id))
        val path = "scripts/probe." + if (runtime == ScriptRuntime.PYTHON) "py" else "js"
        Files.createDirectories(source.resolve("scripts"))
        Files.writeString(source.resolve("prompt.md"), "Host-authored native qualification")
        Files.writeString(source.resolve(path), script)
        val permissions = setOf(Permission.EXECUTE_SCRIPT, Permission.READ_EVIDENCE)
        val manifest =
            ExtensionManifest(
                kind = ExtensionKind.SKILL,
                id = id,
                version = "1.0.0",
                name = "Native qualification",
                description = "Synthetic production sandbox probe",
                entrypoint = "prompt.md",
                inputSchema = "input.v1",
                outputSchema = "output.v1",
                contextKinds = setOf("qualification"),
                evidenceKinds = setOf("synthetic"),
                permissions = permissions,
                runtime = runtime,
                scripts = setOf(path),
                files = listOf("prompt.md", path).associateWith {
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.resolve(it))))
                },
            )
        Files.writeString(source.resolve("manifest.json"), PlatformJson.codec.encodeToString(manifest))
        val key = registry.install(source).key
        registry.trust(key)
        registry.enable(key)
        return ScriptRunRequest(
            key,
            path,
            ScriptInput("input.v1", setOf("native-evidence"), emptySet(), JsonObject(emptyMap())),
            permissions,
        )
    }

    @Test
    fun `publication retains signed artifact checks before upload`() {
        val workflow = Files.readString(repository.resolve(".github/workflows/release.yml"))
        assertTrue(workflow.contains("Existing tag differs from the dispatched source"))
        listOf("macos", "windows").forEach { platform ->
            val job =
                workflow.substringAfter("\n  package-$platform:")
                    .split(Regex("\\n  (?=\\S)"), limit = 2).first()
            val sign = job.indexOf("sign.py sign $platform")
            val verify = job.indexOf("sign.py verify $platform")
            val receipt = job.indexOf("publication.py record")
            val upload = job.indexOf("uses: actions/upload-artifact@v7")
            assertTrue(job.contains("environment: aps-release-signing"))
            assertTrue(sign >= 0 && verify > sign && receipt > verify && upload > receipt)
            assertFalse(job.contains("gh release upload"))
            assertFalse(job.contains("continue-on-error"))
        }
        assertFalse(workflow.contains("--clobber"))
        assertTrue(workflow.contains("publication.py publish"))
        assertTrue(workflow.contains("format('v{0}'"))
    }

    @Test
    fun `signing tools use env references and restore temporary identities`() {
        val script = Files.readString(repository.resolve("scripts/ai-release/sign.py"))
        listOf(
            "APS_ENABLE_RELEASE_SIGNING",
            "Developer ID Application: ",
            "Developer ID Installer: ",
            "previous_search",
            "previous_default",
            "delete-keychain",
            "--wait",
            "--timestamp",
            "--strict",
            "Accepted",
            "stapler",
            "source_sha",
        ).forEach { assertTrue(script.contains(it), it) }
        assertFalse(script.contains("shell=True"))
        val windows = Files.readString(repository.resolve("scripts/ai-release/sign-windows.ps1"))
        assertTrue(windows.contains("finally"))
        assertTrue(windows.contains("TimeStamperCertificate"))
        assertTrue(windows.contains("-DeleteKey"))
    }

    @Test
    fun `deterministic fixtures cover fail closed gates redaction and cleanup without release side effects`() {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val output = temporary.resolve("fixtures.log")
        val builder =
            ProcessBuilder(executable, "-B", repository.resolve("scripts/ai-release/test_contracts.py").toString())
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
        builder.environment().keys.removeIf { name ->
            name.startsWith("APS_") || name.startsWith("APPLE_") || name == "ANTHROPIC_API_KEY" || name == "OPENAI_API_KEY"
        }
        builder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
        val process = builder.start()
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        assertTrue(completed, "Deterministic release fixtures timed out")
        assertEquals(0, process.exitValue(), Files.readString(output))
        assertTrue(Files.readString(output).contains("OK"))
    }
}
