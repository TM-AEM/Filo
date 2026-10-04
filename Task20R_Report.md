# TASK 20R — SECURITY AUDIT CORRECTION REPORT

## 1. Executive Summary

**CONDITIONALLY READY** for Task 21.

Task 20's P2 findings (F-P2-01 replay, F-P2-02 key direction) are **overstated** and reclassified below. However, Task 20 **missed** a more significant P2: the production identity algorithm (Ed25519) is likely unavailable in AndroidKeyStore on the project's minSdk 30 range (API 30-33), with no runtime fallback.

Task 21 can proceed. The Ed25519 availability issue should be verified via an Android instrumented test before production deployment, but it does not block transport encryption work.

## 2. Task 20 Corrections

| # | Task 20 Statement | Correction |
|---|-------------------|------------|
| 1 | "No P0 or P1 findings" | **CORRECT** — confirmed. |
| 2 | F-P2-01 "No replay protection" as P2 | **OVERSTATED.** The handshake contains fresh ephemeral keys and nonces per session. Replaying a captured message does NOT recreate session keys. Reclassified to **INFO/DEFERRED**. |
| 3 | F-P2-02 "Key direction is application convention" as P2 | **OVERSTATED.** keyA/keyB are directionally distinct via HKDF. The TX/RX mapping is a standard protocol convention. Reclassified to **INFO**. |
| 4 | "Identity algorithm: Ed25519 (production)" listed as PASS | **INCOMPLETE.** Ed25519 in AndroidKeyStore was added in API 34. Project minSdk=30. On API 30-33, key generation will likely fail. **UNVERIFIED** — requires instrumented test. |
| 5 | "P-256 via platform JCA available since API 24" | **CORRECT.** |
| 6 | F-P3-01 "Peer EC key not validated for P-256 curve" | **CORRECT** as P3. |
| 7 | "bindingKey for future protocol binding" | **CORRECT** — reserved/deferred. No active code consumes it. |
| 8 | `createKeyGenSpec` reflection | **NOT REVIEWED in Task 20.** May not match standard constructor. See F-20R-02. |

## 3. Production Identity

| Question | Answer |
|----------|--------|
| Default algorithm | `"Ed25519"` (line 23) |
| Is default P-256? | **NO** — default is Ed25519. P-256 is supported but never activated by any code path. |
| KeyPairGenerator | `KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")` (line 79) |
| Ed25519 in AndroidKeyStore on minSdk 30 | **UNVERIFIED.** Per Android docs, API 34+. Expected to fail on API 30-33. No fallback. |
| Runtime fallback | **NONE.** `generateKeyPair()` has no try-catch. |
| `getOrCreateIdentity()` preserves existing | **YES** |
| `loadPublicKey()` consistent | **YES** |
| `sign()` uses `algorithm` field | **YES** |
| `verifySignature()` supports production algos | **PARTIAL.** `Signature.getInstance("P-256")` is not a valid JCA name. Method not used in handshake. |
| Algorithm persisted? | **NO.** `@Volatile private var`, resets to "Ed25519" on restart. |
| KeyGenParameterSpec correct? | **UNVERIFIED.** Reflection may not match standard constructor. |
| EC parameters for P-256? | **NO.** Always passes `null` for AlgorithmParameterSpec. |

## 4. Handshake State Machine

