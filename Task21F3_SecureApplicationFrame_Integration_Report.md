# Task 21F-3 — Secure Application Frame Integration Verification Report

## TASK21F3_STATUS=BLOCKED

Stopped per Section 24 stop conditions (1, 2, 11). No production code modified, no new tests added, no git operations performed.

## Executive Summary

The required invariant

```
Handshake -> SecureSession -> SECURE -> Application ProtocolFrame -> SecureFrameCodec -> TCP
```

is NOT implemented in the production transfer path. The cryptographic transport stack
(`SecureHandshake`, `SecureSession`, `SecureFrameCodec`, `CryptoSequence`, `AesGcm`) exists
as a fully unit-tested, isolated component tree under
`app/src/main/java/com/filo/transfer/core/network/security/`, but it has ZERO call sites
outside that package. Every application frame in production — including all post-handshake
traffic — is transmitted and received in plaintext through `FrameCodec` (VER=1 framing) via
`SocketConnection`.

Triggered stop conditions:

1. **SecureFrameCodec is not actually reachable from the production application-frame path.** (Stop 1)
2. **FrameCodec remains reachable after the handshake** — in fact it is the ONLY post-handshake path. (Stop 2)
11. **The actual architecture contradicts the previous 21F-2R report**, which claimed the secure
    integration was implemented and verified in `TransferSender.kt`/`TransferReceiver.kt`.
    `git status` shows both files unmodified at HEAD `bfa71ca`. (Stop 11)

Per Section 24, this report stops and reports the smallest required change set instead of
implementing it by guessing.

---

## Checkpoint (Section 4)

### A. Actual outbound application-frame path (plaintext)

```
TransferSender.transfer() / transferSingleFile()
  -> FramePayloads.encode*()                       (application payload encoding, FramePayloads.kt)
  -> ProtocolFrame(type, sequence, payload)        (in-memory frame, ProtocolFrame.kt)
  -> SocketConnection.sendFrame(frame)             (SocketConnection.kt:66)
  -> FrameCodec.writeFrame(bufferedOut, frame)     (SocketConnection.kt:70, FrameCodec.kt:20)
  -> MAGIC(4,"FILO") || VER=1 || TYPE || SEQ(8) || LEN(4) || PAYLOAD   -> TCP (BufferedOutputStream -> Socket.getOutputStream())
```

Every post-handshake sender frame type flows through this exact path:
`MANIFEST` (TransferSender.kt:213), `FILE_HEADER` (TransferSender.kt:249, sequence=fileIndex),
`RESUME_RESPONSE` (TransferSender.kt:276), `DATA_CHUNK` (TransferSender.kt:325, sequence=currentOffset),
`CHECKSUM` (TransferSender.kt:365), `COMPLETE` (TransferSender.kt:147), `CANCEL` (TransferSender.kt:77),
`ERROR` (TransferSender.kt:173).

No encryption step exists anywhere in this path. No `SecureSession`, key, or
`CryptoSequence` is referenced.

### B. Actual inbound application-frame path (plaintext)

```
TransferReceiver.receive() / receiveSingleFile()
  -> SocketConnection.receiveFrame()               (SocketConnection.kt:93)
  -> FrameCodec.readFrame(bufferedIn)             (SocketConnection.kt:97, FrameCodec.kt:53)
  -> ProtocolFrame (verbatim plaintext payload)
  -> FramePayloads.decode*() -> application handling
```

Every post-handshake receiver frame type flows through this exact path:
`MANIFEST` (TransferReceiver.kt:225), `FILE_HEADER` (TransferReceiver.kt:314),
`RESUME_RESPONSE` (TransferReceiver.kt:346), `DATA_CHUNK` (TransferReceiver.kt:382),
`CHECKSUM` (TransferReceiver.kt:407), `COMPLETE` (TransferReceiver.kt:149).

Application processing receives the unauthenticated plaintext payload directly.

### C. Exact SecureFrameCodec call sites

