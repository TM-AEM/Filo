# Task 21E — Post-Task-21D Secure Transport Continuation Audit

**Status**: AUDIT_COMPLETE
**Date**: 2026-10-05
**Scope**: READ-ONLY. No production, test, Gradle, or Git changes.

Task 21D remains as previously reported: `SecureFrameCodecTest` 61/61, security JVM 150/150, full JVM 298/291 with 7 known baseline failures. This audit does not re-run those suites.

F-20R-01 and F-20R-02 remain **UNVERIFIED**. No AndroidKeyStore / API 31 runtime validation was performed.

---

## 1. Executive Summary

Filo today is a **fully plaintext application protocol** on a single TCP socket.

The cryptographic library exists but is **not on the wire**:

| Component | On-wire? | Caller |
|---|---|---|
| `SecureHandshake` / `SecureSession` | No | Unit tests only |
| `SecureFrameCodec` / `AesGcm` / `CryptoSequence` | No | Unit tests only |
| `IdentityManager` (AndroidKeyStore) | No | Not wired into transfer |
| `FrameCodec` + `SocketConnection.sendFrame/receiveFrame` | **Yes — sole I/O path** | `TransferSender`, `TransferReceiver` |

There is **exactly one** place that writes protocol bytes to TCP (`SocketConnection.sendFrame` → `FrameCodec.writeFrame`) and **exactly one** place that reads them (`SocketConnection.receiveFrame` → `FrameCodec.readFrame`). That pair is the safest minimal insertion point.

Recommended next implementation (Task 21F) is **handshake-on-wire + session attach + SocketConnection secure mode**. Do not rewrite Sender/Receiver data loops. Do not touch resume/reconnect (there is no TCP reconnect today). Do not merge `FrameCodec` with `SecureFrameCodec`.

---

## 2. Current TCP Lifecycle

Traced from production source. Approximate lines from files as they exist at audit time.

### Sender (TCP client)

| # | Step | File | Class | Method | Approx. line | Protocol state |
|---|---|---|---|---|---|---|
| 1 | TCP connection creation | `core/service/TransferService.kt` | `TransferService` | `handleStartSend` | 129 | Idle → Initializing |
| 2 | TCP socket establishment | `core/network/transport/TcpClientTransport.kt` | `TcpClientTransport` | `connect` | 28–32 | Connecting |
| 3 | Input stream creation | `core/network/transport/SocketConnection.kt` | `SocketConnection` | init | 40, 43 | Connected, no bytes yet |
| 4 | Output stream creation | same | same | init | 41, 44 | Connected, no bytes yet |
| 5 | First bytes sent | `core/network/transfer/TransferSender.kt` | `TransferSender` | `performHandshake` | 196–201 | Handshaking — plaintext HELLO |
| 6 | First bytes received | same | same | `performHandshake` | 203 | Waiting HELLO_ACK |
| 7 | HELLO generation | same | same | `performHandshake` | 191–195 | `FramePayloads.encodeHello(v1, deviceName, transferId)` |
| 8 | HELLO transmission | same + `SocketConnection.sendFrame` | | 196–201 / 66–71 | PLAINTEXT |
| 9 | HELLO parsing (receiver side) | `TransferReceiver.kt` | `performHandshake` | 198–202 | PLAINTEXT |
| 10 | HELLO_ACK generation | `TransferReceiver.kt` | `performHandshake` | 205–209 | PLAINTEXT |
| 11 | HELLO_ACK transmission | `TransferReceiver.kt` | `performHandshake` | 210–215 | PLAINTEXT |
| 12 | MANIFEST transmission | `TransferSender.kt` | `exchangeManifest` | 213–220 | PLAINTEXT application |
| 13 | DATA transmission | `TransferSender.kt` | `transferSingleFile` | 325–331 | PLAINTEXT `DATA_CHUNK`, `sequence` = **file byte offset** |
| 14 | ACK/control | `TransferReceiver.kt` | `receiveManifest` / `receiveSingleFile` | 294–298, 339–343, 417–421 | PLAINTEXT MANIFEST_ACK, RESUME_*, CHECKSUM_RESULT |
| 15 | Connection close | `TransferService.kt` | `handleStartSend` finally / `cleanupActiveTransfer` | 194, 327–341 | Socket closed |
| 16 | Cancellation | `TransferSender.cancel` / `TransferReceiver.cancel` | | 67–90 / 65–90 | Sends plaintext CANCEL then `conn.close()` |
| 17 | Reconnect | — | — | — | **Does not exist.** No new TCP socket on failure. |
| 18 | Resume | `TransferReceiver.receiveSingleFile` / `TransferSender.transferSingleFile` | | 323–353 / 257–281 | **Same TCP connection.** File byte offset only. |

