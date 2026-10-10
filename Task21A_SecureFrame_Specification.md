# TASK 21A — SECURE FRAME SPECIFICATION

Date: 2026-10-04
Status: SPECIFICATION-ONLY
Git operations: NONE

---

## 1. Objective

Define one definitive wire-level specification for Filo authenticated encrypted transport.

This document is the review contract for Task 21B and later. It does not implement encryption, handshake-on-wire, or codec changes.

Physical Android 12 / API 31 runtime validation was not executed. F-20R-01 and F-20R-02 remain unverified. No AndroidKeyStore runtime claims.

---

## 2. Current Protocol Findings

Source of truth: current Kotlin implementation.

### 2.1 On-wire frame (`FrameCodec` / `ProtocolConstants`)

Total header: 18 bytes, then payload.

| Field | Size | Encoding | Byte order | Meaning today | Encrypted | Authenticated | Attacker-controlled |
|---|---|---|---|---|---|---|---|
| MAGIC | 4 | ASCII `FILO` (`0x46 0x49 0x4C 0x4F`) | network order as written | protocol marker | no | no | yes (peer can send anything) |
| version | 1 | unsigned byte | n/a | `ProtocolConstants.CURRENT_PROTOCOL_VERSION = 1` | no | no | yes |
| type | 1 | `FrameType.code` | n/a | frame kind | no | no | yes |
| sequence | 8 | signed Java `long` via `DataOutputStream.writeLong` | big-endian | **file byte offset** on `DATA_CHUNK`; **file index** on `FILE_HEADER`; **0** on most other frames | no | no | yes |
| payloadLength | 4 | signed Java `int` via `writeInt` | big-endian | payload byte count; reject if `< 0` or `> MAX_FRAME_PAYLOAD` (262144) | no | no | yes |
| payload | 0..262144 | raw bytes | n/a | type-specific (`FramePayloads`) or raw file bytes | no | no | yes |

`ProtocolFrame.sequence` currently represents a **file offset** (or file index). It MUST NOT automatically become the cryptographic sequence number.

### 2.2 Frame types (`FrameType`)

HELLO `0x01`, HELLO_ACK `0x02`, MANIFEST `0x10`, MANIFEST_ACK `0x11`, FILE_HEADER `0x20`, RESUME_REQUEST `0x21`, RESUME_RESPONSE `0x22`, DATA_CHUNK `0x30`, CHECKSUM `0x40`, CHECKSUM_RESULT `0x41`, PAUSE `0x50`, RESUME `0x51`, CANCEL `0x52`, ERROR `0x53`, COMPLETE `0x60`.

### 2.3 Live TCP sequence

`TransferSender` / `TransferReceiver`: TCP connect → HELLO/HELLO_ACK → MANIFEST → per-file FILE_HEADER / RESUME_* / DATA_CHUNK / CHECKSUM → COMPLETE.

`SecureHandshake` is not called. No AES-GCM exists.

### 2.4 Handshake library (not on the wire)

`SecureHandshake.PROTOCOL_VERSION = 1` (same numeric value as plaintext frames; different code path).

HKDF-SHA256 `deriveSessionMaterial`: 96 bytes = keyA(32) || keyB(32) || bindingKey(32). Javadoc names these clientToServer || serverToClient || handshakeKey.

Ephemeral ECDH: P-256 `secp256r1`. Handshake nonces: 32 bytes. `SecureRandomWrapper.nextIv()` exists (12 bytes) and is unused.

---

## 3. Secure Session Boundary

Future flow:

```
TCP accept
→ protocol version 2 frames only
→ cryptographic handshake (plaintext handshake frames only)
→ SecureSession established
→ ALL subsequent application frames encrypted
```

After secure-session establishment, these existing messages travel **only** as encrypted inner payloads (same `FrameType` codes, inner payload encodings unchanged except where noted):

| Message | After session |
|---|---|
| HELLO / HELLO_ACK | Not reused for deviceName/sessionId. Those fields, if still needed, go in MANIFEST / existing payloads. Handshake consumes 0x01/0x02/0x03 (section 4). |
| MANIFEST / MANIFEST_ACK | Encrypted |
| FILE_HEADER | Encrypted |
| DATA_CHUNK | Encrypted; file offset lives in inner streaming logic / existing resume payloads, **not** in the outer crypto sequence |
| CHECKSUM / CHECKSUM_RESULT | Encrypted |
| PAUSE / RESUME / CANCEL / ERROR / COMPLETE | Encrypted |
| RESUME_REQUEST / RESUME_RESPONSE | Encrypted; `offset` remains file byte offset inside payload |

