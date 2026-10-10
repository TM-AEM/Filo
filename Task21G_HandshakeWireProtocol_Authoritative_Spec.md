# Task 21G — Authoritative Handshake Wire Protocol Specification

**TASK21G_STATUS=SPECIFICATION_COMPLETE**

Date: 2026-10-06
Scope: SPECIFICATION ONLY. No production, test, Gradle, or Git changes.
Authority: Task 21G is authorized to make project-level protocol decisions that Task 21F-0 and Task 21F-S found unspecified.

This document is the **authoritative** byte-level encoding for Filo pre-session handshake frames. Task 21F MUST implement these layouts without alteration.

Task 21F-0 outcome **ENCODING_UNSPECIFIED** and Task 21F-S `TASK21FS_STATUS=SPECIFICATION_BLOCKED` remain historically correct: those tasks were **not** authorized to invent encodings. This document **does** make those decisions, and labels each one **PROJECT PROTOCOL DECISION**.

---

## How to read this document

**CONFIRMED EXISTING PROTOCOL FACT** items come from Task 21A / Task 21F-0 / corrected Task 21F-S. They were already established.

**PROJECT PROTOCOL DECISION** items are newly selected by Task 21G. Each such item states its exact byte-level consequence and why it was chosen.

Never treat an unlabelled implementation convention as wire law.

---

## Confirmed Existing Protocol Facts

### C-1. Outer handshake frame layout

```
MAGIC(4) || VER(1) || TYPE(1) || SEQ(8) || LEN(4) || PAYLOAD(LEN)
```

| Item | Value | Source |
|---|---|---|
| MAGIC | 4 bytes `0x46 0x49 0x4C 0x4F` (`FILO`) | `ProtocolConstants.MAGIC_HEADER` |
| VER | 1 byte = `2` | Task 21A section 4 |
| TYPE | 1 byte | Handshake types defined below |
| SEQ | 8 bytes signed i64 big-endian = `0` | Task 21A section 4; `FrameCodec.writeLong` |
| LEN | 4 bytes signed i32 big-endian | Task 21A section 4; `FrameCodec.writeInt` |
| PAYLOAD | plaintext handshake bytes | Task 21A section 4 |

Total header = 18 bytes (`ProtocolConstants.HEADER_SIZE`).

### C-2. Handshake message types

| TYPE | Name | Direction | Semantic payload |
|---|---|---|---|
| `0x01` | HELLO | initiator -> responder | initiator `HandshakeMessage` |
| `0x02` | HELLO_ACK | responder -> initiator | responder `HandshakeMessage` + responder signature |
| `0x03` | HANDSHAKE_FINISH | initiator -> responder | initiator signature over full transcript |

### C-3. Handshake is plaintext; post-session uses SecureFrameCodec

Handshake frames MUST NOT use AES-GCM, `SecureFrameCodec`, `CryptoSequence`, or session keys. After both signatures verify and both sides hold `SecureSession`, application frames MUST use `SecureFrameCodec`. No plaintext application-frame fallback exists.

### C-4. Cryptographic APIs (not redesigned)

`SecureHandshake.initiate` / `respond` / `completeAsInitiator` / `completeAsResponder` remain the API. Signatures cover `FullHandshakeTranscript.encode()`. Session keys remain `keyA` / `keyB` / `bindingKey` from HKDF. `ProtocolFrame.sequence` file-offset semantics for DATA_CHUNK are unchanged. Resume/reconnect architecture is unchanged.

### C-5. Identity (not redesigned)

Production: `IdentityManager` / AndroidKeyStore. JVM tests: existing `InMemorySigningIdentity`. No factory, no Keystore-to-memory fallback, no `IdentityManager` modification.

### C-6. Inner protocol version vs outer frame version

Two distinct version fields exist:

| Field | Width | Value | Meaning | Source |
|---|---|---|---|---|
| Outer frame VER | 1 byte | `2` | Frame format version | Task 21A |
| `HandshakeMessage.protocolVersion` | 1 byte | `1` | Crypto/handshake inner version | `SecureHandshake.PROTOCOL_VERSION` |

