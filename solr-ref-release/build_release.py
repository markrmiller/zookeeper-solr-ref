#!/usr/bin/env python3
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Build the source-bound Solr ZooKeeper dependency release; never publish implicitly."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

BASE = "293c895a8d966a3ecb92872be4a1daf87d725da2"
SOURCES = {
    "zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/LearnerHandler.java":
        "c2fde68554ba0c75f5fac7f36d717974a3e7db4c16e23d0a25f4918d000a9f7e",
    "zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/FastLeaderElection.java":
        "da2ba3a7d565c390daae509f66aa6f6890d2f49f4ecaa1ee468aadcb56a60932",
}
VERSION = "3.9.5-solr-ref-4798-fle.5330884b1d66"
REPOSITORY = "markrmiller/zookeeper-solr-ref"
TAG = "dependency-zookeeper-" + VERSION
BUILD_TIME = "2026-10-02 22:00 UTC"
NS = {"p": "http://maven.apache.org/POM/4.0.0"}


def run(args, cwd, env=None):
    return subprocess.check_output(args, cwd=cwd, env=env, text=True).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_sources(root):
    for source, expected in SOURCES.items():
        if digest(root / source) != expected:
            raise ValueError("Correction source differs from the native proof: " + source)


def ivy_descriptor(effective, destination):
    project = ET.parse(effective).getroot()
    def value(element, name, default=""):
        return element.findtext("p:" + name, default=default, namespaces=NS)
    if value(project, "version") != VERSION:
        raise ValueError("Unexpected effective project version")
    ivy = ET.Element("ivy-module", {"version": "2.0"})
    ET.SubElement(ivy, "info", {"organisation": "org.apache.zookeeper", "module": "zookeeper", "revision": VERSION})
    configurations = ET.SubElement(ivy, "configurations")
    ET.SubElement(configurations, "conf", {"name": "default", "visibility": "public"})
    publications = ET.SubElement(ivy, "publications")
    ET.SubElement(publications, "artifact", {"name": "zookeeper", "type": "jar", "ext": "jar", "conf": "default"})
    dependencies = ET.SubElement(ivy, "dependencies")
    for dependency in project.findall("p:dependencies/p:dependency", NS):
        if value(dependency, "scope", "compile") not in ("compile", "runtime") or value(dependency, "optional") == "true":
            continue
        group, artifact, version = (value(dependency, key) for key in ("groupId", "artifactId", "version"))
        if not all((group, artifact, version)) or "${" in version:
            raise ValueError("Unresolved runtime dependency")
        if value(dependency, "type", "jar") != "jar":
            raise ValueError("Unsupported runtime dependency type")
        child = ET.SubElement(dependencies, "dependency", {"org": group, "name": artifact, "rev": version, "conf": "default->default"})
        classifier = value(dependency, "classifier")
        if classifier:
            ET.SubElement(child, "artifact", {"name": artifact, "type": "jar", "ext": "jar", "{http://ant.apache.org/ivy/maven}classifier": classifier})
        for exclusion in dependency.findall("p:exclusions/p:exclusion", NS):
            ET.SubElement(child, "exclude", {"org": value(exclusion, "groupId"), "module": value(exclusion, "artifactId"), "name": "*", "type": "*", "ext": "*"})
    ET.indent(ivy)
    ET.ElementTree(ivy).write(destination, encoding="utf-8", xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    if run(["git", "status", "--porcelain", "--untracked-files=all"], root):
        raise ValueError("Build requires a clean committed source checkout")
    head = run(["git", "rev-parse", "HEAD"], root)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, head], cwd=root, check=True)
    verify_sources(root)
    output = args.output.resolve()
    if output.exists() or output.is_relative_to(root):
        raise ValueError("Output must be a new directory outside the source checkout")
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    java_properties = subprocess.run([str(args.java_home.resolve() / "bin/java"), "-XshowSettings:properties", "-version"], cwd=root, env=env, capture_output=True, text=True, check=True).stderr
    if "java.version = 25.0.4.1" not in java_properties:
        raise ValueError("Release requires JDK 25.0.4.1")
    maven = str(args.maven.resolve())
    maven_version = run([maven, "--version"], root, env)
    if "Apache Maven 3.9.9" not in maven_version:
        raise ValueError("Release requires Maven 3.9.9")
    output.mkdir(parents=True)
    common = [maven, "-B", "-ntp", "-pl", "zookeeper-server", "-DskipTests", "-Dcheckstyle.skip", "-Dspotbugs.skip=true", "-Dbuild.time=" + BUILD_TIME]
    commands = [common + ["clean", "package"], common + ["org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom", "-Doutput=" + str(output / "effective-pom.xml")]]
    for index, command in enumerate(commands):
        # Build logs are local verification evidence, not public release assets.
        with (output.parent / (output.name + ".build-" + str(index) + ".log")).open("w") as log:
            subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    jar = root / "zookeeper-server/target" / ("zookeeper-" + VERSION + ".jar")
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        if "Implementation-Build: " + head not in manifest or "-dirty" in manifest:
            raise ValueError("Jar is not bound to the clean source commit")
    for suffix in (".jar", "-sources.jar"):
        name = "zookeeper-" + VERSION + suffix
        shutil.copyfile(root / "zookeeper-server/target" / name, output / name)
    for name in ("LICENSE.txt", "NOTICE.txt"):
        shutil.copyfile(root / name, output / name)
    ivy_descriptor(output / "effective-pom.xml", output / ("ivy-" + VERSION + ".xml"))
    with (output / "source.patch").open("wb") as patch:
        subprocess.run(["git", "diff", "--binary", BASE, head], cwd=root, stdout=patch, check=True)
    with (output / "source.tar").open("wb") as source:
        subprocess.run(["git", "archive", "--format=tar", "--prefix=zookeeper-solr-ref/", head], cwd=root, stdout=source, check=True)
    shutil.copyfile(Path(__file__), output / "build_release.py")
    provenance = {"repository": REPOSITORY, "tag": TAG, "sourceCommit": head, "upstreamCommit": BASE, "productionSourcesSha256": SOURCES, "version": VERSION, "juteVersion": "3.9.5", "javaVersion": "25.0.4.1", "mavenVersion": "3.9.9", "buildTime": BUILD_TIME, "outputTimestamp": 1770840858, "buildGoals": ["clean package", "org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom"], "validation": "Package skips tests. Native regression and control results must be verified separately before publication."}
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n")
    (output / "SHA256SUMS").write_text("".join(digest(path) + "  " + path.name + "\n" for path in sorted(output.iterdir())))
    print(json.dumps({"sourceCommit": head, "output": str(output), "jarSha256": digest(output / jar.name)}))


if __name__ == "__main__":
    main()
