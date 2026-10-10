# Task 21G — Final Report

**Status**: COMPLETE (specification only)
**Date**: 2026-10-06
**TASK21G_STATUS=SPECIFICATION_COMPLETE**
**No Git operations were performed.**

---

## 1. What this task did

Task 21G made the **authorized protocol decisions** that Task 21F-0 / 21F-S could not derive from existing sources.

21F-S correctly stayed **SPECIFICATION_BLOCKED** (inference is not protocol). 21G was explicitly allowed to decide the missing encodings. Those decisions are now written as the authoritative wire spec.

No production code, tests, Gradle, or Git operations.

---

## 2. Deliverable

| File | Role |
|---|---|
| `Task21G_HandshakeWireProtocol_Authoritative_Spec.md` | Authoritative byte-level handshake wire protocol |
| `Task21G_HandshakeWireProtocol_Report.md` | This report |
| `Task21FS_HandshakeWireProtocol_Spec.md` | Historical blocked state (`SPECIFICATION_BLOCKED`); superseded for encoding |

Task 21F MUST implement the 21G spec without alteration.

---

## 3. Protocol decisions (G-01 .. G-11)

| ID | Decision |
|---|---|
| G-01 | `HandshakeTranscript.encode()` layout **is** the `HandshakeMessage` network encoding |
| G-02 | HELLO_ACK = HandshakeMessage then SignatureBlock; no trailing bytes |
| G-03 | SignatureBlock = `u32be signatureLen` + signature bytes; length not inferred from algorithm |
| G-04 | HANDSHAKE_FINISH = SignatureBlock only |
| G-05 | Signature bytes = raw `signTranscript` / `Signature.sign()` output |
| G-06 | Public keys = X.509 SPKI |
| G-07 | Algorithms = exact UTF-8 `Ed25519` / `SHA256withECDSA` / `P-256` |
| G-08 | Integers big-endian; inner lengths u32be; outer SEQ i64be; outer LEN i32be |
| G-09 | Strings = UTF-8, u32be byte length |
| G-10 | Nonce = 32 raw bytes, no prefix |
| G-11 | Role = 1 unsigned byte; 0 initiator, 1 responder |

Unchanged outer facts: `MAGIC||VER||TYPE||SEQ||LEN||PAYLOAD`; handshake VER=2, SEQ=0, plaintext; types 0x01 / 0x02 / 0x03; post-session = `SecureFrameCodec` only.

---

## 4. Byte-layout summary

**Outer handshake frame**

```
FILO | 0x02 | type | i64be 0 | i32be LEN | PAYLOAD
```

**HandshakeMessage / HELLO (0x01)**

```
u8  protocolVersion
u8  role
u32be idAlgorithmLen;     utf8[idAlgorithmLen]
u32be idPublicKeyLen;     spki[idPublicKeyLen]
u32be ephPublicKeyLen;    spki[ephPublicKeyLen]
u8[32] nonce
u32be keyAgreementAlgLen; utf8[keyAgreementAlgLen]
```

HELLO: `LEN = encoded_size`; role MUST be 0.

**HELLO_ACK (0x02)**

```
HandshakeMessage || u32be signatureLen || u8[signatureLen]
```

role MUST be 1. `LEN = encoded_size + 4 + signatureLen`.

**HANDSHAKE_FINISH (0x03)**

```
u32be signatureLen || u8[signatureLen]
```

`LEN = 4 + signatureLen`.

Signatures cover `FullHandshakeTranscript.encode()`, not the HELLO payload. That transcript is signing-only and is not a TCP body.

---

## 5. Implementation incompatibilities (Task 21F)

Specification is complete. These are **code gaps**, not encoding gaps:

1. `FrameCodec.readFrame` rejects VER != 1.
2. `FrameType` has no `HANDSHAKE_FINISH(0x03)`.
3. `HandshakeMessage` has no encode/decode; reuse `HandshakeTranscript.encode()` per G-01 plus a decoder and SignatureBlock framing.
4. `SocketConnection` still uses `FrameCodec` only (intended insertion point per 21E).
5. Production has no `SigningIdentity` adapter over `IdentityManager`; JVM tests inject `InMemorySigningIdentity`.
6. `FramePayloads.encodeHello` remains legacy VER=1 deviceName/sessionId; crypto handshake replaces that on VER=2 types 0x01–0x03.
7. After session, `SecureFrameCodec` must also forbid HANDSHAKE_FINISH.

Do not modify `AesGcm`, `CryptoSequence`, `SecureFrameCodec`, `Hkdf`, `SecureHandshake`, `SecureSession`, `EphemeralKeyPair`, or `IdentityManager` to satisfy this spec.

---

## 6. Evidence this task stayed specification-only

- HEAD still `bfa71ca`.
- No 21G production/test/Gradle edits.
- Untracked codec files (`SecureFrameCodec.kt` / `SecureFrameCodecTest.kt`) are from Task 21D, not 21G.
- 21G status string appears in the authoritative spec: `TASK21G_STATUS=SPECIFICATION_COMPLETE`.

F-20R-01 / F-20R-02 remain **UNVERIFIED**.

No tests were run (none required; no code change).

---

## 7. Next (not done)

Task 21F implementation, only after this spec: handshake IO at `SocketConnection`; `HANDSHAKE_FINISH` type; post-session `SecureFrameCodec`; no plaintext fallback. **Not started.**

TASK21G_STATUS=SPECIFICATION_COMPLETE
