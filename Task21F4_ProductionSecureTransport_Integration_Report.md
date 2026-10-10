# Task 21F-4 — Production Secure Transport Integration Report

## TASK21F4_STATUS=PASSED

The required invariant

```
Handshake -> SecureSession -> SECURE -> Application ProtocolFrame -> SecureFrameCodec -> TCP
```

is now implemented in the production transfer path and verified end-to-end. Every
application frame after the handshake is encrypted and authenticated by
`SecureFrameCodec` (AES-256-GCM, directional keys, monotonic CRYPTO_SEQ); plaintext
`FrameCodec` (VER=1) framing is unreachable for post-session traffic; all
authentication, sequence, and structural failures fail closed (connection close, no
fallback, no resync). All protected files are unmodified.

---

## Checkpoint (Section 4)

### A. Actual outbound application-frame path (post-SECURE)

```
TransferSender.transfer()                       (TransferSender.kt:106, withContext(Dispatchers.IO))
  -> performHandshake(connection)              (TransferSender.kt:129 -> 201-230, crypto handshake)
  -> connection.enterSecure(SecureTransportState.forInitiator(session))  (TransferSender.kt:224)
  -> FramePayloads.encode*()                   (application payload encoding, unchanged)
  -> ProtocolFrame(type, sequence, payload)    (application sequence preserved verbatim)
  -> SocketConnection.sendFrame(frame)         (SocketConnection.kt:227)
       secureState != null branch (SocketConnection.kt:232-236):
       -> FrameCodec.writeFrame(ByteArrayOutputStream, frame)   (plaintext serialization only, never to socket)
       -> SecureTransportState.encodeFrame(plain, frame.type)   (SocketConnection.kt:235)
       -> SecureFrameCodec.encode(plain, type, txKey, txSequence, txDirection)  (SecureTransportState.kt:42)
       -> MAGIC(4) || VER=2 || TYPE || CRYPTO_SEQ(8) || CT_LEN(4) || Ciphertext+Tag  -> TCP
```

Sender frame types flowing through this path: `MANIFEST`, `FILE_HEADER`, `RESUME_RESPONSE`,
`DATA_CHUNK`, `CHECKSUM`, `COMPLETE`, `CANCEL`, `ERROR` — all encrypted.

Handshake-phase frames use a separate, session-only framing path
(`SocketConnection.sendHandshakeFrame`, SocketConnection.kt:108-146):
`MAGIC(4) || VER=2 || TYPE ∈ {HELLO, HELLO_ACK, HANDSHAKE_FINISH} || SEQ=0 || LEN(4) || PAYLOAD`.
Payloads carry public key material and transcript signatures only — no secrets in transit.

### B. Actual inbound application-frame path (post-SECURE)

```
TransferReceiver.receive()                      (TransferReceiver.kt:130 -> performHandshake 208-241)
  -> connection.enterSecure(SecureTransportState.forResponder(session))  (TransferReceiver.kt:235)
  -> SocketConnection.receiveFrame()            (SocketConnection.kt:279)
       secureState != null branch (SocketConnection.kt:285-315):
       -> read 18-byte header
       -> MAGIC check -> VERSION check (must be 2, else ProtocolVersionMismatch)
       -> CT_LEN bounds pre-check (16 <= ctLen <= MAX_CT_LENGTH) BEFORE body allocation
       -> read ctLen body bytes
       -> SecureTransportState.decodeFrame(header+body)  (SocketConnection.kt:314)
       -> SecureFrameCodec.decode(frame, rxKey, rxSequence, rxDirection)  (SecureTransportState.kt:49)
       -> FrameCodec.readFrame(ByteArrayInputStream(decoded.plaintext))   (SocketConnection.kt:315)
  -> ProtocolFrame (authenticated; application payload semantics unchanged)
```

Receiver frame types flowing through this path: `MANIFEST`, `FILE_HEADER`, `RESUME_RESPONSE`,
`DATA_CHUNK`, `CHECKSUM`, `COMPLETE`, `CANCEL`, `PAUSE`, `RESUME`, `ERROR` — all authenticated.

### C. Exact SecureFrameCodec production call sites

| Location | Call | Context |
|---|---|---|
| `SecureTransportState.kt:42` | `SecureFrameCodec.encode(plaintext, type, txKey, txSequence, txDirection)` | only TX path |
| `SecureTransportState.kt:49` | `SecureFrameCodec.decode(frame, rxKey, rxSequence, rxDirection)` | only RX path |
| `SocketConnection.kt:235` | `state.encodeFrame(...)` | invoked from `sendFrame` (secure branch) |
| `SocketConnection.kt:314` | `state.decodeFrame(...)` | invoked from `receiveFrame` (secure branch) |

