# TASK 21 — SECURE TRANSPORT READINESS REPORT

Date: 2026-10-04
Git operations: NONE

---

## 1. Execution Summary

```
STATUS=AUDIT_COMPLETE
TASK_21_STATUS=BLOCKED_BY_DESIGN_GAPS
```

Read-only audit of current Filo source after Tasks 18–20R.7.

Production code, tests, and Gradle were not modified.

Physical Android 12 / API 31 runtime validation was **not** executed. Wireless ADB to the tablet failed because this environment cannot reach the tablet LAN. That limitation is preserved. No AndroidKeyStore runtime result is claimed.

---

## 2. Current Security Architecture

Observed from source, not from prior reports.

**Layer A — Handshake crypto (library only, not on the TCP path)**

- `IdentityManager` — AndroidKeyStore identity, default algorithm `"Ed25519"`, reflection `createKeyGenSpec`
- `Hkdf` — RFC 5869 HKDF-SHA256; `deriveSessionMaterial` yields 96 bytes
- `SecureRandomWrapper` — 32-byte handshake nonce; unused `nextIv()` (12 bytes)
- `SecureHandshake` — initiate / respond / completeAsInitiator / completeAsResponder
- `EphemeralKeyPair` — in-memory P-256 ECDH (`secp256r1`), not Keystore
- `SigningIdentity` / `InMemorySigningIdentity` / `IdentityVerifier`
- `FullHandshakeTranscript` — canonical encoding + SHA-256
- `SecureSession` — `keyA`, `keyB`, `bindingKey`, peer fingerprint, transcript hash

**Layer B — TCP transfer (live protocol)**

- `TransferSender.performHandshake` / `TransferReceiver.performHandshake` — **HELLO / HELLO_ACK only**
- `FrameCodec` — plaintext framing
- `ProtocolFrame` — version, type, sequence, payload
- `FramePayloads` — HELLO, MANIFEST, FILE_HEADER, RESUME_*, DATA_CHUNK, checksum, control
- `SocketConnection` — `sendFrame` / `receiveFrame` via `FrameCodec`

**Not present**

- No AES-GCM, no ciphertext, no GCM tag, no directional counters
- `SecureHandshake` is not referenced by `TransferSender`, `TransferReceiver`, `TransferService`, or `SocketConnection`
- No production `SigningIdentity` that wraps `IdentityManager`

Handshake crypto is implemented as an isolated library. The live transfer path is unencrypted framed TCP.

---

## 3. Handshake Assessment

Evaluated against `SecureHandshake` and related classes. These APIs are **not** invoked by TCP transport.

| Capability | STATUS | Source |
|---|---|---|
| Persistent device identity | PARTIAL | `IdentityManager.getOrCreateIdentity()` Keystore alias `filo_identity_key`. Not wired to `SecureHandshake` or TCP. JVM tests use `InMemorySigningIdentity`. |
| Peer identity public key | IMPLEMENTED | `HandshakeMessage.idPublicKey`; stored as `SecureSession.peerFingerprint` input |
| Identity fingerprint | IMPLEMENTED | `IdentityManager.fingerprint`; `SecureSession.computeFingerprint` |
| Ephemeral P-256 ECDH | IMPLEMENTED | `EphemeralKeyPair.generateP256()`, `sharedSecret()` |
| Fresh nonce per handshake | IMPLEMENTED | `SecureRandomWrapper.nextNonce()` 32 bytes in `initiate`/`respond` |
| Transcript construction | IMPLEMENTED | `FullHandshakeTranscript.encode()` |
| Transcript signatures | IMPLEMENTED | Both roles sign `fullTranscript.encode()` |
| Signature verification | IMPLEMENTED | `IdentityVerifier.verify`; failure → `HandshakeError.InvalidSignature` |
| ECDH shared secret | IMPLEMENTED | `KeyAgreement.getInstance("ECDH")` |
| HKDF-SHA256 derivation | IMPLEMENTED | `Hkdf.deriveSessionMaterial(sharedSecret, transcriptHash)` |
| Directional key separation | PARTIAL | 32+32+32 split exists; TX/RX mapping not defined in executable code |
| Binding key derivation | IMPLEMENTED | third 32-byte block; unused after storage |
| Destruction of ephemeral private key | PARTIAL | `EphemeralKeyPair.destroy()` sets a flag only; no zeroization |
| Fresh session keys on reconnect | MISSING | TCP reconnect does not call `SecureHandshake`; new TCP still HELLO-only |

