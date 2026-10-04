# Task 20 — Pre-Transport Secure Session Cryptography Audit

## 1. Executive Summary

**CONDITIONALLY READY** for transport integration.

The cryptographic foundation (Tasks 19, 19R, 19R.1) is structurally sound: P-256 ECDH, canonical transcript with role binding, identity-authenticated signatures, and RFC 5869-compliant HKDF. No P0 or P1 findings.

Two P2 findings require resolution before Task 21:
- **F-P2-01**: No replay protection — a captured handshake can be replayed to derive identical session keys.
- **F-P2-02**: Key direction is application-layer convention, not cryptographically enforced via role-bound HKDF derivation.

Both are fixable within Task 21 without redesigning the existing handshake.

## 2. Files Audited

| File | Role |
|------|------|
| `IdentityManager.kt` | Persistent identity (Android Keystore), HKDF, SecureRandomWrapper |
| `EphemeralKeyPair.kt` | P-256 ephemeral key generation + ECDH |
| `SigningIdentity.kt` | Signing interface + InMemorySigningIdentity + IdentityVerifier |
| `HandshakeMessage.kt` | Per-peer handshake data |
| `FullHandshakeTranscript.kt` | Canonical transcript encoding + SHA-256 |
| `SecureHandshake.kt` | Handshake protocol (initiate/respond/complete) |
| `SecureSession.kt` | Post-handshake session object |
| `HandshakeError.kt` | Error hierarchy |
| `HkdfTest.kt` | 9 tests: 3 RFC vectors + 6 project tests |
| `HandshakeSecurityTest.kt` | 25 tests: identity, ECDH, transcript, auth, session |
| `FrameCodec.kt` | Binary frame encode/decode |
| `FramePayloads.kt` | Payload serialization |
| `ProtocolFrame.kt` | Frame data class |
| `FrameType.kt` | Frame type enum |
| `ProtocolConstants.kt` | Protocol constants |
| `TransferSender.kt` | Send engine |
| `TransferReceiver.kt` | Receive engine |
| `TcpServerTransport.kt` | Server TCP |
| `TcpClientTransport.kt` | Client TCP |
| `SocketConnection.kt` | Socket wrapper |
| `TransferService.kt` | Foreground service |

## 3. Cryptographic Data Flow

```
Identity
  IdentityManager.getOrCreateIdentity()
    -> P-256 KeyPair in Android Keystore (alias: "filo_identity_key")
    -> public key: X.509 SPKI bytes
    -> algorithm: "Ed25519" (prod) / "SHA256withECDSA" (test)
    -> fingerprint: SHA-256(SPKI) -> 64-char lowercase hex

Ephemeral ECDH
  EphemeralKeyPair.generateP256() -- fresh per handshake
    -> private key: in-memory only
    -> public key: SPKI bytes -> HandshakeMessage
  EphemeralKeyPair.sharedSecret(myPrivate, peerSpki)
    -> ECDH shared secret: 32 bytes

Nonces
  SecureRandomWrapper.nextNonce() -> 32 random bytes
  Bound in transcript; changes transcript hash

Canonical Transcript
  FullHandshakeTranscript(version=1, keyAgreementAlg="P-256",
    initiator=HandshakeData, responder=HandshakeData)
  encode() -> deterministic bytes:
    [1B ver][4B len + keyAgreementAlg]
    [4B len + idAlg][4B len + idPub][4B len + ephPub][32B nonce] (init)
    [4B len + idAlg][4B len + idPub][4B len + ephPub][32B nonce] (resp)
  hash() -> SHA-256(encode()) -> 32 bytes

Signatures
  identity.signTranscript(fullTranscript.encode())
  Verified via: IdentityVerifier.verify(peerSpki, alg, encode(), sig)
  Signature NOT included in signed data.

HKDF
  Hkdf.deriveSessionMaterial(sharedSecret, transcriptHash):
    IKM  = sharedSecret (32B)
    salt = empty -> 32 zero bytes
    info = "Filo-Secure-Session-v1" + transcriptHash (56B)
    L    = 96
  Split: keyA=[0..31]  keyB=[32..63]  bindingKey=[64..95]

Session
  SecureSession(sessionId=UUID, peerFingerprint=SHA256(peerSpki),
    keyAgreementAlg="P-256", transcriptHash=32B,
    keyA=32B, keyB=32B, bindingKey=32B)
  Convention: keyA = initiator TX = responder RX
              keyB = responder TX = initiator RX
```

## 4. Identity Findings

| Item | Finding |
|------|---------|
| Generated only when absent | PASS |
| Existing key retrieved from Keystore | PASS |
| Private key never leaves Keystore | PASS |
| Public key retrieval consistent | PASS |
| Repeated calls return same identity | PASS |
| Algorithm | Ed25519 (prod) / SHA256withECDSA (test) |
| Fingerprint | SHA-256 of SPKI, deterministic, no private-key material |

**No findings.**

## 5. ECDH Findings

| Item | Finding |
|------|---------|
| P-256 actually used | PASS |
| Private key not exported | PASS |
| Fresh key per handshake | PASS |
| No accidental reuse | PASS (destroy + checkNotDestroyed) |
| Peer key validated before ECDH | PASS (X509EncodedKeySpec deserialization) |
| Invalid keys fail closed | PASS |
| X25519 not attempted | PASS |
| minSdk 30 compatible | PASS |

**F-P3-01** (P3): Peer public key not validated for P-256 curve specifically. `KeyAgreement.getInstance("ECDH")` accepts any EC key. Risk is low since the key is authenticated by the identity signature.

