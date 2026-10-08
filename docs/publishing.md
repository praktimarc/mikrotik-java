# Maven Central publishing

This fork publishes under `io.github.praktimarc:mikrotik`. It retains Java 11 and the public `me.legrange.mikrotik.*` API. Maven Central is the distribution target because consumers can resolve it anonymously with standard Maven settings.

## Current release and baseline

- Release: `3.0.8-praktimarc.4`
- Baseline GitHub tag: `v3.0.8-praktimarc.4`
- Baseline commit: `c170858efaac04fc78771903ef4c2bdbb6d35325`
- The tag and GitHub Release must never be modified to introduce publishing metadata.
- Publishing metadata lives on `master` while `src/` remains identical to the release tag. The workflow also compares all compiled class bytes against the existing GitHub Release JAR.
- The Maven build uses a fixed archive timestamp for this release. Update `project.build.outputTimestamp` when preparing each new version.

The publishing setup is prepared separately from the actual deployment. Do not claim that `.4` is available on Maven Central until a fresh external Maven resolution succeeds.

## Prerequisites

1. Verify the `io.github.praktimarc` namespace in the [Central Portal](https://central.sonatype.com/).
2. Generate a Central Portal publishing user token.
3. Create or select a suitable GPG signing key and store its exported private key and passphrase securely.
4. Configure these GitHub Actions repository secrets, without ever committing their values:
   - `CENTRAL_TOKEN_USERNAME`
   - `CENTRAL_TOKEN_PASSWORD`
   - `MAVEN_GPG_PRIVATE_KEY`
   - `MAVEN_GPG_PASSPHRASE`
5. Ensure the public signing key is accessible to signature validators through a suitable public key server.

The workflow uses `actions/setup-java@v6` to create temporary Maven authentication settings and import the signing key. Secrets must not be stored in the POM, workflows, documentation, source tree, or commit history.

## Prepare and stage a release

For `.4`, no new Git tag or GitHub Release is necessary. After separate authorization for staging/uploading to Sonatype, manually start **Stage Maven Central Release** on branch `master` with input `3.0.8-praktimarc.4`.

The workflow checks:

- the input version equals the POM version and is not a snapshot;
- the corresponding immutable `v<version>` tag exists;
- `src/` is identical between the tag and current `master`;
- Maven tests and the main, source and Javadoc JARs succeed;
- compiled `.class` files match the GitHub Release main JAR byte-for-byte;
- the bundle is signed with `maven-gpg-plugin` and uploaded using the Sonatype Central Maven plugin.

The Maven profile `central-release` is activated only during staging. It sets `autoPublish=false` and `waitUntil=validated`; uploading a validated bundle does **not** permanently publish it.

After reviewing the deployment at [Central Portal Deployments](https://central.sonatype.com/publishing/deployments), obtain separate explicit authorization for the irreversible **Publish** action. Published GAVs cannot be overwritten.

## External verification

After the portal reports the version as published and Maven Central has propagated the files, verify with a clean local Maven repository:

```bash
mvn -B -U -Dmaven.repo.local=/tmp/mikrotik-clean-m2 \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
  -Dartifact=io.github.praktimarc:mikrotik:3.0.8-praktimarc.4
```

Then clone `praktimarc/mikrotik-facade` and run `mvn clean verify` with an empty Maven cache and Java 17. No extra repository, token, installed JAR, or `systemPath` must be necessary.

## Later releases

1. Update the POM version to e.g. `3.0.8-praktimarc.5`, update the archive timestamp and release documentation, and run normal CI and compatibility tests.
2. The existing `release.yml` **automatically** creates an annotated tag and GitHub Release after pushing a `release: v<version>` commit to `master`. Because those are separate actions, obtain explicit approval for tag creation and the GitHub Release before pushing such a commit.
3. Once the GitHub tag and release assets exist, obtain approval for Sonatype upload and manually dispatch the Central staging workflow using the exact version.
4. Review the signed bundle, then obtain separate explicit authorization for permanent publication in the Central Portal.
5. Verify Maven resolution anonymously from a fresh cache and update downstream consumer documentation.

Ordinary `mvn clean verify` requires neither a Central token nor a GPG key. The consumer workflow does not require JitPack or manually installed JARs.