They are independent. Outer VER=2 does not imply inner version=2.

### C-7. FullHandshakeTranscript purpose

`FullHandshakeTranscript.encode()` is the canonical **signing** input for both peers. It is NOT sent on the wire. It requires both participants' data. It is structurally different from a single-participant `HandshakeMessage`.

Current `FullHandshakeTranscript.encode()` layout (signing only, unchanged):

```
u8  protocolVersion
u32be + UTF-8 keyAgreementAlg
initiator block:
  u32be + UTF-8 idAlgorithm
  u32be + SPKI idPublicKey
  u32be + SPKI ephPublicKey
  u8[32] nonce
responder block (same four fields)
```

---

## Project Protocol Decisions

### D-HM: HandshakeMessage wire encoding

**Decision:** Adopt `HandshakeTranscript.encode()` layout as the authoritative network encoding for `HandshakeMessage`.

**Why this decision (not inference):** Task 21F-0 confirmed `HandshakeTranscript.encode()` is unused on TCP, has no decoder, and was not named as HELLO. Its field overlap with `HandshakeMessage` was noted but not sufficient to establish it as wire law. Task 21G now explicitly adopts it.

**Exact byte layout:**

```
offset    size                  field
------    ----                  --------------------------------------------
0         1                     protocolVersion     unsigned 8-bit
1         1                     role                unsigned 8-bit
2         4                     idAlgorithmLen      unsigned 32-bit BE
6         idAlgorithmLen        idAlgorithm         UTF-8 bytes
A         4                     idPublicKeyLen      unsigned 32-bit BE
A+4       idPublicKeyLen        idPublicKey         X.509 SPKI bytes
B         4                     ephPublicKeyLen     unsigned 32-bit BE
B+4       ephPublicKeyLen       ephPublicKey        X.509 SPKI bytes
C         32                    nonce               raw, fixed width
C+32      4                     keyAgreementAlgLen  unsigned 32-bit BE
C+36      keyAgreementAlgLen    keyAgreementAlg     UTF-8 bytes
```

```
A = 6 + idAlgorithmLen
B = A + 4 + idPublicKeyLen
C = B + 4 + ephPublicKeyLen
encoded_size = C + 36 + keyAgreementAlgLen
```

**Compatibility with SecureHandshake:** `SecureHandshake.initiate` and `SecureHandshake.respond` construct `HandshakeMessage` with these exact fields in this exact order. No changes needed.

**Transcript semantics:** Signing still covers `FullHandshakeTranscript.encode()`, not these wire bytes. The two encodings serve different purposes (network transport vs authentication binding).

**No field meaning changes:** All field names, types, and values map directly to `HandshakeMessage` properties.

**Deterministic dual-side parse:** Both peers can parse: version(1) + role(1) + idAlgorithm(len+bytes) + idPublicKey(len+bytes) + ephPublicKey(len+bytes) + nonce(fixed 32) + keyAgreementAlg(len+bytes). Total = encoded_size. Self-describing; no ambiguity.

**Ambiguity with FullHandshakeTranscript.encode():** None. Different structure entirely:
- HandshakeMessage has 7 top-level fields including explicit role; FullHandshakeTranscript has 2 participant blocks with implied roles
- FullHandshakeTranscript places keyAgreementAlg BEFORE participant blocks; HandshakeMessage places it AFTER nonce
- FullHandshakeTranscript encodes two participants; HandshakeMessage encodes one

**FrameCodec compatibility:** Outer 18-byte header unchanged. Handshake payload is longer than legacy VER=1 HELLO (which carried deviceName/sessionId), but stays within MAX_FRAME_PAYLOAD (262144 bytes). Typical handshake payload is approximately 300-500 bytes.

**Unnecessary fields:** None. Every field corresponds to an existing `HandshakeMessage` property.

**Semantic changes to HELLO/HELLO_ACK/HANDSHAKE_FINISH:** None. These remain the same logical messages, now with defined byte layouts.

---

### D-ACK: HELLO_ACK payload structure

**Decision:** HELLO_ACK payload = encoded `HandshakeMessage` immediately followed by signature bytes. No inner length prefix between them.

