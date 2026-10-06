# Task 21F-6: Secure-Transport Residual Findings — Read-Only Audit

Status: `TASK21F6_READ_ONLY_AUDIT_STATUS = COMPLETE`

This report is Phase 1 of Task 21F-6. It inspects the **current on-disk source tree**
for residual findings F-01..F-08. No additional source/test edits were made during
this Phase-1 completion pass.

## Method

For every finding the current source was re-read directly:

- live `grep` / file reads against the paths listed below
- Task 21G spec (`Task21G_HandshakeWireProtocol_Authoritative_Spec.md`) lines 325/369
- production vs test classification of every relevant symbol

## F-01 — Existing test coverage (final Phase-1 item)

### Production (current disk)

- `SecureFrameCodec.kt:83-84`:
  `FORBIDDEN_POST_SESSION_TYPES = setOf(FrameType.HELLO, FrameType.HELLO_ACK, FrameType.HANDSHAKE_FINISH)`
- KDoc at `:78-81` states all three handshake types (0x01, 0x02, 0x03) are rejected
  on both encode and decode.
- `FrameType.kt:9`: `HANDSHAKE_FINISH(0x03)` exists.
- Encode check: `SecureFrameCodec.kt:116`. Decode check: `:185`.

**On-disk status: production gap is already closed.**

### A. P01 (`SecureFrameCodecTest.kt:518`)

Currently tests encode-side rejection of:

- `FrameType.HELLO` (0x01)
- `FrameType.HELLO_ACK` (0x02)
- `FrameType.HANDSHAKE_FINISH` (0x03) — already present at `:532-537`

No other types are in this test. HELLO/HELLO_ACK/HANDSHAKE_FINISH encode rejection
is covered.

### B. P02 (`SecureFrameCodecTest.kt:541`)

P02 decode-side test uses `rawFrame(type = 0x02)` only (HELLO_ACK). Receive sequence
is left at 0.

**0x03 is not in P02 itself.** Coverage is in a sibling test:

- `P02b` (`:551`) — `rawFrame(type = 0x03)` decode rejection, sequence unchanged.

### C. `testHandshakeFrameTypesAreForbiddenAfterSessionStart` (`:398`)

This test covers the **handshake-channel** after `enterSecure`:

- send: `sendHandshakeFrame(FrameType.HELLO, ...)` after session start
- receive: `receiveHandshakeFrame()` after session start

It does **not** send `HANDSHAKE_FINISH` through that handshake channel, and it does
**not** exercise the post-session **secure codec** (`sendFrame`).

A separate test `testHandshakeFinishForbiddenInPostSessionSecureFrames` (`:418`)
covers 0x03 via `sendFrame(ProtocolFrame(HANDSHAKE_FINISH, ...))` after session start.

Valid D-FIN during handshake is covered by `handshakeOverWire` (`:110` sends
`sendHandshakeFrame(FrameType.HANDSHAKE_FINISH, ...)` **before** `enterSecure`).

### D. `testAllApplicationFrameTypesRoundTripThroughSecureTransport` (`:237`)

Application type list (`:240-254`): MANIFEST, MANIFEST_ACK, FILE_HEADER,
RESUME_REQUEST, RESUME_RESPONSE, DATA_CHUNK, CHECKSUM, CHECKSUM_RESULT, PAUSE,
RESUME, CANCEL, ERROR, COMPLETE.

**Does not include `HANDSHAKE_FINISH`.** That is correct: HANDSHAKE_FINISH is a
handshake frame, not a post-session application frame. It must not be added to
this round-trip list.

### F-01 Phase-1 conclusion

| Check | Result |
|-------|--------|
| Production forbidden set includes 0x03 | YES (already on disk) |
| Encode HELLO / HELLO_ACK / 0x03 | YES (P01) |
| Decode 0x02 | YES (P02) |
| Decode 0x03 | YES (P02b) |
| Wire-level 0x03 via sendFrame after session | YES (`testHandshakeFinishForbiddenInPostSessionSecureFrames`) |
| Valid handshake D-FIN still uses handshake path | YES (`handshakeOverWire`) |
| Round-trip list wrongly includes 0x03 | NO |

**F-01 = CONFIRMED, ALREADY REMEDIATED ON DISK. No further production change required.**

## F-04 — Existing reliability coverage (final Phase-1 item)