`HandshakeError.ReplayDetected` exists and is never thrown.

`validateIncomingMessage` accepts `idAlgorithm` `"Ed25519"` or `"SHA256withECDSA"`. `IdentityManager.identityAlgorithm` is `"Ed25519"` or `"P-256"`. A production IdentityManager using `"P-256"` would fail handshake validation if wired as-is.

---

## 4. Transport Encryption Boundary

### A. Where handshake currently starts

`TransferSender.start` / `TransferReceiver.start` → `TransferState.Handshaking` → `performHandshake`.

That method is **not** `SecureHandshake`. It is HELLO/HELLO_ACK.

### B. Where HELLO currently occurs

First application frames on the accepted TCP socket:

- Sender: `FrameType.HELLO` with version, `deviceName`, `sessionId` (`manifest.transferId`)
- Receiver: `FrameType.HELLO_ACK` with version, accepted flag, `deviceName`

### C. Sensitive data before cryptographic handshake

All of it. There is no cryptographic handshake on the wire.

Sent in plaintext after TCP accept:

- HELLO: protocol version, device name, transfer/session id
- MANIFEST: transferId, sender name, file ids, names, sizes, mime, lastModified
- FILE_HEADER: names, sizes, mime
- RESUME_REQUEST/RESPONSE: fileId, byte offsets
- DATA_CHUNK: file bytes (`ProtocolFrame.sequence` = file byte offset)
- CHECKSUM / CHECKSUM_RESULT
- PAUSE / RESUME / CANCEL / ERROR / COMPLETE

### D. Where the encryption boundary should be placed

Recommended minimum:

1. TCP accept (unencrypted)
2. Optional unauthenticated version probe **or** fold version into the first authenticated handshake frame
3. **Cryptographic handshake** (`SecureHandshake` messages + signatures) before MANIFEST
4. All subsequent frames: AES-256-GCM with AAD over the authenticated header

HELLO as currently designed (device name + transfer id) is **not** safe as a pre-auth plaintext protocol if those fields matter. Either:

- replace HELLO with handshake messages, or
- keep a minimal version byte then immediately handshake, then encrypt HELLO metadata if still needed

### E. Ownership recommendation (do not implement)

| Concern | Owner |
|---|---|
| Encryption / decryption | New session cipher object, not `FrameCodec` |
| Nonce / counter | Same object; one TX and one RX counter per `SecureSession` |
| AAD construction | Cipher + header fields; codec supplies header bytes |
| Tag verification | Cipher unwrap; fail closed |
| Sequence validation | Cipher / session layer, **not** file resume offsets |

### F. FrameCodec role

Keep `FrameCodec` as length-delimited framing only (magic, version, type, length, payload).

Do **not** put AES-GCM inside `FrameCodec` as it exists today. Either:

- wrap payload as `nonce || ciphertext||tag` inside the existing payload field, or
- add a thin `SecureFrameCodec` that uses `FrameCodec` underneath

A breaking header change is avoidable if ciphertext lives in `payload` and crypto sequence lives in `ProtocolFrame.sequence` **only after** that field is no longer used as a file offset.

### G. Independent TX/RX counters

Sender and receiver already have independent write/read loops (`SocketConnection` writeLock/readLock). They do **not** have session keys or counters.

Once `SecureSession` is available on both sides, independent TX/RX counters are feasible **if** key direction is specified (section 9).

---

## 5. AES-256-GCM Design Requirements

Intended (not implemented):

- AES-256-GCM
- 12-byte nonce
- AAD = authenticated plaintext header
- encrypted payload + 16-byte tag
- per-direction unique nonce/counter
- never reuse nonce with the same key

Current architecture supplies 32-byte `keyA`/`keyB` (AES-256 size) and unused `SecureRandomWrapper.nextIv()` (12 bytes). No GCM code exists.

`MAX_FRAME_PAYLOAD` is 256 KiB. GCM ciphertext is plaintext length + 16. Encrypted payloads must stay within that limit or the constant must change in a later implementation task.

---

## 6. Nonce and Sequence Requirements

`ProtocolFrame.sequence` is **not** a cryptographic sequence number today:

- `FILE_HEADER`: `sequence = fileIndex`
- `DATA_CHUNK`: `sequence = currentOffset` (file byte offset)
- most other frames: default `0`

Therefore:

- unique TX/RX nonce: **cannot** be guaranteed by the current field
- independent directional nonce spaces: **not present**
- counter reset only on new session key: **no session key on the wire**
- reconnect/resume: file offsets **will collide** with any scheme that reuses `sequence` as GCM nonce material
- counter overflow: **not handled**
- deterministic nonce from direction + seq: **appropriate**, but only with a **new** crypto sequence, not file offsets

**Smallest safe design (recommendation only):**

- 12-byte nonce = 4-byte direction/session discriminator + 8-byte unsigned counter
- direction byte distinct for initiator-TX vs responder-TX (or use separate keys so counters are independent)
- counters start at 0 on each **new** `SecureSession` (new handshake / new keys)
- reject wrap; terminate session before 2^64-1
- **never** use resume byte offset as GCM nonce or crypto sequence
- keep file resume offset **inside encrypted payload** (`ResumeRequestPayload.offset`)

---

## 7. AAD Requirements

Current on-wire header (`FrameCodec`, 18 bytes):

| Field | Size | Candidate AAD |
|---|---|---|
| MAGIC `FILO` | 4 | Optional (constant) |
| version | 1 | Yes |
| frame type | 1 | Yes |
| sequence | 8 | Yes **if** it becomes crypto sequence, not file offset |
| payload length | 4 | Yes (length of ciphertext+tag) |

Existing payload fields (not header): transferId, fileId, filenames, sizes, checksums, resume offsets. Those should be **encrypted**, not AAD, except where a future spec needs an unencrypted routing id.

Ambiguity: `sequence` currently means file offset. Using it as AAD while still meaning offset would authenticate the offset but would not give uniqueness for GCM nonces.

Do not invent new header fields unless Task 21A changes the spec. Prefer re-purposing `sequence` **only after** file offsets move fully into payload (they already exist in resume payloads; DATA_CHUNK should not need header-offset once encrypted).

---

## 8. Frame Format Assessment

Current:

```
[magic 4][version 1][type 1][sequence 8][payloadLength 4][payload]
```

Minimum encrypted payload (example, not implemented):

```
payload = [ciphertext || 16-byte GCM tag]
nonce derived locally from direction + crypto sequence
```

Compatibility:

- Peers that do not encrypt cannot interoperate with peers that do
- `CURRENT_PROTOCOL_VERSION` is already `1` for plaintext frames and `SecureHandshake.PROTOCOL_VERSION` is also `1` — **collision / ambiguity**
- Bumping transport version (or a post-handshake “crypto-on” state) is required
- `FrameCodec` can carry opaque ciphertext without a breaking header layout **if** payload remains length-prefixed and max size accounts for the tag

---

## 9. Key Direction

HKDF output (`Hkdf.deriveSessionMaterial`):

```
96 bytes = clientToServerKey(32) || serverToClientKey(32) || handshakeKey(32)
```

`SecureHandshake.buildSession` stores:

```
keyA = material[0..32)
keyB = material[32..64)
bindingKey = material[64..96)
```

Javadoc names **client/server**. Handshake roles are **initiator (role 0) / responder (role 1)**. There is no code that assigns:

Initiator:

- TX key = ?
- RX key = ?

Responder:

- TX key = ?
- RX key = ?

Tests only assert both sides derive **equal** `keyA`/`keyB`, not complementary TX/RX use.

**Recommended convention (do not implement here):** treat initiator as client:

- Initiator TX / Responder RX = `keyA` (first HKDF block, documented as clientToServer)
- Initiator RX / Responder TX = `keyB` (second block, documented as serverToClient)
- `bindingKey` reserved; do not use as AES key

This must be written into the Task 21A spec before GCM work. Complementary derivation already holds; usage mapping does not.

---

## 10. Reconnect and Resume Security

| Question | Finding |
|---|---|
| Reconnect creates a new handshake? | New TCP runs HELLO again, not `SecureHandshake` |
| Fresh ephemeral keys? | Only if `SecureHandshake.initiate/respond` is called; transport never does |
| New session key? | No session key exists on the wire |
| Old AES keys survive? | N/A (no AES). After future GCM, keys live on `SecureSession` with no destroy API |
| Old sequence counters reused? | `sequence` is file offset; resume **intentionally** reuses offsets |
| Resume metadata authenticated? | No. `RESUME_REQUEST`/`RESPONSE` are plaintext |
| Malicious peer alter offsets? | Yes. Receiver trusts peer `confirmedOffset`; sender trusts requested offset within `[0, fileSize]` |
| Resume bound to crypto session? | No |