One new outer frame type is required because the handshake is three flights and current HELLO is two:

| Code | Name | Role |
|---|---|---|
| `0x01` HELLO | Initiator `HandshakeMessage` | pre-session, plaintext |
| `0x02` HELLO_ACK | Responder `HandshakeMessage` + responder signature | pre-session, plaintext |
| `0x03` HANDSHAKE_FINISH | Initiator signature over the same full transcript | pre-session, plaintext; **new code, required** |

No other new application messages.

Inner encodings of handshake payloads are the existing `HandshakeMessage` / signature bytes, length-delimited, not specified as a new high-level protocol.

Session begins only after:

1. Responder signature verifies
2. Initiator signature verifies
3. Both sides have `SecureSession` (`keyA`, `keyB`, `bindingKey`, transcript hash, peer fingerprint)

The first encrypted frame MUST be the next frame after `HANDSHAKE_FINISH` is processed. Typically MANIFEST from the initiator.

---

## 4. Definitive Secure Frame Format

One format for **post-handshake** frames. Handshake frames use the same 18-byte header with **plaintext** payload (section 3) and `version = 2`.

Post-handshake encrypted frame:

```
[magic 4]
[version 1]
[frame type 1]
[crypto sequence 8]
[ciphertext length 4]
[ciphertext || GCM tag]
```

Nonce is **not** sent on the wire. It is reconstructed (section 7).

| Field | Size | Encoding | Byte order | Plaintext/encrypted | Authenticated | Validation |
|---|---|---|---|---|---|---|
| magic | 4 | `FILO` | as today | plaintext | not AAD (constant; reject if mismatch before crypto) | must equal `0x46 0x49 0x4C 0x4F` |
| version | 1 | unsigned | n/a | plaintext | AAD | must equal `2` after this spec is implemented |
| frame type | 1 | `FrameType.code` | n/a | plaintext | AAD | known type; handshake types `0x01..0x03` **forbidden** after session start |
| crypto sequence | 8 | unsigned integer in the 8-byte field; encoded with `writeLong` as two's-complement big-endian of the value 0..2^63-1 | big-endian | plaintext | AAD | see section 6; **not** a file offset |
| ciphertext length | 4 | `writeInt` | big-endian | plaintext | AAD | `16 <= length <= MAX_FRAME_PAYLOAD` (tag is 16 bytes, ciphertext may be empty) |
| ciphertext \|\| tag | length | AES-256-GCM output (ciphertext then 16-byte tag, Android/JCA default) | n/a | encrypted + tag | GCM | decrypt with AAD; fail closed |

Inner plaintext after decrypt is the existing `FramePayloads` blob for that `frame type` (or raw chunk bytes for `DATA_CHUNK`).

`SECURE_FRAME_FORMAT=MAGIC(4)||VER(1)||TYPE(1)||CRYPTO_SEQ(8)||CT_LEN(4)||CT||TAG(16)`

Handshake frames (version 2, types 0x01–0x03): same header; `sequence` MUST be 0; payload is plaintext handshake bytes; not GCM.

---

## 5. AAD Specification

Exactly one canonical AAD, 14 bytes, big-endian, no length prefix, no magic, no session UUID (`SecureSession.sessionId` is local-only and not in the current header).

```
AAD =
  version          (1 byte)
  frame type       (1 byte)
  crypto_sequence  (8 bytes, same encoding as on the wire)
  ciphertext_length (4 bytes, same encoding as on the wire)
```

`AAD_FORMAT=VER(1)||TYPE(1)||CRYPTO_SEQ(8 BE)||CT_LEN(4 BE)`

Session binding is via HKDF info (`"Filo-Secure-Session-v1" || transcriptHash`). Keys already depend on the transcript. Do not put `sessionId` in AAD.

---

## 6. Cryptographic Sequence Number

Independent of file offset, chunk offset, and resume offset.

```
CRYPTO_SEQUENCE_WIDTH=64
CRYPTO_SEQUENCE_INITIAL_VALUE=0
CRYPTO_SEQUENCE_INCREMENT=1
CRYPTO_SEQUENCE_MAX=9223372036854775806
CRYPTO_SEQUENCE_OVERFLOW=TERMINATE_SESSION
CRYPTO_SEQUENCE_RECONNECT_RULE=NEW_SESSION_RESET_TO_0
CRYPTO_SEQUENCE_RESUME_RULE=NEW_SESSION_RESET_TO_0
```

