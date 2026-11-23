"""Standard-library Windows packaging contracts; PowerShell cases skip if absent.

Run: python -B -m unittest discover -s scripts/tests -p test_windows_packaging.py -v
No test invokes WiX, msiexec, Cargo, publication, or an application binary.
"""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
WINDOWS = ROOT / "scripts/windows"
WORKFLOW = ROOT / ".github/workflows/aps-desktop-release.yml"
NS = {"w": "http://schemas.microsoft.com/wix/2006/wi"}
PWSH = shutil.which("pwsh")


def job(workflow, name):
    # Same bounded job extraction as scripts/ai-release/test_contracts.py.
    return re.split(r"\n  (?=\S)", workflow.split("\n  " + name + ":\n", 1)[1], maxsplit=1)[0]


def step(windows, name):
    return re.split(r"\n      - (?=\S)", windows.split("      - name: " + name + "\n", 1)[1], maxsplit=1)[0]


def run_script(source):
    run = source.split("\n        run: ", 1)[1]
    return textwrap.dedent(run[1:]).strip() if run.startswith("|") else run.strip()


class InstallerContractTest(unittest.TestCase):
    def setUp(self):
        self.product = ET.parse(WINDOWS / "installer.wxs").find("w:Product", NS)

    def test_preserves_x64_machine_scope_and_upgrade_identity(self):
        self.assertEqual("*", self.product.get("Id"))
        self.assertEqual("7D3A2A7A-48B6-4EF0-8D8D-5C9D1E7E8F21", self.product.get("UpgradeCode"))
        self.assertEqual("$(var.ProductVersion)", self.product.get("Version"))
        package = self.product.find("w:Package", NS)
        self.assertEqual("x64", package.get("Platform"))
        self.assertEqual("perMachine", package.get("InstallScope"))
        upgrade = self.product.find("w:MajorUpgrade", NS)
        self.assertEqual("yes", upgrade.get("AllowSameVersionUpgrades"))
        self.assertEqual("afterInstallInitialize", upgrade.get("Schedule"))
        self.assertNotEqual("yes", upgrade.get("AllowDowngrades"))
        self.assertTrue(upgrade.get("DowngradeErrorMessage"))

    def test_guided_install_folder_and_maintenance_without_placeholder_license(self):
        refs = {node.get("Id") for node in self.product.findall("w:UIRef", NS)}
        self.assertIn("WixUI_InstallDir", refs)
        prop = self.product.find("w:Property[@Id='WIXUI_INSTALLDIR']", NS)
        self.assertEqual("INSTALLFOLDER", prop.get("Value"))
        folder = self.product.find(".//w:Directory[@Id='ProgramFiles64Folder']/w:Directory", NS)
        self.assertEqual("INSTALLFOLDER", folder.get("Id"))
        navigation = self.product.findall("w:UI/w:Publish", NS)
        self.assertEqual({("WelcomeDlg", "Next", "InstallDirDlg"), ("InstallDirDlg", "Back", "WelcomeDlg")},
                         {(node.get("Dialog"), node.get("Control"), node.get("Value")) for node in navigation})
        self.assertTrue(all(node.get("Order") == "2" for node in navigation))
        self.assertNotIn("LicenseAgreementDlg", (WINDOWS / "installer.wxs").read_text())

    def test_shortcut_is_advertised_and_owned_by_the_app_file_component(self):
        app = self.product.find(".//w:Component[@Id='AppComponent']", NS)
        file = app.find("w:File", NS)
        self.assertEqual("yes", file.get("KeyPath"))
        shortcut = file.find("w:Shortcut", NS)
        self.assertEqual("yes", shortcut.get("Advertise"))
        self.assertEqual("ProgramMenuFolder", shortcut.get("Directory"))
        self.assertEqual("INSTALLFOLDER", shortcut.get("WorkingDirectory"))
        self.assertIsNotNone(self.product.find(".//w:Directory[@Id='ProgramMenuFolder']", NS))
        self.assertEqual("yes", app.get("Win64"))
        self.assertEqual("AppIcon.exe", shortcut.get("Icon"))

    def test_advertised_shortcut_icon_resolves_and_matches_key_file_extension(self):
        file = self.product.find(".//w:Component[@Id='AppComponent']/w:File", NS)
        shortcut = file.find("w:Shortcut[@Advertise='yes']", NS)
        icon_id = shortcut.get("Icon")
        icon = self.product.find(f"w:Icon[@Id='{icon_id}']", NS)
        self.assertIsNotNone(icon, "Advertised shortcut must reference an existing Icon")
        self.assertEqual(Path(file.get("Source")).suffix.lower(), Path(icon_id).suffix.lower(),
                         "ICE50 requires the advertised shortcut icon ID and key file extensions to match")
        self.assertEqual("$(var.AppIcon)", icon.get("SourceFile"))
        arp = self.product.find("w:Property[@Id='ARPPRODUCTICON']", NS)
        self.assertEqual(icon_id, arp.get("Value"))

    def test_upgrade_remembers_install_location_and_arp_keeps_uninstall(self):
        search = self.product.find("w:Property[@Id='INSTALLFOLDER']/w:RegistrySearch", NS)
        write = self.product.find(".//w:Component[@Id='AppComponent']/w:RegistryValue", NS)
        for attribute in ("Root", "Key", "Name"):
            self.assertEqual(search.get(attribute), write.get(attribute))
        self.assertEqual("HKLM", write.get("Root"))
        self.assertEqual("yes", search.get("Win64"))
        self.assertEqual("[INSTALLFOLDER]", write.get("Value"))
        arp = self.product.find("w:SetProperty[@Id='ARPINSTALLLOCATION']", NS)
        self.assertEqual("[INSTALLFOLDER]", arp.get("Value"))
        self.assertEqual("CostFinalize", arp.get("After"))
        self.assertIsNone(self.product.find("w:Property[@Id='ARPNOREMOVE']", NS))
        self.assertIsNone(self.product.find("w:Property[@Id='ARPNOREPAIR']", NS))

    def test_feature_installs_both_binaries_and_entire_resource_harvest(self):
        feature = self.product.find("w:Feature", NS)
        self.assertEqual({"AppComponent", "WorkerComponent"},
                         {ref.get("Id") for ref in feature.findall("w:ComponentRef", NS)})
        self.assertEqual("BundledResources", feature.find("w:ComponentGroupRef", NS).get("Id"))
        self.assertEqual("resources", self.product.find(".//w:Directory[@Id='ResourcesDir']", NS).get("Name"))
        sources = {file.get("Source") for file in self.product.findall(".//w:File", NS)}
        self.assertEqual({"$(var.PayloadSource)\\aps-app.exe", "$(var.PayloadSource)\\aps-memory-worker.exe"}, sources)


class WorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()
        self.windows = job(self.workflow, "windows")

    def step(self, name):
        return step(self.windows, name)

    def test_static_crt_cannot_be_shadowed_by_setup_action_flags(self):
        step = self.step("Build binaries")
        self.assertIn("CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_RUSTFLAGS: '-C target-feature=+crt-static'", step)
        run = run_script(step)
        removal = "Remove-Item Env:RUSTFLAGS, Env:CARGO_ENCODED_RUSTFLAGS"
        self.assertIn(removal, run)
        self.assertLess(run.index(removal), run.index("cargo build"))
        self.assertIn("--target x86_64-pc-windows-msvc", run)
        self.assertIn("--locked --release", run)
        self.assertIn("$LASTEXITCODE -ne 0", run)
        self.assertIn("shell: pwsh", step)

    def test_regression_and_smoke_checks_gate_artifact_upload(self):
        test = self.step("Test Windows packaging contracts")
        self.assertIn("test_windows_packaging.py", run_script(test))
        self.assertNotIn("pip install", test)
        package = self.step("Package portable ZIP and guided MSI")
        smoke = self.step("Smoke test Windows install, repair and uninstall")
        upload = self.step("Upload Windows packages")
        self.assertIn("./scripts/windows/package.ps1", run_script(package))
        self.assertIn("./scripts/windows/smoke.ps1", run_script(smoke))
        self.assertIn("timeout-minutes: 10", smoke)
        self.assertLess(self.windows.index(test), self.windows.index(package))
        self.assertLess(self.windows.index(package), self.windows.index(smoke))
        self.assertLess(self.windows.index(smoke), self.windows.index(upload))
        self.assertNotIn("continue-on-error", smoke)
        self.assertIn("windows-x86_64.zip", upload)
        self.assertIn("dist/*.msi", upload)
        logs = self.step("Upload Windows installer smoke logs")
        self.assertIn("if: always()", logs)
        self.assertIn("name: windows-installer-smoke-logs", logs)

    def test_preserves_release_trigger_and_publication_artifact_contract(self):
        self.assertIn('on:\n  push:\n    tags:\n      - "aps-desktop-v*"\n      - "v*"\n', self.workflow)
        publish = job(self.workflow, "publish")
        self.assertIn("needs: [linux, macos, windows]", publish)
        self.assertIn("pattern: packages-*", publish)
        self.assertIn("gh release create", publish)

    def test_packaging_checks_full_resources_native_failures_and_both_artifacts(self):
        script = (WINDOWS / "package.ps1").read_text()
        self.assertIn("firefox-profiler/index.html", script)
        self.assertIn("perfetto-tools/trace_processor_shell.exe", script)
        self.assertIn("Compress-Archive -LiteralPath $payload", script)
        self.assertIn("'BundledResources'", script)
        self.assertIn("'ResourcesDir'", script)
        for tool in ("heat.exe", "candle.exe", "light.exe"):
            self.assertIn("Invoke-CheckedNative (Join-Path $wix '" + tool + "')", script)
        self.assertIn("$component.SetAttribute('Win64', 'yes')", script)
        self.assertIn("Assert-StaticRuntime (Find-Dumpbin) $binaries", script)

    def test_smoke_is_bounded_ci_only_and_checks_payload_repair_and_removal(self):
        script = (WINDOWS / "smoke.ps1").read_text()
        for token in ("$env:GITHUB_ACTIONS -ne 'true'", "$process.WaitForExit(120000)",
                      "REBOOT=ReallySuppress", "@(0, 3010)", "Get-FileHash", "CommonPrograms",
                      "Invoke-Msi 'install'", "Invoke-Msi 'repair'", "Invoke-Msi 'uninstall'", "finally"):
            self.assertIn(token, script)
        self.assertNotIn("Win32_Product", script)


