# TASK 21F-3 — SECURE APPLICATION FRAME INTEGRATION FINAL VERIFICATION

## Status

TASK21F3_STATUS=PASS

Strict read-only re-verification of the CURRENT on-disk source tree (post Task21F-4 / 21F4R / 21F-5 and refactors). No historical report was used as evidence; every conclusion below is anchored to current file/line evidence. No production source, tests, Gradle, protocol spec, or existing documentation was modified; no Git operations were performed. The only file created is this report.

## 1. Executive Summary

The CURRENT production TCP execution path securely integrates application-frame processing after the handshake, on BOTH sides:

- Sender: `TransferService.handleStartSend` → `TcpClientTransport.connect` → `TransferSender.transfer` → secure `performHandshake` (D-HELLO/D-ACK/D-FIN + `enterSecure`) → all application frames (MANIFEST, FILE_HEADER, RESUME_*, DATA_CHUNK, CHECKSUM, CHECKSUM_RESULT, COMPLETE, control frames) encrypted via `SecureFrameCodec` AES-256-GCM.
- Receiver: `TransferService.handleStartReceive` → `TcpServerTransport.bind/accept` → `TransferReceiver.receive` → secure `performHandshake` (responder) → `enterSecure` → all application frames authenticated/decrypted via `SecureFrameCodec`; no application frame is accepted before the session is established.

Explicit comparison with the historical Task21F-3 result:

- Historical `TASK21F3_STATUS=BLOCKED` was because the production transfer path was plaintext and the secure components were not integrated.
- That condition NO LONGER EXISTS. The refactored production path (`TcpServerTransport` + `receive()`, replacing the old `acceptAndTransfer` path) runs the full crypto handshake before any application frame and routes all post-handshake frames through the secure codec. No production-reachable plaintext application-data path remains (Section 5).

The single residual spec deviation (post-session forbidden-type set omits `HANDSHAKE_FINISH` 0x03) is LOW severity and does not break the security boundary: such a frame can only be produced by an authenticated peer, and the application engine fails closed on it (Section 7). No blocking or HIGH defect remains.

## 2. Current Production Execution Path

Server (receiver) entry point — exact trace:

1. Service command: `core/service/TransferService.kt:202` `handleStartReceive(TransferCommand.StartReceive)`; duplicate-start guard at :203-206.
2. Server creation: `TcpServerTransport()` constructed at `TransferService.kt:232`; `server.bind(command.listenPort)` at :234 → `TcpServerTransport.kt:40-57` creates the `ServerSocket`, `reuseAddress=true`, binds, returns `actualBoundPort`.
3. Socket acceptance: `connection = server.accept()` at `TransferService.kt:246` → `TcpServerTransport.kt:62-83`: blocking `server.accept()` with `soTimeout = CONNECT_TIMEOUT_MS` (15 000 ms), wraps the accepted socket in `SocketConnection(clientSocket)` (`TcpServerTransport.kt:72`).
4. Connection lifecycle: the `SocketConnection` takes exclusive ownership of the socket and its buffered streams (`SocketConnection.kt:37-53`); deterministic teardown in `close()` (`SocketConnection.kt:367-388`).
5. Downstream frame processing: `receiver.receive(connection, destinationDir, allowResume = true)` at `TransferService.kt:255` → `transfer/TransferReceiver.kt:110-206` (`receive()`).
6. Application payload extraction: `receiveManifest` (`TransferReceiver.kt:243-320`) and `receiveSingleFile` (`TransferReceiver.kt:322-458`); payloads come out of `connection.receiveFrame()` → `FrameCodec.readFrame(ByteArrayInputStream(decoded.plaintext))` (`SocketConnection.kt:315`), i.e. only after AEAD authentication.
7. Final application-frame handling: per-file DATA_CHUNK streaming to `.filo.part`, SHA-256 verification, atomic rename (`TransferReceiver.kt:390-458`); terminal COMPLETE frame check at `TransferReceiver.kt:160-163`.

Sender entry point (for completeness of the wire path):

