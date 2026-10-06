# TASK 21F-6 — SECURE TRANSPORT RESIDUAL FINDINGS FINAL VERIFICATION

## Status

TASK21F6_STATUS=PASS

## 1. Executive Summary

Task 21F-6 completed a Phase-1 read-only audit of residual findings F-01..F-08,
confirmed on-disk remediations against the mandated Phase-2 order, and re-verified
with a fresh `--rerun-tasks` targeted suite and a fresh full suite.

Security-critical work is complete:

- F-01 (post-session 0x03 forbidden) is fixed and tested.
- F-07 (`printStackTrace`) is removed; no logging framework was introduced.
- F-06 (`InMemorySigningIdentity`) is in the test source set, same package.
- F-04 focused reliability tests exist and pass; codec-level truncation was
  already covered and was not duplicated.

Accepted LOW items remain and are documented: F-02, F-03, F-05, F-08.

No HIGH/CRITICAL/blocking defect remains. Task 21G wire values and handshake
order are unchanged. No plaintext application-data fallback exists after
`enterSecure`. No Git operations were performed.

## 2. Phase 1 Audit Result

Report: `Task21F6_Residual_Findings_ReadOnly_Audit.md`

| ID | Phase-1 determination |
|----|------------------------|
| F-01 | CONFIRMED; production forbidden set already includes HANDSHAKE_FINISH |
| F-02 | ACCEPTED COMPATIBILITY LIMITATION (minSdk 30, Ed25519 API 31+, fail-closed) |
| F-03 | ACCEPTED / NO REMEDIATION (lazy generation; single active transfer) |
| F-04 | CONFIRMED; codec truncation already covered; TCP truncated-stream and consecutive-connection tests already on disk |
| F-05 | ACCEPTED / NO REMEDIATION — KEEP RetryPolicy (6-test contract) |
| F-06 | CONFIRMED; class already in test source set, zero production refs |
| F-07 | CONFIRMED; printStackTrace already removed; no project logger exists |
| F-08 | ACCEPTED / NO REMEDIATION (digest in message is not a real extra boundary) |

F-01 existing-test facts recorded in Phase 1:

- P01 tests HELLO, HELLO_ACK, **and** HANDSHAKE_FINISH encode rejection.
- P02 tests decode of type 0x02 only; 0x03 decode is P02b.
- `testHandshakeFrameTypesAreForbiddenAfterSessionStart` covers HELLO via the
  handshake channel after session start, not 0x03 via the secure codec.
- `testAllApplicationFrameTypesRoundTripThroughSecureTransport` does **not**
  include HANDSHAKE_FINISH (correct: it is not an application frame).

F-04 existing-coverage facts:

- Codec: B01 truncated header, B02 truncated ciphertext, B10 CT_LEN below MIN.
- `TransferReceiverEarlyEofTest` is source-file EOF, not truncated secure frames.
- Wire TCP truncated-stream + consecutive independent connections: already present
  as two focused tests; not duplicated.

## 3. F-01 Remediation and Verification

Production (`SecureFrameCodec.kt:83-84`):

```
FORBIDDEN_POST_SESSION_TYPES =
    setOf(FrameType.HELLO, FrameType.HELLO_ACK, FrameType.HANDSHAKE_FINISH)
```

KDoc updated. Encode (`:116`) and decode (`:185`) both use this set.

Handshake D-FIN remains on the dedicated path:
`TransferSender.performHandshake` → `sendHandshakeFrame(FrameType.HANDSHAKE_FINISH, ...)`
then `enterSecure`. No change to numeric 0x03, handshake framing, or AEAD.

Tests:

1. P01 encode rejects HELLO, HELLO_ACK, HANDSHAKE_FINISH.
2. P02b decode rejects raw type 0x03; receive sequence unchanged.
3. HELLO/HELLO_ACK decode still covered by P02 (0x02).
4. Valid handshake still succeeds (`handshakeOverWire` +
   `testHandshakeCompletesAndBothSidesEnterSecureMode` + engine transfer tests).