### Receiver (TCP server)

| # | Step | File | Class | Method | Approx. line |
|---|---|---|---|---|---|
| Bind | `TransferService.handleStartReceive` | `TcpServerTransport.bind` | 232–234 |
| Accept | same | `TcpServerTransport.accept` | 246, wraps `server.accept()` → `SocketConnection` 71–72 |
| Handshake | `TransferReceiver.performHandshake` | 197–223 |
| Manifest | `receiveManifest` | 225–301 |
| Per-file | `receiveSingleFile` | 304–440 |
| Complete | `receive` waits `COMPLETE` | 149–152 |

Socket options (`SocketConnection.configureSocket`, 165–181): `tcpNoDelay=true`, `keepAlive=true`, `soTimeout=SOCKET_TIMEOUT_MS` (30s), send/recv buffer hints 128 KiB.

---

## 3. Raw Socket Write Map

Every production path that can place bytes on TCP:

| Location | Mechanism | Classification |
|---|---|---|
| `SocketConnection.kt:40–44, 66–71` | `socket.getOutputStream()` → `BufferedOutputStream` → `FrameCodec.writeFrame` → `flush` | **PLAINTEXT PROTOCOL** — **the only live write path** |
| `FrameCodec.kt:20–44` | `DataOutputStream.write` of MAGIC/VER/TYPE/SEQ/LEN/payload | PLAINTEXT PROTOCOL (called only from `sendFrame`) |
| `FramePayloads.kt` `DataOutputStream` | Serializes payloads into `ByteArray` | Not a socket write (memory only) |
| `SecureFrameCodec.serializeFrame` | `ByteArrayOutputStream.write` | **SECURE PROTOCOL** but **not connected** to any socket |
| `FullHandshakeTranscript.encode` / `HandshakeTranscript.encode` | memory transcript | HANDSHAKE crypto material, not on wire |
| `TransferReceiver.kt:388` | `FileOutputStream.write` | Disk, not network |

**No other `getOutputStream` exists in `app/src/main`.** Discovery (`NsdDiscoveryService`) uses NSD, not this TCP socket.

**Plaintext bypass conclusion (current):** every application frame (HELLO through COMPLETE, CANCEL, ERROR) is plaintext because `sendFrame` always uses `FrameCodec`. After a future secure session, any remaining call to `FrameCodec.writeFrame` on the live socket is a bypass. The chokepoint is `SocketConnection.sendFrame`.

---

## 4. Raw Socket Read Map

| Location | Mechanism | Classification |
|---|---|---|
| `SocketConnection.kt:40, 43, 93–97` | `socket.getInputStream()` → `BufferedInputStream(128 KiB)` → `FrameCodec.readFrame` | **PLAINTEXT PROTOCOL** — **the only live read path** |
| `FrameCodec.kt:53–110` | `DataInputStream.readFully` MAGIC(4), then VER, TYPE, SEQ, LEN, payload | Frame boundary = 18-byte header + `payloadLength` |
| `FramePayloads.decode*` | Parses already-read payload bytes | Not a socket read |
| `SecureFrameCodec.decode` | Parses a complete `ByteArray` | **SECURE PROTOCOL**, not connected |

**Frame-boundary assumption:** `FrameCodec` assumes:

- MAGIC = `FILO`
- **VER must equal `CURRENT_PROTOCOL_VERSION` (1)** or throws `ProtocolVersionMismatch`
- TYPE is a known `FrameType`
- SEQ is an opaque `long` (file offset / file index / 0)
- LEN is plaintext payload length, `0..MAX_FRAME_PAYLOAD`

It **cannot** read a Task-21A secure frame (VER=2, CT_LEN = ciphertext\|\|tag). It also **cannot** read Task-21A handshake frames if those are VER=2. Demux on version (and, post-session, rejection of VER=1) must live **above** `FrameCodec`, inside `SocketConnection` (or a new IO helper), without merging the two codecs.

`available()` is not used for framing. Timeouts are `Socket.soTimeout` only.

---

## 5. Current FrameCodec Role

Exact on-wire layout (`FrameCodec.kt` 30–43 / 56–102):

