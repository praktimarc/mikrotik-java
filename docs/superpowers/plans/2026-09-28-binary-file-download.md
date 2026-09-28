# Binary-Safe RouterOS File Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `ApiConnection.downloadFile()` that streams arbitrary RouterOS files byte-for-byte over the existing native API connection to a local `Path` without charset conversion or stale/partial final files.

**Architecture:** Replace the receive-side newline/String intermediate representation with raw RouterOS sentence words (`byte[]`) while preserving the existing text API externally. Normal tagged responses are decoded only after word boundaries are known; `/file/read` responses for an internal binary listener expose only the raw bytes after the `=data=` prefix. A small file-download helper owns chunking, `.part` lifecycle, size validation, and final publication so transport and filesystem behavior remain independently testable.

**Tech Stack:** Java 11 bytecode, Maven, JUnit 4.13.1, RouterOS native API on 8728/8729, RouterOS 7.13+ `/file/read`, Java NIO `Path`/`Files`.

**Spec:** `docs/superpowers/specs/2026-09-28-binary-file-download-design.md`

## Global Constraints

- Public API: `long downloadFile(String remoteFile, Path localFile) throws MikrotikApiException, IOException` directly on `ApiConnection`.
- Primary RouterOS path requires 7.13+ and `/file/read` with maximum chunk size `32768` bytes.
- Binary payload bytes must never pass through `String`, charset decoding, line splitting, or `StringBuilder`.
- Existing `execute(String)` and `execute(String, ResultListener)` behavior and return types remain compatible.
- Reuse the existing authenticated API socket and tag routing; do not open FTP, SFTP, REST, HTTP, or a second API connection.
- Remote filenames for file discovery/read must be built with `Command`/`Parameter`/query words, not by concatenating pseudo-command-line strings.
- The whole remote file must never be buffered in memory; steady-state payload memory is one `/file/read` chunk plus protocol metadata.
- Existing final target and sibling `<name>.part` are removed before the transfer is allowed to publish new data; inability to perform required cleanup aborts the operation.
- During transfer only `.part` is written. Success requires expected byte count and local size to match before final move.
- Transfer/validation failure cleans final and `.part` best-effort and never reports success.
- No resume, RouterOS <7.13 fallback, public raw protocol API, or `uploadFile()` in this implementation.
- Internal raw-word boundaries must not make a future binary write/upload path require another receive-transport redesign, but no unused upload production code is added.
- Planned maintained-fork version after implementation: `3.0.8-praktimarc.2`.
- Preserve Java 11, Maven coordinates `io.github.praktimarc:mikrotik`, packages `me.legrange.mikrotik.*`, release workflow, and upstream PR #91 branch isolation.
- Feature-branch pushes must not add GitHub Actions usage; `.github/workflows/maven.yml` remains master/PR-to-master only.

## Review Focus

1. **Remote names with spaces, `=`, or punctuation:** commands must preserve the exact filename as an API parameter/query value instead of reparsing it as CLI text; Task 5 integration tests assert the exact received command word.
2. **Zero-byte files:** must publish an empty final file without issuing `/file/read`; Task 4 unit tests and Task 5 integration tests pin this behavior.
3. **Old local file plus remote lookup/API failure:** the old final file must not survive a failed `downloadFile()` result; Task 4 tests cover failure from `Source.size()` after local reset.
4. **Interleaved tagged text and binary responses:** a file-read response must not steal or corrupt a normal asynchronous result on the same socket; Task 5 integration tests interleave both tags.
5. **File changes or malformed transfer:** zero progress, short completion, duplicate/missing `data`, or bytes beyond the declared size must fail with no published final file; Tasks 3–5 cover these paths.

---

### Task 1: Add Raw RouterOS Word/Sentence Decoding

**Files:**
- Modify: `src/main/java/me/legrange/mikrotik/impl/Util.java`
- Create: `src/test/java/me/legrange/mikrotik/impl/UtilTest.java`

