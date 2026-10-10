# TASK 21F-5 — Secure Transport Security & Production Hardening Audit (Strict Read-Only)

## TASK21F5_STATUS=COMPLETE

- Date: 2026-10-06
- Mode: strict read-only. No production source, test, Gradle, spec, or existing document was modified. No Git operations were performed. The only file created by this task is this report.
- Evidence method: direct source reads of all transport/security/engine files on disk, plus JUnit XML results under `app/build/test-results/testDebugUnitTest/`. No finding below rests on agent summaries; every non-INFO finding carries file/line evidence.
- Classifications: CONFIRMED DEFECT / CONFIRMED LIMITATION / UNVERIFIED ASSUMPTION / MISSING TEST COVERAGE / PRODUCT / TRUST MODEL / DOCUMENTATION / INFO.
- Severities: CRITICAL / HIGH / MEDIUM / LOW / INFO.
- Remote exploitability: REMOTE AUTHENTICATED / REMOTE UNAUTHENTICATED / LOCAL / NONE / N/A.

## 1. Executive Summary

- 0 CRITICAL, 0 HIGH findings.
- No plaintext fallback exists anywhere in the secure transport: after `enterSecure`, only VER=2 secure frames are accepted; any version, authentication, sequence, or framing failure closes the connection (`SocketConnection.kt:279-344`).
- All network length fields are bounds-checked before any allocation; no unbounded allocation on hostile input (`SocketConnection.kt:301-312`, `HandshakeFraming.kt:70-90`, `HandshakeMessageCodec.kt:77-144`).
- Crypto sequence handling is strict: duplicates/replays/gaps are rejected before GCM, and the receive sequence advances only after successful authentication (`SecureFrameCodec.kt:188-230`).
- 1 CONFIRMED DEFECT, LOW severity: the post-session forbidden frame-type set omits `HANDSHAKE_FINISH` (0x03), which Task21G explicitly requires once the type exists in `FrameType` (it does).
- 1 CONFIRMED LIMITATION preserved: the known API 31 runtime gap — secure transport is unavailable on `minSdk = 30` devices and fails closed (no unsafe degradation).
- Test evidence: `SecureTransportIntegrationTest` 13/13 PASS. Full suite: 348 tests / 6 failures / 0 errors; all 6 failures have verified, non-network causes (5 x environment JDK mismatch, 1 x UI assertion). They are reported with evidence, not labeled "baseline".

## 2. Findings

### F-01 — Post-session forbidden-type set omits HANDSHAKE_FINISH (0x03)

- Classification: CONFIRMED DEFECT (spec-compliance gap). Severity: LOW. Remote exploitability: REMOTE AUTHENTICATED only.
- Evidence:
  - `security/crypto/SecureFrameCodec.kt:83` — `FORBIDDEN_POST_SESSION_TYPES = setOf(FrameType.HELLO, FrameType.HELLO_ACK)`.
  - `protocol/FrameType.kt:9` — `HANDSHAKE_FINISH(0x03)` is now a known frame type, so `decode` step 4 (`SecureFrameCodec.kt:182-186`) accepts a post-session secure frame with TYPE=0x03: it is a known type and is not in the forbidden set.
  - `Task21G_HandshakeWireProtocol_Authoritative_Spec.md` line 369: "Handshake types 0x01..0x03 forbidden after session start." Line 486: "SecureFrameCodec currently forbids HELLO/HELLO_ACK post-session; HANDSHAKE_FINISH must also be forbidden after session once the type exists."
  - The codec's own KDoc (`SecureFrameCodec.kt:78-82`) asserts 0x03 "is not yet a known FrameType code and is rejected by the unknown-type check" — a stale premise invalidated when `HANDSHAKE_FINISH(0x03)` was added to `FrameType` for the handshake (Task21G line 446).
- Observed behavior: a GCM-valid post-session secure frame with TYPE=0x03 passes `SecureFrameCodec.decode` and is returned by `SocketConnection.receiveFrame` (`SocketConnection.kt:314-315`). The application engine's expected-type check then rejects it: e.g. `TransferReceiver.receiveSingleFile` expects DATA_CHUNK (`TransferReceiver.kt:401-403`) and throws `NetworkError.InvalidFrame`, closing the connection.
- Trigger: an authenticated peer (holder of the session key and the correct next CRYPTO_SEQ) sends a secure frame of type 0x03.
- Consequence: forced connection close / transfer abort. No plaintext, key, or sequence material is exposed; the outcome is identical to what the peer can already cause by closing the socket or sending a tampered frame.
- Protocol change required: none. Wire format is unchanged; the fix is a one-line codec hardening (add `FrameType.HANDSHAKE_FINISH` to `FORBIDDEN_POST_SESSION_TYPES`) plus a test — out of scope for this read-only task.
- Test gap: `testHandshakeFrameTypesAreForbiddenAfterSessionStart` (`SecureTransportIntegrationTest.kt`) exercises the `SocketConnection`-level gate (`sendHandshakeFrame`/`receiveHandshakeFrame` after secure → close) and `SecureFrameCodecTest` P01 covers encode-side rejection of HELLO/HELLO_ACK only. No test asserts decode-side rejection of 0x03 post-session.

