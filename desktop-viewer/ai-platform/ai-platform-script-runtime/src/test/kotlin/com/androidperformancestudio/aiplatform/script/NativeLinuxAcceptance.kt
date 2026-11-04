package com.androidperformancestudio.aiplatform.script

import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionManifest
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.registry.PlatformDirectories
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Explicit native acceptance, invoked only with real Linux tools in a disposable environment. */
internal object NativeLinuxAcceptance {
    fun verify(
        root: Path,
        bubblewrap: Path,
        helper: Path,
        language: ScriptRuntime = ScriptRuntime.PYTHON,
    ) = runBlocking {
        check(System.getProperty("os.name").startsWith("Linux"))
        val label = "native-api.${language.name.lowercase()}"
        val extension = if (language == ScriptRuntime.PYTHON) "py" else "js"

        fun scriptPath(name: String) = "scripts/$name.$extension"
        val permissions = setOf(Permission.EXECUTE_SCRIPT, Permission.READ_EVIDENCE)
        val emitPython = "print(json.dumps({'schema':'output.v1','evidenceIds':['e1'],'candidateIds':[],'result':{'ok':True}}))"
        val emitNode = "console.log(JSON.stringify({schema:'output.v1',evidenceIds:['e1'],candidateIds:[],result:{ok:true}}));"
        val scripts =
            (
                if (language == ScriptRuntime.PYTHON) {
                    mapOf(
                        "valid" to "import json,sys\nassert json.load(sys.stdin)['evidenceIds']==['e1']\n$emitPython",
                        "cpu" to "while True: pass",
                        "output" to "import os\nos.write(1,b'o'*40000)\nos.write(2,b'e'*40000)",
                        "wait" to "import time\ntime.sleep(60)",
                        "memory" to
                            "import json\ntry: value=bytearray(320*1024*1024)\nexcept MemoryError: pass\nelse: raise Exception('limit')\n$emitPython",
                    )
                } else {
                    mapOf(
                        "valid" to
                            """
                            const input=JSON.parse(require('fs').readFileSync(0,'utf8'));
                            if(input.evidenceIds[0]!=='e1')throw Error('input');
                            $emitNode
                            """.trimIndent(),
                        "cpu" to "for(;;){}",
                        "output" to
                            """
                            const fs=require('fs');
                            fs.writeSync(1,Buffer.alloc(40000,'o'));
                            fs.writeSync(2,Buffer.alloc(40000,'e'));
                            """.trimIndent(),
                        "wait" to "setTimeout(()=>{},60000);",
                        "memory" to
                            """
                            let denied=false;
                            try{Buffer.alloc(320*1024*1024)}catch(e){
                                if(!(e instanceof RangeError)&&e.code!=='ENOMEM')throw e;
                                denied=true;
                            }
                            if(!denied)throw Error('limit');
                            $emitNode
                            """.trimIndent(),
                    )
                }
            ).mapKeys { (name, _) -> scriptPath(name) }
        val bundle = Files.createDirectory(root.resolve("bundle"))
        Files.createDirectory(bundle.resolve("scripts"))
        val files = (scripts + ("SKILL.md" to "# Synthetic sandbox qualification\n")).mapValues { it.value.toByteArray() }
        files.forEach { (path, bytes) -> Files.write(bundle.resolve(path), bytes) }
        val manifest =
            ExtensionManifest(
                kind = ExtensionKind.SKILL,
                id = "aps.native-qualification",
                version = "1.0.0",
                name = "Synthetic native qualification",
                description = "Bounded test fixtures only",
                entrypoint = "SKILL.md",
                inputSchema = "input.v1",
                outputSchema = "output.v1",
                contextKinds = setOf("test"),
                evidenceKinds = emptySet(),
                permissions = permissions,
                budget = ResourceBudget(maxCpuMillis = 1000, timeoutMillis = 10_000),
                runtime = language,
                scripts = scripts.keys,
                files = files.mapValues { digest(it.value) },
            )
        Files.writeString(bundle.resolve("manifest.json"), PlatformJson.codec.encodeToString(manifest))
        val registry = ExtensionRegistry(PlatformDirectories(root.resolve("profile")))
        val entry = registry.install(bundle)
        registry.trust(entry.key)
        registry.enable(entry.key)
        val work = root.resolve("work")
        val runtime = SandboxedScriptRuntime(registry, work, SandboxToolchain(bubblewrap = bubblewrap, linuxLauncher = helper))
        val availability = runtime.discover(language)
        assertTrue(availability is SandboxAvailability.Available, availability.toString())
        println("PASS $label.discover=$availability")
        val request =
            ScriptRunRequest(
                entry.key,
                scriptPath("valid"),
                ScriptInput("input.v1", setOf("e1"), emptySet(), JsonObject(emptyMap())),
                permissions,
            )
        val output = runtime.run(request)
        assertEquals("true", (output.output["result"] as JsonObject)["ok"]?.jsonPrimitive?.content)
        assertEquals("linux-bubblewrap-prlimit-seccomp-v1", output.audit.backend)
        assertEquals(0, output.audit.exitCode)
        println("PASS $label.run-structured-output-and-audit")
        assertEquals("E_SCRIPT_EXIT", assertFailsWith<ScriptException> { runtime.run(request.copy(scriptPath = scriptPath("cpu"))) }.code)
        println("PASS $label.cpu")
        assertEquals(
            "E_SCRIPT_OUTPUT_LIMIT",
            assertFailsWith<ScriptException> { runtime.run(request.copy(scriptPath = scriptPath("output"))) }.code,
        )
        println("PASS $label.aggregate-output")
        try {
            runtime.run(request.copy(scriptPath = scriptPath("memory")))
        } catch (failure: ScriptException) {
            if (language != ScriptRuntime.NODE || failure.code != "E_SCRIPT_EXIT") throw failure
        }
        println("PASS $label.memory")
        assertEquals(
            "E_SCRIPT_TIMEOUT",
            assertFailsWith<ScriptException> {
                runtime.run(request.copy(scriptPath = scriptPath("wait"), budget = ResourceBudget(timeoutMillis = 100)))
            }.code,
        )
        println("PASS $label.timeout")
        val cancelled = async { runtime.run(request.copy(scriptPath = scriptPath("wait"))) }
        delay(250)
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        runtime.run(request)
        assertFalse(Files.list(work).use { it.findAny().isPresent })
        println("PASS $label.cancel-reuse-and-cleanup")
    }
}