**Exact byte layout:**

```
payload+0                         HandshakeMessage (encoded_size bytes)
payload+encoded_size              signature           (LEN - 4 - encoded_size bytes)
END. Trailing bytes prohibited.
```

**Why no signature length prefix:** The `HandshakeMessage` portion is self-describing (D-HM). After consuming `encoded_size` bytes, the remaining `LEN - 4 - encoded_size` bytes are unambiguously the signature. Adding a u32be prefix would be redundant and increase every HELLO_ACK by 4 bytes unnecessarily.

**Compatibility with SecureHandshake:** `HandshakeResponderState.fullTranscriptSignature` provides the signature bytes. `HandshakeMessage.localMessage.encode()` provides the message bytes. The concatenation is deterministic.

**Parsing determinism:** Decoder reads outer LEN. Parses HandshakeMessage via D-HM to get encoded_size. Remaining bytes = signature. Reject if remaining <= 0 (truncated) or if trailing bytes exist (LEN mismatch).

**No semantic change to HELLO_ACK:** Same three flights, same signature over same full transcript. Only the byte layout changed from unspecified to specified.

---

### D-FIN: HANDSHAKE_FINISH payload structure

**Decision:** HANDSHAKE_FINISH payload = signature bytes only. No HandshakeMessage. No length prefix. No extra fields.

**Exact byte layout:**

```
payload+0                       signature
                                (LEN - 4 bytes)
END. Trailing bytes prohibited.
```

**Signature length = outer LEN - 4.** No inner framing.

**Compatibility with SecureHandshake:** `InitiatorCompletion.signatureToSend` provides the bytes. These are identical to `HandshakeResponderState.fullTranscriptSignature` in form (both are `signTranscript(FullHandshakeTranscript.encode())`).

**Deterministic parse:** Decoder reads outer LEN. All `LEN - 4` bytes are the signature. Length is known from outer frame. No ambiguity.

**No semantic change to HANDSHAKE_FINISH:** Same initiator signature, same signing input, same verification. Only the byte layout is now specified.

---

### D-SIG-BYTES: Signature representation

**Decision:** Transmitted signature bytes are the raw output of JCA `Signature.sign()` after `update(transcript)`. No ASN.1 wrapper, no hash prepended, no algorithm OID.

**Source bytes:**

| Message | Source field | What it contains |
|---|---|---|
| HELLO_ACK | `HandshakeResponderState.fullTranscriptSignature` | `identity.signTranscript(fullTranscript.encode())` |
| HANDSHAKE_FINISH | `InitiatorCompletion.signatureToSend` | `identity.signTranscript(fullTranscript.encode())` |

**Algorithm independence:** ECDSA DER length is variable (approximately 70-72 bytes for P-256). Ed25519 length is typically 64 bytes. Neither is a protocol constant. The length comes from outer LEN / self-describing message, not from algorithm assumptions.

**Verification input:** `IdentityVerifier.verify(idPublicKey, idAlgorithm, FullHandshakeTranscript.encode(), signature)`. NOT the HELLO payload bytes.

---

### D-ALG: Algorithm identifier encoding

**Decision:** `idAlgorithm` and `keyAgreementAlg` are transmitted as UTF-8 strings with a u32be byte-length prefix.

**Exact mapping:**

| Wire UTF-8 (exact, case-sensitive) | Field | Encoded via |
|---|---|---|
| `Ed25519` | idAlgorithm | `SigningIdentity.getIdentityAlgorithm()` |
| `SHA256withECDSA` | idAlgorithm | `SigningIdentity.getIdentityAlgorithm()` |
| `P-256` | keyAgreementAlg | Literal `"P-256"` in SecureHandshake |

**Encoding:**

```
u32be length (byte count of UTF-8 encoding)
utf8[length bytes]
```

**Example:** `Ed25519` → `00 00 00 07 45 64 32 35 35 39` (4-byte length 7, then 7 ASCII bytes).

**Not numeric IDs:** No protocol-level numeric algorithm table. String comparison is deterministic and human-readable on wire captures.

