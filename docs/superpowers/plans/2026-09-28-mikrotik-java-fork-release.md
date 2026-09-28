# mikrotik-java Fork Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `praktimarc/mikrotik-java` into a maintainable Praktimarc fork that produces versioned GitHub Release JARs while preserving a clean upstream-compatible bugfix branch.

**Architecture:** The fork-specific release line starts from the already verified bugfix commit `72a40fd735cb8a6328c878ba92f736f83db7d68c` on `fix/sync-listener-error-completion`. A separate branch `feature/praktimarc-release-line` adds only fork identity, documentation and GitHub release automation. Java packages and APIs remain unchanged, while Maven artifact ownership changes to `io.github.praktimarc`.

**Tech Stack:** Java 11 bytecode baseline, `maven-compiler-plugin` 3.13.0 with `<release>11</release>`, JDK 11 for CI/release builds, newer JDKs permitted for local builds, Maven, JUnit 4.13.1, GitHub Actions, GitHub Releases, GitHub CLI on hosted Actions runners.

**Spec:** `docs/superpowers/specs/2026-09-28-mikrotik-java-fork-release-design.md`

## Global Constraints

- Maven coordinates: `io.github.praktimarc:mikrotik`.
- Initial fork version: `3.0.8-praktimarc.1`.
- Initial release tag: `v3.0.8-praktimarc.1`.
- Java packages remain `me.legrange.mikrotik.*`.
- Fork artifacts target Java 11 bytecode.
- No public Java API changes are introduced by the fork setup.
- No Maven Central deployment.
- No GitHub Packages publishing in this phase.
- GitHub Releases are the initial binary distribution mechanism.
- Preserve Apache License 2.0 and original attribution.
- The clean branch `fix/sync-listener-error-completion` must not receive fork-specific changes.
- The upstream PR will later originate from `fix/sync-listener-error-completion`, not from fork `master`.
- Avoid unnecessary GitHub Actions runs.
- Merge to `master`, tagging, creating a release and opening an upstream PR remain separate explicit gates.

## Review Focus

1. A release tag whose version differs from the Maven project version must fail before a GitHub Release is created.
2. A Maven test/build failure must prevent release creation.
3. Missing main, sources or Javadoc JARs must prevent release creation.
4. Ordinary feature-branch pushes must not trigger unnecessary GitHub Actions runs.
5. Fork-specific changes must not appear on `fix/sync-listener-error-completion`.

---

### Task 1: Establish the Fork Release Line and Maven Identity

**Files:**
- Create: `docs/superpowers/specs/2026-09-28-mikrotik-java-fork-release-design.md`
- Create: `docs/superpowers/plans/2026-09-28-mikrotik-java-fork-release.md`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: clean bugfix HEAD `72a40fd735cb8a6328c878ba92f736f83db7d68c`.
- Produces: Maven artifact `io.github.praktimarc:mikrotik:3.0.8-praktimarc.1` targeting Java 11 bytecode.

- [ ] Verify the upstream-compatible bugfix branch against upstream base `ec5f6650816e5e63a00fbb0ae3aa511493df3a65`; expected changed files are only `ApiConnectionImpl.java` and `ApiConnectionImplTest.java`, with one production-line addition.
- [ ] Create `feature/praktimarc-release-line` from `72a40fd735cb8a6328c878ba92f736f83db7d68c` without modifying `fix/sync-listener-error-completion`.
- [ ] Add the approved design and implementation documents.
- [ ] Confirm pre-change Maven identity is `me.legrange:mikrotik:3.0.8`.
- [ ] Change `pom.xml` to `io.github.praktimarc:mikrotik:3.0.8-praktimarc.1`; update project URL and SCM metadata to `https://github.com/praktimarc/mikrotik-java`; do not rename Java packages and do not invoke the Sonatype release profile.
- [ ] Update `maven-compiler-plugin` to 3.13.0 and compile with `<release>11</release>` so the maintained fork targets Java 11 bytecode.
- [ ] Verify Maven model with `mvn help:evaluate` for groupId, artifactId and version when Maven is available.
- [ ] Run `mvn -B -Dtest=ApiConnectionImplTest test` when Maven is available; expected PASS.
- [ ] Commit gate: suggested commit `build: establish praktimarc fork release line`.

### Task 2: Make CI Validate the Maintained Fork Efficiently

**Files:**
- Modify: `.github/workflows/maven.yml`

**Interfaces:**
- Consumes: Maven project identity from Task 1.
- Produces: lightweight verification for `master` without builds for ordinary feature-branch pushes.

