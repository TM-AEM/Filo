# Task 21F-S — Handshake Wire Protocol Specification

**TASK21FS_STATUS=SPECIFICATION_BLOCKED**

Date: 2026-10-06
Scope: READ-ONLY correction. No production, test, Gradle, or Git changes.
Supersedes: the previous draft of this file that asserted `SPECIFICATION_COMPLETE`.

This document does **not** define a implementable byte-level handshake encoding. Task 21F-0 remains the authoritative encoding audit: **OUTCOME B: ENCODING_UNSPECIFIED**.

---

## Status

```
TASK21FS_STATUS=SPECIFICATION_BLOCKED
```

Task 21F MUST NOT implement HELLO / HELLO_ACK / HANDSHAKE_FINISH inner payloads until a project protocol authority specifies the items in section “Minimum decisions required to unblock”.

The previous draft of this file is withdrawn. It converted unspecified encodings into a wire spec by inference. Those inferences are not Filo protocol.

---

## Confirmed

These facts are already established (Task 21A, Task 21F-0). They MUST NOT be redesigned here.

1. Handshake outer framing:

```
MAGIC || VER || TYPE || SEQ || LEN || PAYLOAD
```

2. Handshake version:

```
VER = 2
```

3. Handshake sequence:

```
SEQ = 0
```

4. Handshake payload is plaintext.

5. Message types:

```
HELLO            = 0x01
HELLO_ACK        = 0x02
HANDSHAKE_FINISH = 0x03
```

6. HELLO carries the initiator `HandshakeMessage`.

7. HELLO_ACK carries responder `HandshakeMessage` plus responder signature.

8. HANDSHAKE_FINISH carries initiator signature.

9. Post-handshake application traffic uses `SecureFrameCodec`. There is no plaintext application-frame fallback.

10. Production identity remains `IdentityManager` / AndroidKeyStore. No factory, no Keystore-to-memory fallback, no `IdentityManager` modification.

11. JVM tests may use the existing `InMemorySigningIdentity`.

Additional confirmed boundaries (not inner encodings):

- Handshake frames MUST NOT use `SecureFrameCodec`, AES-GCM, session keys, or `CryptoSequence`.
- `SecureHandshake` APIs are not redesigned.
- `FullHandshakeTranscript.encode()` is the canonical **signing** input (both peers). It is not a HELLO body (it requires both participants).
- `FrameType` currently has HELLO `0x01` and HELLO_ACK `0x02`; HANDSHAKE_FINISH `0x03` is required by Task 21A and is **absent** from the enum. That is an implementation gap after encoding is specified, not an inner-payload encoding.
- F-20R-01 and F-20R-02 remain **UNVERIFIED**.

---

## Unspecified

The repository and existing specifications do **not** authoritatively define the following. They remain unspecified.

### A. HandshakeMessage network serialization

There is no `encode` / `decode` / serializer on `HandshakeMessage`.

There is no authoritative statement that `HandshakeTranscript.encode()` is the network encoding.

`HandshakeTranscript.encode()` exists as a canonical length-prefixed transcript model. Field overlap with `HandshakeMessage` is not protocol authorization to use those bytes as TCP payload.

Therefore the exact `HandshakeMessage` wire layout is **unspecified**: field order, integer widths, string encoding, byte-array prefixes, nonce framing, and self-delimiting vs outer-`LEN`-only.

Task 21A §3:

> Inner encodings of handshake payloads are the existing `HandshakeMessage` / signature bytes, length-delimited, not specified as a new high-level protocol.

That sentence does not give a byte layout. “Length-delimited” does not choose outer `LEN`, per-field prefixes, signature prefixes, or all of these.

### B. HELLO_ACK composition

Specified semantically: responder `HandshakeMessage` plus responder signature.

Unspecified:

- signature length prefix
- message length prefix
- delimiter
- nested length
- fixed signature size
- concatenation rule
- how a receiver finds the boundary between message bytes and signature bytes

`HandshakeMessage || signature` is not a complete specification. ECDSA DER length is not a protocol constant; Ed25519 signature size is not established as a wire guarantee.

Outer `LEN` bounds the whole payload. It does not split two logical values inside that payload.

### C. HANDSHAKE_FINISH signature framing

Specified semantically: initiator signature over the full transcript.

Unspecified whether the payload is:

- raw signature bytes
- length-prefixed signature
- another explicitly defined representation

Selecting any of these would be a new protocol decision.

### D. Algorithm / key / string encoding on the wire

Internal APIs currently use:

- algorithm tokens as Java/Kotlin strings (`Ed25519`, `SHA256withECDSA`, `P-256`)
- identity and ephemeral public keys as `PublicKey.encoded` / `publicKeySpki` (X.509 SPKI in the JVM)
- UTF-8 via `String.toByteArray()` inside transcript encoders