```
MAGIC(4) || VER(1)=1 || TYPE(1) || SEQUENCE(8 BE) || PAYLOAD_LEN(4 BE) || payload[0..LEN]
```

- MAGIC is checked.
- Frame length is explicit (`PAYLOAD_LEN`).
- SEQUENCE today is **file offset** on `DATA_CHUNK`, **file index** on `FILE_HEADER`, **0** otherwise. It is not a crypto sequence.
- No distinction of plaintext vs secure frames (VER is required to be 1).
- **Must remain completely untouched.** It stays the inner/application framing helper and the legacy plaintext encoder. `SecureFrameCodec` stays the outer encrypted layer. Do not merge.

Header size coincides with secure frames (18 bytes) but **semantics of VER, SEQUENCE, and LEN differ**. Coincidence of size is not a reason to merge.

---

## 6. HELLO / HELLO_ACK Lifecycle

### Current fields (`FramePayloads.kt` 22–78)

**HELLO payload:**

- `version: Byte` (`ProtocolConstants.CURRENT_PROTOCOL_VERSION` = 1)
- `deviceName: String` (UTF, max 255)
- `sessionId: String` (UTF, max 255) — this is **`manifest.transferId`**, not `SecureSession.sessionId`

**HELLO_ACK payload:**

- `version: Byte`
- `accepted: Boolean` (false on version mismatch)
- `deviceName: String`

No capabilities bitmap. No identity public key. No ephemeral key. No nonce. No signature. No file-transfer metadata (that is MANIFEST).

### Current lifecycle

1. Sender `performHandshake` (`TransferSender.kt` 190–211): send HELLO, require HELLO_ACK with `accepted==true`.
2. Receiver `performHandshake` (`TransferReceiver.kt` 197–223): require HELLO, send HELLO_ACK, throw `ProtocolVersionMismatch` after ACK if versions differ.

Do **not** change these payload formats. Task 21A **repurposes the outer type codes** 0x01/0x02 (and new 0x03) for cryptographic handshake messages. Device name / transfer id, if still needed, already exist on MANIFEST (`senderDeviceName`, `transferId`).

---

## 7. SecureHandshake Integration Point

### Actual APIs (unmodified, already implemented)

`SecureHandshake.kt`:

| Method | Role | Inputs | Outputs |
|---|---|---|---|
| `initiate(identity: SigningIdentity)` | Initiator phase 1 | signing identity | `HandshakeInitiatorState` (identity, ephemeral P-256, local `HandshakeMessage` role=0) |
| `respond(identity, initiatorMsg)` | Responder phase 1 | identity + peer HELLO body | `HandshakeResponderState` (local msg role=1 + `fullTranscriptSignature`) |
| `completeAsInitiator(state, peerMsg, peerSig)` | Initiator phase 2 | responder msg + responder sig | `InitiatorCompletion(session, signatureToSend)` |
| `completeAsResponder(state, peerMsg, peerSig)` | Responder phase 2 | initiator msg + initiator sig | `SecureSession` |

Supporting:

- `SigningIdentity.getIdentityPublicKey / getIdentityAlgorithm / signTranscript`
- `InMemorySigningIdentity` (tests); **no production `SigningIdentity` adapter over `IdentityManager` exists**
- `EphemeralKeyPair.generateP256` / `sharedSecret` / `destroy` (called inside `buildSession`)
- `Hkdf.deriveSessionMaterial` → `keyA(32)\|\|keyB(32)\|\|bindingKey(32)`
- `SecureSession.create(...)` — `sessionId` is a **local UUID**, not sent on the wire
- `HandshakeError.*` — category-only messages, no crypto material

**Gap:** `HandshakeMessage` has **no encode/decode**. Transcript encoding exists only for signing (`FullHandshakeTranscript.encode`). Wire serialization of handshake payloads is a required next-task deliverable (new helper, not a `FramePayloads` change unless explicitly scoped).

### Concrete sequence (Task 21A + real APIs)