**Interfaces:**
- Consumes: existing RouterOS length-prefix implementation in `Util`.
- Produces:
  - `static byte[] readWord(InputStream in) throws ApiDataException, ApiConnectionException`
  - `static List<byte[]> readSentence(InputStream in) throws ApiDataException, ApiConnectionException`
- `readWord()` returns the exact bytes of one word; a zero-length array represents the sentence terminator internally.
- `readSentence()` returns all non-terminator words from one sentence as separate byte arrays.
- Existing command writing remains text-compatible; do not add unused binary-write/upload public behavior.

- [ ] **Step 1: Write failing raw-word tests in `UtilTest`.** Cover an exact payload containing every byte `0x00..0xFF`, truncated input, sentence termination, and length boundaries `0x7f/0x80`, `0x3fff/0x4000`, `0x1ffff/0x20000` using independently constructed encoded input bytes where practical.
- [ ] **Step 2: Run `mvn -B -Dtest=UtilTest test`.** Expected: FAIL because `readWord`/`readSentence` do not exist.
- [ ] **Step 3: Implement `readWord()` and `readSentence()` in `Util`.** Reuse the existing length-prefix rules but read exactly `len` raw bytes before returning; EOF/truncation becomes `ApiDataException`, I/O becomes `ApiConnectionException`.
- [ ] **Step 4: Run `mvn -B -Dtest=UtilTest test`.** Expected: PASS with byte-for-byte equality across all test payloads.
- [ ] **Step 5: Run `mvn -B -Dtest=ApiConnectionImplTest,UtilTest test`.** Expected: existing synchronous-error regression remains green.
- [ ] **Step 6: Commit only Task 1 files.** Suggested commit: `refactor: decode RouterOS words as raw bytes`.

### Task 2: Represent and Decode Complete Raw Sentences

**Files:**
- Create: `src/main/java/me/legrange/mikrotik/impl/RawSentence.java`
- Create: `src/test/java/me/legrange/mikrotik/impl/RawSentenceTest.java`
- Modify: `src/main/java/me/legrange/mikrotik/impl/ApiConnectionImpl.java`

**Interfaces:**
- Consumes: `Util.readSentence(InputStream)` from Task 1.
- Produces package-private `RawSentence` with:
  - `RawSentence(List<byte[]> words)`
  - `String getType() throws ApiDataException`
  - `String getTag() throws ApiDataException`
  - `Response toTextResponse() throws MikrotikApiException`
  - `byte[] requireSingleRawAttribute(String name) throws ApiDataException`
- `toTextResponse()` must construct the existing `Result`, `Done`, or `Error` objects and decode only normal textual words as UTF-8.

- [ ] **Step 1: Write failing `RawSentenceTest`.** Assert `!re` attributes become the same `Result` values as today, embedded CR/LF inside one attribute value stays inside that value, `.tag` is preserved, `!done` keeps `=ret=`, and `!trap` keeps message/category/tag.
- [ ] **Step 2: Add binary-attribute tests.** Build `=data=` followed by hostile bytes (`00`, `0d0a`, invalid UTF-8, `fffe`, literal `=data=`, `!re`, `!done`) and assert `requireSingleRawAttribute("data")` returns exactly the original payload; missing or duplicate `data` must throw `ApiDataException`.
- [ ] **Step 3: Run `mvn -B -Dtest=RawSentenceTest test`.** Expected: FAIL because `RawSentence` does not exist.
- [ ] **Step 4: Implement `RawSentence`.** Determine structure only from word boundaries; split textual attributes at the second `=` in the raw word, not on newline content. `requireSingleRawAttribute()` strips only the ASCII `=<name>=` prefix and returns payload bytes unchanged.
- [ ] **Step 5: Refactor `ApiConnectionImpl.Reader` to queue complete `RawSentence` objects from `Util.readSentence(in)` and refactor `Processor` to use `RawSentence.toTextResponse()` for existing listeners.** Remove the newline-based `lines`/`line` reconstruction once no longer used.
- [ ] **Step 6: Run `mvn -B -Dtest=RawSentenceTest,ApiConnectionImplTest test`.** Expected: PASS.
- [ ] **Step 7: Commit Task 2.** Suggested commit: `refactor: process RouterOS responses as raw sentences`.