5. Wire-level post-session `sendFrame(HANDSHAKE_FINISH)` fails closed:
   `testHandshakeFinishForbiddenInPostSessionSecureFrames`.

HANDSHAKE_FINISH was **not** added to the application-frame round-trip list.

## 4. F-02 Accepted Limitation

- `app/build.gradle.kts:18` `minSdk = 30`
- `IdentityManager.algorithm` hard-coded `"Ed25519"`
- AndroidKeyStore Ed25519 requires API 31+
- P-256 branch exists but is unreachable
- API 30: generation throws → `KeystoreSigningIdentity.signTranscript` throws
  `GeneralSecurityException` → handshake `NetworkError.HandshakeFailed` → fail-closed

minSdk was not raised. Algorithm was not switched.

F-02 = ACCEPTED COMPATIBILITY LIMITATION

## 5. F-03 Accepted Finding

- `getOrCreateIdentity()` has zero callers
- Generation is lazy via `loadPublicKey()` fallback
- `TransferService.handleStartSend` / `handleStartReceive` reject a second START
  while `activeJob?.isActive == true`

No concrete concurrent-generation defect. Keys were not eagerly generated.

F-03 = ACCEPTED / NO REMEDIATION

## 6. F-04 Reliability Test Coverage

Not duplicated:

- Codec truncated header / ciphertext / CT_LEN-below-min already in
  `SecureFrameCodecTest` B01, B02, B10.

Added earlier (retained, not duplicated in this continuation):

1. `testTruncatedSecureFrameOverTcpFailsClosedWithoutHanging` — peer close
   mid-session; next `receiveFrame` fails closed within 5s (no hang).
2. `testConsecutiveSecureTransfersOverSameServerPort` — two independent engine
   transfers on the same bound server port both succeed (fresh per-connection
   handshake/codec state).

Both pass in the fresh targeted run.

## 7. F-05 Accepted / Retained

`RetryPolicy.kt` remains. Zero production callers; `RetryPolicyTest` has 6 tests
and 0 failures in the fresh run.

F-05 = ACCEPTED / NO REMEDIATION (KEEP)

## 8. F-06 Remediation and Verification

- Definition: `app/src/test/java/.../security/handshake/InMemorySigningIdentity.kt`
  package `com.filo.transfer.core.network.security.handshake`
- Production `SigningIdentity.kt` contains only `SigningIdentity` + `IdentityVerifier`
- `grep -rln InMemorySigningIdentity app/src/main` → 0
- 9 test files still import the same package; HandshakeSecurityTest 25/25 pass;
  TcpTransferIntegrationTest 8/8 pass

IdentityVerifier was not moved.

## 9. F-07 Remediation and Verification

No `android.util.Log`, Timber, or project logger exists in `app/src/main`.
The four `printStackTrace()` calls were removed. Control flow preserved:

- `sign` → `return null`
- `getOrCreateIdentity` → fall through to generate
- `loadPublicKey` → fall through to generate
- `verifySignature` → `return false`

`grep -rn printStackTrace app/src/main` → 0 matches.

## 10. F-08 Decision

`NetworkError.ChecksumMismatch` default message includes expected/actual strings.
Throw site uses the file SHA-256 as `expected` and a fixed phrase as `actual`.
Notification content text uses `error.message`.

No test asserts on digest-containing message text (dummy `"a","b"` / type checks only).

Both peers already hold file content; local UI is the user's device. Not a real
additional security-boundary violation. Wire checksum values were not changed.

F-08 = ACCEPTED / NO REMEDIATION

## 11. Task21G Compliance