```
TCP CONNECT (client) / ACCEPT (server)
        |
        |  [plaintext, VER=2, TYPE=0x01 HELLO, SEQ=0]
        |  payload = encoded HandshakeMessage (initiator, role=0)
        v
INITIATOR: SecureHandshake.initiate(identity)
        --> send HELLO
RESPONDER: receive HELLO, decode HandshakeMessage
        SecureHandshake.respond(identity, initiatorMsg)
        |
        |  [plaintext, VER=2, TYPE=0x02 HELLO_ACK, SEQ=0]
        |  payload = encoded HandshakeMessage (responder) || responder signature
        v
INITIATOR: completeAsInitiator(state, peerMsg, peerSig)
        --> SecureSession + signatureToSend
        |
        |  [plaintext, VER=2, TYPE=0x03 HANDSHAKE_FINISH, SEQ=0]
        |  payload = initiator signature
        v
RESPONDER: completeAsResponder(state, initiatorMsg, initiatorSig)
        --> SecureSession
        |
        |  SECURE SESSION ESTABLISHED (both sides have keyA/keyB/bindingKey)
        |  SocketConnection enters secure mode
        v
        FIRST ENCRYPTED FRAME = typically MANIFEST (initiator)
        SecureFrameCodec.encode(inner payload, type, keyA, txSeq, INITIATOR_TO_RESPONDER)
        v
        APPLICATION MESSAGES (MANIFEST, DATA, ACK, control) as inner plaintext
        of SecureFrameCodec; never FrameCodec on this socket again
```

Initiator = TCP client = `TransferSender` (handshake role 0). Responder = TCP server = `TransferReceiver` (role 1). Matches Task 21A section 8.

Insertion in code: replace `TransferSender.performHandshake` (190–211) and `TransferReceiver.performHandshake` (197–223). Do not insert after MANIFEST.

---

## 8. SecureSession Lifecycle

Created only inside `SecureHandshake.buildSession` after:

1. Peer signature verifies over the **same** full transcript
2. ECDH shared secret computed
3. HKDF material split into keyA / keyB / bindingKey
4. Local ephemeral destroyed

Fields: `sessionId` (local UUID), `peerFingerprint`, `keyAgreementAlg`, `transcriptHash`, `keyA`, `keyB`, `bindingKey`. No TX/RX sequence objects are stored on the session — sequences must be created by the transport layer at session attach:

```
INITIATOR_TX_KEY=keyA + CryptoSequence(INITIATOR_TO_RESPONDER)
INITIATOR_RX_KEY=keyB + CryptoReceiveSequence(RESPONDER_TO_INITIATOR)
RESPONDER_TX_KEY=keyB + CryptoSequence(RESPONDER_TO_INITIATOR)
RESPONDER_RX_KEY=keyA + CryptoReceiveSequence(INITIATOR_TO_RESPONDER)
```

`bindingKey` is reserved; never a GCM key.

**There is no session object on `SocketConnection` or `TransferSession` today.** `model/TransferSession.kt` is transfer metadata only (host, port, manifest, protocolVersion).

---

## 9. HANDSHAKE_FINISH Analysis

| Question | Finding |
|---|---|
| Exists in `FrameType`? | **No.** Enum ends HELLO 0x01, HELLO_ACK 0x02, then MANIFEST 0x10. 0x03 is unknown → `FrameCodec` would `InvalidFrame`. |
| Who sends it? | Initiator, after `completeAsInitiator`, carrying `signatureToSend`. |
| Who receives it? | Responder, before `completeAsResponder`. |
| Authenticated? | The **signature** authenticates the transcript. The frame itself is **plaintext** (pre-session). |
| Encrypted? | **No.** Pre-session only. |
| Before or after `SecureSession` creation? | Initiator already has `SecureSession` when sending it. Responder creates `SecureSession` **after** verifying it. Session is mutually established only after both signatures verify. |
| State transition | Pre-session handshake → SECURE. First subsequent frame MUST be encrypted. |

**Design/implementation gap.** Do not add the type in this audit.

---

## 10. Plaintext Boundary

### Current state (all plaintext)

| Message | Currently plaintext? | Required after SecureSession |
|---|---|---|
| HELLO (0x01) | Yes | Pre-session handshake only; forbidden after session (`SecureFrameCodec` already rejects) |
| HELLO_ACK (0x02) | Yes | Same |
| HANDSHAKE_FINISH (0x03) | N/A (absent) | Pre-session plaintext only; then forbidden |
| MANIFEST / MANIFEST_ACK | Yes | Encrypted inner payload |
| FILE_HEADER | Yes | Encrypted |
| DATA_CHUNK | Yes | Encrypted; file offset stays inner / `ProtocolFrame.sequence` analog, **not** CRYPTO_SEQ |
| CHECKSUM / CHECKSUM_RESULT | Yes | Encrypted |
| RESUME_REQUEST / RESUME_RESPONSE | Yes | Encrypted; `offset` remains file byte offset |
| PAUSE / RESUME frame types | Defined but **never sent** | If used later: encrypted |
| CANCEL / ERROR | Yes (cancel/error paths) | Encrypted once session exists; if session never established, plaintext CANCEL is still handshake-era |
| COMPLETE | Yes | Encrypted |