### A. Truncated secure frames — already present (do not duplicate codec-level)

| Coverage | Location | Notes |
|----------|----------|-------|
| Truncated header | `SecureFrameCodecTest` B01 (`:605`) | 10-byte array, InvalidFrame |
| Truncated ciphertext | B02 (`:614`) | chops 8 bytes of blob; seq unchanged |
| Missing tag | B03 (`:627`) | chops TAG_SIZE |
| Extra trailing byte | B04 (`:638`) | |
| CT_LEN mismatch | B05 (`:649`) | |
| CT_LEN below MIN (tag size) | B10 (`:716`) | `MIN_CT_LENGTH - 1` |
| Excessive CT_LEN | B08 (`:685`) | OversizedPayload |
| Source-side file EOF | `TransferReceiverEarlyEofTest` | NOT a truncated *secure frame*; truncated *file source* |
| Plaintext FrameCodec truncated | `ProtocolFrameCodecTest.testTruncatedFrameThrowsEof` | NOT the secure codec |

Codec-level truncated/malformed secure-frame coverage is **adequate**. Do not add
more codec tests.

Wire-level truncated **secure stream over TCP** (peer close mid-session →
`SocketConnection.receiveFrame` fails closed, no hang) is **not** covered by the
codec tests or by `TransferReceiverEarlyEofTest`. On disk this is already covered by:

- `testTruncatedSecureFrameOverTcpFailsClosedWithoutHanging` (`SecureTransportIntegrationTest.kt:499`)

**Do not add another truncated-TCP test.**

### B. Consecutive independent connections

Search for reconnect / repeated transfer / consecutive TCP / sequence reset:

- Only match: `testConsecutiveSecureTransfersOverSameServerPort` (`:525`)
- `testFirstSecureFrameUsesCryptoSequenceZeroAndAdvancesMonotonically` covers seq=0
  on a **single** connection, not across two connections.

On disk, one focused consecutive-connection test already exists: two independent
engine transfers over the same bound server port both succeed (implies fresh
per-connection handshake + codec state; no cross-connection sequence leak that
would fail the second transfer).

**Do not add another consecutive-connection test.**

### F-04 Phase-1 conclusion

**F-04 = CONFIRMED GAPS WERE REAL; FOCUSED TESTS ALREADY ON DISK. No further tests required.**

## F-07 — Logging mechanism

- `grep` of `app/src/main` for `android.util.Log`, Timber, project Logger, `Log.d/e/w/i`:
  **zero matches**. There is no existing production logging sink.
- `grep -rn printStackTrace app/src/main`: **zero matches**.
- `IdentityManager.kt` catch sites (`:52`, `:68`, `:119`, `:143`) are `catch (_: Exception)`
  with original control flow preserved:
  - `sign()` → `return null`
  - `getOrCreateIdentity()` → fall through to `generateKeyPair()`
  - `loadPublicKey()` → fall through to `generateKeyPair().public.encoded`
  - `verifySignature()` → `return false`

**Minimal equivalent given no logging framework: removal (already done).** Introducing
a logger would be a new dependency / new framework, which the task forbids.

**F-07 = CONFIRMED, ALREADY REMEDIATED ON DISK.**

## F-08 — ChecksumMismatch assertions

- Class: `NetworkError.kt:55-60` — default message embeds `expected` and `actual`.
- Throw site: `TransferSender.kt:398-401` — `expected = sha256` (file digest),
  `actual = "Receiver reported integrity mismatch"`.
- UI: `TransferNotificationManager.kt:164` sets notification content text to
  `networkState.error.message` (so the digest *can* appear in the local notification).
- Tests:
  - `TransferReceiverFailureCleanupTest` — asserts **type** `is ChecksumMismatch`, not message text
  - `RetryPolicyTest` — constructs `ChecksumMismatch("a","b")` / `"expected","actual"` dummy strings; asserts class / retryability
  - `TcpTransferIntegrationTest` — `is NetworkError.ChecksumMismatch`
  - `TransferNotificationManagerTest:105` — constructs dummy `"a","b"`; asserts notification **notNull**, not message body

**No test contract depends on digest-containing message text.**

Security judgment: both peers already hold the file content; the peer is an
authenticated participant; the local UI is the user's own device. This is not a
meaningful additional security-boundary violation. Protocol-required checksum
values on the wire must not be removed.

**F-08 = ACCEPTED / NO REMEDIATION.**