Width on the wire is 8 bytes. Only values `0 .. 2^63-2` are legal so `writeLong` stays non-negative and `MAX+1` can be detected before wrap to a reused nonce.

Each direction has its **own** counter (initiator TX counter, responder TX counter).

TCP is in-order. Encrypted frames MUST be processed strictly in order per direction.

Receiver, for the RX key of this session:

| Incoming crypto_seq | Behavior |
|---|---|
| equal to expected (next) | decrypt; expected += 1 |
| equal to a previous value (duplicate / replay) | fail; terminate session |
| less than expected | fail; terminate session |
| greater than expected (gap) | fail; terminate session |
| > CRYPTO_SEQUENCE_MAX or negative as signed long | fail; terminate session |
| overflow of local TX before send | do not send; terminate session |

`expected` starts at 0 after a successful handshake. No sliding window. No out-of-order decrypt.

TCP retransmission is below this layer and does not create a second application frame.

---

## 7. AES-256-GCM Nonce Construction

```
NONCE_SIZE=12
NONCE_CONSTRUCTION=DIR4||SEQ8
```

Exactly one construction:

```
nonce[0..4)  = I2OSP(direction_id, 4)   big-endian
nonce[4..12) = I2OSP(crypto_sequence, 8) big-endian
```

```
direction_id = 0  for frames encrypted with keyA (initiator → responder)
direction_id = 1  for frames encrypted with keyB (responder → initiator)
```

Nonce is not transmitted. Decrypt reconstructs it from role + crypto_sequence.

Uniqueness for a fixed AES key:

- Each key is used in one direction only (section 8)
- In that direction, crypto_sequence is unique and incremental
- direction_id is constant per key, so nonce uniqueness reduces to sequence uniqueness
- reconnect / resume: new handshake → new HKDF keys → counters reset to 0 is safe
- first frame in a direction: seq 0 → nonce `DIR || 0x00..00`
- subsequent: seq n → `DIR || n`
- overflow: session ends before seq reuse
- TX and RX use different keys **and** different direction_id

Do not use `SecureRandomWrapper.nextIv()` for transport nonces (random nonces would require sending them and tracking reuse).

---

## 8. Directional Key Mapping

HKDF layout unchanged (do not modify HKDF in this task):

```
keyA = material[0..32)    // documented clientToServer
keyB = material[32..64)   // documented serverToClient
bindingKey = material[64..96)
```

Initiator is the TCP client / transfer sender (handshake role 0). Responder is the TCP server / transfer receiver (role 1).

```
INITIATOR_TX_KEY=keyA
INITIATOR_RX_KEY=keyB
RESPONDER_TX_KEY=keyB
RESPONDER_RX_KEY=keyA
```

Checks:

- Initiator TX == Responder RX == keyA
- Initiator RX == Responder TX == keyB

`bindingKey`: **reserved**. Not used as an AES-GCM key. Not required for transport frames. May bind future control-plane checks; not part of this frame spec.

---

## 9. Session Rekeying

```
SESSION_REKEY_RULE=NEW_TCP_CONNECTION_REQUIRES_NEW_HANDSHAKE_AND_NEW_KEYS
```

| Event | Keys / counters |
|---|---|
| Initial connection | Handshake; new `SecureSession`; counters 0 |
| Reconnect / connection replacement | Previous keys MUST NOT be reused. New handshake, new ephemeral ECDH, new HKDF output, counters 0 |
| Interrupted transfer | TCP close discards session keys. Resume is application state (file offsets on disk), not crypto state |
| Resumed transfer | New TCP + new handshake + new keys + counters 0. File offset is in encrypted RESUME_* payload |
| Failed handshake | No application frames. Do not retain partial keys. Close TCP |
| Counter overflow | Terminate; reconnect allowed only with a full new handshake |

There is no in-connection rekey in this spec. 2^63-1 frames per direction per session is enough for Filo file sizes (`MAX_FILE_SIZE_BYTES` 4 GiB, chunk 128 KiB).

---

## 10. Resume Security

Keep separate:

```
FILE_OFFSET     = ResumeRequestPayload.offset / DATA_CHUNK inner file position
CRYPTO_SEQUENCE = outer header field after handshake
```