### F-02 — API 31 runtime gap (preserved known limitation)

- Classification: CONFIRMED LIMITATION + UNVERIFIED ASSUMPTION. Severity: LOW. Remote exploitability: N/A (device capability, not an attack surface).
- Evidence:
  - `app/build.gradle` line 18: `minSdk = 30`.
  - `security/crypto/IdentityManager.kt:79` — `KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")`; Android Keystore Ed25519 support requires API 31.
  - `security/handshake/SigningIdentity.kt:70` — `Signature.getInstance("Ed25519")` (verification side) is likewise API 31+.
  - `IdentityManager.kt:48` — `Signature.getInstance(algorithm)` for signing is API 31+.
- Observed behavior on API 30 devices:
  - `IdentityManager.loadPublicKey()` (line 113-124) falls through to `generateKeyPair()` when the alias is absent; on API 30 the keystore generation throws and the exception propagates out of `getIdentityPublicKey()` → `KeystoreSigningIdentity` (no fallback) → `SecureHandshake.initiate/respond` fail → `performHandshake` throws `HandshakeFailed` → connection closed.
  - `IdentityVerifier.verify` with `Ed25519` returns `false` on API 30 → peer identity signatures fail → handshake rejected.
- Consequence: secure transport is functionally unavailable on API 30 (the minSdk floor); all failures are fail-closed (no plaintext fallback exists, verified in F-03 checklist below).
- Unverified: actual runtime behavior on a physical API 30 device (unit tests run on JVM with the P-256 `InMemorySigningIdentity`).
- Protocol change required: none. Resolution (API 31-only identity vs a P-256 identity algorithm branch, whose verification path already exists in `IdentityVerifier`) is a spec/product decision for a future task; the P-256 identity branch in `IdentityManager.generateKeyPair` (line 84-92) is currently unreachable because `algorithm` is hard-coded to `Ed25519` (line 22-23).

### F-03 — Identity key lifecycle relies on lazy generation; `getOrCreateIdentity` has no production callers

- Classification: UNVERIFIED ASSUMPTION. Severity: LOW. Remote exploitability: NONE (local availability).
- Evidence:
  - Grep over `app/src/main` shows zero production callers of `IdentityManager.getOrCreateIdentity()`; production reaches identity material via `KeystoreSigningIdentity.getIdentityPublicKey()` → `IdentityManager.loadPublicKey()` (`KeystoreSigningIdentity.kt:19`), which lazily generates the keystore key when the alias is absent (`IdentityManager.kt:123`).
  - Transient-keystore-read failure is swallowed (`IdentityManager.kt:120-122`, `printStackTrace`) and control falls to `generateKeyPair()`; generating under an existing AndroidKeyStore alias fails (key already exists), so the exception propagates → handshake fail-closed.
- Assumption (not runtime-verified here): AndroidKeyStore rejects a second `generateKeyPair()` under the same alias, i.e. there is no silent key rotation on transient errors. Observed design is fail-closed; the positive assertion about key-persistence-on-transient-error is unverified.
- Protocol change required: none.

### F-04 — Missing test coverage (reliability, not security)

- Classification: MISSING TEST COVERAGE. Severity: INFO/LOW. Remote exploitability: N/A.
- The 13 `SecureTransportIntegrationTest` cases cover: replay, tampered ciphertext, wrong directional key, app-sequence vs crypto-sequence independence, seq=0 monotonic start, double-`enterSecure`, happy-path handshake, handshake types forbidden after session (HELLO/HELLO_ACK at the connection gate), corrupt signature, all app frame types round-trip, plaintext frame after secure mode, engine e2e over secure transport, out-of-order sequence.
- Gaps (all reliability-class, low practical risk given the codec bounds above):
  1. Decode-side rejection of a 0x03 secure frame post-session (ties to F-01).
  2. Wrong-role handshake message over the wire at integration level (role ∈ {0,1} is unit-tested in `HandshakeMessageCodecTest` / `HandshakeSecurityTest` and enforced by `HandshakeFraming.decodeHello`/`decodeHelloAck` role checks).
  3. Duplicate/retransmitted handshake message over the wire (engine strict type-sequencing catches it: after D-ACK the initiator expects HANDSHAKE_FINISH — `TransferSender.kt:210-213`).
  4. Truncated secure frame over TCP mid-body (`EOFException` → close is handled at `SocketConnection.kt:330-332`; codec-level short frames are covered in `SecureFrameCodecTest`; no explicit secure-mode TCP truncation integration case).
  5. Consecutive independent connections (per-connection fresh `SecureTransportState`, seq restart at 0 — implied by every integration test using a fresh connection; no explicit two-connection test).