## F-06 — Complete reference inventory

| Location | Class |
|----------|-------|
| `app/src/main/.../handshake/SigningIdentity.kt` | interface `SigningIdentity` + object `IdentityVerifier` only. **No `InMemorySigningIdentity`.** |
| `app/src/test/.../handshake/InMemorySigningIdentity.kt` | class definition, package `com.filo.transfer.core.network.security.handshake` |
| Production `app/src/main` references | **zero** |
| Test references | `HandshakeSecurityTest`, `TransferReceiverCollisionCascadeTest`, `TransferReceiverCollisionResumeTest`, `TransferReceiverEarlyEofTest`, `TransferReceiverFailureCleanupTest`, `TransferReceiverManifestLimitTest`, `TransferReceiverPartialCleanupTest`, `SecureTransportIntegrationTest`, `TcpTransferIntegrationTest` (9 callers + definition file) |

Package is identical under the test source set; existing test imports compile
without change. `IdentityVerifier` remains in production (not test-only).

**F-06 = CONFIRMED, ALREADY MOVED ON DISK. No further move required.**

## F-05 — RetryPolicy

- Production callers: **zero** (only `RetryPolicy.kt` itself)
- `RetryPolicyTest.kt`: **6** `@Test` methods (test contract depends on it)

**F-05 = ACCEPTED / NO REMEDIATION. KEEP RetryPolicy.**

## F-03 — Lazy identity (concurrency/lifecycle)

- `getOrCreateIdentity()`: zero production/test callers.
- Production identity path: `KeystoreSigningIdentity` → `IdentityManager.identityPublicKey`
  / `.sign()` → `loadPublicKey()` / `sign()`.
- `loadPublicKey()` generates lazily on first miss (`:122`).
- `TransferService.handleStartSend` / `handleStartReceive` (`:102-105`, `:203-206`):
  if `activeJob?.isActive == true`, duplicate START is rejected. Single active transfer.
- AndroidKeyStore generation of a named alias is itself serialized by the keystore;
  first-time generation on a single-active-transfer path is practically unreachable
  as a concurrent race.

No concrete defect found. Do not eagerly generate keys.

**F-03 = ACCEPTED / NO REMEDIATION.**

## F-02 — API 31 / minSdk 30

- `app/build.gradle.kts:18` `minSdk = 30`
- `IdentityManager.kt:23` `algorithm = "Ed25519"` hard-coded
- `:78` `KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")` is API 31+
- P-256 branch exists (`:83-90`) but is unreachable (algorithm never switched)
- On API 30, generation throws → `KeystoreSigningIdentity.signTranscript` throws
  `GeneralSecurityException` → handshake fails closed (`TransferSender`/`TransferReceiver`
  wrap as `NetworkError.HandshakeFailed`). No plaintext fallback.

Do not raise minSdk. Do not switch algorithm speculatively.

**F-02 = ACCEPTED COMPATIBILITY LIMITATION.**

## Findings matrix (current disk)

| ID | Status | Severity | Remediation required now? |
|----|--------|----------|---------------------------|
| F-01 | CONFIRMED, already remediated | LOW (was post-session rule gap) | No |
| F-02 | ACCEPTED COMPATIBILITY LIMITATION | LOW | No (do not raise minSdk) |
| F-03 | ACCEPTED / NO REMEDIATION | LOW | No (do not eager-generate) |
| F-04 | CONFIRMED, focused tests already on disk | LOW | No (do not duplicate) |
| F-05 | ACCEPTED / NO REMEDIATION (KEEP) | LOW | No |
| F-06 | CONFIRMED, already moved | LOW | No |
| F-07 | CONFIRMED, already remediated | LOW | No |
| F-08 | ACCEPTED / NO REMEDIATION | LOW | No |

## Out of scope

- Task 21G wire values, handshake design, file-transfer engine, app features
- New logging framework / new dependencies
- Raising minSdk or switching default key algorithm
- Git operations; `rm` / file deletion

## Recommended Phase-2 order (if anything were still open)

1. F-01 (already done)
2. F-07 (already done)
3. F-08 accept (already decided)
4. F-06 (already done)
5. F-05 keep
6. F-03 accept
7. F-02 accept
8. F-04 focused tests (already done)

Phase 2 on this continuation: **verify on-disk remediations match the spec; make no further edits unless a gap is found.**