- `TransferService.kt:101-199` `handleStartSend` → `TcpClientTransport.connect(targetHost, targetPort)` at :129 → `TcpClientTransport.kt:23-43` (`Socket`, `configureSocket`, bounded 15 s connect, `SocketConnection(socket)` at :32) → `sender.transfer(connection, manifest, sources)` at :185 → `transfer/TransferSender.kt:106-199`.

No other production `SocketConnection` construction sites exist: grep shows exactly two — `TcpClientTransport.kt:32` and `TcpServerTransport.kt:72`. No legacy plaintext engines (`TcpSender`/`TcpReceiver`) exist on disk.

## 3. Secure Session Integration

1. Where the handshake is performed:
   - Initiator: `TransferSender.performHandshake` (`TransferSender.kt:201-230`) — `SecureHandshake.initiate` → `sendHandshakeFrame(HELLO, HandshakeFraming.helloPayload(...))` at :205-208 → `receiveHandshakeFrame()` expects HELLO_ACK at :210-213 → `HandshakeFraming.decodeHelloAck` at :214 → `SecureHandshake.completeAsInitiator` (verifies responder signature BEFORE sending D-FIN, :216-220) → `sendHandshakeFrame(HANDSHAKE_FINISH, completion.signatureToSend)` at :222.
   - Responder: `TransferReceiver.performHandshake` (`TransferReceiver.kt:208-241`) — `receiveHandshakeFrame()` expects HELLO at :209-212 → `HandshakeFraming.decodeHello` at :215 → `SecureHandshake.respond` (validates incoming message before generating the ephemeral key pair, `SecureHandshake.kt:82-84`) → `sendHandshakeFrame(HELLO_ACK, ...)` at :219-222 → `receiveHandshakeFrame()` expects HANDSHAKE_FINISH at :224-227 → `SecureHandshake.completeAsResponder` (verifies D-FIN signature before session creation, `SecureHandshake.kt:175-181`).
2. Where the secure session is created: `SecureHandshake.buildSession` (`SecureHandshake.kt:195-219`) — ECDH P-256 shared secret, `Hkdf.deriveSessionMaterial` (96 bytes → keyA/keyB/bindingKey, `IdentityManager.kt:223-226`), ephemeral key destroyed before session return (`SecureHandshake.kt:209`), `SecureSession.create` (`SecureSession.kt:42-62`).
3. Where the encrypted transport state is established: `connection.enterSecure(SecureTransportState.forInitiator(completion.session))` (`TransferSender.kt:224`) and `connection.enterSecure(SecureTransportState.forResponder(session))` (`TransferReceiver.kt:235`). `enterSecure` is once-per-connection, fail-closed on double entry (`SocketConnection.kt:90-98`); state holds directional keys + per-connection `CryptoSequence`/`CryptoReceiveSequence` starting at 0 (`SecureTransportState.kt:54-75`).
4. Where application frames are received after handshake: first application RX on the receiver is `receiveManifest` → `connection.receiveFrame()` (`TransferReceiver.kt:244`) — only reachable after `performHandshake` succeeded (step ordering in `receive()`, `TransferReceiver.kt:130-133`). Sender's first application exchange is `exchangeManifest` (`TransferSender.kt:232-249`), after `performHandshake` at :129.
5. Application frames DO pass through the production `SecureFrameCodec`: `SocketConnection.sendFrame` secure branch → `state.encodeFrame` → `SecureFrameCodec.encode` (`SocketConnection.kt:232-236`; `SecureTransportState.kt:41-42`); `SocketConnection.receiveFrame` secure branch → `state.decodeFrame` → `SecureFrameCodec.decode` (`SocketConnection.kt:314`; `SecureTransportState.kt:48-49`).
6. Plaintext application-frame processing is NOT reachable in production — see Section 5.

This proves (not merely asserts) that the production TCP execution path reaches the secure transport: the only two production `SocketConnection` construction sites (Section 2) feed exclusively into the two engines, whose first network operation is always the secure handshake.

## 4. Secure Application Frame Path

Exact post-handshake path (receiver side; sender symmetric):

