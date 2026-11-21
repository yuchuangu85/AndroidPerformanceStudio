"""Pinned, offline Studio perflib versus Rust same-file comparison."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_hash(path, expected):
    actual = digest(path)
    if actual != expected:
        raise ValueError(f"SHA256 mismatch: {path}: expected {expected}, got {actual}")
    return actual


def source_manifest(root=ROOT):
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--",
                                     "rust-desktop", "tools/heap-parity"], cwd=root).split(b"\0")
    result = {}
    for raw in sorted(set(paths) - {b""}):
        name = raw.decode()
        path = root / name
        result[name] = digest(path) if path.is_file() else None
    return result


def rows_diff(left, right, keys, fields):
    def index(rows):
        result = {}
        for row in rows:
            key = tuple(row[k] for k in keys)
            if key in result:
                raise ValueError(f"duplicate row identity: {key}")
            result[key] = row
        return result
    a, b = index(left), index(right)
    differences = []
    for key in sorted(a.keys() | b.keys()):
        if key not in a or key not in b:
            differences.append({"key": key, "studio": a.get(key), "rust": b.get(key), "reason": "missing row"})
            continue
        for field in fields:
            # Missing is not null; null is not zero.
            if field not in a[key] or field not in b[key] or a[key][field] != b[key][field]:
                differences.append({"key": key, "field": field,
                                    "studio": a[key].get(field), "rust": b[key].get(field),
                                    "studioPresent": field in a[key], "rustPresent": field in b[key]})
    return differences


ROOT_KIND = {"NATIVE_STATIC": "JNI_GLOBAL", "NATIVE_LOCAL": "JNI_LOCAL", "JAVA_LOCAL": "JAVA_FRAME",
             "SYSTEM_CLASS": "STICKY_CLASS", "BUSY_MONITOR": "MONITOR_USED", "NATIVE_MONITOR": "JNI_MONITOR"}


def compare(studio, rust):
    # Heap display capitalization is an explicit presentation normalization only.
    def classes(data):
        return [dict(r, heap=r["heap"].lower()) for r in data["classes"]]
    def roots(data):
        from collections import Counter
        counts = Counter((r["id"], ROOT_KIND.get(r["kind"], r["kind"])) for r in data["roots"])
        return [{"id": key[0], "kind": key[1], "count": count} for key, count in counts.items()]
    objects_a = [dict(r, heap=r["heap"].lower() if r["heap"] else None) for r in studio["objects"]]
    objects_b = [dict(r, heap=r["heap"].lower() if r["heap"] else None) for r in rust["objects"]]
    differences = {
        "classes": rows_diff(classes(studio), classes(rust), ("heap", "classId"), ("count", "shallow", "retainedSum")),
        "roots": rows_diff(roots(studio), roots(rust), ("id", "kind"), ("count",)),
        "objects": rows_diff(objects_a, objects_b, ("id",), ("classId", "heap", "shallow", "retained", "native", "depth", "rootChain", "rootChainComplete")),
    }
    unsupported = ["Root strength is not exposed by perflib RootObj; raw kinds and multiplicities are compared, not fabricated strengths.",
                   "Native size is null in the Rust exporter; this comparison cannot establish native-size parity.",
                   "Root-chain tie-breaking may differ: chain differences are reported, not silently accepted.",
                   "Class-object histogram and union retained-size metrics are outside this extraction."]
    return {"status": "different" if any(differences.values()) else "incomplete", "differences": differences,
            "unsupported": unsupported, "counts": {"studioClassRows": len(studio["classes"]), "rustClassRows": len(rust["classes"]),
            "studioRawRoots": len(studio["roots"]), "rustRawRoots": len(rust["roots"])}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--studio-contents", type=Path, required=True)
    parser.add_argument("--hprof", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=ROOT / "build/heap-parity")
    parser.add_argument("--object-id", action="append", default=[])
    args = parser.parse_args()
    baseline = json.loads((HERE / "studio-baseline.json").read_text())
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    hashes = {name: verify_hash(args.studio_contents / name, expected) for name, expected in baseline["files"].items()}
    hprof_hash = verify_hash(args.hprof, baseline["hprofSha256"])
    product = json.loads((args.studio_contents / "Resources/product-info.json").read_text())
    for key in ("productCode", "buildNumber"):
        if product[key] != baseline[key]:
            raise ValueError(f"Studio identity mismatch for {key}")
    cargo = "/opt/homebrew/bin/cargo"
    cargo_version = subprocess.check_output([cargo, "--version"], text=True).strip()
    if not cargo_version.startswith("cargo 1.99.1 "):
        raise ValueError(f"Expected cargo 1.99.1, got {cargo_version}")
    java = args.studio_contents / "jbr/Contents/Home/bin/java"
    javac = java.with_name("javac")
    cp = f"{args.studio_contents}/plugins/android/lib/*:{args.studio_contents}/lib/*"
    sources = source_manifest()
    subprocess.run([str(javac), "-cp", cp, "-d", str(args.out), str(HERE / "StudioProbe.java")], check=True, cwd=ROOT)
    with (args.out / "studio.json").open("w") as output:
        subprocess.run([str(java), "-Xmx3g", "-cp", f"{args.out}:{cp}", "StudioProbe", str(args.hprof.resolve()), *args.object_id],
                       check=True, cwd=ROOT, stdout=output)
    selected_ids = [row["id"] for row in json.loads((args.out / "studio.json").read_text())["objects"]]
    with (args.out / "rust.json").open("w") as output:
        subprocess.run([cargo, "run", "--offline", "--locked", "--manifest-path", str(ROOT / "rust-desktop/Cargo.toml"),
                        "-p", "aps-hprof-engine", "--example", "parity_export", "--", str(args.hprof.resolve()), *selected_ids],
                       check=True, cwd=ROOT, stdout=output)
    # Detect an input/library change during either analysis as well as before it.
    for name, expected in hashes.items():
        verify_hash(args.studio_contents / name, expected)
    verify_hash(args.hprof, hprof_hash)
    if source_manifest() != sources:
        raise ValueError("Analyzer source manifest changed during comparison")
    report = compare(json.loads((args.out / "studio.json").read_text()), json.loads((args.out / "rust.json").read_text()))
    report["identity"] = {"baseline": baseline, "product": product, "hprofSha256": hprof_hash, "files": hashes,
                          "cargo": cargo_version, "java": subprocess.check_output([str(java), "--version"], text=True),
                          "rustRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                          "sourceManifest": sources}
    (args.out / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "counts": report["counts"], "differenceCounts": {k: len(v) for k, v in report["differences"].items()},
                      "report": str(args.out / "comparison.json")}))
    return 1 if report["status"] == "different" else 2


if __name__ == "__main__":
    sys.exit(main())