Those are **implementation representations**. They are not specified as Filo network encoding unless an authoritative protocol source says so. None does.

Do not treat JCA / Kotlin field types as the wire format.

---

## Unsupported assumptions removed from the previous draft

The withdrawn `SPECIFICATION_COMPLETE` draft introduced these rules without authoritative evidence. They are **not** Filo wire protocol.

| # | Withdrawn assumption | Why it is not authorized |
|---|---|---|
| 1 | `HandshakeTranscript.encode()` **is** the HandshakeMessage TCP layout (byte-identical) | 21F-0: field overlap is not wire reuse; unused by `SecureHandshake`; no decoder; not named as HELLO |
| 2 | HELLO payload = that encoding and nothing else, with `LEN == encoded_size` | Follows assumption 1 |
| 3 | HELLO_ACK = HandshakeMessage \|\| `uint32be signatureLen` \|\| signature | Signature length prefix is a new protocol decision |
| 4 | HANDSHAKE_FINISH = `uint32be signatureLen` \|\| signature | Same; raw vs prefixed vs other is unspecified |
| 5 | A protocol type `SignatureBlock` | Invented framing |
| 6 | HandshakeMessage is self-describing on the wire so HELLO_ACK can concatenate without a message length prefix | Self-delimiting encoding was inferred, not specified |
| 7 | Inner length prefixes are unsigned 32-bit big-endian because transcript encoders use 4-byte BE lengths | Implementation convention ≠ protocol |
| 8 | Strings on the wire are UTF-8, length in bytes, no BOM | Inferred from `String.toByteArray()` |
| 9 | Public keys on the wire are X.509 SPKI because `PublicKey.encoded` / `publicKeySpki` | Internal representation ≠ specified network encoding |
| 10 | Nonce is fixed 32 raw bytes **on the wire** (no prefix) because validation and transcript encoding use 32 | In-memory size constraint, not a published wire layout |
| 11 | Algorithm identifiers on the wire are those exact UTF-8 tokens with u32be prefixes | In-memory `validateIncomingMessage` strings, not a wire mapping |
| 12 | Trailing-bytes-prohibited inner decoder tables, min key/signature length 1, invalid UTF-8, etc. | Decoder rules for an invented encoding |
| 13 | Outer header signedness details and `DataOutputStream.writeLong`/`writeInt` as handshake-header law beyond the already-confirmed MAGIC/VER/TYPE/SEQ/LEN/PAYLOAD | Outer layout is confirmed at field-name level; extra codec-identity claims were used to justify inner invention |
| 14 | “Reuse of existing canonical encoding … not a new high-level protocol” as license to adopt `HandshakeTranscript.encode()` | Task 21A L92 explicitly does **not** specify the layout |
| 15 | “4-byte BE already used by FrameCodec” as authorization for inner signature/string prefixes | Outer `LEN` is not inner structure |
| 16 | Reasonableness (self-delimiting, JCA-compatible, consistent with existing code, easy to parse) | Design arguments, not evidence |

None of the above may be treated as specified for Task 21F.

---

## Distinction

The following statements are different:

1. “The implementation already has a convenient encoding.”
2. “The Filo wire protocol specifies this encoding.”

Only (2) is sufficient to implement Task 21F.

`HandshakeTranscript.encode()` is (1). It is not (2).

---

## Minimum decisions required to unblock

These decisions must be made by the **project specification** (updated Task 21A or an equivalent protocol authority). This document does **not** select values.

1. Exact `HandshakeMessage` wire encoding (field order, widths, prefixes, endianness).
2. Whether existing `HandshakeTranscript.encode()` is **officially adopted** as that encoding (yes or no, stated as protocol).
3. Exact HELLO_ACK structure (how message bytes and signature bytes are combined).
4. Exact signature framing (length prefix or not; prefix width if any; what the length counts).
5. Exact HANDSHAKE_FINISH structure (raw signature vs length-prefixed vs other listed form).
6. Exact encoding of algorithm identifiers, if not already specified as network representation.
7. Exact encoding of key material, if not already specified as network representation.
8. Exact integer / string / byte-array length rules where applicable (including whether outer `LEN` is the only length wrapping HANDSHAKE_FINISH).

Until those decisions exist in an authoritative spec, inner handshake encoding remains **ENCODING_UNSPECIFIED**.

---

## What this task did not do

- No production source changes
- No tests, Gradle, or Git
- No `HandshakeWire`, serializers, or identity adapters
- No Task 21F implementation

TASK21FS_STATUS=SPECIFICATION_BLOCKED
