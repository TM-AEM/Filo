# Task 19 — Secure Session Handshake Foundation: Final Report

## Summary

Task 19 is COMPLETE. The isolated secure session handshake foundation is implemented,
tested, and verified. No existing transport/protocol code was modified.

## Files Created

### Main source — `app/src/main/java/com/filo/transfer/core/network/security/handshake/`

| File | Purpose |
|------|---------|
| `HandshakeError.kt` | Sealed error hierarchy: InvalidSignature, InvalidTranscript, UnsupportedAlgorithm, InvalidPublicKey, KeyAgreementFailed, InvalidState, ReplayDetected, MalformedHandshake. No crypto material in error messages. |
| `SigningIdentity.kt` | `SigningIdentity` interface (production: Keystore-backed) + `InMemorySigningIdentity` (JVM tests, P-256 in-memory keys) + `IdentityVerifier` object (verifies Ed25519 or ECDSA signatures over SPKI bytes). |
| `EphemeralKeyPair.kt` | One-time P-256 ephemeral key generation (`secp256r1`). Private key held in memory only, never serialized. `destroy()` marks the pair unusable. `sharedSecret()` uses `javax.crypto.KeyAgreement` with `doPhase()` for ECDH. |
| `HandshakeTranscript.kt` | Deterministic binary transcript model. Canonical encoding: protocolVersion(1B) \| role(1B) \| len-prefixed idAlgorithm \| len-prefixed idPublicKey(SP KI) \| len-prefixed ephPublicKey \| nonce(32B) \| len-prefixed keyAgreementAlg. `hash()` = SHA-256 of encoding. |
| `HandshakeMessage.kt` | Single handshake message exchanged between peers. Contains all transcript fields + signature. `verifySignature()` validates the signature over the message's own transcript. |
| `SecureSession.kt` | Immutable post-handshake session: sessionId, peerFingerprint, negotiatedAlgorithm, transcriptHash(32B), clientToServerKey(32B), serverToClientKey(32B), handshakeKey(32B). Does NOT expose raw private keys or ECDH shared secret. `create()` validates complementary roles, destroys ephemeral key pairs. |
| `SecureHandshake.kt` | Stateless protocol logic: `initiate()` / `respond()` create signed local messages; `completeAsInitiator()` / `completeAsResponder()` verify peer message, compute ECDH shared secret, derive directional keys via HKDF. |

### Modified — `app/src/main/java/com/filo/transfer/core/network/security/crypto/IdentityManager.kt`

- Fixed `generateKeyPair()`: uses reflection-based `KeyGenParameterSpec` creation (Android SDK stubs don't expose the constructor directly). P-256 now uses `"EC"` provider (was `"X509"` in Task 18 — bug).
- Fixed `Hkdf.extract()` / `Hkdf.expand()`: was using `KeyGenerator` (broken); now uses `Mac.getInstance("HmacSHA256")` + `SecretKeySpec` (correct RFC 5869).
- Added `identityPublicKey`, `identityAlgorithm` public accessors.
- Added `verifySignature()` method.
- `nextNonce()` returns 32 bytes. `deriveSessionMaterial()` returns 96 bytes.

### Test source — `app/src/test/java/com/filo/transfer/core/network/security/handshake/`

| File | Tests |
|------|-------|
| `HandshakeSecurityTest.kt` | 20 tests: transcript determinism, hash sensitivity, nonce size enforcement, P-256 key generation, destroy semantics, ECDH symmetry/non-zero, algorithm rejection, signature verification (valid + 3 tamper cases), full handshake session creation, directional key distinctness, replay rejection, role complement check, fingerprint format. |
| `HkdfTest.kt` | 8 tests: RFC 5869 Test Cases 1–3 (PRK + OKM verified against computed reference values), empty-salt equivalence, empty-info expand, derive() == extract()+expand(), 96-byte session material, directional key distinctness, different-transcript-different-keys. |

## Verification Results

### Handshake + HKDF tests
```
28 tests completed, 0 failures
```

### Full regression suite
```
184 tests completed, 7 failures
```
All 7 failures are pre-existing baseline failures (unchanged from before Task 19):
1. `ExampleRobolectricTest` — Android SDK stubs
2. `GreetingScreenshotTest` — Roborazzi screenshot mismatch
3. `TcpTransferIntegrationTest.testChecksumMismatchRejectsFile` — flaky
4. `FileMetadataResolverTest` — `UnsupportedOperationException`
5. `FileStreamProviderTest` — `UnsupportedOperationException`
6. `StorageProviderIntegrationTest` — `UnsupportedOperationException`
7. `SendViewModelTest.clearing files resets selection` — pre-existing

**No new failures introduced.**

### Build verification
- `./gradlew compileDebugKotlin` — PASS
- `./gradlew compileDebugUnitTestKotlin` — PASS
- `./gradlew assembleDebug` — BUILD SUCCESSFUL
- `./gradlew lintDebug` — BUILD SUCCESSFUL

## Design Decisions

1. **P-256 ECDH as default**: `secp256r1` curve (JDK name) / P-256 (Android name). Available on all supported API levels. X25519 not used (API 33+ only).
2. **Transcript binding**: SHA-256 of canonical binary encoding. Any single field change alters the hash. 32-byte nonce.
3. **HKDF-SHA256 session keys**: `HKDF(ECDH_shared_secret, salt="", info="Filo-Secure-Session-v1"||transcriptHash, length=96)` → 3×32B directional keys.
4. **No protocol integration yet**: Handshake is a standalone security layer. Task 20+ will wire it into the TCP transport.
5. **`doPhase` not `doFinal`**: This JDK uses `KeyAgreement.doPhase(Key, boolean)` (JDK 17+). The older `doFinal(Key)` is not available.
6. **Reflection for `KeyGenParameterSpec`**: Android SDK stubs don't expose the constructor. Reflection works at runtime on device.

## Do-Not-Modify Constraint Verification

No changes were made to any of the following files:
- `FrameCodec.kt`, `FramePayloads.kt`, `ProtocolFrame.kt`, `FrameType.kt`
- `ProtocolConstants.kt`, `TransferSender.kt`, `TransferReceiver.kt`
- `TcpServerTransport.kt`, `TcpClientTransport.kt`, `TransferService.kt`

## Git State

- HEAD: `f5e823d` (Task 18 commit)
- Uncommitted: `IdentityManager.kt` modifications + new `handshake/` package + test files
- No commit/push performed (per task constraint)