| Spec | Implementation |
|------|----------------|
| 0x03 = HANDSHAKE_FINISH | `FrameType.kt:9` |
| D-FIN payload = signature bytes | `sendHandshakeFrame(HANDSHAKE_FINISH, completion.signatureToSend)` |
| Handshake types 0x01..0x03 forbidden after session start (spec line 369, 486) | `FORBIDDEN_POST_SESSION_TYPES` includes all three |
| Handshake frames do not consume CryptoSequence | handshake path is `sendHandshakeFrame` / `receiveHandshakeFrame`, not the codec |
| First post-handshake secure frame CRYPTO_SEQ = 0 | unchanged; covered by integration test |

No frame ID, wire layout, handshake order, AEAD, or checksum-wire change in 21F-6.

## 12. Production Secure Transport Regression Verification

Initiator:

`TcpClientTransport.connect` → `SocketConnection`
→ `TransferSender.transfer` → `performHandshake`
→ `SecureHandshake.initiate` / D-HELLO / D-ACK / D-FIN via handshake frames
→ `enterSecure(SecureTransportState.forInitiator)`
→ subsequent `sendFrame`/`receiveFrame` go through `SecureFrameCodec`

Responder:

`TcpServerTransport.accept` → `SocketConnection`
→ `TransferReceiver.receive` → `performHandshake`
→ D-HELLO / D-ACK / D-FIN via handshake frames
→ `enterSecure(SecureTransportState.forResponder)`
→ subsequent frames through `SecureFrameCodec`

After `enterSecure`, `receiveFrame` uses only `state.decodeFrame`; plaintext
`FrameCodec.readFrame(bufferedIn)` is reachable only when `secureState == null`.
Authentication / sequence / version failure fail-closes the connection.

No handshake regression: D-FIN still uses the handshake-specific path.

## 13. Test Results

Fresh targeted (`--rerun-tasks`, 33 tasks executed):

- Aggregate of filtered XML: **213 tests, 0 failures, 0 errors**
- `SecureFrameCodecTest`: 62 / 0 / 0
- `SecureTransportIntegrationTest`: 16 / 0 / 0
- `HandshakeSecurityTest`: 25 / 0 / 0
- `TcpTransferIntegrationTest`: 8 / 0 / 0
- `RetryPolicyTest`: 6 / 0 / 0
- `TransferReceiverEarlyEofTest`: 2 / 0 / 0
- BUILD SUCCESSFUL in 3m 8s

Fresh full suite (`--rerun-tasks`, 33 tasks executed):

- **352 tests completed, 6 failed, 0 errors**
- BUILD FAILED due to those 6 tests

Inspected XML root causes (not assumed):

1–5. Environment (Robolectric sandbox), **not** secure-transport code:

- `ExampleRobolectricTest` / classMethod
- `GreetingScreenshotTest` / classMethod
- `FileMetadataResolverTest` / classMethod
- `FileStreamProviderTest` / classMethod
- `StorageProviderIntegrationTest` / classMethod

Each: `java.lang.UnsupportedOperationException`:
`Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java 17)`
at `DefaultSdkProvider.java:170`.

6. Unrelated UI view-model assertion, **not** networking:

- `SendViewModelTest` / `clearing files resets selection`
- `java.lang.AssertionError: expected:<0> but was:<4>` at `SendViewModelTest.kt:120`
- Test adds a 4-byte file then `clearFiles()`; `totalBytes` remained 4.
  No secure-transport, handshake, or codec involvement.

Secure-transport / handshake / crypto / transfer / transport suites are green.

## 14. Remaining Findings

Accepted LOW only:

- L-01 / F-02: API 30 cannot complete Ed25519 Keystore identity (fail-closed)
- L-02 / F-03: lazy identity generation (bounded by single active transfer)
- L-03 / F-05: RetryPolicy retained for test contract
- L-04 / F-08: ChecksumMismatch message may show digest in local UI / peer ERROR

No remaining security-critical defect.

## 15. Final Determination

TASK21F6_STATUS=PASS

All security-critical remediations are complete, no HIGH/CRITICAL/blocking defect
remains, no secure-transport regression, Task 21G remains compliant. Accepted LOW
limitations are explicitly documented.