**Versioning:** New algorithms require a new `HandshakeMessage.protocolVersion` (inner version 2+).

---

### D-KEY: Public-key encoding

**Decision:** Identity and ephemeral public keys are transmitted as X.509 SubjectPublicKeyInfo (SPKI) with a u32be byte-length prefix.

**Exact representation:**

| Key | Source | Wire bytes |
|---|---|---|
| identity public key | `SigningIdentity.getIdentityPublicKey()` | Returns `PublicKey.encoded` = X.509 SPKI |
| ephemeral public key | `EphemeralKeyPair.publicKeySpki` | Returns `kp.public.encoded` = X.509 SPKI |

**Encoding:**

```
u32be length
spki[length bytes]
```

**Private keys never sent:** `EphemeralKeyPair.privateKey` and identity private keys are never serialized or transmitted.

**Minimum length:** `length >= 1`. Zero-length keys are malformed at framing.

**Cryptographic validity:** SPKI parse failure occurs in `IdentityVerifier` / `KeyFactory.getInstance(jcaName)` after structural validation passes. That is `InvalidPublicKey`.

---

### D-INT: Integer encoding

**Decision:** All multi-byte integers use big-endian byte order. No little-endian. No varints. One consistent convention.

| Field | Width | Signedness | Byte order | Source alignment |
|---|---|---|---|---|
| Outer VER, TYPE | 8-bit | unsigned | n/a | `writeByte` |
| Outer SEQ | 64-bit | signed two's complement | big-endian | `DataOutputStream.writeLong` |
| Outer LEN | 32-bit | signed two's complement | big-endian | `DataOutputStream.writeInt` |
| Inner length prefixes | 32-bit | unsigned | big-endian | `HandshakeTranscript.encode()` `writeLenPrefixed` |
| protocolVersion, role | 8-bit | unsigned | n/a | `writeByte` |

A length whose value exceeds remaining payload bytes is malformed. Outer LEN negative or greater than 262144 is malformed.

---

### D-STR: String encoding

**Decision:** All string fields use UTF-8 with a u32be byte-length prefix. Length is measured in bytes, not characters.

| Rule | Value |
|---|---|
| Character encoding | UTF-8 (`String.toByteArray(Charsets.UTF_8)` / `String(bytes, UTF_8)`) |
| Length prefix | unsigned 32-bit big-endian |
| Length unit | bytes of UTF-8 encoding |
| Empty string | length 0, zero bytes; then rejected by algorithm validation |
| BOM | not a required prefix |
| Invalid UTF-8 | malformed; reject |
| Maximum | no separate cap; must fit remaining payload and outer LEN (262144) |

---

### D-ARR: Variable-length byte-array encoding

**Decision:** Each variable-length byte array type has a defined prefix scheme.

| Field | Prefix | Prefix counts | Min | Max |
|---|---|---|---|---|
| idAlgorithm | u32be | UTF-8 bytes only | 1 | remaining payload |
| keyAgreementAlg | u32be | UTF-8 bytes only | 1 | remaining payload |
| idPublicKey | u32be | SPKI bytes only | 1 | remaining payload; also outer LEN |
| ephPublicKey | u32be | SPKI bytes only | 1 | remaining payload; also outer LEN |
| nonce | **none** | fixed 32 raw bytes | 32 | 32 |
| signature | **none** | raw bytes | 1 | LEN - 4 (FINISH); LEN - 4 - encoded_size (ACK) |

Nonce has no length prefix because D-HM fixes it at 32 bytes structurally (matching `SecureHandshake.validateIncomingMessage`).

Signature has no length prefix because D-ACK and D-FIN derive signature length from outer LEN and/or self-describing HandshakeMessage.

Truncated prefix or truncated body is malformed.

---

### D-VAL: Validation and malformed-input rules

**Decision:** Reject and fail closed on any malformed input. Do not reinterpret as VER=1 application traffic.