**Intended final invariant**

- Before secure session: handshake-control traffic only (0x01, 0x02, 0x03), VER=2, SEQ=0, plaintext payload.
- After secure session: **no application plaintext on the socket.** `SocketConnection.sendFrame` must refuse `FrameCodec.writeFrame`.

---

## 11. Post-Handshake Plaintext Bypasses

Every `sendFrame` / `receiveFrame` call site will become a bypass if `SocketConnection` still uses `FrameCodec` after session:

**Sender** (`TransferSender.kt`): 77 (CANCEL), 147 (COMPLETE), 173 (ERROR), 196 (HELLO), 215 (MANIFEST), 249 (FILE_HEADER), 276 (RESUME_RESPONSE), 325 (DATA_CHUNK), 365 (CHECKSUM).

**Receiver** (`TransferReceiver.kt`): 77 (CANCEL), 183 (ERROR), 210 (HELLO_ACK), 239/255/268/285/294 (MANIFEST_ACK), 339 (RESUME_REQUEST), 417 (CHECKSUM_RESULT). Plus all `receiveFrame` sites (HELLO, MANIFEST, FILE_HEADER, RESUME_RESPONSE, DATA_CHUNK, CHECKSUM, COMPLETE).

**Service** does not write frames itself; it holds `currentConnection` and closes it.

`FrameCodec.writeFrame` is public. Future code could call it with the raw `bufferedOut` if that stream is ever exposed. Today `bufferedOut` is private. Keep it private. Do not add a public raw-write API.

**Enforcement architecture:** put the mode flag and codec switch **inside** `SocketConnection`. Then Sender/Receiver keep calling `sendFrame(ProtocolFrame)` and cannot bypass by omission.

---

## 12. Sender Analysis

`TransferSender.transfer` (`TransferSender.kt` 95–188), `Dispatchers.IO`:

1. `Connecting` → `Handshaking` → `performHandshake` (plaintext HELLO).
2. `exchangeManifest` — `FramePayloads.encodeManifest`, wait MANIFEST_ACK.
3. Per file: FILE_HEADER (`sequence = fileIndex`), wait RESUME_REQUEST, send RESUME_RESPONSE, stream DATA_CHUNK (`sequence = currentOffset` **file bytes**), CHECKSUM, wait CHECKSUM_RESULT.
4. COMPLETE.
5. `pause()` is a local mutex (`pauseMutex`); it does **not** send `FrameType.PAUSE`.
6. `cancel()` sends plaintext CANCEL then closes.
7. Failure sends plaintext ERROR then closes.
8. `RetryPolicy` is **not used** by sender or service.

**FILE OFFSET** (`ProtocolFrame.sequence` on DATA_CHUNK / resume payloads) is independent of **CRYPTO_SEQUENCE** (not present on this path). Do not replace file offset with crypto sequence.

---

## 13. Receiver Analysis

`TransferReceiver.receive` (`TransferReceiver.kt` 99–195):

1. Handshake (expect HELLO, send HELLO_ACK).
2. `receiveManifest` — decode, validate file count / size / filenames, MANIFEST_ACK.
3. Per file: FILE_HEADER → compute resume offset from `.filo.part` length → RESUME_REQUEST → RESUME_RESPONSE → stream DATA_CHUNK to disk → CHECKSUM → CHECKSUM_RESULT → atomic rename.
4. Wait COMPLETE.
5. Malformed frames: `NetworkError.InvalidFrame` / `HandshakeFailed` / `OversizedPayload` / `UnsafeFilename`; then ERROR frame + close.
6. Cancellation deletes partial file, sends CANCEL, closes.

**Where `SecureFrameCodec.decode` must sit:** inside `SocketConnection.receiveFrame` after session, **before** `TransferReceiver` sees a `ProtocolFrame`. Receiver continues to parse inner payloads via `FramePayloads`. Do not call `SecureFrameCodec.decode` from each receive site.

---

## 14. Resume / Reconnect Analysis

