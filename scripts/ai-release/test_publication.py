"""Deterministic release transaction fixtures; FakeGitHub never invokes a network client."""

import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import publication
from qualification import GateError
import sign

SOURCE = "a" * 40


class FakeGitHub:
    def __init__(self):
        self.tag_value = None
        self.release_value = None
        self.calls = []
        self.bytes = {}
        self.hook = lambda *args: None

    def tag(self, tag):
        self.calls.append(("tag", tag))
        self.hook("tag", tag)
        return self.tag_value

    def release(self, tag):
        self.calls.append(("release", tag))
        self.hook("release", tag)
        return copy.deepcopy(self.release_value)

    def releases(self):
        self.calls.append(("releases",))
        self.hook("releases", None)
        return [copy.deepcopy(self.release_value)] if self.release_value else []

    def request(self, path, method="GET", body=None):
        self.calls.append((method, path, copy.deepcopy(body)))
        self.hook(method, path)
        if (method, path) == ("POST", "/git/refs"):
            self.tag_value = (body["sha"], body["sha"])
            return {"object": {"type": "commit", "sha": body["sha"]}}
        if (method, path) == ("POST", "/releases"):
            self.release_value = {**body, "id": 11, "assets": []}
        elif (method, path) == ("PATCH", "/releases/11"):
            self.release_value["draft"] = body["draft"]
        return copy.deepcopy(self.release_value)

    def upload(self, tag, paths):
        self.calls.append(("upload", tag))
        self.hook("upload", tag)
        for index, path in enumerate(paths, start=100):
            data = path.read_bytes()
            self.bytes[index] = data
            self.release_value["assets"].append({"id": index, "name": path.name, "size": len(data), "state": "uploaded"})

    def download(self, asset_id, destination):
        self.calls.append(("download", asset_id))
        self.hook("download", asset_id)
        destination.write_bytes(self.bytes[asset_id])