### Task 3: Add an Internal Tagged Binary Response Path

**Files:**
- Create: `src/main/java/me/legrange/mikrotik/impl/BinaryResultListener.java`
- Modify: `src/main/java/me/legrange/mikrotik/impl/ApiConnectionImpl.java`
- Modify: `src/test/java/me/legrange/mikrotik/impl/ApiConnectionImplTest.java`

**Interfaces:**
- Consumes: `RawSentence.getTag()`, `getType()`, and `requireSingleRawAttribute("data")`.
- Produces package-private `BinaryResultListener`:
  - `void receive(byte[] data)`
  - `void error(MikrotikApiException ex)`
  - `void completed()`
- Produces private `byte[] executeBinaryRead(Command cmd, int timeout) throws MikrotikApiException` in `ApiConnectionImpl` backed by a synchronous binary listener.
- Existing `Map<String, ResultListener> listeners` remains the text-listener registry; binary commands use a separate tag-keyed internal registry so the public listener contract does not change.

- [ ] **Step 1: Extend `ApiConnectionImplTest` with failing tests for the synchronous binary listener.** Assert one payload is returned unchanged, `error()` completes immediately with the original error, timeout still fails, and multiple/missing `data` results cannot be accepted as one chunk.
- [ ] **Step 2: Run `mvn -B -Dtest=ApiConnectionImplTest test`.** Expected: FAIL because binary listener execution does not exist.
- [ ] **Step 3: Implement `BinaryResultListener`, binary-listener registration, and `executeBinaryRead()`.** Use normal `nextTag()` and `Util.write(cmd,out)`; do not create a second socket.
- [ ] **Step 4: Update `Processor` dispatch.** For a tag registered as binary: `!re` delivers exactly one raw `data` payload, `!done` completes and removes the binary listener, and `!trap`/`!halt` delivers `ApiCommandException` and removes it. Other tags continue through the existing text listener path.
- [ ] **Step 5: Run `mvn -B -Dtest=ApiConnectionImplTest,RawSentenceTest,UtilTest test`.** Expected: PASS.
- [ ] **Step 6: Commit Task 3.** Suggested commit: `feat: add tagged binary API response handling`.

### Task 4: Implement the Filesystem-Safe Chunk Download Helper

**Files:**
- Create: `src/main/java/me/legrange/mikrotik/impl/FileDownload.java`
- Create: `src/test/java/me/legrange/mikrotik/impl/FileDownloadTest.java`

**Interfaces:**
- Produces package-private `FileDownload` with constant `CHUNK_SIZE = 32768` and:

```java
interface Source {
    long size() throws MikrotikApiException;
    byte[] read(long offset, int chunkSize) throws MikrotikApiException;
}

static long download(Path target, Source source)
        throws MikrotikApiException, IOException;
```

- `download()` owns final/`.part` deletion, source-size query, streaming, offset calculation, validation, cleanup, and final move. This placement ensures the old final file is deleted before a later `Source.size()`/Router failure can leave stale data visible.