| Question | Current fact |
|---|---|
| Does reconnect create a new TCP socket? | **No reconnect exists.** `RetryPolicy` is unit-tested only, unused by `TransferService`. Failure closes the socket (`finally` 194 / 265). |
| New session on reconnect? | N/A today. Future: **new TCP = new SecureHandshake + new keys + new crypto sequences.** |
| Can SecureHandshake be rerun? | Yes, APIs are stateless per call; ephemeral is destroyed at session build. |
| Are old session keys retained? | No session keys exist at runtime. Future: must **not** reuse keys across TCP connections. |
| Crypto sequence recreated? | N/A. Future: reset to 0 on new handshake (Task 21A §7). |
| File offset retained independently? | **Yes, today, on the same TCP connection:** `.filo.part` length → `RESUME_REQUEST.offset`. This is file resume, not TCP resume. |

**Required future invariant (not implemented):** new TCP session = new handshake + new keys + new CRYPTO_SEQ; file resume offset may continue from persisted `.filo.part`. Marked as a **later** task. Do not implement in 21F.

Local `pause()`/`resume()` only freeze the coroutine; the TCP session and (future) keys stay as they are. That is not a reconnect.

---

## 15. Error / Cancellation Analysis

| Failure | Current behavior | Plaintext fallback risk |
|---|---|---|
| Handshake failure | `HandshakeFailed` / version mismatch; receiver may already have sent HELLO_ACK; connection closed | Today everything is plaintext. Future: if handshake fails, **never** enter secure mode; **never** send MANIFEST in plaintext as a fallback. Close. |
| Auth failure (`AesGcmException`, `SequenceViolationException`) | Unreachable on wire today | Future: fail closed, close socket, no FrameCodec fallback |
| Malformed secure frame | Unreachable | Future: `InvalidFrame` / close; SocketConnection already closes on `NetworkError` |
| Socket failure / timeout | `Timeout` / `ConnectionFailed` / `IoError`; close | No retry at service layer |
| Cancellation | CANCEL frame + close | Future: CANCEL after session must be encrypted; if send fails, still close |
| Process death | `START_NOT_STICKY`; no auto-restart of transfer | Partial files remain for later file-offset resume on a **new** TCP session (which must re-handshake) |
| Checksum mismatch | ERROR path / throw `ChecksumMismatch`; receiver deletes partial | Application-level, after frames |

**Rule to implement later (not now):** once secure mode is set, `sendFrame`/`receiveFrame` must not call `FrameCodec` even on error. Error/CANCEL frames go through `SecureFrameCodec` or the socket is closed without a frame.

---

## 16. Threading / Concurrency

- `TransferService` runs send/receive on `serviceScope` + `Dispatchers.IO` (one active job).
- `pause`/`cancel`/`resume` may run from another coroutine (`handlePause` launches; `handleCancel` calls `cancel()` which `sendFrame`s).
- `SocketConnection` already has `writeLock` and `readLock`. Writes are serialized; reads are serialized.
- `CryptoSequence.nextSequence` is **not** internally synchronized. It must be called only under `writeLock` (i.e. inside `sendFrame`).
- `CryptoReceiveSequence.validateAndAdvance` must run only under `readLock` (inside `receiveFrame`). `SecureFrameCodec.decode` already does this on a single thread per call.
- Half-duplex application protocol (send then wait) plus locks is sufficient if sequences live **inside** `SocketConnection`, not as unsynchronized fields on Sender/Receiver.
- Do not share one `CryptoSequence` across two sockets.

No additional lock is required in 21F if sequence state is owned by `SocketConnection` and only mutated in `sendFrame`/`receiveFrame`.

---

## 17. Confirmed Security Gaps

Source-level, observed in code. Not invented runtime vulns.

