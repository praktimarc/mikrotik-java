# mikrotik-java Fork Release Design

## Context

The repository `praktimarc/mikrotik-java` is a fork of `GideonLeGrange/mikrotik-java`.

The fork serves two purposes:

1. provide a reliable, independently maintainable build for Praktimarc's own projects;
2. preserve the ability to contribute suitable fixes back to the upstream project without mixing fork-specific release infrastructure into upstream pull requests.

The current upstream code base uses Maven and version `3.0.8`. It already produces the main JAR as well as source and Javadoc JARs.

The first fork-specific functional change is the synchronous error-handling fix on branch `fix/sync-listener-error-completion`. That branch intentionally remains upstream-compatible and must not contain fork-specific publishing or branding changes.

## Goals

The fork shall provide:

- reproducible Maven builds;
- automated tests before a fork release;
- clearly distinguishable fork versions;
- downloadable binary, source, and Javadoc JARs;
- GitHub Releases as the initial distribution mechanism;
- a structure that can later support GitHub Packages or another Maven repository without changing Java package names;
- an easy path for syncing future upstream changes;
- clean upstream pull requests containing only the relevant upstream-compatible changes.

## Non-Goals

The first fork release will not:

- publish to Maven Central;
- use the original maintainer's Sonatype release credentials;
- rename Java packages;
- change the public Java API solely for fork branding;
- introduce a new build system;
- publish to GitHub Packages yet;
- automatically merge upstream changes;
- include unrelated modernization beyond the compiler/tooling changes required for the fork's Java 11 baseline.

## Maven Identity

The fork will use its own Maven coordinates:

```text
groupId:    io.github.praktimarc
artifactId: mikrotik
```

The existing Java packages remain unchanged:

```text
me.legrange.mikrotik.*
```

This deliberately separates artifact ownership from Java API compatibility. Existing application source imports therefore remain unchanged.

## Fork Versioning

Fork releases are based on the upstream version plus a fork-specific suffix.

Initial version:

```text
3.0.8-praktimarc.1
```

Subsequent fork-only releases based on the same upstream version use:

```text
3.0.8-praktimarc.2
3.0.8-praktimarc.3
...
```

If upstream later releases version `3.0.9`, the fork sequence resets relative to that upstream base:

```text
3.0.9-praktimarc.1
```

Git tags use:

```text
v3.0.8-praktimarc.1
```

## Branch Model

### `master`

`master` is the maintained production branch of the Praktimarc fork.

It may contain accepted upstream code, Praktimarc-specific bug fixes, fork Maven coordinates, fork version metadata, fork release workflows, and fork-specific documentation. Released versions are tagged from `master`.

### Upstream-compatible fix branches

Changes that may later be proposed upstream must be developed on narrowly scoped branches that do not contain fork-specific infrastructure.

Example:

```text
fix/sync-listener-error-completion
```

Such branches should be based on an appropriate upstream-compatible commit and contain only the bugfix, its regression tests, and documentation changes directly relevant to that bugfix if required.

A pull request to `GideonLeGrange/mikrotik-java` is created from such a clean branch rather than from the Praktimarc `master`.

### Future fork-only work

Fork-specific changes should use normal feature or fix branches and merge into the fork's `master`. They should not be proposed upstream unless deliberately separated into an upstream-compatible branch.

## Upstream Synchronization

The original repository remains the conceptual upstream source.

When upstream changes occur:

1. inspect the upstream changes before integrating them;
2. update the fork from the new upstream state;
3. preserve Praktimarc-specific commits on top where still necessary;
4. remove fork fixes when an equivalent upstream fix makes them obsolete;
5. run the complete test suite before producing another fork release.

No automatic upstream merge is required. The fork should prefer a small, reviewable delta from upstream wherever practical.

## Build

Maven remains the authoritative build tool.

The maintained fork targets Java 11 bytecode. GitHub Actions builds use Temurin JDK 11. Local builds may use a newer JDK as long as Maven compiles with `<release>11</release>`. The fork uses `maven-compiler-plugin` 3.13.0 for this baseline.

The fork must continue to produce:

```text
mikrotik-<version>.jar
mikrotik-<version>-sources.jar
mikrotik-<version>-javadoc.jar
```