**NONE in production code.** Exhaustive grep for `SecureFrameCodec` across
`app/src/main/java` returns zero references outside
`com/filo/transfer/core/network/security/crypto/` (its own definition and doc comments).
Only test code references it: `SecureFrameCodecTest.kt` (61 tests).

### D. Exact FrameCodec call sites

| Location | Call | Context |
|---|---|---|
| `SocketConnection.kt:70` | `FrameCodec.writeFrame(bufferedOut, frame)` | sole production TX framing path |
| `SocketConnection.kt:97` | `FrameCodec.readFrame(bufferedIn)` | sole production RX framing path |
| `ProtocolFrameCodecTest.kt` | both | test |

`SocketConnection.kt` imports only `FrameCodec` from the protocol package; it imports
nothing from `security.*`.

### E. Whether plaintext is reachable after the handshake

**YES — unconditionally.** `SocketConnection` has no secure mode and no state at all
(`SocketConnection.kt:28-60` holds only streams/locks/close flag). The transfer
state machine (`TransferState.kt:6-35`) has states Idle, Connecting, Handshaking,
Transferring, Verifying, Completed, Paused, Cancelled, Failed — **no SECURE state
exists**. The "handshake" performed by `TransferSender.performHandshake()`
(TransferSender.kt:190-211) and `TransferReceiver.performHandshake()`
(TransferReceiver.kt:197-223) is a **plaintext version negotiation**
(HELLO/HELLO_ACK via `FramePayloads`, carrying version/deviceName/sessionId only —
no key material, no ephemeral keys, no signatures, no HANDSHAKE_FINISH).
After it completes, both sides continue using plaintext `FrameCodec` for all
application traffic. All traffic is VER=1 (`ProtocolConstants.CURRENT_PROTOCOL_VERSION = 1`,
ProtocolConstants.kt:11), while `SecureFrameCodec` operates exclusively on VER=2
(SecureFrameCodec.kt:55).

### F. Exact files that would need modification (smallest required change set)

The integration was never written. The smallest change set to satisfy the invariant:

1. **`TransferSender.kt` / `TransferReceiver.kt`** — replace/extend
   `performHandshake()` with the real cryptographic handshake:
   `SecureHandshake.initiate/respond/completeAsInitiator/completeAsResponder`
   over `HandshakeMessageCodec`-encoded HELLO / HELLO_ACK||signature /
   HANDSHAKE_FINISH-signature frames; obtain the `SecureSession`; instantiate
   `CryptoSequence` (TX) + `CryptoReceiveSequence` (RX) with the Task21A
   directional keys (initiator: TX=keyA/INITIATOR_TO_RESPONDER,
   RX=keyB/RESPONDER_TO_INITIATOR; responder: TX=keyB, RX=keyA).
2. **`SocketConnection.kt` (not on the protected list)** — add a post-SECURE mode:
   TX: serialize the `ProtocolFrame` bytes -> `SecureFrameCodec.encode(bytes, type,
   sessionKey, txSequence, direction)` -> TCP. RX: read VER=2 secure frame ->
   `SecureFrameCodec.decode(bytes, sessionKey, rxSequence, direction)` -> parse
   `ProtocolFrame` -> application. Fail-closed: any `AesGcmException`,
   `SequenceViolationException`, or structural `NetworkError` closes the
   connection with **no fallback to `FrameCodec`** and no sequence resync.
3. **`TransferState.kt` / gating** — introduce a SECURE gate so application frames
   are blocked before SECURE and plaintext (VER=1) frames are rejected after SECURE
   (a VER=1 frame fails `SecureFrameCodec`'s version check at decode step 3 —
   component behavior verified by SecureFrameCodecTest A02).
4. **New integration tests** (Section 17 items A-W) — only meaningful after 1-3.

Design decisions this change set forces (connection-level key storage, whether the
mode switch lives in `SocketConnection` vs. a wrapper, where `SigningIdentity` is
sourced from — `IdentityManager` does not implement `SigningIdentity` in production,
only the test-only `InMemorySigningIdentity` exists) are scope beyond this
verification task and are deliberately not improvised here.