| ID | Severity | Confirmed/Unverified | Location | Description | Required future task |
|---|---|---|---|---|---|
| G-21E-01 | Critical | Confirmed | `SocketConnection.sendFrame` / entire transfer path | All application traffic is plaintext. `SecureFrameCodec` unused. | 21F session mode + 21G if split; preferably 21F chokepoint encrypts subsequent frames |
| G-21E-02 | Critical | Confirmed | `TransferSender.performHandshake`, `TransferReceiver.performHandshake` | `SecureHandshake` never called. No identity, ECDH, or session keys on the wire. | 21F |
| G-21E-03 | High | Confirmed | `FrameType.kt` | `HANDSHAKE_FINISH` (0x03) missing. | 21F |
| G-21E-04 | High | Confirmed | `HandshakeMessage.kt` | No wire encode/decode for handshake payloads. | 21F |
| G-21E-05 | High | Confirmed | `IdentityManager` vs `SigningIdentity` | No production adapter; transfer cannot sign with Keystore identity. | 21F (adapter only; do not change IdentityManager internals) |
| G-21E-06 | High | Confirmed | `SocketConnection` | No secure-mode flag; cannot refuse plaintext after session. | 21F |
| G-21E-07 | Medium | Confirmed | `FrameCodec.readFrame` VER==1 | Cannot parse VER=2 handshake or secure frames. Must not be relaxed globally. Demux belongs in SocketConnection. | 21F |
| G-21E-08 | Medium | Confirmed | `RetryPolicy.kt` unused | No TCP reconnect. Future reconnect must not reuse keys/sequences. | Later reconnect task |
| G-21E-09 | Medium | Confirmed | `TransferState` | No `SECURE` state. Closest: `Handshaking` then `Transferring`. | 21F may map Handshaking→session then Transferring; optional new state later |
| G-21E-10 | Low | Confirmed | `FrameType.PAUSE` / `RESUME` | Unused on wire; pause is local mutex. | Out of scope |
| G-21E-11 | Info | Confirmed | Task21A nonce `direction_id` 0/1 vs `CryptoDirection` 1/2 | 21C implementation (`0x1` / `0x2`) is what `SecureFrameCodec` uses. Do not “fix” to 0/1. | Follow 21C constants |

---

## 18. Runtime-Unverified Items

| ID | Status | Notes |
|---|---|---|
| F-20R-01 | **UNVERIFIED** | AndroidKeyStore / API 31 physical device. Prior Wireless ADB tablet unreachable. Emulator is not evidence. |
| F-20R-02 | **UNVERIFIED** | Same. Unchanged. |
| Handshake on a real two-device path | Unverified | Library unit-tested; never executed on TCP. |
| SecureFrameCodec on TCP | Unverified | 61 JVM tests only. |

Do not reclassify F-20R-01/02 without physical API 31 evidence.

---

## 19. Exact Proposed Scope for Next Implementation Task (21F)

**Name:** Task 21F — Secure handshake on TCP + session attach + SocketConnection secure mode.

**In scope (smallest complete insertion):**

1. Wire `SecureHandshake` into `performHandshake` on sender (initiator) and receiver (responder).
2. Add `HANDSHAKE_FINISH` (0x03) so the third flight exists.
3. Add handshake payload wire encoding/decoding (new helper).
4. Add a production `SigningIdentity` adapter over existing `IdentityManager` (no IdentityManager redesign).
5. After both signatures verify, attach `SecureSession` + directional `CryptoSequence` / `CryptoReceiveSequence` to `SocketConnection`.
6. Switch `sendFrame` / `receiveFrame` to:
   - pre-session: plaintext VER=2 handshake frames (not `FrameCodec` VER=1)
   - post-session: `SecureFrameCodec` wrapping the existing inner `ProtocolFrame` payload and `FrameType`
   - post-session: **reject** any attempt to write/read VER=1 `FrameCodec` on that socket (no plaintext fallback)
7. Unit tests for the handshake IO helper and SocketConnection mode switch (JVM). No emulator.

**Out of scope for 21F:**

- Rewriting Sender/Receiver DATA loops, checksum, manifest validation
- File-offset resume behavior
- TCP reconnect / `RetryPolicy` integration
- `FrameCodec` / `ProtocolFrame` / `FramePayloads` / `ProtocolConstants` changes except `FrameType` +0x03
- `AesGcm`, `CryptoSequence`, `SecureFrameCodec`, `Hkdf`, `SecureHandshake`, `SecureSession`, `EphemeralKeyPair` internals
- AndroidKeyStore runtime evidence (F-20R-01/02)

**Why encryption of later frames is included despite “not full Sender encryption”:** Sender/Receiver already speak only through `sendFrame`. Putting `SecureFrameCodec` inside that chokepoint is the **minimal** way to make “transition into secure mode” real. Splitting “handshake only, still plaintext MANIFEST” would ship a false secure mode and a guaranteed bypass (G-21E-01). Do not split that way.

**Why resume/reconnect stay separate:** there is no TCP reconnect path. File resume is offset-only on the same socket and keeps working if inner `ProtocolFrame.sequence` / resume payloads remain the inner plaintext of secure frames.