- Framing: 18-byte header `MAGIC(4) | VER=2(1) | TYPE(1) | CRYPTO_SEQ(8 BE) | CT_LEN(4 BE) | CT||TAG(16)` — read at `SocketConnection.kt:286-312`; `CT_LEN` bounds-checked (16..262144) BEFORE body allocation (`SocketConnection.kt:301-309`); header+body reassembled for codec input (`:310-312`).
- Frame-type validation: `SecureFrameCodec.decode` step 4 requires a known `FrameType` and rejects post-session forbidden handshake types (`SecureFrameCodec.kt:181-186`); the engine then enforces the expected application type per protocol step (e.g. `TransferReceiver.kt:333-335` FILE_HEADER, `:401-403` DATA_CHUNK, `:426-428` CHECKSUM, `:161-163` COMPLETE).
- Sequence-number validation: `CRYPTO_SEQ` range [0, 2^63-2] and strict expected+1 checked BEFORE any GCM work (`SecureFrameCodec.kt:188-196`); receiver sequence advances only after successful authentication (`:226-230`). The application-layer `ProtocolFrame.sequence` (file index / byte offset inside the encrypted payload, `TransferSender.kt:271, 347`) is independent of CRYPTO_SEQ — verified by `testProtocolFrameSequenceIsIndependentFromCryptoSequence`.
- AEAD authentication/decryption: `AesGcm.decrypt` with 12-byte nonce `DIR4|SEQ8` and 14-byte AAD `VER|TYPE|SEQ8|CT_LEN4` (MAGIC excluded per Task21G), `SecureFrameCodec.kt:218-224`; any tag mismatch → `AesGcmException("AUTHENTICATION_FAILED")` (`AesGcm.kt:68-69`).
- Ciphertext/plaintext boundary: ciphertext blob is `ciphertext||tag` (`MIN_CT_LENGTH = 16`); maximum inner plaintext 262128 bytes (`SecureFrameCodec.kt:60-67`); plaintext is produced only after GCM success and is parsed from a `ByteArrayInputStream` — never from the socket (`SocketConnection.kt:315`).
- Checksum/digest handling: per-file SHA-256 computed incrementally on the receiver over all received bytes (`TransferReceiver.kt:374-418, 431-450`), compared against the sender's CHECKSUM frame; mismatch → partial file deleted + `ChecksumMismatch` + connection closed. Digests travel inside the AEAD-protected CHECKSUM payload — Task21G-compliant.
- Payload extraction: `FrameCodec.readFrame(ByteArrayInputStream(decoded.plaintext))` at `SocketConnection.kt:315` — inner framing (VER=1 app-layer header) parsed from authenticated bytes only.
- Application-layer dispatch: per expected type in `receiveManifest` / `receiveSingleFile` / `receive` (`TransferReceiver.kt:243-458, 160-163`).

Wire-level cross-check against Task21G (`Task21G_HandshakeWireProtocol_Authoritative_Spec.md`): secure app frame layout (spec :365-371), AAD construction (:35-39), nonce construction, and handshake outer frame (:30, :356) all match the implementation exactly. The only deviation is the post-session forbidden-type set (spec :369, :486 vs `SecureFrameCodec.kt:83`) — Section 7.

## 5. Plaintext Fallback Analysis

Exhaustive classification of every path that can touch application/control data without the secure session:

| # | Path | Location | Classification |
| --- | --- | --- | --- |
| 1 | `SocketConnection.receiveFrame` pre-secure branch (`FrameCodec.readFrame(bufferedIn)`) | `SocketConnection.kt:317` | Unreachable for production application data: the receiver's first RX call is `receiveHandshakeFrame()` (`TransferReceiver.kt:209`), which rejects any VER=1 frame with `ProtocolVersionMismatch` and closes (`SocketConnection.kt:171-177`). No production code calls `receiveFrame()` before `enterSecure`. Test-only reachability. |
| 2 | `SocketConnection.sendFrame` pre-secure branch (`FrameCodec.writeFrame`) | `SocketConnection.kt:237-238` | Production-reachable ONLY for control frames (ERROR in the catch-alls `TransferSender.kt:183-190`, `TransferReceiver.kt:193-201`; CANCEL in `TransferSender.kt:86-94`) in the pre-session failure window. Carries no application payload (protocol-level error strings only). A peer expecting a VER=2 handshake frame fails closed on VER=1 (`SocketConnection.kt:171-177`). LOW finding F3-F3; not a plaintext APPLICATION-data fallback. |
| 3 | `receiveHandshakeFrame` / `sendHandshakeFrame` (VER=2, SEQ=0, plaintext payload) | `SocketConnection.kt:108-146, 156-214` | Handshake-only by design (Task21G C-3: handshake is plaintext; post-session uses SecureFrameCodec). Strictly forbidden after `enterSecure` on both directions (`SocketConnection.kt:109-112, 157-160`) → close. |
| 4 | Legacy `FramePayloads.encodeHello/decodeHello/encodeHelloAck/decodeHelloAck` (VER=1 plaintext HELLO payloads) | `protocol/FramePayloads.kt:28-67` | Dead in production: zero production callers (grep); production handshake uses `HandshakeFraming` secure codecs. Task21G :485 acknowledges these legacy codecs remain. No downgrade path exists. |
| 5 | `InMemorySigningIdentity` | `security/handshake/SigningIdentity.kt:29-50` | Test-only: no production references (production default is `KeystoreSigningIdentity`, `TransferSender.kt:41`, `TransferReceiver.kt:44`). |
| 6 | `FrameCodec` inner-plaintext parsing | `protocol/FrameCodec.kt:53-110` | In-memory only after 21F-4: post-secure it parses authenticated plaintext from a `ByteArrayInputStream` (`SocketConnection.kt:315`), never the socket. |

Conclusion: there is NO production-reachable plaintext APPLICATION-data path. Post-secure, `receiveFrame` accepts only VER=2 secure frames; a VER=1 frame after session start is a `ProtocolVersionMismatch` → connection close (`SocketConnection.kt:291-300`). All application frames in both engines are encrypted (TX) / authenticated+decrypted (RX) before any payload processing. The single plaintext-reachable write (item 2) carries control strings only, in the pre-session failure window, and the peer fails closed.

## 6. State-Machine Verification

- Pre-handshake rejection: first RX on the connection is a handshake frame; non-handshake VER=2 types and any VER=1 frame are rejected with close (`SocketConnection.kt:178-186, 171-177`). The receiver engine cannot process any application frame before `performHandshake` completes (`TransferReceiver.kt:130-133` ordering; no `receiveFrame` call exists earlier).
- Handshake completion: D-HELLO → D-ACK (responder verifies before sending) → D-FIN (both sides verify peer signature over the full transcript before creating the session, `SecureHandshake.kt:129-136, 174-181`); corrupt signature → `HandshakeFailed`, no session, close (`TransferSender.kt:225-229`, `TransferReceiver.kt:236-240`).
- Session state transition: exactly one `enterSecure` per connection; second call → `close()` + `InvalidFrame` (`SocketConnection.kt:90-98`), verified by `testEnteringSecureModeTwiceFails`.
- Post-handshake frame acceptance: only VER=2 secure frames; per-connection sequences start at 0 and are strict expected+1 (`SecureFrameCodec.kt:188-196`); verified by `testFirstSecureFrameUsesCryptoSequenceZeroAndAdvancesMonotonically`, `testOutOfOrderCryptoSequenceIsRejectedAndConnectionClosed`, `testReplayedSecureFrameIsRejectedAndConnectionClosed`.
- Invalid state rejection: handshake frames after session → close on both send and receive sides (`SocketConnection.kt:109-112, 157-160`), verified by `testHandshakeFrameTypesAreForbiddenAfterSessionStart`; plaintext frame after session → close, verified by `testPlaintextFrameAfterSecureModeIsRejectedAndConnectionClosed`.
- Teardown on protocol violation: every decode/auth/sequence/framing failure closes the connection before rethrowing (`SocketConnection.kt:318-342`); engines then close and clear `activeConnection` in `finally` (`TransferSender.kt:193-198`, `TransferReceiver.kt:203-205`).
- Sequence-before-AEAD: CRYPTO_SEQ range + strict-order checks occur structurally before `AesGcm.decrypt` (`SecureFrameCodec.kt:188-196` precede :224) — no out-of-order or replayed ciphertext is ever decrypted. No logic was redesigned; only verified.

## 7. Frame-Type and Task21G Compliance