```
INITIATOR:
  initiate(identity)
    → EphemeralKeyPair.generateP256()
    → SecureRandomWrapper.nextNonce()  // 32 bytes
    → HandshakeMessage(role=0, ...)
    → returns HandshakeInitiatorState
    → [sends msg]

RESPONDER:
  respond(identity, initiatorMsg)
    → validateIncomingMessage(initiatorMsg)
    → EphemeralKeyPair.generateP256()
    → SecureRandomWrapper.nextNonce()
    → localMsg = HandshakeMessage(role=1, ...)
    → FullHandshakeTranscript(1, "P-256", init.toData(), local.toData())
    → sig = identity.signTranscript(transcript.encode())
    → returns HandshakeResponderState
    → [sends localMsg + sig]

INITIATOR:
  completeAsInitiator(state, peerMsg, peerSig)
    → validateIncomingMessage(peerMsg)
    → FullHandshakeTranscript(1, "P-256", state.msg.toData(), peerMsg.toData())
    → verify peerSig against peerMsg.idPublicKey
    → sharedSecret = ECDH(state.eph.privateKey, peerMsg.ephPub)
    → material = Hkdf.deriveSessionMaterial(sharedSecret, transcriptHash)
    → keyA=mat[0..31], keyB=mat[32..63], binding=mat[64..95]
    → state.ephemeral.destroy()
    → ourSig = identity.signTranscript(transcript.encode())
    → returns InitiatorCompletion(session, ourSig)
    → [sends ourSig]

RESPONDER:
  completeAsResponder(state, peerMsg, peerSig)
    → validateIncomingMessage(peerMsg)
    → FullHandshakeTranscript(1, "P-256", peerMsg.toData(), state.msg.toData())
    → verify peerSig against peerMsg.idPublicKey
    → sharedSecret = ECDH(state.eph.privateKey, peerMsg.ephPub)
    → material = Hkdf.deriveSessionMaterial(sharedSecret, transcriptHash)
    → state.ephemeral.destroy()
    → returns SecureSession
```

Both sides derive identical: transcriptHash, sharedSecret, keyA, keyB, bindingKey. **Verified correct.**

## 5. Replay Analysis

| Scenario | Exploitable? | Reason |
|----------|:---:|--------|
| A: Replay old init msg to fresh responder | **NO** | Fresh responder keys → different transcript → no session without initiator ECDH |
| B: Replay old resp msg to fresh initiator | **NO** | Different initiator ephemeral+nonce → transcript mismatch → sig verification fails |
| C: Replay both msgs + both sigs | **THEORETICAL** | Requires both parties to still hold ephemeral private keys (before GC) |
| D: Replay entire old handshake on new connection | **THEORETICAL** | Same as C |
| E: Replay init msg, responder generates fresh keys | **NO** | Same as A |
| F: Replay old signature against new transcript | **NO** | SHA-256 collision resistance |
| G: Reuse old ephemeral key | **NO** | Fresh nonces → different transcript hash → different HKDF output |
| H: Reuse old nonce | **NO** | Fresh ephemeral → different ECDH → different keys |

**F-P2-01 Reclassification: INFO/DEFERRED.** No practical replay vulnerability.

## 6. Key Direction Analysis

| Category | Status |
|----------|--------|
| Cryptographic derivation correctness | **CORRECT** — keyA != keyB |
| Transport-layer direction mapping | **UNDEFINED** — Task 21 spec decision |
| Role confusion resistance | **ADEQUATE** — role in transcript prevents swapping |
| Application-layer convention | **YES** — standard pattern (analogous to TLS) |

**F-P2-02 Reclassification: INFO.** Design contract for transport spec, not a vulnerability.

## 7. Transcript and Signature Analysis

All security-critical fields are bound: protocol version, roles, key agreement algorithm, identity algorithms/keys, ephemeral keys, nonces. Deterministic 4-byte length-prefixed encoding. Roles enforced at construction. Signatures cover complete canonical transcript. **No findings.**

## 8. ECDH Analysis

P-256 (secp256r1) confirmed. Private key in-memory only. Fresh per handshake. `destroy()` flag prevents reuse. Peer key deserialized via X509. Malformed keys fail closed. X25519 not attempted. minSdk 30 compatible.

**F-20R-03 (P3)**: Peer EC key not validated for P-256 curve specifically. Mitigated by identity signature.

## 9. HKDF Analysis

RFC 5869-compliant. HMAC-SHA256. Official A.1/A.2/A.3 vectors pass. Empty salt = 32 zero bytes. Max output 8160 bytes. Transcript hash in info. No mutable buffer reuse. No truncation bugs. **No findings.**

## 10. Session Material Analysis

| Bytes | Content | Status |
|-------|---------|--------|
| 0..31 | keyA | Future AEAD TX/RX key |
| 32..63 | keyB | Future AEAD TX/RX key |
| 64..95 | bindingKey | **UNUSED** — reserved/deferred |

Javadoc mismatch: "clientToServerKey" vs field name "keyA". **P3.**

## 11. Nonce / IV Analysis