| Condition | Action |
|---|---|
| Truncated outer header (< 18 bytes) | reject |
| MAGIC != FILO | reject |
| Handshake TYPE with VER != 2 | reject |
| During handshake, TYPE not in {0x01, 0x02, 0x03} | unexpected type; reject |
| Handshake TYPE received after SECURE mode | forbidden post-session; reject |
| SEQ != 0 on handshake types | reject |
| LEN < 0 or LEN > 262144 | oversized / invalid length; reject |
| Stream shorter than LEN | truncated; reject |
| HELLO: LEN != encoded_size(HandshakeMessage) | trailing or truncated; reject |
| HELLO_ACK: remaining after HandshakeMessage <= 0 | truncated or empty signature; reject |
| HELLO_ACK / FINISH: trailing bytes after signature | trailing bytes; reject |
| Any u32 length prefix > remaining payload | invalid length; reject |
| protocolVersion != 1 | unsupported inner version; MalformedHandshake |
| HELLO role != 0 or HELLO_ACK role != 1 | unsupported role; reject |
| idAlgorithm not `Ed25519` or `SHA256withECDSA` | unsupported algorithm; UnsupportedAlgorithm |
| keyAgreementAlg not `P-256` | unsupported algorithm; UnsupportedAlgorithm |
| nonce structurally short of 32 bytes | invalid nonce length; MalformedHandshake |
| idPublicKeyLen == 0 or ephPublicKeyLen == 0 | invalid key length; MalformedHandshake |
| signatureLen == 0 | malformed signature framing; MalformedHandshake |
| Invalid UTF-8 in string fields | malformed; reject |
| SPKI fails KeyFactory.parse | InvalidPublicKey |
| ECDH fails | KeyAgreementFailed |
| Signature.verify false | InvalidSignature |

Do not invent error types beyond existing `HandshakeError` / `NetworkError` categories.

---

### D-SEP: Handshake framing vs SecureFrameCodec boundary

**Decision:** The two formats are separate and MUST NOT be merged. Handshake frames are NOT a plaintext mode of SecureFrameCodec.

**Handshake frames (this spec):**

```
MAGIC(4) || VER=2(1) || TYPE 0x01|0x02|0x03(1) || SEQ=0(8 BE) || LEN(4 BE) || plaintext PAYLOAD
```

- No GCM, no tag, no CT_LEN, no CRYPTO_SEQ consumption.
- The 8-byte SEQ field is zero for handshake. It is NOT a crypto sequence.

**Post-session application frames (Task 21A / 21D; unchanged):**

```
MAGIC(4) || VER=2(1) || TYPE(1) || CRYPTO_SEQ(8 BE) || CT_LEN(4 BE) || ciphertext||tag(16)
```

- AES-256-GCM, AAD as Task 21A, CRYPTO_SEQ starts at 0 after successful handshake.
- Handshake types 0x01..0x03 forbidden after session start.

Same 18-byte header widths. Different payload semantics. Outer VER=2 is shared. Inner protocolVersion=1 is unrelated to outer VER.

Handshake MUST NOT consume `CryptoSequence`. First secure application frame uses CRYPTO_SEQ = 0 per Task 21A/21C/21D.

---

### D-ROLE: Role encoding

**Decision:**

| Width | Value | Meaning |
|---|---|---|
| 1 unsigned byte | `0` | initiator (TCP client / transfer sender) |
| 1 unsigned byte | `1` | responder (TCP server / transfer receiver) |

HELLO payload role MUST be 0. HELLO_ACK payload role MUST be 1. Any other value is malformed.

Matches `HandshakeMessage.role` and `HandshakeData.role` in existing code.

---

## Handshake sequence and secure-mode transition

```
TCP CONNECT / ACCEPT
  |
  |  HELLO (0x01)  initiator HandshakeMessage
  v
responder: SecureHandshake.respond(identity, initiatorMsg)
  |
  |  HELLO_ACK (0x02)  responder HandshakeMessage + signature
  v
initiator: SecureHandshake.completeAsInitiator(state, peerMsg, peerSig)
  |  -> SecureSession + signatureToSend
  |
  |  HANDSHAKE_FINISH (0x03)  initiator signature
  v
responder: SecureHandshake.completeAsResponder(state, peerMsg, peerSig)
  |  -> SecureSession
  |
  |  BOTH SIDES ENTER SECURE MODE
  v
first application frame (typically MANIFEST) via SecureFrameCodec, CRYPTO_SEQ = 0
```