- [ ] **Step 1: Write failing success tests in `FileDownloadTest`.** Cover a payload larger than two 32768-byte chunks with a final partial chunk, assert requested offsets are based on actual returned byte count, assert byte equality, returned length, no `.part`, and a valid zero-byte source that never calls `read()`.
- [ ] **Step 2: Write failing stale-file/failure tests.** Start with both final and `.part` present; assert they are reset. Make `Source.size()` throw and assert the old final no longer exists. Cover zero-progress before completion, oversized chunk, premature source failure after at least one chunk, and final expected-size mismatch; successful cleanup leaves neither final nor `.part`.
- [ ] **Step 3: Add filesystem failure tests where portable.** A target with an unusable parent/directory or required deletion failure must propagate `IOException` and must not report success; do not create missing parent directories or weaken permissions.
- [ ] **Step 4: Run `mvn -B -Dtest=FileDownloadTest test`.** Expected: FAIL because `FileDownload` does not exist.
- [ ] **Step 5: Implement `FileDownload.download()`.** Use sibling `<filename>.part`, `Files.deleteIfExists`, buffered or direct output streaming, actual `payload.length` offsets, `Files.size(part)` validation, `ATOMIC_MOVE` first with fallback only on `AtomicMoveNotSupportedException`, and best-effort cleanup that preserves the primary exception and attaches cleanup failures as suppressed where practical.
- [ ] **Step 6: Run `mvn -B -Dtest=FileDownloadTest test`.** Expected: PASS.
- [ ] **Step 7: Commit Task 4.** Suggested commit: `feat: add safe chunked file download storage`.

### Task 5: Expose `ApiConnection.downloadFile()` and Prove the Full Wire Path

**Files:**
- Modify: `src/main/java/me/legrange/mikrotik/ApiConnection.java`
- Modify: `src/main/java/me/legrange/mikrotik/impl/ApiConnectionImpl.java`
- Create: `src/test/java/me/legrange/mikrotik/impl/RouterOsTestServer.java`
- Create: `src/test/java/me/legrange/mikrotik/impl/ApiConnectionFileDownloadTest.java`

**Interfaces:**
- Consumes: `FileDownload.download(...)` and `executeBinaryRead(...)`.
- Produces public:

```java
public abstract long downloadFile(String remoteFile, Path localFile)
        throws MikrotikApiException, IOException;
```

- `ApiConnectionImpl` private helpers:
  - `long getRemoteFileSize(String remoteFile) throws MikrotikApiException`
  - `byte[] readFileChunk(String remoteFile, long offset, int chunkSize) throws MikrotikApiException`
- File discovery uses `Command("/file/print")`, `.proplist` for only required fields, and an exact `?name=<remoteFile>` query word. Chunk reads use `Command("/file/read")` with `file`, `offset`, and `chunk-size` parameters. Do not invoke `Parser.parse()` for caller-supplied filename construction.

- [ ] **Step 1: Create a minimal local `RouterOsTestServer` test fixture.** It accepts one native API socket, reads command sentences/`.tag`, and can emit raw tagged `!re`, `!done`, and `!trap` sentences including arbitrary `=data=` bytes. Keep it test-only and deterministic.
- [ ] **Step 2: Write failing end-to-end binary test.** Use a synthetic payload spanning multiple chunks and containing all `0x00..0xFF` values plus hostile sequences; fake `/file/print` returns integer byte size and fake `/file/read` returns requested slices. Assert `Files.readAllBytes(target)` equals the source exactly and returned byte count matches.
- [ ] **Step 3: Add command-construction and edge tests.** Use a remote name containing spaces, `=`, and punctuation and assert the test server receives the exact API query/parameter value. Cover zero-byte file, missing remote file, malformed/non-numeric/negative size, missing/duplicate `data`, and RouterOS `!trap` cleanup.
- [ ] **Step 4: Add interleaved tag-routing test.** Start a normal asynchronous text command and a file download on the same connection; have the test server interleave tagged responses. Assert the text listener gets only its text result and the downloaded file gets only its raw bytes.
- [ ] **Step 5: Run `mvn -B -Dtest=ApiConnectionFileDownloadTest test`.** Expected: FAIL because public `downloadFile()` is not implemented.
- [ ] **Step 6: Add the public method/Javadoc to `ApiConnection` and implement it in `ApiConnectionImpl`.** Validate non-null arguments and non-blank remote filename; delegate local lifecycle to `FileDownload`. Remote discovery must require exactly one matching file with a parseable non-negative integer `size`.
- [ ] **Step 7: Implement `readFileChunk()` with `/file/read` and `executeBinaryRead()`.** Always request chunk size `<= 32768`; use raw payload bytes without conversion.
- [ ] **Step 8: Run `mvn -B -Dtest=ApiConnectionFileDownloadTest,FileDownloadTest,ApiConnectionImplTest,RawSentenceTest,UtilTest test`.** Expected: PASS.
- [ ] **Step 9: Commit Task 5.** Suggested commit: `feat: download RouterOS files without binary conversion`.