- 32-byte handshake nonce: transcript binding only. NOT an AEAD IV.
- 12-byte `nextIv()`: correct size for AES-256-GCM. Counter-based strategy is Task 21 decision.
- No conflation between the two.

## 12. Failure-Path Analysis

All failure paths fail closed. No fail-open. `printStackTrace()` logs operational context, no key material. **P3 only.**

## 13. Test Coverage

Actual: HkdfTest=9, HandshakeSecurityTest=25. Total=34.

Missing: destroy()/checkNotDestroyed(), expand() boundaries, validateIncomingMessage() rejections, completeAsResponder() negative cases, Android Keystore (instrumented only).

## 14. Transport Integration Boundary

- Plaintext begins at `SocketConnection.sendFrame()` → raw TCP
- Handshake inserts between connect/accept and first HELLO
- FrameCodec can remain unchanged (AEAD wraps SocketConnection)
- Frame headers (18B) can be AAD
- `ProtocolFrame.sequence` (8B) can participate in GCM IV
- New connections require new handshakes (ephemeral ECDH)

## 15. Corrected Security Findings

| ID | Severity | Finding | Corrected Status | Recommendation |
|----|----------|---------|-----------------|----------------|
| F-20R-01 | P2 | Ed25519 in AndroidKeyStore requires API 34. minSdk=30. No fallback. | NEW (missed by Task 20). UNVERIFIED. | Instrumented test. Switch to P-256 or raise minSdk. |
| F-20R-02 | P2 | `createKeyGenSpec` reflection may not match standard constructor. | NEW. UNVERIFIED. | Replace with KeyGenParameterSpec.Builder (API 28+). |
| F-20R-03 | P3 | Peer EC key not validated for P-256 curve. | CONFIRMED. | Add curve check. |
| F-20R-04 | P3 | `algorithm` field not persisted. Resets on restart. | NEW. Theoretical. | Persist alongside Keystore key. |
| F-20R-05 | P3 | `verifySignature()` with "P-256" would fail (invalid JCA name). | NEW. Not used in handshake. | Fix or remove. |
| F-20R-06 | INFO | Key direction is application convention. | RECLASSIFIED from P2. | Document in Task 21 spec. |
| F-20R-07 | INFO | `bindingKey` unused. Reserved. | CONFIRMED. | No action. |
| F-20R-08 | INFO | Complete handshake replay theoretical. | RECLASSIFIED from P2. | No action for Task 21. |
| F-20R-09 | P3 | Javadoc "clientToServer" mismatches keyA/keyB. | CONFIRMED. | Update comments. |
| F-20R-10 | P3 | `printStackTrace()` in IdentityManager. | CONFIRMED. | Structured logging. |
| F-20R-11 | P3 | Missing edge-case tests. | CONFIRMED. | Add in Task 21. |
| F-20R-12 | P3 | 12-byte random GCM IV collision risk. | CONFIRMED. | Counter-based IV in Task 21. |

## 16. Task 21 Requirements

1. Verify Ed25519 AndroidKeyStore availability (F-20R-01). Instrumented test.
2. Fix `createKeyGenSpec` reflection (F-20R-02). Use Builder API if broken.
3. Implement AES-256-GCM AEAD as decorator around SocketConnection.
4. Document key direction: keyA=initiator-TX=responder-RX, keyB=responder-TX=initiator-RX.
5. Define GCM IV strategy (counter XOR random nonce).
6. Use frame headers as AAD.
7. Use `bindingKey` for session-to-transport binding.
8. Add missing edge-case tests.

## 17. Deferred Hardening

| ID | Item | Priority |
|----|------|----------|
| F-20R-03 | P-256 curve validation | Low |
| F-20R-04 | Persist algorithm field | Low |
| F-20R-05 | Fix verifySignature() name | Low |
| F-20R-09 | Update Javadoc | Low |
| F-20R-10 | Structured logging | Low |
| F-20R-12 | Counter-based GCM IV | Task 21 |

## 18. Final Verdict

**Task 21 can begin.**

The cryptographic foundation is correct. Task 20's P2 findings were overstated. Two new P2 findings (F-20R-01, F-20R-02) are UNVERIFIED and require Android instrumented testing — they do not block transport encryption design but must be resolved before production deployment.