- No protocol change required; test additions only.

### F-05 — `RetryPolicy` has zero production call sites

- Classification: PRODUCT / INFO. Severity: INFO. Remote exploitability: N/A.
- Evidence: `transfer/RetryPolicy.kt:13` defines the class; a grep over `app/src/main` finds no production references (only `RetryPolicyTest` in tests). Secure transport does not retry: every secure failure path closes the connection (`SocketConnection.kt:318-342`), and the best-effort ERROR-frame send in the catch-alls is swallowed after close (`TransferSender.kt:183-190`, `TransferReceiver.kt:193-201`).
- Consequence: dead production code with tests; no security impact. Optional cleanup for a future task.

### F-06 — Test scaffolding and legacy codecs shipped in MAIN source

- Classification: DOCUMENTATION / INFO. Severity: INFO.
- Evidence:
  - `security/handshake/SigningIdentity.kt:29-50` — `InMemorySigningIdentity` (JVM test helper, P-256 in-memory keys) lives in the main source set; no production references (tests only).
  - `protocol/FramePayloads.kt:28-67` — legacy VER=1 plaintext HELLO/HELLO_ACK payload codecs (`encodeHello`, `decodeHello`, `encodeHelloAck`, `decodeHelloAck`) have zero production callers; production handshake uses `HandshakeFraming.decodeHello/decodeHelloAck` (secure D-HM/D-ACK). Task21G line 485 explicitly states these legacy codecs remain — so this is acknowledged retention, not drift.
- Consequence: no downgrade path exists (nothing in production emits the legacy payloads); packaging/hygiene note only.

### F-07 — Diagnostics hygiene in crypto-identity paths; flag-only ephemeral key destroy (by design)

- Classification: INFO. Severity: INFO.
- Evidence:
  - `IdentityManager.kt:53, 70, 121, 145` — `e.printStackTrace()` in identity generation/signing/verification catch blocks. No key material appears in the exception messages; on Android this surfaces in logcat (diagnostic noise, not a leak).
  - `EphemeralKeyPair.kt:27-31` — `destroy()` only sets a `destroyed` flag (+ `checkNotDestroyed` guard); JCA `ECPrivateKey` internals cannot be deterministically zeroed. Invoked once, before session creation, at `SecureHandshake.kt:209`. Documented as best-effort; the key is consumed exactly once by ECDH and then collected by GC. Not a concrete leak.
- No protocol change required.

### F-08 — `ChecksumMismatch` error message embeds file-content digests

- Classification: TRUST MODEL / INFO. Severity: INFO.
- Evidence: `model/NetworkError.kt:55-60` — `ChecksumMismatch` message includes both SHA-256 digests; the error propagates to `TransferServiceState`/notifications (`TransferService.kt:354-356, 366-370`) and to the peer via the best-effort ERROR frame (`TransferSender.kt:397-401` reports the mismatch).
- Assessment: both peers already hold the file content (it was transferred), so exposing the digests to an already-authenticated peer adds no practical attacker capability. Not a security issue; informational.

## 3. Verified Areas (no defect found)

Each item lists the observed on-disk behavior with evidence.