@unittest.skipUnless(PWSH, "PowerShell is required for executable helper regressions")
class PowerShellContractTest(unittest.TestCase):
    def ps(self, code, **environment):
        env = dict(os.environ, APS_TEST_WINDOWS=str(WINDOWS), **environment)
        result = subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                                 "$ErrorActionPreference = 'Stop'\n"
                                 ". (Join-Path $env:APS_TEST_WINDOWS 'package.ps1')\n" + code],
                                env=env, text=True, capture_output=True, timeout=45)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def test_all_powershell_sources_and_windows_workflow_steps_parse(self):
        runs = [run_script(source) for source in re.split(r"\n      - (?=\S)", job(WORKFLOW.read_text(), "windows"))
                if "shell: pwsh" in source and "\n        run: " in source]
        result = self.ps("""
$errorsFound = @()
foreach ($file in Get-ChildItem $env:APS_TEST_WINDOWS -Filter '*.ps1') {
    $tokens = $null; $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    $errorsFound += @($parseErrors | ForEach-Object { $_.ToString() })
}
foreach ($run in ($env:APS_TEST_RUNS | ConvertFrom-Json)) {
    $tokens = $null; $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseInput($run, [ref]$tokens, [ref]$parseErrors)
    $errorsFound += @($parseErrors | ForEach-Object { $_.ToString() })
}
ConvertTo-Json -InputObject @($errorsFound) -Compress
""", APS_TEST_RUNS=json.dumps(runs))
        self.assertEqual([], result)

    def test_versions_are_strict_bounded_and_rebuilds_keep_product_version(self):
        valid = {"v0.8.2": "0.8.2", "aps-desktop-v1.2.3-rebuild.4": "1.2.3",
                 "rust-desktop-v255.255.65535": "255.255.65535"}
        invalid = ["v1.2", "v1.2.3.4", "v1.2.3-rc.1", "v256.1.1", "v1.256.1", "v1.2.65536", "v1.2.3/evil"]
        result = self.ps("""
$results = @{}
foreach ($tag in ($env:APS_TEST_TAGS | ConvertFrom-Json)) {
    try { $results[$tag] = Get-MsiVersion $tag } catch { $results[$tag] = 'rejected' }
}
$results | ConvertTo-Json -Compress
""", APS_TEST_TAGS=json.dumps([*valid, *invalid]))
        self.assertEqual({**valid, **dict.fromkeys(invalid, "rejected")}, result)

    def test_wix_discovery_handles_spaces_and_incomplete_explicit_toolsets(self):
        with tempfile.TemporaryDirectory(prefix="APS WiX With Spaces ") as directory:
            root = Path(directory)
            for name in ("heat.exe", "candle.exe", "light.exe", "WixUIExtension.dll"):
                (root / name).touch()
            discovered = self.ps("Find-WixBin $env:APS_TEST_WIX | ConvertTo-Json", APS_TEST_WIX=str(root))
            self.assertEqual(root.resolve(), Path(discovered).resolve())
            (root / "WixUIExtension.dll").unlink()
            self.assertTrue(self.ps("""
$rejected = $false
try { $null = Find-WixBin $env:APS_TEST_WIX } catch { $rejected = $true }
$rejected | ConvertTo-Json
""", APS_TEST_WIX=str(root)))

    def test_cargo_prelude_clears_both_overrides_but_keeps_target_flags(self):
        prelude = run_script(step(job(WORKFLOW.read_text(), "windows"), "Build binaries")).split("cargo build", 1)[0]
        result = self.ps("""
$env:RUSTFLAGS = '-Dwarnings'
$env:CARGO_ENCODED_RUSTFLAGS = '-Dwarnings'
$env:CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_RUSTFLAGS = '-C target-feature=+crt-static'
Invoke-Expression $env:APS_TEST_PRELUDE
@{ globalFlags = $env:RUSTFLAGS; encodedFlags = $env:CARGO_ENCODED_RUSTFLAGS
   targetFlags = $env:CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_RUSTFLAGS } | ConvertTo-Json -Compress
""", APS_TEST_PRELUDE=prelude)
        self.assertFalse(result["globalFlags"])
        self.assertFalse(result["encodedFlags"])
        self.assertIn("+crt-static", result["targetFlags"])

    def test_native_nonzero_exit_is_not_hidden(self):
        result = self.ps("""
$child = Join-Path $PSHOME $(if ($IsWindows) { 'pwsh.exe' } else { 'pwsh' })
try {
    Invoke-CheckedNative $child @('-NoProfile', '-Command', 'exit 37')
    $message = 'unexpected success'
} catch { $message = $_.Exception.Message }
$message | ConvertTo-Json
""")
        self.assertIn("Native command failed (37)", result)

    def test_runtime_import_guard_rejects_release_and_debug_redist_dlls(self):
        dlls = ["VCRUNTIME140.dll", "vcruntime140_1.dll", "MSVCP140_ATOMIC_WAIT.dll", "MSVCR120.dll",
                "CONCRT140.dll", "VCOMP140.dll", "ucrtbased.dll", "KERNEL32.dll", "ucrtbase.dll"]
        result = self.ps("""
function Invoke-CheckedNative { "    $env:APS_TEST_IMPORT" }
$results = @{}
foreach ($dll in ($env:APS_TEST_DLLS | ConvertFrom-Json)) {
    $env:APS_TEST_IMPORT = $dll
    try { Assert-StaticRuntime 'fixture-dumpbin' @('fixture.exe'); $results[$dll] = 'accepted' }
    catch { $results[$dll] = 'rejected' }
}
$results | ConvertTo-Json -Compress
""", APS_TEST_DLLS=json.dumps(dlls))
        self.assertEqual({dll: "accepted" if dll in ("KERNEL32.dll", "ucrtbase.dll") else "rejected"
                          for dll in dlls}, result)


if __name__ == "__main__":
    unittest.main()
