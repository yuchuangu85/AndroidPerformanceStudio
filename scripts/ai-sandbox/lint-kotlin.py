#!/usr/bin/env python3
"""Run the already cached ktlint CLI on owned sources only, without Gradle/network."""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    repo = Path(__file__).resolve().parents[2]
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    selected = set()
    jars = []

    def pom_context(path):
        pom = ET.parse(path).getroot()
        props, managed = {}, {}
        parent = pom.find("m:parent", namespace)
        if parent is not None:
            fields = [parent.findtext("m:" + key, namespaces=namespace) for key in ("groupId", "artifactId", "version")]
            matches = sorted((cache / fields[0] / fields[1] / fields[2]).glob("*/*.pom"))
            if matches:
                _, props, managed = pom_context(matches[0])
        version = pom.findtext("m:version", namespaces=namespace)
        if version:
            props["project.version"] = version
        properties = pom.find("m:properties", namespace)
        if properties is not None:
            props.update({child.tag.split("}")[-1]: child.text for child in properties})
        for dep in pom.findall("m:dependencyManagement/m:dependencies/m:dependency", namespace):
            fields = {child.tag.split("}")[-1]: child.text for child in dep}
            if "version" in fields:
                managed[(fields["groupId"], fields["artifactId"])] = fields["version"]
        return pom, props, managed

    def resolve(group, artifact, version):
        if group == "org.jetbrains.kotlin" and artifact == "kotlin-stdlib-common":
            return
        if group == "org.jetbrains.kotlin" and artifact in ("kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8"):
            version = "2.2.10"
        key = (group, artifact)
        if key in selected:
            return
        selected.add(key)
        base = cache / group / artifact / version
        matches = [p for p in base.glob("*/*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
        if not matches:
            raise SystemExit("Missing cached lint dependency: " + ":".join((group, artifact, version)))
        jars.append(str(sorted(matches)[0]))
        poms = sorted(base.glob("*/*.pom"))
        if not poms:
            return
        pom, props, managed = pom_context(poms[0])
        for dep in pom.findall("m:dependencies/m:dependency", namespace):
            fields = {child.tag.split("}")[-1]: child.text for child in dep}
            if fields.get("scope", "compile") not in ("compile", "runtime") or fields.get("optional") == "true":
                continue
            dependency_version = fields.get("version", managed.get((fields["groupId"], fields["artifactId"]), ""))
            for _ in range(5):
                for key, value in props.items():
                    dependency_version = dependency_version.replace("${" + key + "}", value)
            if not dependency_version or "$" in dependency_version:
                raise SystemExit("Unresolved cached POM version: " + str(fields))
            resolve(fields["groupId"], fields["artifactId"], dependency_version)

    resolve("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.10")
    resolve("com.pinterest.ktlint", "ktlint-cli", "1.5.0")
    module = repo / "desktop-viewer/ai-platform/ai-platform-script-runtime"
    sources = sorted(str(p.relative_to(repo)) for p in (module / "src").rglob("*.kt"))
    command = ["/usr/bin/java", "-cp", ":".join(jars), "com.pinterest.ktlint.Main"]
    if sys.argv[1:] == ["--format"]:
        command += ["--format"]
    elif sys.argv[1:]:
        raise SystemExit("Only --format is accepted")
    raise SystemExit(subprocess.call(command + sources, cwd=repo))


if __name__ == "__main__":
    main()