## 6. Transcript Findings

All security-critical fields are bound: protocol version, roles, key agreement algorithm, identity algorithms, identity public keys, ephemeral public keys, nonces. Deterministic encoding with 4-byte big-endian length prefixes. Role enforced at construction. **No findings.**

## 7. Signature Findings

Both peers sign the same canonical transcript. Signature not included in signed data. Verified against peer identity public key. Fails closed on any exception. **No findings.**

## 8. HKDF Findings

RFC 5869-compliant. Official A.1/A.2/A.3 vectors pass. Empty salt correctly handled. Output length validated. Block counter correct. No mutable buffer reuse. **No findings.**

## 9. Session-Key Findings

| Property | Status |
|----------|--------|
| Transcript binding in HKDF | YES |
| Changing ECDH secret changes output | YES |
| Changing transcript hash changes output | YES |
| Changing roles changes output | NO (same transcript hash) |
| Changing nonce changes output | YES |
| Output split deterministic | YES (32/32/32) |

**F-P2-02** (P2): Key direction is application-layer convention, not cryptographically enforced.
**F-P3-02** (P3): Doc comment "clientToServer" doesn't match field names keyA/keyB.

## 10. Nonce / IV Findings

**F-P3-03** (P3): 12-byte random GCM IV has collision risk at ~2^48 messages. Plan counter-based IV for production transport.

## 11. Replay / Freshness Findings

| Attack | Status |
|--------|--------|
| Replay old handshake | NOT PREVENTED |
| Replay entire old handshake | NOT PREVENTED |
| Reflect messages | PREVENTED (role binding) |
| Swap roles | PREVENTED (init block) |
| Reuse old ephemeral key | NOT PREVENTED |
| Reuse old nonce | NOT PREVENTED |
| Replay old signature | NOT PREVENTED |

**F-P2-01** (P2): No replay protection. Mitigations: per-connection session nonce, transcript-hash dedup, or require fresh handshake per connection.

## 12. Failure-Path Findings

All failure paths fail closed. No secret material in error messages. No private key in logs. `e.printStackTrace()` in IdentityManager is a P3 hardening issue (F-P3-04).

## 13. Test Coverage Findings

| Category | Covered | Missing |
|----------|---------|---------|
| Identity | 5 tests | Keystore retrieval (Android-only) |
| ECDH | 3 tests | Invalid peer key, destroy()/checkNotDestroyed() |
| Transcript | 7 tests | Complete |
| Signatures | 5 tests | Modified signature bytes, empty signature |
| HKDF | 9 tests | expand() boundary lengths |
| Session | 5 tests | SecureSession field validation |
| Replay | NONE | No replay detection to test |

**F-P3-05** (P3): Missing edge-case tests for destroy(), expand() boundaries, validateIncomingMessage() rejection.

## 14. Transport Integration Findings

| Question | Answer |
|----------|--------|
| Where plaintext begins | SocketConnection.sendFrame/receiveFrame -> raw TCP |
| Where handshake inserts | Between connect/accept and first HELLO frame |
| Where session state lives | TransferSender/TransferReceiver hold SecureSession |
| Where sequence numbers | ProtocolFrame.sequence (8B) + AEAD counter |
| Where AEAD auth | Wrap SocketConnection or modify FrameCodec |
| Resume interaction | RESUME_REQUEST/RESPONSE must be encrypted; session persistence needed |

**F-P3-06** (P3): Design AEAD wrapper/decorator around SocketConnection.

## 15. Security Findings

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| F-P2-01 | P2 | No replay protection | Add per-connection nonce to transcript; reject duplicate transcript hashes |
| F-P2-02 | P2 | Key direction is convention, not cryptographic | Add role to HKDF info or document convention in transport spec |
| F-P3-01 | P3 | No P-256 curve validation on peer key | Add curve name check before ECDH |
| F-P3-02 | P3 | Javadoc "clientToServer" mismatches keyA/keyB | Update comments |
| F-P3-03 | P3 | Random 12B GCM IV collision risk at scale | Plan counter-based IV |
| F-P3-04 | P3 | printStackTrace() in IdentityManager | Replace with structured logging |
| F-P3-05 | P3 | Missing edge-case tests | Add in Task 21 or later |
| F-P3-06 | P3 | SocketConnection needs AEAD wrapper | Design decorator for Task 21 |

## 16. Required Changes Before Task 21

1. **F-P2-01**: Add replay protection (session nonce in transcript or transcript-hash dedup).
2. **F-P2-02**: Resolve key direction (role in HKDF info, or documented convention).

## 17. Deferred Improvements

| ID | Item | When |
|----|------|------|
| F-P3-01 | P-256 curve validation | Task 21 or later |
| F-P3-02 | Fix Javadoc | Any time |
| F-P3-03 | Counter-based GCM IV | Task 21 |
| F-P3-04 | Structured logging | Code quality pass |
| F-P3-05 | Edge-case tests | Task 21 or later |
| F-P3-06 | AEAD wrapper design | Task 21 |

## 18. Final Verdict

**Task 21 can begin.**

The cryptographic foundation is sound. No P0 or P1 issues. The two P2 findings are additive fixes that can be implemented within Task 21's transport integration without breaking the existing handshake.

**Recommendation**: Begin Task 21 by addressing replay protection (add session nonce to transcript), then implement AES-256-GCM AEAD using keyA/keyB with documented direction convention. Design the AEAD layer as a decorator around SocketConnection to keep FrameCodec and ProtocolFrame unchanged.