**Initiator** may send application frames only after `completeAsInitiator` succeeds AND HANDSHAKE_FINISH has been written.

**Responder** enters SECURE mode only after `completeAsResponder` verifies the initiator signature.

Message completing the transition: `HANDSHAKE_FINISH` (0x03), after responder successfully verifies the initiator signature.

Key mapping after session (unchanged Task 21A):

```
Initiator TX = keyA   direction INITIATOR_TO_RESPONDER   (CryptoDirection 0x00000001)
Initiator RX = keyB   direction RESPONDER_TO_INITIATOR   (CryptoDirection 0x00000002)
Responder TX = keyB   direction RESPONDER_TO_INITIATOR
Responder RX = keyA   direction INITIATOR_TO_RESPONDER
```

CRYPTO_SEQ starts at 0 on both TX and RX. Independent from `ProtocolFrame.sequence` (file offset).

Failed handshake: no application frames. Do not retain partial keys. Close TCP.

---

## Compatibility mapping to existing source APIs

| Wire element | Existing Kotlin source |
|---|---|
| outer MAGIC | `ProtocolConstants.MAGIC_HEADER` |
| outer SEQ/LEN encoding | `FrameCodec.writeFrame` / `readFrame` |
| outer LEN bounds | `ProtocolConstants.MAX_FRAME_PAYLOAD` (= 262144) |
| outer VER=2 | Task 21A; `SecureFrameCodec.SECURE_FRAME_VERSION` (same value post-session) |
| TYPE 0x01 / 0x02 | `FrameType.HELLO` / `FrameType.HELLO_ACK` |
| TYPE 0x03 | **missing from FrameType**; add `HANDSHAKE_FINISH(0x03)` |
| protocolVersion | `HandshakeMessage.protocolVersion` = `SecureHandshake.PROTOCOL_VERSION` (= 1) |
| role | `HandshakeMessage.role` |
| idAlgorithm | `HandshakeMessage.idAlgorithm` = `SigningIdentity.getIdentityAlgorithm()` |
| idPublicKey | `HandshakeMessage.idPublicKey` = `SigningIdentity.getIdentityPublicKey()` |
| ephPublicKey | `HandshakeMessage.ephPublicKey` = `EphemeralKeyPair.publicKeySpki` |
| nonce | `HandshakeMessage.nonce` = `SecureRandomWrapper.nextNonce()` (32 bytes) |
| keyAgreementAlg | `HandshakeMessage.keyAgreementAlg` = `"P-256"` |
| HandshakeMessage encode | `HandshakeTranscript.encode()` per D-HM |
| HandshakeMessage decode | matching decoder implementing D-HM layout |
| HELLO_ACK signature | `HandshakeResponderState.fullTranscriptSignature` |
| HANDSHAKE_FINISH signature | `InitiatorCompletion.signatureToSend` |
| Signed bytes | `FullHandshakeTranscript.encode()` (not HELLO payload) |
| Session keys | `SecureSession.keyA` / `keyB` / `bindingKey` |
| Verification | `IdentityVerifier.verify(idPublicKey, idAlgorithm, transcript, signature)` |

This mapping is sufficient to implement the wire without redesigning `SecureHandshake`, `SecureSession`, `IdentityManager`, `EphemeralKeyPair`, `Hkdf`, or `AesGcm`.

---

## Explicit statements required by the task

**ProtocolFrame.sequence semantics remain unchanged.** DATA_CHUNK `sequence` remains the file byte offset. This is independent of CRYPTO_SEQ used by SecureFrameCodec post-handshake.

**CryptoSequence starts only for post-handshake secure frames.** Handshake frames (HELLO, HELLO_ACK, HANDSHAKE_FINISH) do NOT consume `CryptoSequence`. The first post-handshake SecureFrameCodec frame uses CRYPTO_SEQ = 0.

**No plaintext fallback exists after secure mode.** Once both sides hold `SecureSession`, all subsequent frames MUST use `SecureFrameCodec`. A plaintext frame (any type) after secure mode is a protocol violation: terminate.