Transfer-level resume offsets and cryptographic sequence numbers **must stay separate**.

---

## 11. Existing Security Findings

```
F-20R-01=UNVERIFIED — RUNTIME EVIDENCE REQUIRED
F-20R-02=UNVERIFIED — RUNTIME EVIDENCE REQUIRED
F-20R-03=CONFIRMED (source)
F-20R-04=CONFIRMED (source)
F-20R-05=CONFIRMED (source; handshake uses IdentityVerifier, not this method)
```

**F-20R-01** — `IdentityManager.generateKeyPair()` uses `KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")`. Physical API 31 instrumentation did not execute (20R.2–20R.7). Not fixed. Not disproven.

**F-20R-02** — `createKeyGenSpec` reflects constructor `(String, int[], boolean[], boolean[], AlgorithmParameterSpec)` then calls `newInstance(alias, purposes[0], false, false, null)`. Argument types vs values are inconsistent (`purposes[0]` is `Int` while constructor is declared `int[]`). Not runtime-tested. Not fixed.

**F-20R-03** — `EphemeralKeyPair.sharedSecret` imports peer SPKI via `X509EncodedKeySpec` with no `secp256r1` check. Identity signature binds the SPKI bytes if handshake runs; transport never runs handshake.

**F-20R-04** — `IdentityManager.algorithm` is in-memory `@Volatile`, default `"Ed25519"`, not persisted. Process restart resets the field even if a Keystore key remains.

**F-20R-05** — `IdentityManager.verifySignature` uses `Signature.getInstance(idAlgorithm)`. `"P-256"` is not a JCA signature name. Handshake verification uses `IdentityVerifier` with `"Ed25519"` or `"SHA256withECDSA"`. `IdentityManager.verifySignature` is unused by handshake.

---

## 12. Test Coverage

Existing coverage only. No tests added.

| Topic | Coverage |
|---|---|
| Successful handshake | YES — `HandshakeSecurityTest` 16, 21 (in-memory P-256) |
| Tampered transcript / identity / eph / nonce | YES — tests 17–19 |
| Invalid signature | YES — modified data / wrong identity (3–5, 17) |
| Wrong identity | YES — test 5, 17 |
| Mismatched nonce / ephemeral | YES — 18, 19 |
| Wrong role | YES — test 20 |
| Wrong protocol version (transcript) | YES — test 10 |
| Wrong key agreement (transcript) | YES — test 15 |
| HKDF output | YES — `HkdfTest` RFC 5869 A.1–A.3 + session material |
| Directional key **distinctness** | YES — `HkdfTest` directional keys are distinct |
| Directional key **TX/RX assignment** | NO |
| Reconnect / new-session on TCP | NO |
| Malformed frames | YES — `ProtocolFrameCodecTest` magic, type, version |
| Oversized payloads | YES — codec write/read tests |
| Invalid / duplicate crypto sequence | NO |
| GCM auth failure / modified AAD / ciphertext | NO |
| Nonce reuse / counter overflow | NO |
| AndroidKeyStore Ed25519 on API 31 | NO (instrumented tests exist, never executed) |
| Filename safety | YES — `FilenameValidatorSecurityTest` |

Handshake tests never use `IdentityManager` or AndroidKeyStore.

---

## 13. Security Boundary Findings

Unencrypted attacker-controlled data on the current TCP path:

| Input | Classification |
|---|---|
| HELLO version, deviceName, sessionId | MUST BE AUTHENTICATED/ENCRYPTED (deviceName/sessionId). Version may be SAFE BEFORE AUTHENTICATION as a probe only if no other fields are trusted. |
| HELLO_ACK accepted, deviceName | MUST BE AUTHENTICATED/ENCRYPTED |
| MANIFEST (names, sizes, ids, mime) | MUST BE AUTHENTICATED/ENCRYPTED |
| FILE_HEADER | MUST BE AUTHENTICATED/ENCRYPTED |
| RESUME offsets | MUST BE AUTHENTICATED/ENCRYPTED |
| DATA_CHUNK file bytes | MUST BE AUTHENTICATED/ENCRYPTED |
| CHECKSUM / CHECKSUM_RESULT | MUST BE AUTHENTICATED/ENCRYPTED |
| PAUSE / RESUME / CANCEL / ERROR / COMPLETE | MUST BE AUTHENTICATED/ENCRYPTED |
| Frame magic/type/length used only to parse | SAFE BEFORE AUTHENTICATION for framing, then must be AAD after crypto-on |
| NSD discovery endpoints | Out of band; `EndpointValidator` exists; not a substitute for transport crypto |

`FilenameValidator` reduces path traversal on plaintext names; it does not authenticate the peer.

---

## 14. Minimal Implementation Sequence

Do not write code in this task.

### Task 21A — Finalize secure frame specification

- **Objective:** Specify handshake-on-wire placement, version bump, keyA/keyB TX/RX mapping, crypto sequence vs file offset, AAD fields, GCM nonce formula, max payload with tag.
- **Files:** spec/report only (later `ProtocolConstants`, `FrameType` if new handshake frame types are required)
- **Invariant:** File resume offset never equals GCM nonce/counter
- **Tests:** spec tests later; none in 21A if still design-only
- **Compatibility:** plaintext v1 peers will not interoperate
- **Rollback:** keep v1 plaintext path until encrypted path is complete

### Task 21B — AES-256-GCM primitive

- **Objective:** Encrypt/decrypt with 32-byte key, 12-byte nonce, AAD, fail closed on tag mismatch
- **Files:** new crypto module beside `Hkdf`; not `FrameCodec`
- **Invariant:** no nonce reuse API; no key logging
- **Tests:** known-answer, tampered ciphertext, tampered AAD
- **Compatibility:** none (library)
- **Rollback:** unused until integrated

### Task 21C — Directional nonce/sequence management

- **Objective:** Per-session TX/RX counters; bind to `SecureSession`; overflow abort
- **Files:** new class using `SecureSession.keyA/keyB`
- **Invariant:** counters reset only with new keys
- **Tests:** uniqueness, wrap, direction isolation
- **Compatibility:** none until 21D
- **Rollback:** unused until integrated

### Task 21D — Encrypted frame codec

- **Objective:** Wrap `FrameCodec` payload as GCM ciphertext; AAD = version+type+crypto-seq+length
- **Files:** new `SecureFrameCodec` or equivalent; `ProtocolConstants.MAX_FRAME_PAYLOAD`
- **Invariant:** `FrameCodec` remains framing-only
- **Tests:** round-trip, truncated tag, wrong key
- **Compatibility:** requires 21A version rules
- **Rollback:** feature flag / protocol version

### Task 21E — Integrate sender/receiver transport

- **Objective:** Run `SecureHandshake` after TCP connect, **before** MANIFEST; pass `SecureSession` into 21D
- **Files:** `TransferSender.kt`, `TransferReceiver.kt`, `SocketConnection.kt`, new handshake frame types in `FrameType`/`FramePayloads`
- **Invariant:** no MANIFEST/DATA until session exists
- **Tests:** integration handshake + one encrypted chunk
- **Compatibility:** breaking vs current HELLO-first
- **Rollback:** do not ship mixed plaintext data after handshake

### Task 21F — Secure reconnect/resume

- **Objective:** New handshake + new keys + new counters on each TCP; resume offsets only inside encrypted payloads
- **Files:** `TransferSender.kt`, `TransferReceiver.kt`, resume payloads
- **Invariant:** offset ≠ crypto sequence
- **Tests:** resume after drop with new session keys
- **Compatibility:** old resume-over-plaintext gone
- **Rollback:** disable resume until proven

### Task 21G — Negative/security tests

- **Objective:** Cover section 12 gaps (GCM, sequence, nonce, wrong role, reconnect)
- **Files:** `androidTest`/`test` under security and protocol
- **Invariant:** no secret material in assertions
- **Tests:** the suite itself
- **Compatibility:** n/a
- **Rollback:** n/a

### Task 21H — Runtime validation

- **Objective:** Execute `CryptoRuntimeValidationTest` on physical API 31; resolve F-20R-01/02 before production identity default
- **Files:** none unless identity algorithm must change after evidence
- **Invariant:** no fabricated Keystore results
- **Tests:** connected instrumentation
- **Compatibility:** may force P-256 identity default
- **Rollback:** keep library handshake on in-memory P-256 for JVM

