# Solr ZooKeeper dependency release

This fork supplies `org.apache.zookeeper:zookeeper:3.9.5-solr-ref-4798.27c88d635a5d`
for Solr issue #4798. Divergent learner histories receive the authoritative SNAP
instead of TRUNC; matching histories retain DIFF. Jute remains Apache 3.9.5.
The release is built from a clean commit containing the correction, native tests,
and this recipe. It is a dependency release, with Apache license and notices.

Run the native controls on that commit with JDK 25.0.4.1 and Maven 3.9.9:

```sh
JAVA_HOME=/path/to/jdk-25.0.4.1 /path/to/maven-3.9.9/bin/mvn -B -ntp \
  -pl zookeeper-server -Dcheckstyle.skip -Dspotbugs.skip=true \
  -Dtest=LearnerHandlerSnapshotFallbackTest,QuorumSnapshotRollbackTest,LearnerHandlerTest test
```

Require all 16 cases to execute with no failures, errors, or skips. Retain raw
results and source identity under `/shared/agents`; test logs and retained
ZooKeeper snapshots are not release assets.

Build in two independent clean clones of the same commit:

```sh
python3 solr-ref-release/build_release.py --maven /path/to/maven-3.9.9/bin/mvn \
  --java-home /path/to/jdk-25.0.4.1 --output /outside/checkout/release
```

Require byte equality of the main jar and its descriptor, and require all six
LearnerHandler class files to match the validated correction. The generated
manifest pins every asset. The jar manifest binds the actual clean Git commit;
`provenance.json` records upstream source, correction digest and fixed build
inputs. The Ivy descriptor preserves effective nonoptional compile/runtime
dependencies, classifiers and exclusions. Source is available both as a jar and
as the complete tagged source archive and patch.

After verifying those results, push the source branch and create the public
release pointing to the same source commit:

```sh
git push origin HEAD:solr-ref-4798
gh release create dependency-zookeeper-3.9.5-solr-ref-4798.27c88d635a5d \
  /outside/checkout/release/* --repo markrmiller/zookeeper-solr-ref \
  --target "$(git rev-parse HEAD)" \
  --title 'ZooKeeper 3.9.5: authoritative snapshot fallback for Solr #4798' \
  --notes-file /outside/checkout/validated-release-notes.md
```

Verify the live tag commit and anonymously downloaded jar/descriptor hashes
before selecting this version in Solr. Pin the independently measured jar and
descriptor checksums in the Solr checkout. Do not overwrite published assets or
reuse a version for different bytes; a correction requires a new source-bound
version. GitHub release hosting is public and needs no download credentials.