```
RESUME_SECURITY_RULE=RESUME_FIELDS_ENCRYPTED_AND_AUTHENTICATED_AS_INNER_PAYLOAD
```

Existing resume fields (already in `FramePayloads`): `fileId`, `offset`, `confirmedOffset`, `accepted`. Related existing fields: `transferId` (manifest/complete), `fileSize` (header/manifest), checksum hex, chunk bytes.

After session start, RESUME_REQUEST and RESUME_RESPONSE are encrypted frames. GCM authenticates the inner payload. No extra resume MAC.

Authenticated resume state is exactly those existing payload fields. Do not add new resume fields.

Resume MUST NOT copy crypto_sequence from the old TCP session.

---

## 11. Replay Protection

```
REPLAY_RULE=STRICT_MONOTONIC_EXPECTED_PLUS_ONE
```

Per direction: `expected = previous_accepted + 1` (starting at 0).

| Case | Action |
|---|---|
| duplicate frame (seq == old) | terminate |
| replayed old frame | terminate |
| skipped / future seq | terminate |
| malformed (negative, > MAX) | terminate |

TCP retransmission is not an application retry. If the app retries a logical send, it is a new frame with the next crypto_sequence.

---

## 12. Length and Parsing Validation

Do not change `MAX_FRAME_PAYLOAD = 262144`.

GCM tag = 16 bytes. Maximum inner plaintext per frame = `262144 - 16 = 262128` bytes.

```
MAX_ENCRYPTED_FRAME_SIZE=262162
```

That is header 18 + max payload 262144.

`DEFAULT_CHUNK_SIZE` is 131072, which remains below 262128 after the tag.

Validation order for an encrypted frame:

1. Parse fixed 18-byte header (bounded reads, same as `FrameCodec`)
2. Validate magic, version (`2`), type (known; not handshake types after session)
3. Validate crypto sequence against expected
4. Validate ciphertext length (`16..MAX_FRAME_PAYLOAD`)
5. Allocate at most `payloadLength` bytes, then read
6. Authenticate/decrypt with reconstructed nonce and canonical AAD
7. Process inner payload with existing `FramePayloads` / chunk logic

Handshake frames: length check as today (`0..MAX_FRAME_PAYLOAD`); no GCM.

---

## 13. Authentication Failure

```
INVALID_AUTH_RULE=TERMINATE_SECURE_SESSION_AND_TCP_NO_CRYPTO_DETAIL
```

On any of:

- GCM tag failure
- AAD mismatch (wrong header vs AAD used)
- nonce reconstruction inconsistency (implementation bug: treat as fatal)
- invalid sequence (section 6)
- invalid ciphertext length
- invalid frame type after session
- missing / destroyed session state
- handshake signature failure

Behavior:

- Do not send a decrypted error body
- Peer-visible ERROR frame is optional and MUST NOT include keys, nonces, tags, or exception strings from crypto
- Close TCP
- Wipe session key references (best effort)
- Local error category only (existing `HandshakeError` / `NetworkError` style)

---

## 14. Downgrade Protection

```
DOWNGRADE_PROTECTION=VERSION_2_ONLY_NO_PLAINTEXT_APPLICATION_AFTER_SESSION
```

```
PLAINTEXT_AFTER_HANDSHAKE=FORBIDDEN
```

Rules:

- Implementation of this spec uses `version = 2` only
- Version 1 plaintext peers fail version check (`ProtocolVersionMismatch`)
- After `SecureSession` exists, `send`/`receive` MUST go through GCM; no API to write raw `FrameCodec` application payloads on that socket
- HELLO/HELLO_ACK/HANDSHAKE_FINISH are the only plaintext application frames, and only before session
- Successful handshake then plaintext MANIFEST is a protocol violation: receiver terminates
- No configuration flag to “skip crypto” in production builds of this spec

---

## 15. Security Invariants

1. Application data is not plaintext after secure-session establishment.
2. Every encrypted frame is authenticated with AES-256-GCM.
3. AES-256-GCM is used with 32-byte directional keys.
4. Every nonce is unique for its key (`DIR4||SEQ8`, monotonic seq, new keys on reconnect).
5. TX and RX keys are separated (keyA vs keyB).
6. Crypto sequence is independent of file offset.
7. Reconnect establishes a fresh secure session (new handshake, new keys, counters 0).
8. Resume does not reuse old cryptographic sequence state.
9. AAD is the single 14-byte canonical encoding in section 5.
10. Authentication failure terminates the secure session and TCP.
11. Plaintext fallback after handshake is impossible.
12. Frame lengths are bounded before allocation (`MAX_FRAME_PAYLOAD`).
13. Handshake types 0x01–0x03 are rejected after session start.
14. `bindingKey` is never used as a GCM key.
15. File resume offsets appear only inside encrypted inner payloads.

