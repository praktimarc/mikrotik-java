# Binary-Safe RouterOS File Download Design

## Status

Design specification for the maintained Praktimarc fork of `mikrotik-java`.

Target branch: `feature/binary-file-download`

Planned fork release: `3.0.8-praktimarc.2`

## Context

The existing RouterOS API connection is already established through the network and firewall infrastructure. Introducing FTP, SFTP, or another transfer service would require opening and maintaining additional paths through a large number of firewalls, so file transfer must reuse the native RouterOS API connection on ports 8728/8729.

The immediate production use case is downloading compiled DOCSIS configuration files from RouterOS storage to a local server. These files are binary and must arrive byte-for-byte unchanged because they are decompiled later by a server-side shell command.

The current library cannot guarantee this. The low-level decoder reads API word bytes and immediately converts them to UTF-8 `String` values. Invalid UTF-8 sequences, NUL bytes, and arbitrary binary data can therefore be changed before application code sees them.

RouterOS 7.13+ provides `/file/read` with offset-based reads and a maximum chunk size of 32768 bytes. That command is the primary transport mechanism for this feature. Support for older RouterOS versions and the legacy small-file `contents` mechanism is explicitly deferred.

## Goals

1. Add a public `downloadFile()` operation directly to `ApiConnection`.
2. Reuse the existing authenticated native RouterOS API connection.
3. Preserve every downloaded payload byte exactly.
4. Support files larger than the legacy `contents` size limit by using `/file/read` chunking.
5. Stream data to disk instead of buffering the complete file in memory.
6. Ensure a failed download never publishes an old or incomplete file as a successful new result.
7. Keep all existing text-based `execute()` behavior backward compatible.
8. Build the internal raw-word boundary so a future binary upload feature is not architecturally blocked.

## Non-goals

The first implementation will not:

- add FTP, SFTP, HTTP, REST, or other transfer transports;
- provide an upload API;
- provide resume support;
- implement a RouterOS <7.13 fallback;
- expose arbitrary raw protocol access as a new general-purpose public API;
- add unused binary-upload production code;
- change existing Java packages;
- change Maven coordinates;
- change normal `execute()` return types or `ResultListener` semantics.

## Public API

`ApiConnection` gains one new abstract method:

```java
public abstract long downloadFile(String remoteFile, Path localFile)
        throws MikrotikApiException, IOException;
```

Example:

```java
long bytes = con.downloadFile(
        "flash/docsis/cm123456.cfg",
        Path.of("/srv/docsis/cm123456.cfg"));
```

The returned `long` is the number of bytes successfully written to the final local file.

`MikrotikApiException` represents RouterOS/API/connection failures. `IOException` represents local filesystem failures such as permissions, missing directories, or disk-full conditions.

No charset is accepted or implied by this method. The remote file is treated only as bytes.

## Local file semantics

The final target path must never be accepted as a successful result when it contains stale or partial data.

For target `file.cfg`, the implementation uses a sibling temporary path such as `file.cfg.part`.

Before the transfer starts:

1. delete an existing final target file;
2. delete an existing `.part` file;
3. if either required deletion fails, abort before transferring any payload and propagate the filesystem error.

During transfer, only the `.part` file is written.

On successful completion:

1. close the output stream;
2. verify that the number of written bytes equals the expected remote size;
3. verify the local `.part` size;
4. move the `.part` file to the final target path, using an atomic move when the local filesystem supports it and a normal move fallback otherwise.

On any transfer or validation failure, both the final target and `.part` file are removed best-effort before the original exception is propagated. If the operating system itself prevents cleanup, the method must still fail and must never report the download as successful; cleanup failures may be attached as suppressed exceptions where practical.

Therefore the normal postcondition is intentionally simple:

- success: exactly one complete new final file exists;
- failure with successful cleanup: neither an old final file nor an incomplete `.part` file remains;
- failure because the filesystem prevents deletion/cleanup: no success is reported, and the filesystem error remains visible to the caller.

## Why the existing string decoder cannot be used

The current low-level path is effectively:

```text
Socket
  -> RouterOS API word bytes
  -> Util.decode()
  -> new String(bytes, UTF-8)
  -> Reader
  -> Processor
  -> Result / Map<String,String>
```

This is safe for textual RouterOS API data but not for arbitrary binary payloads.

The new design changes the internal boundary so RouterOS API words are first represented as raw byte arrays:

```text
Socket
  -> API word length
  -> exact byte[] word
  -> raw sentence
       -> normal result: decode textual fields as UTF-8
       -> file data: preserve payload bytes unchanged
```

No binary file payload may pass through `String`, `Charset`, character normalization, line splitting, or `StringBuilder`.

## Raw protocol representation

The decoder will be split conceptually into two operations:

```java
byte[] readWord(InputStream in)
List<byte[]> readSentence(InputStream in)
```

`readWord()` reads the RouterOS API length prefix and then exactly that number of bytes.

`readSentence()` repeatedly reads words until the zero-length sentence terminator and returns the words without decoding their payloads.

The maximum API word sizes already supported by the RouterOS length encoding remain supported. The binary download feature itself uses 32768-byte `/file/read` chunks.

Normal protocol keywords and normal attributes are decoded only after the complete raw sentence is available.

## Sentence processing and compatibility

The current implementation joins decoded words into a newline-delimited `String` block and later splits that block again in `Processor`. That representation cannot remain the authoritative intermediate form because arbitrary binary data can contain every possible byte value, including newline, carriage return, NUL, `=`, and `!`.

The processor will therefore consume sentence words as discrete protocol units rather than reconstructing boundaries from textual delimiters.

For normal commands, each word is decoded as UTF-8 and mapped into the same `Response`, `Result`, `Done`, and `Error` structures used today. Existing public behavior must remain unchanged.

For a binary file read response, the processor recognizes an attribute word whose raw bytes begin with the ASCII prefix:

```text
=data=
```

Only the attribute name/prefix is interpreted as text. Every byte after the six prefix bytes is the exact binary payload and is delivered to the file-download path unchanged.

A binary payload may itself contain the byte sequence `=data=`, API-looking text, CR/LF, NUL, or any other byte value; none of those bytes have structural meaning because the RouterOS API word length already defines the word boundary.

## Routing and concurrent commands

The library already supports tagged API commands and asynchronous listeners. Binary transfer must not rely on an untagged global "raw mode" because that could misroute responses if another command is active on the same connection.

The internal design must preserve tag-based routing:

1. `downloadFile()` sends each `/file/read` command with a normal generated API tag.
2. Raw sentences are parsed sufficiently to identify the sentence type and `.tag` word without converting binary `data` bytes to text.
3. The sentence is routed to the listener/handler registered for that tag.
4. Normal tagged commands continue to use the existing `ResultListener` behavior.
5. File-read commands use an internal binary-aware listener/handler that receives raw `data` payload bytes and completion/error events.

This avoids a second socket and preserves the connection's existing multiplexing model.

`downloadFile()` itself is synchronous from the caller's perspective. It may perform several sequential tagged `/file/read` commands, one chunk at a time.

## Remote file discovery and size

Before downloading payload data, `downloadFile()` must resolve the requested RouterOS file and obtain its size through the normal text API path.

The size is treated as the authoritative expected byte count for the transfer. If the file cannot be found, has no usable size, or the reported size cannot be parsed safely into a non-negative `long`, the download fails before creating a completed target file.

The implementation should request only the file properties required for lookup and size validation where RouterOS permits this.

This design assumes the file remains stable for the duration of the download. Mutation detection beyond final byte-count validation is not part of the first implementation.

## Chunked download algorithm

The primary RouterOS 7.13+ path is:

1. resolve the remote file and expected size;
2. remove final target and stale `.part`;
3. create the `.part` output file;
4. set `offset = 0`;
5. execute `/file/read file=<remote> offset=<offset> chunk-size=32768`;
6. receive the raw `data` payload bytes;
7. write the payload directly to the output stream;
8. increment `offset` by `payload.length`;
9. repeat until `offset == expectedSize`;
10. reject any response that would move `offset` beyond `expectedSize`;
11. reject a zero-length/non-progress response before completion;
12. close and verify the local size;
13. rename `.part` to the final target;
14. return the byte count.

A zero-byte remote file is valid: the transfer loop is skipped, the empty `.part` file is size-verified, and it becomes the final file.

The offset is always based on raw byte count, never `String.length()` or character count.

The implementation should not assume that every non-final chunk has exactly 32768 bytes. It uses the actual received payload length and the expected remote size as the termination condition.

## Error handling

The download fails rather than publishing questionable data if any of the following occurs:

- RouterOS returns `!trap` or another API command error;
- the connection fails during a chunk;
- the response contains no expected binary `data` attribute;
- more than one ambiguous `data` payload is returned for a single expected chunk;
- no progress is made while bytes remain;
- received bytes exceed the expected size;
- the command completes before the expected number of bytes is collected;
- local file creation or writing fails;
- local final-size verification fails;
- final move/rename fails.

Cleanup must not replace the primary failure with a cleanup exception. Cleanup failures may be attached as suppressed exceptions where practical.

## Memory behavior

The full remote file is never held in memory.

At steady state, memory use is bounded mainly by:

- one raw RouterOS sentence;
- one `/file/read` payload of at most 32768 bytes;
- small protocol metadata.

This keeps the operation suitable for server-side batch use across many routers and prevents file size from becoming heap usage.

## Backward compatibility

The existing public API must continue to behave as before:

```java
List<Map<String, String>> execute(String cmd)
String execute(String cmd, ResultListener lis)
```

Normal textual API results must preserve their existing values and error behavior.

The raw-word refactor is an internal representation change, not a request to expose binary values through `Map<String,String>`.

Existing login, command tags, cancellation, synchronous timeout handling, asynchronous listeners, trap handling, and connection close behavior must be covered by regression tests where the changed transport path can affect them.

## Testing strategy

### 1. Raw word codec tests

Verify exact handling of raw API words with payloads containing all byte values `0x00` through `0xFF`.

Test RouterOS word-length boundaries, especially around the protocol encoding transitions already handled by the library.

### 2. Binary payload preservation

Use a synthetic binary payload larger than multiple 32768-byte chunks. It must contain repeated `0x00..0xFF` ranges and intentionally hostile sequences including:

- NUL bytes;
- CR and LF;
- invalid UTF-8 byte sequences;
- `0xFF` and `0xFE`;
- UTF-8 replacement-character byte sequences;
- literal `=data=` bytes inside the payload;
- literal `!re` and `!done` text inside the payload.

The final assertion is byte equality, not text equality:

```java
assertArrayEquals(sourceBytes, Files.readAllBytes(downloadedFile));
```

### 3. Multi-chunk behavior

Verify offsets are advanced by actual byte counts, zero-byte files are supported, and final partial chunks are handled correctly.

### 4. Failure cleanup

Cover at least:

- pre-existing final file;
- stale `.part` file;
- inability to delete an existing target;
- API error after one or more chunks;
- connection failure mid-transfer;
- zero-progress response;
- premature completion;
- oversized response;
- local write failure where testable;
- final size mismatch.

When cleanup is permitted by the test filesystem, every transfer failure case must leave no final file and no `.part` file.

### 5. Existing API regression tests

Verify representative normal command results, tagged routing, `!done`, `!trap`, synchronous errors, and asynchronous listener delivery still work after the raw-sentence refactor.

### 6. Real-router verification

Before declaring the feature production-ready, perform at least one real RouterOS 7.13+ transfer using a binary test file whose local source hash is known independently. Compare the downloaded file byte-for-byte or by a cryptographic hash generated outside the Java API path.

DOCSIS configuration files should then be tested as the intended production case.

## Security and operational considerations

The feature does not open any additional network service or firewall path. It uses the same authenticated RouterOS API connection already used by the application.

Remote filenames are command parameters and must be passed through the existing command model safely rather than created through unsafe string concatenation if filenames can contain spaces or other special characters.

Local path authorization and directory ownership remain the responsibility of the calling application. `downloadFile()` must not attempt to create privileged directories or weaken filesystem permissions.

## RouterOS version scope

The initial supported transfer path targets RouterOS 7.13+ because it depends on `/file/read` with offset/chunk semantics.

A later compatibility feature may support older RouterOS versions for small files up to the legacy `contents` limit. Such a fallback must still use a binary-safe raw-word path; it must never retrieve binary data through the current UTF-8 `String` conversion and then re-encode it.

The fallback is deliberately outside this design's implementation scope so that it cannot compromise the correctness of the primary binary path.

## Future direction: upload and edit workflows

The raw-word boundary introduced for downloads must remain suitable for a later bidirectional protocol extension, even though only raw receive handling is required by this release.

Conceptually the long-term internal layer should permit both:

```text
Router -> raw byte[] words -> application
application -> raw byte[] words -> Router
```

The current implementation should therefore avoid receive-side abstractions that would make a future `writeWord(byte[])` or binary command parameter path require another transport redesign. It does not, however, add unused raw-write or upload production code merely for symmetry.

A future workflow may therefore become:

```text
Router
  -> downloadFile()
local compiled file
  -> decompile / edit / compile
local compiled file
  -> future uploadFile()
Router
```

No public `uploadFile()` method is added now because RouterOS currently lacks a documented large-file chunk-write counterpart equivalent to `/file/read`. Small-file upload through `contents` may be investigated separately, but binary safety must be proven explicitly before it is supported.

## Documentation impact

The README or API documentation for the release should document:

- `downloadFile()` syntax and return value;
- RouterOS 7.13+ requirement for the initial implementation;
- binary-safe behavior;
- final-file and `.part` cleanup semantics;
- the fact that existing `execute()` remains text-oriented;
- that upload and older-RouterOS fallback support are not yet included.

## Release strategy

Implementation occurs only on the maintained fork feature branch and must not be added to the clean upstream bugfix branch used by upstream PR #91.

After implementation, verification, and separate integration approval, the expected next maintained-fork release is `3.0.8-praktimarc.2`.

Whether the binary download feature is later suitable for an upstream contribution is a separate decision after the fork implementation has proven stable.