### Task 6: Document, Version, and Verify the Fork Release Candidate

**Files:**
- Modify: `README.md`
- Modify: `pom.xml`
- Test: all tests under `src/test/java`

**Interfaces:**
- Consumes: completed public download feature.
- Produces: documented Maven version `3.0.8-praktimarc.2` ready for separate integration/release gates.

- [ ] **Step 1: Update `pom.xml` version to `3.0.8-praktimarc.2`.** Do not alter coordinates, compiler baseline, CI triggers, or release workflow.
- [ ] **Step 2: Update the fork section/dependency/install examples in `README.md` to `3.0.8-praktimarc.2` and describe the new binary-safe `downloadFile()` API.** State RouterOS 7.13+ for the first path, 32768-byte chunking, byte-preserving semantics, final/`.part` behavior, and that `execute()` remains text-oriented. Explicitly state that upload and pre-7.13 fallback are not included.
- [ ] **Step 3: Add a concise usage example:** `con.downloadFile("flash/docsis/cm123456.cfg", Path.of("/srv/docsis/cm123456.cfg"));` and note the returned `long` is the completed byte count.
- [ ] **Step 4: Run `mvn -B clean verify --file pom.xml`.** Expected: `BUILD SUCCESS`, zero test failures, and successful Javadoc/source JAR generation. Do not claim completion without this fresh output.
- [ ] **Step 5: Inspect generated artifact names.** Expect main, sources, and Javadoc JARs for `3.0.8-praktimarc.2`.
- [ ] **Step 6: Review branch diff against `master` `97bc5278c86884d01ce6efc86e2c302d0776a8a5`.** Expected scope: approved spec/plan, raw receive refactor, binary listener path, `FileDownload`, public `downloadFile`, tests/test server, README, and version bump. No FTP/SFTP/REST, upload API, package rename, workflow expansion, or changes to the clean upstream PR #91 branch.
- [ ] **Step 7: Commit Task 6.** Suggested commit: `docs: prepare binary download release`.

## Real-Router Verification Gate

This gate is required before calling the feature production-ready or tagging `v3.0.8-praktimarc.2`, but it is not simulated by unit tests.

- [ ] Use a real RouterOS 7.13+ router reachable through the already-established API path.
- [ ] Select a binary file whose original local bytes/hash are independently known, preferably a compiled DOCSIS configuration file.
- [ ] Download it with `downloadFile()` to a fresh local path.
- [ ] Compare source and downloaded bytes with `cmp` or compare independent SHA-256 values with `sha256sum`; they must match exactly.
- [ ] Repeat with a file larger than 60 KB if one is available so the multi-chunk path is exercised on real RouterOS.
- [ ] Confirm the downloaded DOCSIS file can be decompiled by the existing server-side shell command without transfer-induced corruption.

## Integration and Release Gates

After implementation, automated verification, code review, and real-router verification:

1. **Source completion review** — inspect the complete feature branch before any PR/merge.
2. **Fork PR/merge gate** — integration into `praktimarc/mikrotik-java:master` requires explicit approval.
3. **Post-merge CI gate** — allow exactly the normal `master` CI run and verify it succeeds; avoid redundant Actions runs.
4. **Tag/release gate** — `v3.0.8-praktimarc.2` requires separate explicit approval and must resolve to the verified release commit.
5. **Release verification** — verify the tag workflow and exactly the expected main/sources/Javadoc JAR assets before declaring the release complete.
6. **Upstream isolation** — PR #91 / `fix/sync-listener-error-completion` remains unchanged; this fork-specific feature is not added to that PR.
