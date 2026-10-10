# Task 21F-0 — Handshake Wire Encoding Resolution Audit

**Status**: AUDIT_COMPLETE
**Outcome**: **ENCODING_UNSPECIFIED**
**Scope**: READ-ONLY. No production, test, Gradle, or Git changes.

Authoritative outer facts (unchanged, not redesigned):

```
MAGIC || VER || TYPE || SEQ || LEN || PAYLOAD
Handshake: VER = 2, SEQ = 0, PAYLOAD = plaintext
HELLO 0x01 = initiator HandshakeMessage
HELLO_ACK 0x02 = responder HandshakeMessage + responder signature
HANDSHAKE_FINISH 0x03 = initiator signature
Post-session application frames = SecureFrameCodec only
```

---

## 1. Existing HandshakeMessage fields

Source: `app/src/main/java/com/filo/transfer/core/network/security/handshake/HandshakeMessage.kt`

| Field | Kotlin type | Notes from construction in `SecureHandshake` |
|---|---|---|
| `protocolVersion` | `Byte` | `SecureHandshake.PROTOCOL_VERSION` = 1 |
| `role` | `Byte` | 0 initiator, 1 responder |
| `idAlgorithm` | `String` | `"Ed25519"` or `"SHA256withECDSA"` |
| `idPublicKey` | `ByteArray` | X.509 SPKI |
| `ephPublicKey` | `ByteArray` | P-256 SPKI from `EphemeralKeyPair` |
| `nonce` | `ByteArray` | 32 bytes (`validateIncomingMessage` rejects other sizes) |
| `keyAgreementAlg` | `String` | `"P-256"` |

`toData()` maps to `HandshakeData` (drops `protocolVersion` and `keyAgreementAlg`; those live on `FullHandshakeTranscript`).

**Serializer/deserializer on `HandshakeMessage`:** none. No `encode`, `decode`, `serialize`, `ByteBuffer`, `DataOutputStream`, or `DataInputStream` in that file or any caller that writes it to a socket.

Wire encoding of `HandshakeMessage` as a HELLO/HELLO_ACK body is **not specified** in source.

---

## 2. Existing canonical encoding rules

Two transcript encoders exist. Both are **signing transcripts**, not declared as TCP payloads.

### 2.1 `HandshakeTranscript.encode()` (`HandshakeTranscript.kt`)

Canonical layout (KDoc + implementation):

```
protocolVersion       : 1 byte
role                  : 1 byte
idAlgorithm           : 4-byte BE length + UTF-8
idPublicKey           : 4-byte BE length + SPKI
ephPublicKey          : 4-byte BE length + bytes
nonce                 : 32 bytes fixed
keyAgreementAlg       : 4-byte BE length + UTF-8
```

Field set matches `HandshakeMessage`. Integer width for lengths is 4-byte big-endian. Strings are UTF-8 via `String.toByteArray()`. Byte arrays are length-prefixed except nonce (fixed 32).

**Not used by `SecureHandshake`.** Production handshake signs `FullHandshakeTranscript.encode()`, not this type. No production decode exists. Task19 described it as a transcript model, not a HELLO payload.

Reuse as HELLO body is **not authorized** by Task21A or by any caller. Field overlap is not a specification.

### 2.2 `FullHandshakeTranscript.encode()` (`FullHandshakeTranscript.kt`)

```
protocolVersion       : 1 byte
keyAgreementAlg       : 4-byte BE length + UTF-8
initiator block:
  idAlgorithm         : 4-byte BE length + UTF-8
  idPublicKey         : 4-byte BE length + SPKI
  ephPublicKey        : 4-byte BE length + bytes
  nonce               : 32 bytes fixed
responder block: same four fields
```

Answers required by this audit:

| Question | Answer |
|---|---|
| Does it encode `HandshakeMessage` itself? | **No.** It encodes two `HandshakeData` blocks plus shared version/alg. Omits per-message `protocolVersion`/`keyAgreementAlg`/`role` (roles are implied by block order; constructor requires initiator.role==0, responder.role==1). |
| Reusable field ordering for one peer? | Partial: participant block order is idAlg, idPub, ephPub, nonce. Not a complete `HandshakeMessage`. |
| Integer widths? | 4-byte big-endian lengths. |
| String encoding? | UTF-8 (`String.toByteArray()`), length-prefixed. |
| Byte-array length widths? | 4-byte BE, except nonce fixed 32. |
| Can participant encoding be reused for HELLO/HELLO_ACK? | **Not specified.** Task21A does not name this encoder as the HELLO body. It binds **both** peers; HELLO exists before the responder message exists. |
| Signing-only? | **Yes.** Used only as input to `signTranscript` / `IdentityVerifier.verify` inside `SecureHandshake`. |