- Order of secure-frame validation: `SocketConnection.receiveFrame` reads the 18-byte header, validates MAGIC, then VERSION (before any length check), then CT_LEN bounds, then allocates the body, then delegates to `SecureFrameCodec.decode` which re-runs the full structural walk before GCM (`SocketConnection.kt:285-315`; `SecureFrameCodec.kt:162-233`). No length-based allocation occurs before the version/CT_LEN checks.
- No plaintext fallback: after `enterSecure`, `receiveFrame` accepts only VER=2 secure frames; a VER=1 frame post-session is a `ProtocolVersionMismatch` and closes the connection (`SocketConnection.kt:294-300, 318-320`). There is no alternate handshake, no compat mode, and no plaintext path reachable from production (`TransferSender.transfer` / `TransferReceiver.receive` always run the secure `performHandshake` before any application frame; `TransferService` invokes only these two engines).
- Close semantics on failure: every failure path in `sendFrame`/`receiveFrame`/`sendHandshakeFrame`/`receiveHandshakeFrame` calls `close()` before rethrowing (`SocketConnection.kt:132-144, 197-212, 241-262, 318-342`). `close()` is CAS-guarded and idempotent (`SocketConnection.kt:367-388`).
- Bounded allocation: secure body allocation is bounded by pre-checked CT_LEN ∈ [16, 262144] (`SocketConnection.kt:301-309`). Handshake payload allocation is bounded by LEN ∈ [0, 262144] with negative lengths rejected (`SocketConnection.kt:187-195`). The D-ACK split walk and D-HM decode reject negative or over-remaining u32be length prefixes and never allocate beyond the received buffer (`HandshakeFraming.kt:70-98`, `HandshakeMessageCodec.kt:96-133`).
- Sequence handling: TX consumes exactly one CRYPTO_SEQ before encryption, with overflow pre-check against `CRYPTO_SEQUENCE_MAX` (2^63-2) (`SecureFrameCodec.kt:124-129`; `CryptoSequence.kt:41-48`). RX enforces strict expected+1 before GCM (`SecureFrameCodec.kt:188-196`) and advances only after successful authentication (`SecureFrameCodec.kt:226-230`; `CryptoSequence.kt:65-70`). Replays, duplicates, gaps, negative, and out-of-range sequences never reach GCM.
- No retries: no production retry logic exists (F-05); post-close ERROR-frame sends fail fast via `ensureOpen` and are swallowed (`SocketConnection.kt:361-365`).
- Handshake framing: handshake frames use the 18-byte VER=2 header with SEQ=0, TYPE whitelist {0x01,0x02,0x03}, and LEN bounds; both directions fail closed and close the connection (`SocketConnection.kt:108-146, 156-214`). Handshake frames are forbidden after `enterSecure` on both send and receive sides (`SocketConnection.kt:109-112, 157-160`).
- Lifecycle: `enterSecure` may be called exactly once; a second call closes and fails (`SocketConnection.kt:90-98`). `SecureTransportState` is created per connection from the fresh `SecureSession` and is never reused (`SecureTransportState.kt:27-76`; `TransferSender.kt:224`, `TransferReceiver.kt:235`). The receiver's listening port is released deterministically: `TcpServerTransport.close()` closes the underlying `ServerSocket` (`TcpServerTransport.kt:85-92`), invoked in `TransferService`'s finally blocks and `cleanupActiveTransfer` on cancel/stop/onDestroy (`TransferService.kt:262-271, 327-342`). Sender sockets are closed on all failure paths (`TcpClientTransport.kt:29-42`, `TransferService.kt:192-198`).
- Logging: no cryptographic material (keys, nonces, ciphertext, plaintext, sequence values) is logged. `SequenceViolationException` carries a deliberately generic message (`SecureFrameCodec.kt:21`); `AesGcmException` reports only `AUTHENTICATION_FAILED` (`AesGcm.kt:68-71`). The only diagnostics in crypto paths are `printStackTrace` calls with no key content (F-07).
- Key mapping: initiator TX=keyA@I2R / RX=keyB@R2I; responder TX=keyB@R2I / RX=keyA@I2R (`SecureTransportState.kt:54-75`); session material is 96 bytes derived by HKDF from the ECDH secret bound to the transcript hash (`IdentityManager.kt:223-226`; `SecureHandshake.kt:200-219`). Directional keys are asymmetric — verified by `testDirectionalKeysAreAsymmetric`.
- Application-layer resource limits: manifest file count ≤ 1000, per-file size ≤ 4 GiB, negative sizes rejected, all filenames pass `FilenameValidator` with a second sanitize at write time, collision-safe rename, `.filo.part` staging with atomic completion only after SHA-256 verification (`TransferReceiver.kt:243-320, 322-458`; `ProtocolConstants.kt:43-47`).

## 4. Test Evidence