The existing Java packages and public interfaces remain unchanged unless a separate functional change requires otherwise.

The existing Sonatype/Maven Central release profile is not used for Praktimarc releases. It may remain present where preserving upstream compatibility is useful, provided the fork release workflow cannot accidentally invoke it.

## Continuous Integration

CI should remain intentionally lightweight.

Normal CI should validate relevant changes with Maven tests and packaging. The workflow should not run unnecessarily often.

At minimum, CI should cover changes proposed for `master` and direct changes to `master` if that remains part of the fork workflow.

A release must never be created from a build that has not successfully completed the required Maven verification.

## GitHub Release Process

A fork release is initiated by a version tag matching the fork convention:

```text
v3.0.8-praktimarc.1
```

The release workflow shall:

1. check out the tagged source;
2. configure Temurin JDK 11;
3. run Maven tests;
4. build the package targeting Java 11 bytecode;
5. verify that the expected JAR artifacts exist;
6. create or populate the corresponding GitHub Release;
7. attach the main JAR, sources JAR, and Javadoc JAR.

The release must correspond exactly to the tagged source revision. No Maven Central deployment occurs.

## Release Contents

The first release will contain the synchronous RouterOS API error-handling fix currently implemented on `fix/sync-listener-error-completion`.

Its functional behavior is:

- an immediate RouterOS API error terminates a synchronous wait immediately;
- the actual RouterOS/API error is preserved;
- it is not replaced later by an artificial command-timeout exception.

Before this becomes part of the fork release line, the bugfix changes must be incorporated into the fork's `master` together with the fork release infrastructure.

## README Changes

The fork README should retain the original project attribution while clearly explaining that this repository is a maintained fork.

It should state:

- upstream project and author;
- upstream base version;
- fork version convention;
- purpose of the Praktimarc fork;
- location of downloadable releases;
- Maven coordinates of the fork artifact;
- Java 11 as the fork bytecode baseline;
- that Java packages remain compatible with the original library.

The README must not imply that Praktimarc's releases are official upstream releases.

## Licensing and Attribution

The existing Apache License 2.0 remains unchanged. Original copyright and attribution information must be preserved. Fork-specific documentation should clearly distinguish original upstream authorship from later Praktimarc modifications.

## First Fork Release

The target first release is:

```text
io.github.praktimarc:mikrotik:3.0.8-praktimarc.1
```

Tag:

```text
v3.0.8-praktimarc.1
```

It includes:

- upstream `3.0.8` as the base;
- the synchronous listener error-completion regression test;
- the corresponding one-line production fix;
- fork Maven identity;
- Java 11 bytecode baseline with JDK 11 CI and release builds;
- fork README information;
- GitHub release automation.

## Future Maven Repository Support

GitHub Releases are the initial distribution mechanism.

If automated Maven dependency resolution becomes desirable later, GitHub Packages or another Maven-compatible repository may be added.

Because the fork already owns the Maven coordinates `io.github.praktimarc:mikrotik`, that future change does not require Java package changes or a redesign of the library.

## Upstream Pull Requests

Fork infrastructure is kept separate from upstream contributions.

For the current bugfix, the eventual upstream pull request should originate from `fix/sync-listener-error-completion` and contain only the regression test and production fix.

It should explain:

- that synchronous commands continue waiting after an immediate RouterOS `!trap`;
- that the original API exception is subsequently overwritten by a timeout;
- that marking the synchronous listener complete on error preserves the real RouterOS error and returns immediately;
- that a regression test accompanies the fix.

The fork-specific POM changes, Maven coordinates, README fork branding, release workflow, Java 11 baseline, and fork versioning must not be part of the upstream pull request.

## Success Criteria

The fork setup is complete when:

- the bugfix is incorporated into fork `master`;
- Maven coordinates identify the Praktimarc fork;
- build tooling targets Java 11 bytecode and CI uses JDK 11;
- the project builds and tests successfully;
- the three expected JAR artifacts are generated;
- tag `v3.0.8-praktimarc.1` can produce a GitHub Release containing those artifacts;
- the README clearly distinguishes the fork from upstream;
- the original clean bugfix branch remains suitable for an upstream pull request.