---

## 3. Evidence for any reusable encoding

| Candidate | Evidence it is the TCP payload codec | Verdict |
|---|---|---|
| `HandshakeMessage` methods | None | No |
| `FramePayloads.encodeHello` | Encodes **deviceName/sessionId**, not crypto handshake fields | No (different protocol object) |
| `FrameCodec` | Outer 18-byte header only; payload is opaque | Outer framing only |
| `HandshakeTranscript.encode` | Field overlap with `HandshakeMessage`; unused by `SecureHandshake`; no decoder; never referenced as HELLO | **Not authoritative for wire** |
| `FullHandshakeTranscript.encode` | Two-party signing transcript; no signatures; cannot be HELLO | Signing only |
| Task21A line 92 | Quote below | Does **not** give a byte layout |

Task21A §3 line 92 (complete sentence):

> Inner encodings of handshake payloads are the existing `HandshakeMessage` / signature bytes, length-delimited, not specified as a new high-level protocol.

That sentence:

- asserts payloads are `HandshakeMessage` bytes and signature bytes
- says “length-delimited”
- **explicitly does not specify** field order, integer widths, which lengths apply (message vs each field vs signature), UTF-8 vs modified UTF, or concatenation of message and signature

Task21B/21C/21D docs cover AES-GCM / crypto sequence / secure frames, not handshake payload bytes. Task21E records the same gap (G-21E-04). Tests pass in-memory `HandshakeMessage` objects; they never serialize them.

**No reusable TCP encoding is specified.** Overlap with transcript encoders is not evidence of wire reuse.

---

## 4. Exact HELLO encoding if specified

**UNSPECIFIED** (inner payload).

Specified only at the outer-frame level:

- TYPE = 0x01
- VER = 2
- SEQ = 0
- PAYLOAD = plaintext initiator `HandshakeMessage` bytes
- Length of that payload: `FrameCodec`/`LEN` field (4-byte BE), if the existing 18-byte header is used

Inner field layout of those bytes: **not specified**.

---

## 5. Exact HELLO_ACK encoding if specified

**UNSPECIFIED.**

Specified semantically: responder `HandshakeMessage` **plus** responder signature (`fullTranscriptSignature`).

No evidence in source, tests, or Task21A–21E for:

- signature length prefix
- fixed signature size (ECDSA DER is variable; Ed25519 is 64; both algorithms are allowed)
- message length prefix inside the payload (distinct from outer `LEN`)
- delimiter
- nested length
- concatenation without length
- any decoder

Without a specified split, `message || signature` cannot be parsed from a single `LEN`-bounded blob unless an inner encoding is invented.

---

## 6. Exact HANDSHAKE_FINISH encoding if specified

**UNSPECIFIED.**

Specified semantically: initiator signature over the same full transcript (`InitiatorCompletion.signatureToSend`). TYPE 0x03 is required and **absent** from `FrameType`.

Not specified:

- raw signature as the entire outer payload
- length-prefixed signature
- transcript hash + signature
- any other structure

`FrameCodec` already length-prefixes the whole payload via `LEN`. That does **not** by itself choose among the forms above. Selecting “raw signature = payload” would be an implementation choice, not a documented encoding.

---

## 7. FrameCodec compatibility analysis

`FrameCodec.writeFrame` / `readFrame` layout:

```
MAGIC(4) || VER(1) || TYPE(1) || SEQUENCE(8 BE) || PAYLOAD_LEN(4 BE) || payload
```

Task21A handshake outer header:

```
MAGIC(4) || VER(1) || TYPE(1) || SEQ(8) || LEN(4) || PAYLOAD
```

**The layouts match.** SEQ on handshake frames must be 0 (21A §4). `FrameCodec` will write/read SEQ=0 without semantic checks.

Differences that block handshake IO today:

| Check | Current `FrameCodec` | Handshake need |
|---|---|---|
| VER | Must equal `CURRENT_PROTOCOL_VERSION` (**1**) | VER **2** |
| TYPE | Must be a known `FrameType` | 0x03 **not in enum** → `InvalidFrame` |
| Payload | Opaque bytes | Still needs an inner codec that does not exist |

Would changing version validation to `VER == 1 || VER == 2` be sufficient for handshake framing?