- `SecureTransportIntegrationTest`: 13 tests, 0 failures, 0 errors (XML: `app/build/test-results/testDebugUnitTest/TEST-com.filo.transfer.core.network.transport.SecureTransportIntegrationTest.xml`). All 13 cases pass: replay rejection, tampered-ciphertext rejection, directional-key asymmetry, app-sequence independence, seq=0 monotonic start, double-enterSecure failure, handshake happy path, handshake-types-forbidden-after-session, corrupt-signature rejection, all app frame types round-trip, plaintext-after-secure rejection, engine e2e, out-of-order sequence rejection.
- All other network/transport/security suites report 0 failures in XML (8+21+1+11+8+14+33+61+37+25+9+6+3+5+6+1+1+11+2+5+5+4+7+13+8+6+6+8+6+2+3 tests across the 38 listed suites).
- Full suite total: 348 tests, 6 failures, 0 errors. The 6 failures, with verified causes (not labeled "baseline"):
  1. `ExampleRobolectricTest` — `UnsupportedOperationException: Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java 17)`. Environment (JDK) mismatch.
  2. `GreetingScreenshotTest` — same Robolectric/Java-21 sandbox error. Environment.
  3. `FileMetadataResolverTest` — same Robolectric/Java-21 sandbox error. Environment.
  4. `FileStreamProviderTest` — same Robolectric/Java-21 sandbox error. Environment.
  5. `StorageProviderIntegrationTest` — same Robolectric/Java-21 sandbox error. Environment.
  6. `SendViewModelTest > clearing files resets selection` — `AssertionError: expected:<0> but was:<4>` (`TransferService`/network code not involved; UI selection-state logic).
- None of the 6 failures involves the secure transport, handshake, crypto, or framing code paths.

## 5. Wire Layout Cross-Check (Task21G)

| Task21G item | Spec location | Implementation | Status |
| --- | --- | --- | --- |
| Handshake outer frame `MAGIC(4)|VER=2|TYPE|SEQ=0|LEN|PAYLOAD` | spec line 30, 356 | `SocketConnection.sendHandshakeFrame` (lines 122-131), `receiveHandshakeFrame` (lines 165-196) | match |
| D-HM layout (version, role, u32be idAlg, u32be idPub, u32be ephPub, nonce[32], u32be keyAg) | spec lines 83-133 | `HandshakeMessageCodec.encode/decode` | match |
| D-ACK = D-HM || signature, no length prefix | spec lines 148-160 | `HandshakeFraming.helloAckPayload/decodeHelloAck` | match |
| D-FIN = raw signature | spec lines 170+ | `HandshakeFraming.helloFinishSignature` | match |
| Secure app frame `MAGIC(4)|VER=2|TYPE|CRYPTO_SEQ(8)|CT_LEN(4)|CT||TAG(16)` | spec lines 365-371 | `SecureFrameCodec.serializeFrame` (lines 253-262) | match |
| AAD = VER|TYPE|SEQ8|CT_LEN4 (14 bytes, MAGIC excluded) | spec lines 35-39 | `SecureFrameCodec.buildAad` (lines 240-247) | match |
| Nonce = DIR4|SEQ8 (12 bytes) | spec lines 41-42 region | `CryptoSequence.kt buildNonce` (lines 73-85) | match |
| Handshake types 0x01..0x03 forbidden after session start | spec lines 325, 369, 486 | connection gate matches (HELLO/HELLO_ACK + 0x03 via whitelist at `SocketConnection.kt:392-396`); codec-level post-session set misses 0x03 | deviation — see F-01 |

## 6. Out-of-Scope Recommendations (for future tasks; no change made here)

1. Add `FrameType.HANDSHAKE_FINISH` to `SecureFrameCodec.FORBIDDEN_POST_SESSION_TYPES` and a decode-side integration test (F-01).
2. Runtime-verify the API 31 gap on an API 30 device and decide between API31-only identity or activating the unreachable P-256 identity branch (F-02).
3. Remove or mark `RetryPolicy`, `InMemorySigningIdentity`, and legacy `FramePayloads` HELLO codecs appropriately (F-05/F-06).
4. Replace `e.printStackTrace()` in `IdentityManager` with structured, key-free diagnostics (F-07).

## 7. Compliance Statement

- Read-only audit: no source, test, Gradle, spec, or existing document was modified.
- No Git operations were performed.
- No protocol redesign, cryptographic primitive change, or Task21G spec change was made or recommended.
- No invented vulnerabilities: every finding above is anchored to on-disk code lines and/or spec lines.
- The known API 31 runtime-verification gap is preserved and carried in F-02.
- The 6 full-suite failures are reported with verified causes (Section 4), not as an unlabeled "baseline".

**TASK21F5_STATUS=COMPLETE** (0 CRITICAL, 0 HIGH; 1 LOW CONFIRMED DEFECT — F-01; 1 LOW CONFIRMED LIMITATION — F-02; 5 INFO findings — F-03..F-08)
