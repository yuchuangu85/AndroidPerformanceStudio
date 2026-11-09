#!/usr/bin/env python3
"""Source-bound release candidates and one fail-closed publication transaction."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

from macos_payload import sha256_file
from qualification import GateError, require_environment
import sign

TARGETS = (("windows", "x64"), ("macos", "arm64"), ("macos", "x64"), ("linux", "arm64"), ("linux", "x64"))
STATUSES = {"windows": "authenticode-verified", "macos": "signed-notarized", "linux": "unsigned-linux-artifact"}
# Tag-push development releases carry no signing or notarization evidence.
DEVELOPMENT_STATUS = "unsigned-development-artifact"


def unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("Duplicate JSON member")
        result[name] = value
    return result


def normalize_version(value):
    if not isinstance(value, str) or not re.fullmatch(r"v?(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", value) or len(value) > 32:
        raise GateError("Version must be canonical numeric semantic version without leading zeroes")
    return value.removeprefix("v")


def identity(version, source_sha, run_id, attempt):
    if normalize_version(version) != version or not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise GateError("Invalid release source identity")
    if not all(isinstance(value, str) and re.fullmatch(r"[1-9][0-9]*", value) for value in (run_id, attempt)):
        raise GateError("Explicit workflow run and attempt identity is required")


def asset_names(target, version, arch):
    if (target, arch) not in TARGETS:
        raise GateError("Unsupported release candidate target")
    if target == "linux":
        return {"AndroidPerfermanceStudio-" + version + "-linux-" + arch + "." + ext for ext in ("deb", "rpm")}
    return sign.names(target, version, arch)


def inventory(directory, expected):
    if directory.is_symlink() or not directory.is_dir() or {path.name for path in directory.iterdir()} != expected:
        raise GateError("Release asset mismatch: unexpected or missing candidate files")
    result = {}
    for path in sorted(directory.iterdir()):
        if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= 10 * 1024 * 1024 * 1024:
            raise GateError("Invalid release candidate file")
        result[path.name] = {"sha256": sha256_file(path), "size": path.stat().st_size}
    return result


def record_candidate(assets, candidate, target, arch, version, source_sha, run_id, attempt, signing_receipt=None, development=False):
    identity(version, source_sha, run_id, attempt)
    expected = inventory(assets, asset_names(target, version, arch))
    if not development and target != "linux":
        if signing_receipt is None:
            raise GateError("Signed release candidate requires a successful signing receipt")
        sign.verify_receipt(signing_receipt, assets, target, version, arch, source_sha)
    candidate.mkdir()
    shutil.copytree(assets, candidate / "assets")
    if inventory(candidate / "assets", set(expected)) != expected:
        raise GateError("Candidate bytes changed while recording receipt")
    data = {"schema": 1, "version": version, "source_sha": source_sha, "run_id": run_id, "run_attempt": attempt,
            "platform": target, "arch": arch, "status": DEVELOPMENT_STATUS if development else STATUSES[target],
            "assets": expected}
    with (candidate / "receipt.json").open("x") as stream:
        json.dump(data, stream, sort_keys=True)


def candidate_receipts(root, version, source_sha, run_id, attempt, development=False):
    identity(version, source_sha, run_id, attempt)
    expected_dirs = {"release-candidate-" + target + "-" + arch + "-" + run_id + "-" + attempt for target, arch in TARGETS}
    if root.is_symlink() or not root.is_dir() or {path.name for path in root.iterdir()} != expected_dirs:
        raise GateError("Missing or unexpected per-job release candidate receipts")
    assets = {}
    for target, arch in TARGETS:
        folder = root / ("release-candidate-" + target + "-" + arch + "-" + run_id + "-" + attempt)
        receipt = folder / "receipt.json"
        if folder.is_symlink() or {path.name for path in folder.iterdir()} != {"assets", "receipt.json"} or receipt.is_symlink():
            raise GateError("Invalid release candidate envelope")
        try:
            if receipt.stat().st_size > 16384:
                raise ValueError()
            data = json.loads(receipt.read_text(), object_pairs_hook=unique_object)
        except (OSError, ValueError):
            raise GateError("Invalid per-job release receipt") from None
        actual = inventory(folder / "assets", asset_names(target, version, arch))
        expected = {"schema": 1, "version": version, "source_sha": source_sha, "run_id": run_id, "run_attempt": attempt,
                    "platform": target, "arch": arch, "status": DEVELOPMENT_STATUS if development else STATUSES[target],
                    "assets": actual}
        if not isinstance(data, dict) or type(data.get("schema")) is not int or data != expected:
            raise GateError("Per-job receipt source, run, signing status or bytes do not match")
        for name, digest in actual.items():
            if name in assets:
                raise GateError("Duplicate candidate release asset")
            assets[name] = {**digest, "path": folder / "assets" / name}
    return assets


class GitHub:
    def __init__(self, repository):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise GateError("Invalid release repository")
        self.repository = repository
        self.base = "repos/" + repository

    def request(self, endpoint, method="GET", body=None, missing=False):
        args = ["gh", "api", "--include", "--method", method, self.base + endpoint]
        if body is not None:
            args += ["--input", "-"]
        try:
            result = subprocess.run(args, input=json.dumps(body).encode() if body is not None else None,
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=120, check=False)
            output = result.stdout.replace(b"\r\n", b"\n")
            header, separator, raw = output.partition(b"\n\n")
            status = re.match(rb"HTTP/[0-9.]+ ([0-9]{3})\b", header)
            if not separator or status is None:
                raise ValueError()
            code = int(status[1])
            if missing and code == 404:
                return None
            if result.returncode or not 200 <= code < 300:
                raise ValueError()
            data = json.loads(raw, object_pairs_hook=unique_object)
            if not isinstance(data, dict):
                raise ValueError()
            return data
        except (OSError, ValueError, subprocess.SubprocessError):
            raise GateError("GitHub release request failed; sensitive details withheld; no retry") from None

    def release(self, tag):
        return self.request("/releases/tags/" + tag, missing=True)

    def tag(self, tag):
        reference = self.request("/git/ref/tags/" + tag, missing=True)
        if reference is None:
            return None
        try:
            node = reference["object"]
            ref_sha = node["sha"]
            for _ in range(8):
                if not re.fullmatch(r"[0-9a-f]{40}", node["sha"]):
                    break
                if node["type"] == "commit":
                    return ref_sha, node["sha"]
                if node["type"] != "tag":
                    break
                node = self.request("/git/tags/" + node["sha"])["object"]
        except (KeyError, TypeError):
            pass
        raise GateError("Release tag does not resolve to a valid commit")

    def upload(self, tag, paths):
        # No --clobber: a duplicate filename is an error, never a replace/reconcile operation.
        sign.command(["gh", "release", "upload", tag, *paths, "--repo", self.repository])

    def download(self, asset_id, destination):
        try:
            with destination.open("xb") as stream:
                result = subprocess.run(["gh", "api", self.base + "/releases/assets/" + str(asset_id),
                                         "-H", "Accept: application/octet-stream"], stdout=stream,
                                        stderr=subprocess.DEVNULL, timeout=2400, check=False)
            if result.returncode:
                raise GateError("Uploaded asset could not be verified; no retry")
        except (OSError, subprocess.SubprocessError):
            raise GateError("Uploaded asset download failed; sensitive details withheld") from None


def check_release(github, version, source_sha):
    tag = "v" + normalize_version(version)
    tagged = github.tag(tag)
    if tagged is not None and tagged[1] != source_sha:
        raise GateError("Release tag commit differs from the qualified source")
    if github.release(tag) is not None:
        raise GateError("Release already exists; automatic reset, adoption and clobber are forbidden")
    return tagged


def assert_tag(github, tag, frozen, source_sha):
    if github.tag(tag) != frozen or frozen[1] != source_sha:
        raise GateError("Release tag changed during publication")


def release_metadata(release, release_id, tag, source_sha, draft):
    if (not isinstance(release, dict) or type(release.get("id")) is not int or release["id"] != release_id
            or release["id"] <= 0
            or release.get("tag_name") != tag or release.get("target_commitish") != source_sha
            or release.get("draft") is not draft or release.get("prerelease") is not False):
        raise GateError("Release identity or draft state changed during publication")


def remote_assets(release, expected):
    observed = {}
    entries = release.get("assets")
    if not isinstance(entries, list):
        raise GateError("Release asset metadata is unavailable")
    for asset in entries:
        if not isinstance(asset, dict):
            raise GateError("Invalid release asset metadata")
        name = asset.get("name")
        if (name not in expected or name in observed or type(asset.get("id")) is not int or asset["id"] <= 0
                or asset.get("state") != "uploaded" or asset.get("size") != expected[name]["size"]):
            raise GateError("Release asset mismatch: metadata differs from per-job receipts")
        observed[name] = asset["id"]
    if set(observed) != set(expected) or len(set(observed.values())) != len(observed):
        raise GateError("Release asset mismatch: missing, duplicate or extra uploaded assets")
    return observed


def assert_release_binding(github, tag, release_id):
    bound = github.release(tag)
    if not isinstance(bound, dict) or bound.get("id") != release_id:
        raise GateError("Release tag now identifies another or missing release")


def publish(github, root, version, source_sha, run_id, attempt, development=False):
    expected = candidate_receipts(root, version, source_sha, run_id, attempt, development)
    tag = "v" + version
    frozen = check_release(github, version, source_sha)
    if frozen is None:
        github.request("/git/refs", "POST", {"ref": "refs/tags/" + tag, "sha": source_sha})
        frozen = github.tag(tag)
        if frozen is None or frozen != (source_sha, source_sha):
            raise GateError("New release tag does not match the qualified source")
    assert_tag(github, tag, frozen, source_sha)
    release = github.request("/releases", "POST", {"tag_name": tag, "target_commitish": source_sha,
                             "name": "AndroidPerfermanceStudio " + version, "draft": True, "prerelease": False})
    release_id = release.get("id") if isinstance(release, dict) else None
    release_metadata(release, release_id, tag, source_sha, True)
    if release.get("assets") != []:
        raise GateError("A new draft must have no pre-existing assets")
    assert_tag(github, tag, frozen, source_sha)
    assert_release_binding(github, tag, release_id)
    github.upload(tag, [expected[name]["path"] for name in sorted(expected)])
    release = github.request("/releases/" + str(release_id))
    release_metadata(release, release_id, tag, source_sha, True)
    uploaded = remote_assets(release, expected)
    with tempfile.TemporaryDirectory(prefix="aps-upload-verification-") as temporary:
        for name, asset_id in uploaded.items():
            destination = Path(temporary) / str(asset_id)
            github.download(asset_id, destination)
            if destination.stat().st_size != expected[name]["size"] or sha256_file(destination) != expected[name]["sha256"]:
                raise GateError("Uploaded asset bytes do not match the qualified per-job receipt")
    # Asset IDs have immutable content; check IDs and tag again immediately before publishing.
    release = github.request("/releases/" + str(release_id))
    release_metadata(release, release_id, tag, source_sha, True)
    if remote_assets(release, expected) != uploaded:
        raise GateError("Uploaded asset identities changed during verification")
    assert_tag(github, tag, frozen, source_sha)
    github.request("/releases/" + str(release_id), "PATCH", {"draft": False, "make_latest": "true"})
    final = github.request("/releases/" + str(release_id))
    release_metadata(final, release_id, tag, source_sha, False)
    assert_tag(github, tag, frozen, source_sha)
    if remote_assets(final, expected) != uploaded:
        raise GateError("Final published release differs from verified assets")
    assert_release_binding(github, tag, release_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("record", "check", "publish"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--platform", choices=tuple(STATUSES))
    parser.add_argument("--arch", choices=("x64", "arm64"))
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--candidates", type=Path)
    parser.add_argument("--signing-receipt", type=Path)
    parser.add_argument("--repository")
    parser.add_argument("--development", action="store_true",
                        help="tag-push development release without signing or live-provider evidence")
    args = parser.parse_args()
    try:
        identity(args.version, args.source_sha, args.run_id, args.run_attempt)
        if args.action == "publish" and (os.environ.get("APS_ENABLE_RELEASE_PUBLICATION") != "true"
                                         or (os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
                                             and not args.development)):
            raise GateError("Release publication requires explicit manual opt-in")
        if sign.command(["git", "rev-parse", "HEAD"]).strip() != args.source_sha:
            raise GateError("Publication source SHA differs from the checkout")
        if args.action == "record":
            if not args.assets or not args.candidate or not args.platform or not args.arch:
                raise GateError("Explicit candidate target and paths are required")
            record_candidate(args.assets, args.candidate, args.platform, args.arch, args.version, args.source_sha,
                             args.run_id, args.run_attempt, args.signing_receipt, args.development)
        else:
            require_environment(("GH_TOKEN",))
            github = GitHub(args.repository or "")
            if args.action == "check":
                check_release(github, args.version, args.source_sha)
            else:
                if not args.candidates:
                    raise GateError("Per-job candidates are required before publication")
                publish(github, args.candidates, args.version, args.source_sha, args.run_id, args.run_attempt,
                        args.development)
    except (GateError, OSError, ValueError, TypeError, KeyError):
        error = sys.exc_info()[1]
        print(str(error) if isinstance(error, GateError) else "Release publication gate failed; details withheld", file=sys.stderr)
        return 1
    print("Release gate passed: " + args.action)
    return 0


if __name__ == "__main__":
    sys.exit(main())