### G. Exact tests that already cover the path

Component-level (all PASS, this session): `SecureFrameCodecTest` (61),
`CryptoSequenceTest` (33), `AesGcmTest` (14), `HandshakeMessageCodecTest` (37),
`HandshakeSecurityTest` (25), `HkdfTest` (9), `ProtocolFrameCodecTest` (11).

End-to-end application-level (all plaintext, all PASS except one known baseline):
`TcpTransferIntegrationTest` (8, 7 pass), `TransferReceiver*Test` (36 total, all pass),
`ChecksumCalculatorTest`/`ContentUriFileSourceTest`/`ResumeLogicTest`/`RetryPolicyTest`
(20 total, all pass).

**No existing test covers any item of Section 17 (A-W) at the integration level,
because the integration does not exist.** No TransferSender-specific test file
exists; sender-side behavior is covered only by `TcpTransferIntegrationTest`
and the helper tests above.

---

## 1. Actual outbound application-frame path

See Checkpoint A. Plaintext `FrameCodec` (VER=1) is the only outbound path for all
application frames, before, during, and after the (plaintext) version handshake.

## 2. Actual inbound application-frame path

See Checkpoint B. Plaintext `FrameCodec` (VER=1) is the only inbound path for all
application frames; application logic receives unauthenticated payloads.

## 3. SecureFrameCodec call sites

None in production. Test-only: `SecureFrameCodecTest.kt`.

## 4. FrameCodec call sites

`SocketConnection.kt:70` (write) and `SocketConnection.kt:97` (read) — see
Checkpoint D. These sites are reachable for 100% of application traffic, including
all post-handshake frames.

## 5. Proof of post-handshake plaintext exclusion

**Exclusion NOT provable — it does not exist.** Evidence:

- `TransferState.kt` defines no SECURE state; nothing in the production tree
  references a secure mode (grep for `SECURE|SecureSession|SecureHandshake|
  CryptoSequence|AesGcm|keyA|keyB` across `transfer/`, `transport/`, `service/`,
  `ui/` returns zero hits).
- `SocketConnection.sendFrame/receiveFrame` unconditionally use `FrameCodec`
  (SocketConnection.kt:70, 97).
- The plaintext HELLO payload (`FramePayloads.encodeHello`, FramePayloads.kt:28-36)
  carries no key material, so no session keys could possibly be established by the
  current handshake; there is nothing for `SecureFrameCodec` to consume.
- The required post-handshake plaintext ban (Section 12: HELLO, HELLO_ACK,
  HANDSHAKE_FINISH, MANIFEST, FILE_HEADER, DATA_CHUNK, CANCEL, PAUSE, RESUME in
  plaintext) is therefore violated in full: all of these frame types are
  transmitted in plaintext after the version exchange.

Counterfactual note: once the Section 4-F integration exists, `SecureFrameCodec`'s
decode step 3 (SecureFrameCodec.kt:173-179) rejects any VER != 2 frame with
`NetworkError.ProtocolVersionMismatch`, so plaintext VER=1 frames cannot be parsed
as secure frames; HELLO(0x01)/HELLO_ACK(0x02) are additionally rejected as
post-session types via `FORBIDDEN_POST_SESSION_TYPES` (SecureFrameCodec.kt:83) and
at encode time (SecureFrameCodec.kt:116-118). This behavior is component-verified
(SecureFrameCodecTest) but not exercised by any production path.

## 6. TX/RX key-direction verification

**Cannot be verified — no call sites.** The Task21A rules
(initiator TX=keyA/RX=keyB, responder TX=keyB/RX=keyA) are implemented at the
component level: `SecureSession` carries `keyA`/`keyB` (SecureSession.kt:20-21),
`SecureHandshake.buildSession()` derives them (SecureHandshake.kt:195-217), and
`CryptoDirection` defines INITIATOR_TO_RESPONDER=0x00000001 /
RESPONDER_TO_INITIATOR=0x00000002 (CryptoSequence.kt:9-17). No production code
performs any direction or key selection. `SecureSession.kt` states explicitly that
its keys are "for future AES-GCM encryption" (SecureSession.kt:11) — the future
integration is this task's missing piece.

