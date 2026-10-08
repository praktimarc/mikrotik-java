# Project Context

## Purpose

`praktimarc/mikrotik-java` is a maintained fork of `GideonLeGrange/mikrotik-java` used as the low-level RouterOS native API transport for Praktimarc projects.

The fork keeps the established `me.legrange.mikrotik.*` API surface compatible where practical while adding narrowly scoped transport, protocol, and binary-file fixes needed by downstream applications.

A separate higher-level project, `praktimarc/mikrotik-facade`, is expected to depend on this library. The facade must be able to rely on the low-level guarantees documented here and must not need to import `me.legrange.mikrotik.impl.*`, add its own send lock, or duplicate RouterOS response dispatch.

## Runtime and build baseline

- Java language/runtime baseline: Java 11.
- Maven coordinates currently remain `io.github.praktimarc:mikrotik`.
- RouterOS native API defaults remain TCP 8728 and API-SSL/TLS 8729.
- CI uses GitHub Actions with JDK 11 and `mvn -B clean verify --file pom.xml`.
- The current fork already supports binary-safe RouterOS `/file/read` downloads through `ApiConnection.downloadFile(...)`.

## ApiConnection ownership and concurrency invariants

One `ApiConnection` owns all of the following for one RouterOS session:

- command-tag allocation;
- response routing by tag;
- text-command registrations;
- binary-command registrations;
- complete RouterOS command writes.

Multiple synchronous, asynchronous, and binary-read operations may be active at the same time on one connection. The complete wire representation of each command is serialized internally so words from different commands cannot interleave on the shared output stream. The write lock is held only around the state-check/write/fatal-transition critical section, never for the lifetime of the RouterOS command or while waiting for its reply.

A facade or application must therefore not add a second command dispatcher or a second connection-wide send lock merely to make one `ApiConnection` safe for concurrent use.

## Command lifecycle invariants

Text and binary listener registrations are terminal and leak-free:

- normal `!done` removes the registration before `completed()` is called;
- RouterOS `!trap` and legacy-compatible `!halt` remove the registration before `error(...)` is called;
- a later `!done` after an error is ignored for that already-terminal registration;
- synchronous timeout removes its local registration and does not automatically issue RouterOS `/cancel`;
- send failure rolls back the command registration;
- callbacks cannot leave a terminal registration behind even if user callback code throws.

The public signatures `String execute(String, ResultListener)` and `void cancel(String)` are retained. No public command-handle abstraction is introduced here.

## Connection lifecycle invariants

The built-in connection has explicit logical states equivalent to connected, intentionally closed, and fatally failed.

Fatal EOF, socket loss, unrecoverable reader/framing failure, RouterOS `!fatal`, or an unrecoverable protocol-vocabulary failure terminates the complete session. A fatal transition:

- marks the connection disconnected;
- retains an `ApiConnectionException` describing the failure;
- drains all active text and binary operations;
- signals each active operation terminally with a connection error;
- makes later submissions fail promptly without writing to the old stream;
- notifies registered `ConnectionListener` instances even when no command is active.

A write-side transport `IOException` marks the session failed before the shared write lock is released. Consequently a writer already waiting for the lock cannot send another command onto a stream that has just failed. Transport shutdown and all user callbacks happen after the write lock is released.

There is no automatic reconnect, command replay, or transparent session replacement in this library.

Intentional `close()` is distinct from fatal connection loss:

- it is idempotent;
- it terminates active commands with `ApiConnectionException`;
- it does not invoke `ConnectionListener.connectionLost(...)`;
- calling `close()` after an already-failed session does not replace or re-notify the retained fatal failure.

## Public exception boundary

Downstream code should use only the public package exception types:

- `ApiConnectionException` — connection, transport, or session-fatal failure;
- `ApiCommandException` — RouterOS command-level error such as `!trap`, including structured tag/category metadata;
- `ApiDataException` — malformed or inconsistent API data when the failure is safely attributable without losing session routing.

`ApiCommandException.hasCategory()` distinguishes a real RouterOS category `0` from an omitted category; `getCategory()` remains integer-compatible and returns `0` when no category was supplied.

Compatibility exception classes remain under `me.legrange.mikrotik.impl`, but new consumers, including `praktimarc/mikrotik-facade`, must not compile against them.

## RouterOS reply vocabulary

The low-level parser/dispatcher recognizes these reply words:

- `!re` — data result;
- `!empty` — valid no-data response introduced by RouterOS 7.18; non-terminal and followed by `!done` for normal completion;
- `!done` — normal command completion;
- `!trap` — command error, treated as terminal by the Java listener API;
- `!halt` — retained as legacy compatibility and treated like `!trap`;
- `!fatal` — whole-session fatal error, including best-effort extraction of free-word diagnostics.

Unknown reply words, duplicate/untrustworthy tag routing, reserved/unsupported control bytes, truncated framing, and other unrecoverable protocol states must not be silently ignored. If a complete tagged sentence remains safely attributable to one active command, a conversion/data error can fail only that command with `ApiDataException`; otherwise the whole connection fails with `ApiConnectionException` and the data/protocol exception is retained as a cause when applicable.

## Scope boundaries

This low-level library intentionally does not provide:

- `CompletableFuture` command APIs;
- `Flow.Publisher` streaming APIs;
- capability detection;
- RouterOS 6/7 facade adapters;
- facade-specific exception mapping;
- automatic reconnect/replay;
- a public `CommandHandle`.

Those concerns belong in higher-level consumers such as `praktimarc/mikrotik-facade` if and when they are required.

## Maven Central distribution invariants

- Public Maven coordinates: `io.github.praktimarc:mikrotik`; the consumer `praktimarc/mikrotik-facade` pins `3.0.8-praktimarc.4`.
- Existing `.4` release baseline: commit `c170858efaac04fc78771903ef4c2bdbb6d35325` and tag `v3.0.8-praktimarc.4`. Neither release tag nor low-level runtime API changes for publishing.
- Normal builds remain on Java 11 and need no publishing credentials.
- Publishing uses a dedicated `central-release` Maven profile and a manually dispatched GitHub Actions workflow. GPG credentials and Sonatype user tokens are only GitHub Actions secrets.
- The Central publishing workflow pins Maven 3.9.16 with SHA-512 archive verification to avoid Maven 3.10.x staging metadata compatibility failures in `central-publishing-maven-plugin:0.11.0`. Normal CI is not pinned.
- The staging workflow verifies unchanged `src/` and byte-for-byte matching compiled classes against the GitHub Release before uploading.
- `autoPublish=false` prevents automatic irreversible publication. Central publication requires separate authorization.
- Version `3.0.8-praktimarc.4` has been published to Maven Central (2026-10-08); its POM and JAR were externally downloaded from an empty Maven cache. A complete `mikrotik-facade` test suite run is a distinct downstream gate. The authoritative procedure is `docs/publishing.md`.