class PublicationFixtures(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="aps-publication-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.candidates = self.root / "candidates"
        self.candidates.mkdir()
        for target, arch in publication.TARGETS:
            source = self.root / (target + "-" + arch)
            source.mkdir()
            for name in publication.asset_names(target, "1.2.3", arch):
                (source / name).write_bytes((target + "/" + arch + "/" + name).encode())
            signing = None
            if target != "linux":
                signing = self.root / (target + "-" + arch + ".json")
                signing.write_text(json.dumps({"schema": 1, "status": publication.STATUSES[target], "platform": target,
                    "version": "1.2.3", "arch": arch, "source_sha": SOURCE, "assets": sign.hashes(source, target, "1.2.3", arch)}))
            publication.record_candidate(source, self.candidates / ("release-candidate-" + target + "-" + arch + "-123-2"),
                                         target, arch, "1.2.3", SOURCE, "123", "2", signing)

    def publish(self, github):
        publication.publish(github, self.candidates, "1.2.3", SOURCE, "123", "2")

    def test_normalized_version_identity_matches_optional_v_but_rejects_aliases(self):
        self.assertEqual("1.2.3", publication.normalize_version("v1.2.3"))
        self.assertEqual("1.2.3", publication.normalize_version("1.2.3"))
        for version in ("01.2.3", "1.02.3", "v1.2.03", "1.2", "1.2.3 ", "1.2.3-pre", "V1.2.3"):
            with self.assertRaises(GateError):
                publication.normalize_version(version)

    def test_all_five_jobs_and_ten_assets_bind_source_version_run_and_attempt(self):
        expected = publication.candidate_receipts(self.candidates, "1.2.3", SOURCE, "123", "2")
        self.assertEqual(10, len(expected))
        for source_sha, run_id, attempt in (("b" * 40, "123", "2"), (SOURCE, "124", "2"), (SOURCE, "123", "3")):
            with self.assertRaises(GateError):
                publication.candidate_receipts(self.candidates, "1.2.3", source_sha, run_id, attempt)

    def test_mixed_sha_receipt_tamper_or_changed_bytes_prevents_any_remote_operation(self):
        folder = next(iter(self.candidates.iterdir()))
        receipt = folder / "receipt.json"
        original = receipt.read_text()
        data = json.loads(original)
        for key, wrong in (("source_sha", "b" * 40), ("run_attempt", "1"), ("version", "1.2.4"), ("status", "skipped")):
            receipt.write_text(json.dumps({**data, key: wrong}))
            github = FakeGitHub()
            with self.assertRaises(GateError):
                self.publish(github)
            self.assertEqual([], github.calls)
        receipt.write_text(original)
        next(iter((folder / "assets").iterdir())).write_bytes(b"tampered candidate")
        github = FakeGitHub()
        with self.assertRaises(GateError):
            self.publish(github)
        self.assertEqual([], github.calls)

    def test_signing_receipt_is_mandatory_for_mac_and_windows_not_fabricated_for_linux(self):
        for target, arch in (("macos", "arm64"), ("windows", "x64")):
            with self.assertRaises(GateError):
                publication.record_candidate(self.root / (target + "-" + arch), self.root / "unsigned", target, arch,
                                             "1.2.3", SOURCE, "123", "2")
        receipt = self.candidates / "release-candidate-linux-arm64-123-2/receipt.json"
        self.assertEqual("unsigned-linux-artifact", json.loads(receipt.read_text())["status"])

    def test_existing_release_is_never_adopted_reset_or_clobbered(self):
        # Draft releases are hidden from the get-by-tag endpoint; the listing must still refuse them.
        github = FakeGitHub()
        github.release_value = {"id": 55, "draft": True, "assets": [], "tag_name": "v1.2.3"}
        with self.assertRaisesRegex(GateError, "already exists"):
            self.publish(github)
        self.assertFalse(any(call[0] in ("POST", "PATCH", "upload") for call in github.calls))

    def test_wrong_existing_tag_commit_blocks_mutations(self):
        github = FakeGitHub()
        github.tag_value = ("b" * 40, "b" * 40)
        with self.assertRaisesRegex(GateError, "tag commit"):
            self.publish(github)
        self.assertFalse(any(call[0] in ("POST", "PATCH", "upload") for call in github.calls))

    def test_fresh_release_downloads_and_hashes_every_asset_before_publication(self):
        github = FakeGitHub()
        self.publish(github)
        downloads = [index for index, call in enumerate(github.calls) if call[0] == "download"]
        patches = [index for index, call in enumerate(github.calls) if call[0] == "PATCH"]
        self.assertEqual(10, len(downloads))
        self.assertEqual(1, len(patches))
        self.assertLess(max(downloads), patches[0])
        self.assertEqual((SOURCE, SOURCE), github.tag_value)
        self.assertFalse(github.release_value["draft"])
        self.assertEqual(10, len(github.release_value["assets"]))

    def test_same_size_uploaded_byte_tampering_blocks_publish_even_when_metadata_matches(self):
        github = FakeGitHub()

        def mutate(event, asset_id):
            if event == "download":
                github.bytes[asset_id] = b"x" * len(github.bytes[asset_id])

        github.hook = mutate
        with self.assertRaisesRegex(GateError, "Uploaded asset bytes"):
            self.publish(github)
        self.assertTrue(github.release_value["draft"])
        self.assertFalse(any(call[0] == "PATCH" for call in github.calls))

    def test_tag_movement_during_asset_download_blocks_publication(self):
        github = FakeGitHub()

        def mutate(event, _value):
            if event == "download":
                github.tag_value = ("b" * 40, "b" * 40)

        github.hook = mutate
        with self.assertRaisesRegex(GateError, "tag changed"):
            self.publish(github)
        self.assertFalse(any(call[0] == "PATCH" for call in github.calls))

    def test_asset_replacement_after_hash_verification_is_detected(self):
        github = FakeGitHub()
        downloads = []

        def mutate(event, value):
            if event == "download":
                downloads.append(value)
            if event == "GET" and value == "/releases/11" and len(downloads) == 10:
                github.release_value["assets"][0]["id"] = 999

        github.hook = mutate
        with self.assertRaisesRegex(GateError, "identities changed"):
            self.publish(github)
        self.assertFalse(any(call[0] == "PATCH" for call in github.calls))

    def test_annotated_tag_must_be_peeled_to_the_expected_commit(self):
        github = publication.GitHub("fixture/repository")
        with patch.object(github, "request", side_effect=[{"object": {"type": "tag", "sha": "b" * 40}},
                                                         {"object": {"type": "commit", "sha": SOURCE}}]):
            self.assertEqual(("b" * 40, SOURCE), github.tag("v1.2.3"))
        with patch.object(github, "request", return_value={"object": {"type": "tree", "sha": SOURCE}}):
            with self.assertRaises(GateError):
                github.tag("v1.2.3")

    def test_404_is_absence_but_permission_and_network_errors_are_not(self):
        github = publication.GitHub("fixture/repository")
        with patch.object(publication.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout=b"HTTP/2.0 404 Not Found\n\n{}")):
            self.assertIsNone(github.release("v1.2.3"))
        for result in (SimpleNamespace(returncode=1, stdout=b"HTTP/2.0 403 Forbidden\n\n{}"),
                       SimpleNamespace(returncode=1, stdout=b"fixture-sensitive-error")):
            with patch.object(publication.subprocess, "run", return_value=result):
                with self.assertRaises(GateError) as failure:
                    github.release("v1.2.3")
                self.assertNotIn("fixture-sensitive", str(failure.exception))

    def test_publication_opt_out_fails_before_git_or_network_commands(self):
        args = [sys.executable, "-B", str(Path(publication.__file__)), "publish", "--version", "1.2.3", "--source-sha", SOURCE,
                "--run-id", "123", "--run-attempt", "2", "--candidates", str(self.candidates)]
        environment = {**os.environ, "APS_ENABLE_RELEASE_PUBLICATION": "false"}
        result = subprocess.run(args, env=environment, capture_output=True, text=True, timeout=10, check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("explicit manual opt-in", result.stderr)