- [ ] Confirm existing triggers are `push -> master` and `pull_request -> master`, with `mvn -B package --file pom.xml`.
- [ ] Preserve low-frequency triggers: pushes to `master` and PRs targeting `master`; do not add generic feature-branch push triggers.
- [ ] Use maintained official checkout and Java setup actions with Temurin JDK 11.
- [ ] Run `mvn -B clean verify --file pom.xml`.
- [ ] Validate workflow semantics: feature-branch push does not run CI; PR to `master` does; push to `master` does; tests run during verify.
- [ ] Run equivalent Maven verification locally where available; a newer local JDK is acceptable because Maven compiles with `<release>11</release>`; do not claim a green build without real evidence.
- [ ] Commit gate: suggested commit `ci: verify maintained fork on master changes`.

### Task 3: Add Tag-Based GitHub Release Automation

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: Maven project version `3.0.8-praktimarc.1`.
- Produces the main, sources and Javadoc JARs plus a GitHub Release for `v3.0.8-praktimarc.1`.

- [ ] Trigger only tags matching `v*-praktimarc.*`; set `contents: write`; do not run on ordinary pushes or pull requests.
- [ ] Check out the exact tagged revision and use Temurin JDK 11.
- [ ] Read Maven project version into `VERSION` and require `GITHUB_REF_NAME == "v${VERSION}"`; mismatch must stop before publication.
- [ ] Run `mvn -B clean verify --file pom.xml`; failure stops release publication.
- [ ] Require exact files `target/mikrotik-${VERSION}.jar`, `target/mikrotik-${VERSION}-sources.jar`, and `target/mikrotik-${VERSION}-javadoc.jar`; absence must fail the workflow.
- [ ] Create or populate the GitHub Release with authenticated `gh` using `GH_TOKEN = github.token`, attaching exactly the three required JARs.
- [ ] Verify failure paths and confirm no Sonatype/Maven Central command is executed.
- [ ] Commit gate: suggested commit `ci: add praktimarc GitHub release workflow`.

### Task 4: Document Fork Usage and Perform Full Pre-Release Verification

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Maven identity and GitHub Release mechanism from Tasks 1–3.
- Produces: clear usage instructions for Praktimarc's maintained fork and a verified release candidate.

- [ ] Add a fork notice identifying `praktimarc/mikrotik-java` as a maintained fork of `GideonLeGrange/mikrotik-java`, based on upstream `3.0.8`, with first fork release `3.0.8-praktimarc.1`; do not imply official upstream status.
- [ ] Document Maven identity `io.github.praktimarc:mikrotik:3.0.8-praktimarc.1`; note that GitHub Releases alone are not a remote Maven repository; document `mvn install` or local installation of the downloaded JAR; Java imports remain `me.legrange.mikrotik.*`; document Java 11 as the bytecode baseline.
- [ ] Document version convention `3.0.8-praktimarc.N` and reset to `3.0.9-praktimarc.1` for a future upstream 3.0.9 base.
- [ ] Run `mvn -B clean verify --file pom.xml`; expected BUILD SUCCESS and zero failing tests.
- [ ] Verify the three expected JARs and confirm the main JAR contains `me/legrange/mikrotik/` classes.
- [ ] Re-verify upstream PR branch isolation: the clean branch still contains only regression test and one-line production fix, without fork POM/README/workflow/docs changes.
- [ ] Review fork release branch diff against upstream base; expected change classes are bugfix, test, Maven identity/version, Java 11 compiler baseline, approved docs, CI workflow, release workflow and fork README; no Java API/package rename.
- [ ] Commit gate: suggested commit `docs: document praktimarc fork distribution`.
- [ ] Push gate: push `feature/praktimarc-release-line` only after fresh verification; stop before merging to `master`.

## Integration and Release Gates

After all four tasks are verified:

1. **Fork merge gate** — merge `feature/praktimarc-release-line` into the fork's `master` only with explicit approval.
2. **Post-merge verification gate** — verify `master` again before tagging.
3. **Tag gate** — create `v3.0.8-praktimarc.1` only with explicit approval.
4. **Release gate** — verify the tag-triggered GitHub Actions run and its three generated JAR assets before declaring the first fork release complete.
5. **Upstream PR gate** — separately review `fix/sync-listener-error-completion` and open a PR from that clean branch to `GideonLeGrange/mikrotik-java:master` only with explicit approval.

The upstream PR must contain none of the Praktimarc release-line changes.