---

## 20. Files the Next Implementation Would Need to Modify

| File | Why |
|---|---|
| `app/src/main/java/com/filo/transfer/core/network/protocol/FrameType.kt` | Add `HANDSHAKE_FINISH(0x03)` |
| `app/src/main/java/com/filo/transfer/core/network/transport/SocketConnection.kt` | Pre-session plaintext v2 IO; post-session `SecureFrameCodec`; session/sequence ownership; no plaintext fallback |
| `app/src/main/java/com/filo/transfer/core/network/transfer/TransferSender.kt` | Replace `performHandshake` with initiator SecureHandshake flights |
| `app/src/main/java/com/filo/transfer/core/network/transfer/TransferReceiver.kt` | Replace `performHandshake` with responder SecureHandshake flights |
| **New** handshake wire helper (e.g. `handshake/HandshakeWire.kt`) | Encode/decode `HandshakeMessage` + signatures for HELLO / HELLO_ACK / HANDSHAKE_FINISH payloads |
| **New** `SigningIdentity` adapter (e.g. wrapping `IdentityManager`) | Production signing without changing IdentityManager |
| Corresponding JVM tests under `app/src/test/...` | Handshake-on-stream + secure-mode chokepoint |

Optional small: `TransferState` only if a distinct `Secure` state is desired; not required (`Handshaking` then `Transferring` is enough).

---

## 21. Files That Must Remain Untouched

- `AesGcm.kt`, `CryptoSequence.kt`, `SecureFrameCodec.kt`
- `Hkdf` / HKDF helpers, `SecureHandshake.kt`, `SecureSession.kt`, `EphemeralKeyPair.kt`, `HandshakeTranscript.kt`, `FullHandshakeTranscript.kt`, `HandshakeError.kt`
- `IdentityManager.kt` internals (adapter only, outside that file)
- `FrameCodec.kt`, `ProtocolFrame.kt`, `FramePayloads.kt`, `ProtocolConstants.kt`
- `TcpClientTransport.kt`, `TcpServerTransport.kt` (they only produce `SocketConnection`)
- Resume internals inside `transferSingleFile` / `receiveSingleFile` (offset math, `.filo.part`)
- `RetryPolicy.kt`, discovery, storage providers, UI, `TransferService.kt` unless a trivial identity bootstrap is required (prefer doing that inside handshake helper called from Sender/Receiver)

---

## 22. Recommended Task Ordering After 21E

| Task | Content |
|---|---|
| **21F** | Handshake on TCP + HANDSHAKE_FINISH + HandshakeMessage wire codec + Identity adapter + SocketConnection secure mode using existing `SecureFrameCodec` |
| **21G** | JVM integration tests: two `SocketConnection`s over loopback, HELLO…FINISH, then encrypted MANIFEST/DATA round-trip; plaintext-bypass tests post-session |
| **21H** | Explicit no-fallback tests (auth fail, sequence fail, VER=1 after session → close, never FrameCodec) |
| **21I** | Reconnect (new TCP = new handshake + new keys + new CRYPTO_SEQ; keep file offset from `.filo.part`) — only when a reconnect path is actually designed |
| **21J** | Physical Android 12 / API 31 Keystore evidence for F-20R-01 / F-20R-02 (not emulator) |
| Later | PAUSE/RESUME as encrypted control frames if product still wants on-wire pause |

Do not start 21I before 21F/21G: there is nothing to reconnect.

---

## Architecture Decision (do not redesign 21A/21D)

```
FrameCodec          = inner / legacy plaintext application framing (VER=1). Untouched.
Handshake plaintext = VER=2, types 0x01–0x03, SEQ=0, plaintext payload, pre-session only.
SecureFrameCodec    = VER=2 post-session, GCM, CRYPTO_SEQ independent of file offset.
SocketConnection    = sole socket owner; mode switch is the insertion point.
```

`CryptoDirection` values stay 21C (`0x00000001` / `0x00000002`), not Task 21A prose `0` / `1`.

---

## Final Status

**AUDIT_COMPLETE**

- All relevant TCP paths traced to file/class/method/line.
- Secure-session insertion point identified: `SocketConnection` + `performHandshake`.
- Plaintext boundaries and every `sendFrame` bypass listed.
- Next implementation scope is concrete (Task 21F).
- No production, test, Gradle, or Git operations were performed.