- `FrameType.kt:6-28`: handshake types HELLO(0x01), HELLO_ACK(0x02), HANDSHAKE_FINISH(0x03); application types MANIFEST(0x10) … COMPLETE(0x60). `fromCode` rejects unknown codes (`FrameType.kt:33`).
- Pre-session handshake whitelist: `SocketConnection.kt:392-396` = {0x01, 0x02, 0x03} — matches Task21G spec :324 (TYPE not in {0x01,0x02,0x03} → reject).
- Post-session forbidden set: `SecureFrameCodec.kt:83` = {HELLO, HELLO_ACK} only. INDEPENDENTLY RE-VERIFIED against current source (fresh reads this session): `FrameType.kt:9` now defines `HANDSHAKE_FINISH(0x03)`, so `decode` step 4 (`SecureFrameCodec.kt:182-186`) accepts a GCM-valid 0x03 frame post-session. Task21G :369 ("Handshake types 0x01..0x03 forbidden after session start") and :486 ("HANDSHAKE_FINISH must also be forbidden after session once the type exists") are therefore not fully implemented. This is the 21F-5 F-01 finding, re-confirmed, NOT fixed (read-only task).
  - Consequence is bounded: crafting such a frame requires the session key + correct CRYPTO_SEQ (authenticated peer only); the application engine's expected-type check rejects it and closes (`TransferReceiver.kt:401-403` et al.) — no plaintext or key exposure; the peer could cause the same outcome by closing the socket.
- Reserved/unknown types: unknown outer type codes → `InvalidFrame` (`SocketConnection.kt:178-179`, `SecureFrameCodec.kt:182-183`).
- Illegal state/type combinations: post-session handshake frames → close (Section 6); pre-session application VER=2 types during handshake → close (`SocketConnection.kt:180-182`).
- Discrepancy recorded as finding F3-F1 (LOW).

## 8. Transport Lifecycle Verification

- Deterministic socket closure: `SocketConnection.close()` is CAS-guarded, flushes then closes both streams and the socket, idempotent (`SocketConnection.kt:367-388`).
- Connection teardown on failure: all error paths in send/receive close first (`SocketConnection.kt:132-144, 197-212, 241-262, 318-342`); engines null out `activeConnection` in `finally` (`TransferSender.kt:193-198`, `TransferReceiver.kt:203-205`).
- Secure-session cleanup: the session lives in per-connection `SecureTransportState` (keys + sequences), created after handshake and destroyed with the connection; never reused across connections (`SecureTransportState.kt:27-35`); `EphemeralKeyPair.destroy()` invoked before session creation (`SecureHandshake.kt:209`).
- No leaked sockets / port release: `TcpServerTransport.close()` closes the underlying `ServerSocket` (`TcpServerTransport.kt:85-92`); `TransferService` finally blocks close connection AND server on all outcomes (`TransferService.kt:262-271`), and `cleanupActiveTransfer` (cancel/stop/onDestroy) does the same plus job cancellation (`TransferService.kt:327-342`). Client failures close the raw socket (`TcpClientTransport.kt:34, 37, 40`).
- Timeout behavior: `soTimeout = SOCKET_TIMEOUT_MS` (30 s) set at construction (`SocketConnection.kt:409`); accept/connect bounded at 15 s (`TcpServerTransport.kt:70`, `TcpClientTransport.kt:31`); `SocketTimeoutException` → `NetworkError.Timeout` + close (`SocketConnection.kt:200-202, 333-335`).
- Exception paths: every `IOException` family in send/receive is mapped to a typed `NetworkError` and closes the connection (`SocketConnection.kt:197-212, 241-262, 318-342`).
- No accidental reuse of an invalid session: post-failure the connection is closed; any subsequent use throws via `ensureOpen` (`SocketConnection.kt:361-365`); best-effort ERROR frames after close are no-ops swallowed by the caller's `try/catch` (`TransferSender.kt:183-190`, `TransferReceiver.kt:193-201`).

## 9. Test Evidence

Source: `app/build/test-results/testDebugUnitTest/` XML (generated 2026-10-06T11:08 — NEWER than every audited source file, latest `SocketConnection.kt` 2026-10-06T10:51, so results reflect the current tree). No test run was executed; the tree was left untouched.