## 7. CryptoSequence verification

Component behavior verified by `CryptoSequenceTest` (33/33 PASS):
- TX `CryptoSequence` starts at 0; each `nextSequence()` consumes exactly one value
  (CryptoSequence.kt:38-49); overflow throws `IllegalStateException`.
- RX `CryptoReceiveSequence` starts expecting 0; `validateAndAdvance` advances only
  on exact match, never on rejection (CryptoSequence.kt:61-71).
- Nonce = DIR4||SEQ8, unique per (direction, sequence) (CryptoSequence.kt:73-85).
- Sequence is structurally independent of any application value: `CryptoSequence`
  has no dependency on file offset, file index, chunk number, `ProtocolFrame.sequence`,
  or transfer ID (no such parameters exist).

**Integration status: not integrated.** No `CryptoSequence`/`CryptoReceiveSequence`
instance is created anywhere in production code.

## 8. ProtocolFrame.sequence verification

Existing application semantics are intact and NOT repurposed:

- `FILE_HEADER` sequence = file index (TransferSender.kt:252, `sequence = fileIndex.toLong()`)
- `DATA_CHUNK` sequence = byte offset in the file (TransferSender.kt:328, `sequence = currentOffset`)
- handshake / control frames: sequence = 0 (default, ProtocolFrame.kt:9)
- `FrameCodec` serializes it verbatim in the 8-byte SEQ field (FrameCodec.kt:37, 86)

Nothing in the codebase assigns `ProtocolFrame.sequence` to a crypto sequence.
(Conversely, no crypto sequence exists yet to keep it independent of.)

## 9. AAD verification

Component level, per Task21D: `buildAad` constructs exactly 14 bytes
VER(1)||TYPE(1)||CRYPTO_SEQ(8)||CT_LEN(4); MAGIC excluded
(SecureFrameCodec.kt:240-247; constants at :264). Encode uses the wire CT_LEN
(ciphertext+tag length) (SecureFrameCodec.kt:133); decode reconstructs AAD from the
validated header before GCM (SecureFrameCodec.kt:218-220). Verified by
SecureFrameCodecTest F09/F10/F11 (14-byte size, MAGIC exclusion, byte layout).
Not exercised by any production path.

## 10. Error / fail-closed behavior