---

## 16. Future Implementation Contract

Do not create these classes in 21A.

| Component | Responsibility |
|---|---|
| A. Session state | Hold `SecureSession` keys, role (initiator/responder), TX seq, RX expected seq. No HKDF change. |
| B. Nonce construction | `DIR4||SEQ8` only. |
| C. AAD construction | 14-byte canonical AAD from header fields. |
| D. AES-GCM encryption | AES-256-GCM, 12-byte nonce, AAD, 16-byte tag appended. |
| E. AES-GCM decryption | Same; fail closed; no padding oracle. |
| F. Secure frame encode/decode | New layer **above** `FrameCodec`. `FrameCodec` stays framing-only (magic, version, type, seq, length, bytes). Crypto MUST NOT be duplicated inside `FrameCodec`. |
| G. TCP sender | After connect: handshake as initiator; then only encrypted send. |
| H. TCP receiver | After accept: handshake as responder; then only encrypted receive. |

`SocketConnection.sendFrame`/`receiveFrame` should be bound to the secure layer once the session exists, not call `FrameCodec` on application payloads directly.

---

## 17. Implementation Sequence

Unchanged order; architecture does not require a swap.

1. **Task 21B** — AES-256-GCM primitive (library, no TCP)
2. **Task 21C** — cryptographic sequence and nonce state
3. **Task 21D** — secure frame codec wrapping `FrameCodec`
4. **Task 21E** — sender integration (handshake + encrypted send)
5. **Task 21F** — receiver integration
6. **Task 21G** — reconnect/resume security (new session, encrypted offsets)
7. **Task 21H** — negative/security tests
8. **Task 21I** — final runtime validation (physical API 31; F-20R-01/02)

21I may run in parallel with 21B–21C. It blocks production `IdentityManager` default, not the GCM primitive.

---

## 18. Required Final Values

```
SECURE_FRAME_FORMAT=MAGIC(4)||VER(1)||TYPE(1)||CRYPTO_SEQ(8)||CT_LEN(4)||CT||TAG(16)
AAD_FORMAT=VER(1)||TYPE(1)||CRYPTO_SEQ(8 BE)||CT_LEN(4 BE)
CRYPTO_SEQUENCE_WIDTH=64
CRYPTO_SEQUENCE_INITIAL_VALUE=0
CRYPTO_SEQUENCE_INCREMENT=1
CRYPTO_SEQUENCE_MAX=9223372036854775806
NONCE_SIZE=12
NONCE_CONSTRUCTION=DIR4||SEQ8
INITIATOR_TX_KEY=keyA
INITIATOR_RX_KEY=keyB
RESPONDER_TX_KEY=keyB
RESPONDER_RX_KEY=keyA
SESSION_REKEY_RULE=NEW_TCP_CONNECTION_REQUIRES_NEW_HANDSHAKE_AND_NEW_KEYS
PLAINTEXT_AFTER_HANDSHAKE=FORBIDDEN
REPLAY_RULE=STRICT_MONOTONIC_EXPECTED_PLUS_ONE
INVALID_AUTH_RULE=TERMINATE_SECURE_SESSION_AND_TCP_NO_CRYPTO_DETAIL
MAX_ENCRYPTED_FRAME_SIZE=262162
RESUME_SECURITY_RULE=RESUME_FIELDS_ENCRYPTED_AND_AUTHENTICATED_AS_INNER_PAYLOAD
DOWNGRADE_PROTECTION=VERSION_2_ONLY_NO_PLAINTEXT_APPLICATION_AFTER_SESSION
```

---

## 19. Runtime Validation Limitation

Physical Android 12 / API 31 runtime validation was NOT executed.

F-20R-01 and F-20R-02 remain unverified.

Do not infer AndroidKeyStore behavior from JVM tests or documentation.

No Wireless Debugging pairing code is included.

---

## 20. Production Impact

```
Production code changed=NO
Test source changed=NO
Gradle changed=NO
Git operations=NONE
```

Live transfers remain unencrypted version-1 TCP frames until later tasks implement this specification.