**No.** Necessary (if handshake uses this header) but not sufficient:

1. `HANDSHAKE_FINISH` (0x03) is still unknown.
2. Inner payloads remain unspecified.
3. Accepting VER=2 on `FrameCodec` would also accept **application** types as plaintext VER=2, which must not happen after session; demux/mode still belongs at `SocketConnection`, not as a blanket FrameCodec relaxation.
4. Handshake must **not** go through `SecureFrameCodec` (no GCM, no session keys, no `CryptoSequence`).

Handshake traffic remains pre-session plaintext. Post-session application traffic remains `SecureFrameCodec` only. Changing handshake framing does not imply AES-GCM.

---

## 8. Identity injection analysis

| Path | Type | Location |
|---|---|---|
| Production | `IdentityManager` (AndroidKeyStore) | `crypto/IdentityManager.kt`. **Does not implement `SigningIdentity`.** |
| JVM tests | `InMemorySigningIdentity` | Already in **main** `SigningIdentity.kt` (documented as test helper). `HandshakeSecurityTest` uses `InMemorySigningIdentity.generate()`. |

21F can inject the existing `InMemorySigningIdentity` into handshake calls from tests **without** modifying `IdentityManager`.

Do not create `SigningIdentityFactory`, production Keystore fallback, or Keystore-to-memory fallback.

Production TCP handshake cannot obtain a `SigningIdentity` until a later adapter task. That is out of scope for encoding resolution.

---

## 9. Confirmed ambiguities

1. **HELLO inner bytes** — no field order, widths, or string/bytes encoding for `HandshakeMessage` on the wire.
2. **HELLO_ACK concatenation** — no rule to split `HandshakeMessage` from signature.
3. **HANDSHAKE_FINISH payload** — raw vs length-prefixed vs any other form.
4. **“Length-delimited” in Task21A line 92** — does not say whether that refers to outer `LEN`, per-field 4-byte prefixes (as in transcripts), signature prefixes, or all of these.
5. **`HandshakeTranscript.encode` vs HELLO** — similar fields, **not** declared as the HELLO codec; unused by current `SecureHandshake`.
6. **`FrameType` 0x03** — missing (type constant, not a payload encoding).
7. **Signature length** — not fixed; cannot parse an unprefixed trailer without more spec.

---

## 10. Exact information required before 21F implementation

Before 21F may implement handshake payloads, an authoritative source must specify, without invention:

**A. HandshakeMessage / HELLO body**

- Field order
- Encoding of `protocolVersion` and `role` (width)
- Encoding of `idAlgorithm` and `keyAgreementAlg` (charset, length prefix width)
- Encoding of `idPublicKey` and `ephPublicKey` (length prefix width)
- Encoding of `nonce` (fixed 32 vs length-prefixed)
- Whether `HandshakeTranscript.encode()` is **the** HELLO body (yes/no). If yes, that must be stated; current documents do not.

**B. HELLO_ACK body**

- How `HandshakeMessage` bytes and signature bytes are combined
- Whether the signature is length-prefixed, and the prefix width
- Whether the message portion uses the same encoding as HELLO

**C. HANDSHAKE_FINISH body**

- Exact bytes: raw signature **or** length-prefixed signature **or** another listed structure
- Confirmation that outer `LEN` is the only length wrapping those bytes

**D. Outer header (already specified; implementation constraint only)**

- Confirm handshake uses the existing 18-byte `FrameCodec` header at VER=2, SEQ=0, not `SecureFrameCodec`
- Confirm 0x03 is added as `FrameType` only, without redesigning HELLO/HELLO_ACK application payloads (`FramePayloads.encodeHello` remains a different, unused-for-crypto object)

Until A–C are specified by a protocol authority (updated Task21A or an equivalent signed encoding spec), 21F must not invent inner encodings.

---

## Decision

**OUTCOME B: ENCODING_UNSPECIFIED**

The repository does **not** contain enough authoritative byte-level information to implement HELLO, HELLO_ACK, and HANDSHAKE_FINISH payloads without inventing protocol behavior.

Missing specification (all three inner payloads):

- HELLO: `HandshakeMessage` wire layout
- HELLO_ACK: `HandshakeMessage` + signature concatenation/delimiters
- HANDSHAKE_FINISH: signature payload layout

No implementation encoding is proposed.

F-20R-01 and F-20R-02 remain **UNVERIFIED** (unchanged; not in scope).

No production, test, Gradle, or Git operations were performed.