Component level (SecureFrameCodec.kt:156-232, verified by SecureFrameCodecTest A/D/S families):
corrupted ciphertext/tag, corrupted AAD, wrong key, wrong direction, wrong/replayed
sequence, truncated frames, invalid CT_LEN, invalid version all reject before or at
GCM authentication; RX sequence advances only post-authentication
(SecureFrameCodec.kt:226-230); TX sequence is consumed before encryption and is
never reused on failure (SecureFrameCodec.kt:124-136, "If encryption subsequently
fails, that sequence is lost and MUST NOT be reused").

Production level: **no authentication failure path exists** — the transfer path
propagates only `NetworkError`/socket errors from plaintext `FrameCodec`
(SocketConnection.kt:72-84, 98-113 close-on-error). The required behavior
"authentication failure -> do not process app data, no FrameCodec fallback, no
plaintext continuation, no silent resync" is currently vacuously "compliant" only
because there is no secure path at all. It must be enforced by the missing
integration (fail-closed connection close on any secure-decode failure).

## 11. Multiple-frame verification

Component level: SecureFrameCodecTest S01-S06 verify consecutive sequences
0,1,2..., duplicate/future/old-sequence rejection, and per-frame unique nonce.
Not exercised by any production path.

## 12. Mixed application-frame verification

No mixed-type secure integration test exists. At the component level,
SecureFrameCodecTest E06 verifies independently authenticated frames of different
types, but the required realistic mixed application sequence
(FILE_HEADER -> DATA_CHUNK xN -> checksum/complete, plus MANIFEST/CANCEL/PAUSE/RESUME
through the secure transport) is not covered anywhere, because none of these frame
types ever transit `SecureFrameCodec` in production. Note: the current application
protocol has no `FILE_END` frame type (FrameType.kt); file completion is signaled by
CHECKSUM/CHECKSUM_RESULT/COMPLETE.

## 13. Tests and exact results

Executed in this session via `./gradlew :app:testDebugUnitTest`
(repeated: 2 runs, identical results; 335 tests total, 7 failures):

| Suite | Tests | Failures | Result |
|---|---|---|---|
| HandshakeMessageCodecTest | 37 | 0 | PASS |
| HandshakeSecurityTest | 25 | 0 | PASS |
| HkdfTest | 9 | 0 | PASS |
| AesGcmTest | 14 | 0 | PASS |
| CryptoSequenceTest | 33 | 0 | PASS |
| SecureFrameCodecTest | 61 | 0 | PASS |
| ProtocolFrameCodecTest | 11 | 0 | PASS |
| TcpTransferIntegrationTest | 8 | 1 | 7/8 PASS (see 14) |
| TransferReceiverCollisionTest | 11 | 0 | PASS |
| TransferReceiverCollisionResumeTest | 1 | 0 | PASS |
| TransferReceiverCollisionCascadeTest | 1 | 0 | PASS |
| TransferReceiverEarlyEofTest | 2 | 0 | PASS |
| TransferReceiverFailureCleanupTest | 5 | 0 | PASS |
| TransferReceiverManifestLimitTest | 5 | 0 | PASS |
| TransferReceiverPartialCleanupTest | 4 | 0 | PASS |
| TransferReceiverPauseTest | 7 | 0 | PASS |
| ChecksumCalculatorTest | 6 | 0 | PASS |
| ContentUriFileSourceTest | 3 | 0 | PASS |
| ResumeLogicTest | 5 | 0 | PASS |
| RetryPolicyTest | 6 | 0 | PASS |

Relevant TransferSender tests: none exist (NOT EXECUTED — no such suite; sender
behavior is covered by TcpTransferIntegrationTest and the helper suites above).
All 20 Section 17 integration-test items (A-W): NOT EXECUTED / NOT ADDABLE —
they presuppose a secure application-frame path that does not exist; adding them
now would fail or would require the production changes this task forbids guessing at.

## 14. Baseline failures

- **TcpTransferIntegrationTest > testChecksumMismatchRejectsFile** — failed again in
  both runs this session (`NetworkError$IoError` at TcpTransferIntegrationTest.kt:382,
  caused by `SocketTimeoutException` after 30s). Reported as **BASELINE FAILURE**
  (documented flake in Task21F-2R §10; no Task21F-3 code change could have caused
  it — this task made no code changes).
- Unrelated pre-existing failures observed in the same run (baseline environment
  issues, not handshake/security related): `ExampleRobolectricTest`,
  `GreetingScreenshotTest`, `FileMetadataResolverTest`, `FileStreamProviderTest`,
  `StorageProviderIntegrationTest` (Robolectric SDK `UnsupportedOperationException`
  at `DefaultSdkProvider.java:170`), and `SendViewModelTest > clearing files resets
  selection` (assertion at SendViewModelTest.kt:120, also documented as baseline in
  Task21F-2R §10).

## 15. Files changed

**NONE.** Task21F-3 performed read-only inspection and test execution only.
All protected files (AesGcm, Hkdf, CryptoSequence, SecureFrameCodec,
SecureHandshake, SecureSession, EphemeralKeyPair, IdentityManager,
HandshakeMessageCodec, HandshakeMessage, HandshakeTranscript, FullHandshakeTranscript,
ProtocolFrame, FramePayloads, FrameType, Task21G spec) remain unmodified.
`git status` at HEAD `bfa71ca`: modified = `FrameType.kt` (21F-1 HANDSHAKE_FINISH
addition) and `Task20R2_Report.md`; all security components and tests are
untracked-but-present; `TransferSender.kt` / `TransferReceiver.kt` /
`SocketConnection.kt` are unmodified.

## 16. Files created

- `Task21F3_SecureApplicationFrame_Integration_Report.md` (this file).

## 17. Any discrepancy with Task21F-2R

**Material discrepancy found (stop condition 11).** The Task21F2 Final
Verification Report asserts, as "verified from actual code":

| 21F-2R claim | Actual codebase state |
|---|---|
| §2: `performHandshake()` sends HANDSHAKE_FINISH; "Connection enters SECURE state" | `TransferSender.kt:190-211` / `TransferReceiver.kt:197-223` perform a plaintext HELLO/HELLO_ACK version exchange only. No HANDSHAKE_FINISH is ever sent or received. No SECURE state exists in `TransferState.kt`. |
| §4: "Both crypto sequences start at zero ... Initialized in `performHandshake()` after handshake completion" | No `CryptoSequence`/`CryptoReceiveSequence` instantiation exists anywhere in production code. |
| §4: "keyA used for outbound (TX) encryption; keyB for inbound (RX)" | No key selection or `SecureFrameCodec` invocation exists anywhere in production code. |
| §6: "No plaintext fallback ... SecureFrameCodec failure -> FrameCodec: NOT FOUND" | Trivially "not found" because SecureFrameCodec is not in the path at all; plaintext `FrameCodec` IS the post-handshake path. |
| §8: "SecureFrameCodec only active after SECURE state entry" | No SECURE state and no activation exist. |
| §9: "Actual production modification: TransferSender.kt and TransferReceiver.kt — handshake integration" | `git status` at HEAD `bfa71ca` shows both files unmodified. No commit in history integrates the secure stack into the transfer path. |
| §11 (acknowledged): HELLO uses `FramePayloads.encodeHello()` instead of `HandshakeMessageCodec.encode()` | Confirmed — and the plaintext HELLO payload carries no key material or algorithm fields, so the handshake cannot bootstrap a `SecureSession`. This discrepancy was acknowledged in 21F-2R §11 but its PASS status was nonetheless retained. |

The 21F-2R "PASS" therefore reflects a specification-level review, not a
code-verified integration. Its §10 claim that "SecureFrameCodecTest PASS —
post-handshake framing verified" is valid only for the isolated component.

## 18. Any unresolved issue

1. **BLOCKER (this task's status):** the post-handshake secure application-frame
   integration (Section 4-F change set) has to be designed and implemented; its
   open design questions include: where SecureSession + TX/RX CryptoSequence
   state lives per connection; how `SocketConnection` gains a post-SECURE mode
   (it is not protected, but the mode-switch design was outside 21F-2's verified
   scope); and where a production `SigningIdentity` comes from
   (`IdentityManager` does not implement `SigningIdentity`; only the test-only
   `InMemorySigningIdentity` exists).
2. **Stale comment in protected file:** `SecureFrameCodec.kt:79-81` states
   "0x03 (HANDSHAKE_FINISH) is not yet a known FrameType code and is rejected by
   the unknown-type check". Since 21F-1, `HANDSHAKE_FINISH(0x03)` IS a known
   `FrameType` (FrameType.kt:9), and it is NOT in
   `FORBIDDEN_POST_SESSION_TYPES` (SecureFrameCodec.kt:83), so a correctly
   authenticated secure frame of type 0x03 would pass the type checks. This is a
   documentation/staleness issue only (the frame would still require valid
   authentication to be accepted); it cannot be fixed here because
   `SecureFrameCodec.kt` is protected. Flagged for a future task.
3. **21F-2R report reliability:** its PASS should be treated as
   specification-level verification only; any downstream task that assumed the
   secure transport is live (e.g., Task 21F-4) must first complete the Section
   4-F integration and re-verify.

---

## Final Status

All Section 24 stop conditions that apply are triggered (1, 2, 11). The
verification is complete; the integration is missing; per task rules, work stops
here with no production edits, no new tests, and no git operations. Task 21F-4
is NOT started.

**TASK21F3_STATUS=BLOCKED**