---

## Implementation incompatibilities (Task 21F, not specification gaps)

These are code gaps that Task 21F must address. They are not missing spec items:

1. `FrameCodec.readFrame` rejects VER != `CURRENT_PROTOCOL_VERSION` (1). Handshake IO must accept VER=2 for types 0x01-0x03 without treating VER=2 application types as plaintext.
2. `FrameType` has no `HANDSHAKE_FINISH(0x03)`.
3. `HandshakeMessage` has no encode/decode methods. Implementation may call `HandshakeTranscript.encode()` per D-HM and add a matching decoder plus D-ACK/D-FIN framing.
4. `SocketConnection` still uses `FrameCodec` exclusively (the intended security-boundary insertion point per Task 21E).
5. Production has no `SigningIdentity` adapter over `IdentityManager`. JVM tests inject `InMemorySigningIdentity`.
6. `FramePayloads.encodeHello` / `decodeHello` remain the legacy VER=1 deviceName/sessionId exchange. Crypto handshake replaces that on the wire for VER=2 types 0x01-0x03. Device name remains on MANIFEST (`senderDeviceName`).
7. `SecureFrameCodec` currently forbids HELLO/HELLO_ACK post-session; HANDSHAKE_FINISH must also be forbidden after session once the type exists.

Do not modify `AesGcm`, `CryptoSequence`, `SecureFrameCodec`, `Hkdf`, `SecureHandshake`, `SecureSession`, `EphemeralKeyPair`, or `IdentityManager` to satisfy this spec.

F-20R-01 and F-20R-02 remain **UNVERIFIED**.

---

## Security invariants

1. Handshake frames are the only plaintext application frames, and only before session.
2. Plaintext application frame after handshake is a protocol violation: terminate.
3. No plaintext fallback after handshake.
4. Handshake MUST NOT use session keys, AES-GCM, or `CryptoSequence`.
5. `bindingKey` is never a GCM key.
6. Signatures bind `FullHandshakeTranscript.encode()`, which includes both peers' identity keys, ephemeral keys, and nonces.
7. Failed handshake: close; do not retain partial keys.
8. Reconnect: new handshake, new ephemeral ECDH, new HKDF, CRYPTO_SEQ = 0.
9. Counter overflow: terminate; reconnect requires full new handshake.

---

## Versioning

| Change | Required action |
|---|---|
| Inner HandshakeMessage field order/widths | Increment `HandshakeMessage.protocolVersion`; version-1 peers reject |
| New algorithm identifier strings | New inner version, or later protocol document; version 1 allows only D-ALG strings |
| Outer header format change | Increment outer VER |
| Post-session codec change | Task 21A / SecureFrameCodec; not this document |

This specification: inner version 1, outer VER 2.

---

## Byte-layout summary

**Outer handshake frame**

```
FILO | 0x02 | type | i64be 0 | i32be LEN | PAYLOAD
```

**HandshakeMessage / HELLO (0x01)**

```
u8   protocolVersion
u8   role                           MUST be 0
u32be idAlgorithmLen
utf8[idAlgorithmLen]
u32be idPublicKeyLen
spki[idPublicKeyLen]
u32be ephPublicKeyLen
spki[ephPublicKeyLen]
u8[32] nonce
u32be keyAgreementAlgLen
utf8[keyAgreementAlgLen]
```

`outer LEN = encoded_size`. Trailing bytes prohibited.

**HELLO_ACK (0x02)**

```
[encoded_size bytes: HandshakeMessage as above, role MUST be 1]
[remaining bytes: signature, length = outer LEN - 4 - encoded_size]
```

Trailing bytes prohibited. Signature length derived from outer LEN and self-describing message.

**HANDSHAKE_FINISH (0x03)**

```
signature bytes
length = outer LEN - 4
```

Trailing bytes prohibited.

**Signed transcript (not on wire)**

```
FullHandshakeTranscript.encode()
= u8 protocolVersion + u32be keyAgreementAlg + [initiator block] + [responder block]
```

TASK21G_STATUS=SPECIFICATION_COMPLETE