**Order note:** 21H can run in parallel with 21A–21C. It **blocks production IdentityManager default**, not the GCM primitive. 21E should not assume Ed25519 Keystore works on API 31.

---

## 15. Risks

1. Handshake library unused on the wire — false sense that transfers are authenticated
2. `ProtocolFrame.sequence` overloaded with file offsets — nonce reuse if naively encrypted
3. keyA/keyB vs client/server naming — crossed keys if sides disagree
4. Dual `PROTOCOL_VERSION = 1` for plaintext frames and handshake
5. `EphemeralKeyPair.destroy()` does not wipe key material
6. `IdentityManager` reflection + Ed25519 Keystore unproven on API 31
7. `IdentityManager.identityAlgorithm` (`P-256`) vs handshake (`SHA256withECDSA`)
8. Resume offsets attacker-controlled
9. No `SecureSession` destruction / key wipe
10. Wireless ADB from this environment cannot validate Keystore

---

## 16. Runtime Validation Limitation

Physical Android 12 / API 31 runtime validation was not executed because the development environment could not reach the physical tablet over Wireless ADB.

Do not treat JVM handshake tests or Android documentation as API 31 AndroidKeyStore evidence.

Pairing codes are not included in this report.

---

## 17. Final Verdict

```
TASK_21_STATUS=BLOCKED_BY_DESIGN_GAPS
```

Not `READY_FOR_IMPLEMENTATION`: handshake works only as a library; transport is plaintext; crypto sequence vs file offset is unspecified; TX/RX key use is unspecified.

Not solely `BLOCKED_BY_RUNTIME_VALIDATION`: even with a tablet, GCM must not start until 21A resolves sequence and key direction.

Runtime identity (F-20R-01, F-20R-02) remains a **parallel** production blocker.

Next engineering task: **Task 21A — finalize secure frame specification** (no production code).

---

## 18. Production Impact

```
Production code changed=NO
Test source changed=NO
Gradle changed=NO
Git operations=NONE
```

Live transfers remain unencrypted, unauthenticated TCP frames.

---

## 19. Evidence

Inspected production:

- `app/src/main/java/com/filo/transfer/core/network/security/crypto/IdentityManager.kt`
- `app/src/main/java/com/filo/transfer/core/network/security/handshake/SecureHandshake.kt`
- `app/src/main/java/com/filo/transfer/core/network/security/handshake/SecureSession.kt`
- `app/src/main/java/com/filo/transfer/core/network/security/handshake/EphemeralKeyPair.kt`
- `app/src/main/java/com/filo/transfer/core/network/security/handshake/SigningIdentity.kt`
- `app/src/main/java/com/filo/transfer/core/network/security/handshake/FullHandshakeTranscript.kt`
- `app/src/main/java/com/filo/transfer/core/network/security/handshake/HandshakeMessage.kt`
- `app/src/main/java/com/filo/transfer/core/network/security/handshake/HandshakeError.kt`
- `app/src/main/java/com/filo/transfer/core/network/security/FilenameValidator.kt`
- `app/src/main/java/com/filo/transfer/core/network/protocol/FrameCodec.kt`
- `app/src/main/java/com/filo/transfer/core/network/protocol/ProtocolFrame.kt`
- `app/src/main/java/com/filo/transfer/core/network/protocol/FrameType.kt`
- `app/src/main/java/com/filo/transfer/core/network/protocol/ProtocolConstants.kt`
- `app/src/main/java/com/filo/transfer/core/network/protocol/FramePayloads.kt`
- `app/src/main/java/com/filo/transfer/core/network/transfer/TransferSender.kt`
- `app/src/main/java/com/filo/transfer/core/network/transfer/TransferReceiver.kt`
- `app/src/main/java/com/filo/transfer/core/network/transport/SocketConnection.kt`

Inspected tests:

- `HandshakeSecurityTest.kt`
- `HkdfTest.kt`
- `ProtocolFrameCodecTest.kt`
- `FilenameValidatorSecurityTest.kt`
- `CryptoRuntimeValidationTest.kt` (present, not executed)

Related limitation reports: `Task20R2_Report.md` … `Task20R7_Report.md`