### D. FrameCodec call sites after this task

| Location | Call | Context |
|---|---|---|
| `SocketConnection.kt:234` | `FrameCodec.writeFrame(ByteArrayOutputStream, frame)` | post-SECURE: builds the authenticated PLAINTEXT bytes only; result is encrypted, never written to the socket in VER=1 form |
| `SocketConnection.kt:315` | `FrameCodec.readFrame(ByteArrayInputStream(plaintext))` | post-SECURE: parses only the GCM-authenticated plaintext |
| `SocketConnection.kt:238` | `FrameCodec.writeFrame(bufferedOut, frame)` | PRE-secure only; no application frames exist pre-secure (handshake uses `sendHandshakeFrame`), so this branch cannot carry application traffic after session start |
| `SocketConnection.kt:317` | `FrameCodec.readFrame(bufferedIn)` | PRE-secure only; after `enterSecure` the secure branch is taken exclusively |

`FrameCodec` was not redesigned; it now serves as the application-frame serializer inside
the authenticated envelope, preserving `ProtocolFrame` semantics byte-for-byte.

### E. Post-handshake plaintext exclusion

- `enterSecure` (SocketConnection.kt:90-98) is one-shot under `writeLock`; a second call
  closes the connection and throws `InvalidFrame` (test `testEnteringSecureModeTwiceFails`).
- Post-SECURE `receiveFrame` validates MAGIC -> VERSION(=2) -> CT_LEN bounds in that order.
  A VER=1 plaintext frame after session start throws `NetworkError.ProtocolVersionMismatch`
  and closes the connection (test `testPlaintextFrameAfterSecureModeIsRejectedAndConnectionClosed`).
- Post-SECURE `sendFrame` never writes VER=1 bytes to the socket.
- `HELLO`/`HELLO_ACK` as application frame types are additionally forbidden by
  `SecureFrameCodec` post-session type checks (encode-time `require` +
  `FORBIDDEN_POST_SESSION_TYPES` at decode time) — test
  `testHandshakeFrameTypesAreForbiddenAfterSessionStart`.
- `sendHandshakeFrame`/`receiveHandshakeFrame` refuse all calls after `enterSecure`
  (SocketConnection.kt:109-112, 157-160) — fail closed.
- No reconnect, retry, or fallback path exists on any authentication failure.

### F. TX/RX key-direction mapping (Task21A / Task21G)

`SecureTransportState` (SecureTransportState.kt:54-75):

| Side | TX key / direction | RX key / direction |
|---|---|---|
| `forInitiator` | keyA / INITIATOR_TO_RESPONDER | keyB / RESPONDER_TO_INITIATOR |
| `forResponder` | keyB / RESPONDER_TO_INITIATOR | keyA / INITIATOR_TO_RESPONDER |

Both `CryptoSequence` and `CryptoReceiveSequence` start at 0 and are fully independent of
`ProtocolFrame.sequence`, file offsets, file indexes, and chunk numbers.

### G. Error / fail-closed behavior (production level)

`SocketConnection.receiveFrame` secure branch (SocketConnection.kt:318-329):

| Failure | Exception | Action |
|---|---|---|
| Non-MAGIC header | `NetworkError.InvalidFrame` | close + throw |
| VER != 2 | `NetworkError.ProtocolVersionMismatch` | close + throw |
| ctLen < 16 | `NetworkError.InvalidFrame` | close + throw |
| ctLen > MAX_CT_LENGTH | `NetworkError.OversizedPayload` | close + throw |
| GCM auth / wrong key / wrong direction / replayed-or-reordered sequence | `SequenceViolationException` / `AesGcmException` -> `NetworkError.InvalidFrame` | close + throw |
| TX crypto sequence overflow | `IllegalStateException` -> `NetworkError.InvalidFrame` | close + throw |
| TX encryption failure | `AesGcmException` -> `NetworkError.IoError` (SocketConnection.kt:247-249) | close + throw |