- `SecureTransportIntegrationTest`: 13 tests / 0 failures / 0 errors. Cases: replay rejection, tampered-ciphertext rejection, directional-key asymmetry, app-sequence independence, seq=0 monotonic start, double-enterSecure failure, handshake happy path, handshake-types-forbidden-after-session, corrupt-signature rejection, all app frame types round-trip, plaintext-after-secure rejection, engine e2e over secure transport, out-of-order sequence rejection.
- Related suites, all 0 failures: `SecureFrameCodecTest` (61), `CryptoSequenceTest` (33), `AesGcmTest` (14), `HandshakeSecurityTest` (25), `HandshakeMessageCodecTest` (37), `HkdfTest` (9), `TcpTransferIntegrationTest` (8 — engine TCP transfers using `InMemorySigningIdentity`, i.e. the secure path), `TransferReceiver*` suites (1+1+11+2+5+5+4+7), `ProtocolFrameCodecTest` (11), `FilenameValidatorSecurityTest` (8).
- Full suite: 348 tests / 6 failures / 0 errors. Root causes of all 6 (verified from XML failure messages; NOT labeled "baseline"):
  1-5. `ExampleRobolectricTest`, `GreetingScreenshotTest`, `FileMetadataResolverTest`, `FileStreamProviderTest`, `StorageProviderIntegrationTest` — each failed with `UnsupportedOperationException: Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java 17)`. Cause: environment JDK mismatch (Java 17 runtime vs Robolectric's Java 21 requirement for SDK 36). Unrelated to the network/transport code.
  6. `SendViewModelTest > clearing files resets selection` — `AssertionError: expected:<0> but was:<4>`. UI selection-state logic; no network code involved.
- None of the 6 failures touches the secure transport, handshake, codec, or framing code.

## 10. Findings

| ID | Severity | Status | File | Symbol | Evidence | Impact | Required action (NOT implemented here) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F3-F1 | LOW | CONFIRMED | security/crypto/SecureFrameCodec.kt:83 | `FORBIDDEN_POST_SESSION_TYPES` | Set = {HELLO, HELLO_ACK}; `FrameType.kt:9` now defines `HANDSHAKE_FINISH(0x03)`, so `decode` (:182-186) accepts a GCM-valid 0x03 frame post-session. Task21G :369/:486 require 0x01..0x03 forbidden post-session. Independently re-verified this session; 21F-5 F-01 confirmed still present. | An authenticated peer can make the codec accept a 0x03-typed secure frame; the application engine's expected-type check then fails closed (close). No plaintext/key exposure; outcome equals what the peer can already cause. Spec-compliance gap only. | Add `FrameType.HANDSHAKE_FINISH` to the forbidden set + decode-side test (future task). |
| F3-F2 | LOW | CONFIRMED LIMITATION (preserved) | security/crypto/IdentityManager.kt:79; security/handshake/SigningIdentity.kt:70; app/build.gradle:18 | `generateKeyPair` / `IdentityVerifier.verify` / `minSdk` | `minSdk = 30`; AndroidKeyStore + JCA Ed25519 support is API 31+. On API 30 devices identity generation and verification throw/fail → handshake fails closed → secure transport unavailable on API 30. Unverified on a physical API 30 device (JVM tests use P-256 `InMemorySigningIdentity`). | Functional gap at the minSdk floor; safe failure (no plaintext fallback exists). | Runtime-verify on API 30; product/spec decision: API31-only identity vs activating the unreachable P-256 identity branch (`IdentityManager.kt:84-92`). |
| F3-F3 | LOW | CONFIRMED (informational security note) | transport/SocketConnection.kt:237-238 via transfer/TransferSender.kt:86-94, 183-190 and transfer/TransferReceiver.kt:193-201 | `sendFrame` pre-secure branch | In the pre-session failure window (handshake failed/cancelled before `enterSecure`), best-effort ERROR/CANCEL control frames are written in plaintext VER=1 framing. | Control strings only; no application payload; peer expects VER=2 handshake frames and fails closed (`SocketConnection.kt:171-177`). Not a plaintext application-data path. | Optional hardening: suppress or secure-gate pre-session control frames (future task). |
| F3-F4 | INFO | CONFIRMED | transfer/RetryPolicy.kt:13; security/handshake/SigningIdentity.kt:29-50; security/crypto/IdentityManager.kt:53,70,121,145 | `RetryPolicy` / `InMemorySigningIdentity` / `printStackTrace` | `RetryPolicy` has zero production callers (tests only); `InMemorySigningIdentity` (test scaffolding) ships in main source with no production references; crypto-identity catch blocks call `e.printStackTrace()` (no key material in messages). | Dead/unshipped-safely code and diagnostic noise; no security impact. | Cleanup in a future task. |
| F3-F5 | INFO | MISSING TEST COVERAGE (reliability) | test suites per 21F-5 F-04 | — | No decode-side test for a 0x03 secure frame post-session (ties to F3-F1); no wire-level tests for duplicate handshake retransmission, truncated secure frame over TCP, consecutive-connection sequence reset. | Reliability coverage only; codec bounds already verified safe. | Add tests in a future task. |

## 11. Clean Verification Items

Directly verified on the current source:

1. Production entry paths reach the secure transport (Section 2-3 evidence; exactly two `SocketConnection` construction sites feeding exclusively the two engines).
2. No production-reachable plaintext application-data path (Section 5, items 1-6).
3. Post-handshake frames are authenticated/decrypted before payload processing; plaintext is produced only from GCM-verified ciphertext (`SocketConnection.kt:314-315`, `SecureFrameCodec.kt:218-232`).
4. Sequence validation precedes AEAD decryption; no out-of-order/replay decrypt (`SecureFrameCodec.kt:188-196` before :224).
5. CRYPTO_SEQ independent from application `ProtocolFrame.sequence` (`SecureTransportState.kt:24-25`; integration test).
6. `enterSecure` once-per-connection, fail-closed on repeat (`SocketConnection.kt:90-98`).
7. Handshake frames forbidden after session on both directions → close (`SocketConnection.kt:109-112, 157-160`).
8. Bounded allocation on all network length fields; no unbounded hostile-input allocation (`SocketConnection.kt:187-195, 301-309`; `HandshakeFraming.kt:70-98`; `HandshakeMessageCodec.kt:96-133`).
9. Deterministic socket/server-socket/port cleanup on all paths, incl. cancel/stop/onDestroy (Section 8 evidence).
10. No cryptographic material in logs; generic failure messages only (`SequenceViolationException`, `AesGcmException`; `IdentityManager` traces carry no keys).
11. Manifest resource limits enforced fail-closed: ≤1000 files, ≤4 GiB/file, negative sizes rejected, filename validation + write-time sanitize + collision-safe rename (`TransferReceiver.kt:243-320, 338-354`).
12. No legacy plaintext transfer engine on disk; no alternate handshake; no downgrade path (Section 5, item 4; `find` results).
13. Wire layouts (handshake outer frame, D-HM, D-ACK, D-FIN, secure app frame, AAD, nonce) match Task21G exactly, except the F3-F1 forbidden-set deviation.

## 12. Final Determination

TASK21F3_STATUS=PASS

Rationale: the CURRENT production TCP execution path — `TransferService` → `TcpClientTransport`/`TcpServerTransport` → `TransferSender.transfer` / `TransferReceiver.receive` — performs the Task21G crypto handshake before any application frame and routes every post-handshake application frame through the production `SecureFrameCodec` (AEAD-encrypted TX, authenticated/sequence-validated RX). No production-reachable plaintext application-data processing remains; every identified plaintext-capable path is unreachable, handshake-only, test-only, or control-string-only in the pre-session failure window with fail-closed peers. The historical BLOCKED condition (plaintext production transfer path, unintegrated secure components) has been resolved by the 21F-4 integration and the subsequent refactor.

Residual findings are non-blocking: one LOW spec-compliance gap (F3-F1, 0x03 missing from the post-session forbidden set — confirmed, re-verified, deliberately NOT fixed in this read-only task), one preserved LOW limitation (F3-F2, API 31 gap at minSdk 30, fail-closed), and informational items (F3-F3..F3-F5). None of them allows an unauthenticated attacker to process, read, or inject plaintext application data, and none weakens the verified security boundary. No CRITICAL or HIGH findings exist.