Handshake phase (`sendHandshakeFrame`/`receiveHandshakeFrame`, SocketConnection.kt:197-212):
any `NetworkError` (version mismatch, non-handshake type, non-zero SEQ, oversize, EOF,
timeout) closes the connection. `TransferSender.performHandshake` /
`TransferReceiver.performHandshake` map `HandshakeError` -> `NetworkError.HandshakeFailed(category)`
and `GeneralSecurityException` -> `NetworkError.HandshakeFailed` — no application frame is
ever processed on a broken handshake, and `enterSecure` is only reached after full
verification on each side (initiator: `completeAsInitiator` verifies the responder's
transcript signature before sending D-FIN; responder: `completeAsResponder` verifies the
initiator's D-FIN signature before entering SECURE).

---

## 1. Files changed (production)

| File | Change |
|---|---|
| `transport/SocketConnection.kt` (+242/-) | `@Volatile secureState`, `isSecure`, `enterSecure` (one-shot); `HandshakeFrame`, `sendHandshakeFrame`, `receiveHandshakeFrame` (VER=2, SEQ=0, type whitelist, oversize guard, fail-closed); `sendFrame`/`receiveFrame` secure branches with version-ordered header validation and pre-allocation CT_LEN bounds |
| `transfer/TransferSender.kt` | ctor `signingIdentity: SigningIdentity = KeystoreSigningIdentity()` (TransferSender.kt:41); `performHandshake` replaced with crypto initiator flow (TransferSender.kt:201-230): initiate -> D-HELLO -> D-ACK -> `completeAsInitiator` -> D-FIN(`signatureToSend`) -> `enterSecure(forInitiator)` |
| `transfer/TransferReceiver.kt` | ctor `signingIdentity: SigningIdentity = KeystoreSigningIdentity()` (TransferReceiver.kt:44); `performHandshake` replaced with crypto responder flow (TransferReceiver.kt:208-241): D-HELLO -> respond -> D-ACK(`fullTranscriptSignature`) -> D-FIN -> `completeAsResponder` (verifies initiator signature) -> `enterSecure(forResponder)` |
| `protocol/FrameType.kt` | pre-existing from 21F-1: `HANDSHAKE_FINISH(0x03)` code (carried into this session's diff vs HEAD `bfa71ca`) |

Public signatures of `TransferSender.transfer()` and `TransferReceiver.receive()` are
unchanged; `service/TransferService.kt` (call sites at :112, :213 using named
`deviceName`) required no modification — `signingIdentity` has a production default.

## 2. Files created (production)

| File | Purpose |
|---|---|
| `security/handshake/KeystoreSigningIdentity.kt` | Production `SigningIdentity` adapter over `IdentityManager` (Android Keystore). Exposes public key + algorithm; `signTranscript` throws `GeneralSecurityException` if the Keystore cannot sign (fail-closed). Never exports the private key. |
| `security/handshake/HandshakeFraming.kt` | D-HELLO / D-ACK / D-FIN wire payload assembly and splitting per Task21G. D-ACK split walks the self-describing D-HM layout (off=2; 3x u32be-prefixed fields idAlgorithm/idPublicKey/ephPublicKey; fixed 32-byte nonce; 1x u32be-prefixed keyAgreementAlg); decoded message re-validated by the protected `HandshakeMessageCodec` (trailing-garbage and role checks). Empty signatures -> `MalformedHandshake`. No boundary guessing. |
| `transport/SecureTransportState.kt` | Per-connection secure state: session keys, directional TX/RX key + `CryptoDirection` selection, TX `CryptoSequence` + RX `CryptoReceiveSequence`. Private constructor; `forInitiator`/`forResponder` factories only. Destroyed with the connection; never reused across connections. |

## 3. Files created (test)

| File | Purpose |
|---|---|
| `transport/SecureHandshakeTestSupport.kt` | Test-only helper `initiateAsClient(conn, identity)`: runs the full crypto initiator handshake over a connected `SocketConnection` (used by 7 manual-protocol receiver test families) |
| `transport/SecureTransportIntegrationTest.kt` | 13 integration tests over a live socket pair (see Section 6) |

## 4. Test files adapted

Engine-driven tests inject `InMemorySigningIdentity.generate()` explicitly (JVM cannot use
`KeystoreSigningIdentity` — see Section 8.1); manual-protocol receiver tests call
`SecureHandshakeTestSupport.initiateAsClient` in place of the old plaintext HELLO exchange;
one checksum test additionally moved its receiver coroutine to `async(Dispatchers.IO)`
(`TcpTransferIntegrationTest.testChecksumMismatchRejectsFile`), consistent with all other
manual-protocol suites:

| File | Adaptation |
|---|---|
| `transport/TcpTransferIntegrationTest.kt` | 12 engine call sites + checksum test handshake -> crypto; receiver on `Dispatchers.IO` |
| `transfer/TransferReceiverEarlyEofTest.kt` | 2 engine pairs |
| `transfer/TransferReceiverCollisionCascadeTest.kt` | engine pair + import |
| `transfer/TransferReceiverCollisionResumeTest.kt` | engine pair (FQN identity) |
| `transfer/TransferReceiverFailureCleanupTest.kt` | helper `handshakeAndStartFile` + inline checksum block |
| `transfer/TransferReceiverManifestLimitTest.kt` | 6 manual blocks -> `initiateAsClient`; 6 receiver sites carry identity |
| `transfer/TransferReceiverPartialCleanupTest.kt` | helper + 2 manual + 2 engine pairs |

## 5. Protected files: unmodified

`AesGcm.kt`, `Hkdf.kt`, `CryptoSequence.kt`, `SecureFrameCodec.kt`, `SecureHandshake.kt`,
`SecureSession.kt`, `EphemeralKeyPair.kt`, `IdentityManager.kt`,
`HandshakeMessageCodec.kt`, `ProtocolFrame.kt`, and the Task21G spec remain untouched.
(`git status`: the security component tree is untracked-but-present from 21B-21F-2;
none of it was modified in this task.)

## 6. New integration tests (SecureTransportIntegrationTest, 13/13 PASS)

| Test | Verifies |
|---|---|
| `testHandshakeCompletesAndBothSidesEnterSecureMode` | Full D-HELLO/D-ACK/D-FIN over a live socket; both sides `isSecure` afterwards |
| `testFirstSecureFrameUsesCryptoSequenceZeroAndAdvancesMonotonically` | `rxSequence.currentExpected == 0` pre-frame; monotonically advances 1,2,3... |
| `testDirectionalKeysAreAsymmetric` | Correct pairing decodes; fresh cross-pairing states (keyA/I2R encrypted, keyB/R2I decrypting) fail GCM authentication |
| `testProtocolFrameSequenceIsIndependentFromCryptoSequence` | FILE_HEADER seq=file index / DATA_CHUNK seq=offset while CRYPTO_SEQ advances per frame |
| `testAllApplicationFrameTypesRoundTripThroughSecureTransport` | All 13 application frame types (MANIFEST, MANIFEST_ACK, FILE_HEADER, RESUME_REQUEST, RESUME_RESPONSE, DATA_CHUNK, CHECKSUM, CHECKSUM_RESULT, PAUSE, RESUME, CANCEL, ERROR, COMPLETE) through the secure transport |
| `testPlaintextFrameAfterSecureModeIsRejectedAndConnectionClosed` | VER=1 frame post-session -> `ProtocolVersionMismatch`, connection closed |
| `testTamperedCiphertextIsRejectedAndConnectionClosed` | 1-byte ciphertext flip -> `InvalidFrame`, connection closed |
| `testOutOfOrderCryptoSequenceIsRejectedAndConnectionClosed` | Frame at seq 2 before seq 1 -> `InvalidFrame` (SequenceViolation), RX sequence not advanced |
| `testReplayedSecureFrameIsRejectedAndConnectionClosed` | Re-sent frame -> `InvalidFrame`, connection closed |
| `testCorruptSignatureFailsHandshakeWithoutEnteringSecureMode` | Tampered D-FIN signature -> `HandshakeFailed`; neither side enters SECURE; connection closed |
| `testHandshakeFrameTypesAreForbiddenAfterSessionStart` | `sendFrame(HELLO)` / `sendFrame(HELLO_ACK)` post-SECURE fail closed |
| `testEnteringSecureModeTwiceFails` | Second `enterSecure` -> close + `InvalidFrame` |
| `testEngineTransferOverSecureTransport` | Full `TransferSender`/`TransferReceiver` engine transfer (manifest, resume exchange, data chunks, checksum, complete) over the secure transport; file integrity verified |

## 7. Full test results

`./gradlew :app:testDebugUnitTest` (this session, final run): **348 tests, 6 failures —
all 6 pre-existing environment failures (Section 8.2); every transfer/transport/security
suite PASS.**

| Suite | Tests | Failures | Result |
|---|---|---|---|
| SecureTransportIntegrationTest (new) | 13 | 0 | PASS |
| TcpTransferIntegrationTest | 8 | 0 | PASS (incl. previously-flaky `testChecksumMismatchRejectsFile`, now deterministic) |
| TransferReceiverCollisionTest | 11 | 0 | PASS |
| TransferReceiverCollisionResumeTest | 1 | 0 | PASS |
| TransferReceiverCollisionCascadeTest | 1 | 0 | PASS |
| TransferReceiverEarlyEofTest | 2 | 0 | PASS |
| TransferReceiverFailureCleanupTest | 5 | 0 | PASS |
| TransferReceiverManifestLimitTest | 5 | 0 | PASS |
| TransferReceiverPartialCleanupTest | 4 | 0 | PASS |
| TransferReceiverPauseTest | 7 | 0 | PASS |
| ChecksumCalculatorTest / ContentUriFileSourceTest / ResumeLogicTest / RetryPolicyTest | 6 / 3 / 5 / 6 | 0 | PASS |
| SecureFrameCodecTest | 61 | 0 | PASS |
| CryptoSequenceTest | 33 | 0 | PASS |
| HandshakeMessageCodecTest | 37 | 0 | PASS |
| HandshakeSecurityTest | 25 | 0 | PASS |
| AesGcmTest / HkdfTest | 14 / 9 | 0 | PASS |
| ProtocolFrameCodecTest | 11 | 0 | PASS |
| FilenameValidatorSecurityTest | 8 | 0 | PASS |
| EndpointValidatorTest / NsdDiscoveryServiceTest / TcpDiscoveryIntegrationTest | 8 / 21 / 1 | 0 | PASS |

## 8. Residual notes

1. **JVM identity limitation (by design, documented in `IdentityManager`):** on a pure
   JVM, `KeyStore.getInstance("AndroidKeyStore")` is unavailable and
   `IdentityManager.sign()` returns null; `KeystoreSigningIdentity` therefore throws
   `GeneralSecurityException` (fail-closed) in JVM tests. All JVM test engines explicitly
   inject `InMemorySigningIdentity` (P-256, in-memory key pair). Production Android
   devices use the default `KeystoreSigningIdentity`. No fallback to in-memory keys
   exists in the production code path.
2. **Pre-existing environment failures (unchanged from Task21F-3 baseline):**
   `ExampleRobolectricTest`, `GreetingScreenshotTest`, `FileMetadataResolverTest`,
   `FileStreamProviderTest`, `StorageProviderIntegrationTest` (Robolectric
   `DefaultSdkProvider` unsupported in this JVM), and
   `SendViewModelTest > clearing files resets selection` (SendViewModelTest.kt:120).
   None touch the network/security code.
3. **Baseline flake eliminated:** `TcpTransferIntegrationTest.testChecksumMismatchRejectsFile`
   previously deadlocked 30s (socket timeout during handshake) because its receiver
   coroutine ran on the runBlocking event loop while the main thread blocked on the
   handshake round-trip. Moving the receiver to `async(Dispatchers.IO)` (the pattern
   already used by all other manual-protocol suites) makes it deterministic.
4. **Manifest sizing under the app limits:** `encodeManifest` ≈ 82 bytes/file
   (writeUTF fileId/fileName/mimeType + writeLong x2). With the application cap of
   1000 files, the largest manifest plaintext ≈ 82.4 KB — well under
   `SecureFrameCodec.MAX_PLAINTEXT_LENGTH` (262128). No reachable manifest can exceed
   the plaintext bound.
5. **Inert inner header:** the VER=1 `FrameCodec` header serialized inside the ciphertext
   is inert; the outer secure header's TYPE is authoritative for the authenticated frame.
   Application semantics (`ProtocolFrame.sequence`, payload layout) are preserved exactly.
6. **Stale comment in protected file (carried from Task21F-3 §18.2):**
   `SecureFrameCodec.kt:79-81` still claims 0x03 is "not yet a known FrameType code";
   since 21F-1 it is known and allowed post-session. Documentation-only; cannot be fixed
   while the file is protected. Flagged again for a future task.

---

## Final Status

The Section 4-F change set identified by Task 21F-3 is implemented exactly as its
smallest-change-set prescribed (SecureTransportState added to `SocketConnection` as the
post-SECURE mode; `TransferSender`/`TransferReceiver` handshake replaced with the real
crypto flow; production `SigningIdentity` sourced from the Android Keystore via
`IdentityManager`). All 13 new integration scenarios plus every adapted suite pass; no
protected file was modified.

**TASK21F4_STATUS=PASSED**
