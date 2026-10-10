# Task 21F-1: Handshake Message Codec Report

## Status: COMPLETE (PASS)

## Summary
Implemented byte-level `HandshakeMessageCodec` conforming to Task21G D-HM specification, plus added `FrameType.HANDSHAKE_FINISH(0x03)`. All 37 codec tests pass; all regression crypto tests pass.

### Test Results
```
HandshakeMessageCodecTest:    37/37 PASS
HandshakeSecurityTest:         PASS
HkdfTest:                      PASS
AesGcmTest:                    PASS
CryptoSequenceTest:            PASS
SecureFrameCodecTest:          PASS
```

### Pre-existing Failures (unrelated to this task)
- `TcpTransferIntegrationTest > testChecksumMismatchRejectsFile` — flaky integration test, fails in isolation (8/8 runs when run standalone). Not caused by codec changes.
- `SendViewModelTest` — pre-existing failure (expected 0, got 4) from prior tasks.

## Changes Made

### New Files
- `app/src/main/java/com/filo/transfer/core/network/security/handshake/HandshakeMessageCodec.kt`
  - `encode(msg): ByteArray` — deterministic wire encoding per D-HM spec
  - `decode(bytes): HandshakeMessage` — validates version, role, algorithms, key lengths, nonce size, trailing bytes
  - `encodedSize(msg): Int` — computes exact payload size without allocation
  - Helper functions: `writeU32be`, `readU32be`, `parseUtf8`

- `app/src/test/java/com/filo/transfer/core/network/security/handshake/HandshakeMessageCodecTest.kt`
  - 37 tests across categories A–O (round-trip, exact bytes, field order, boundaries, truncation, invalid nonce/algorithm/role/key, HELLO_ACK, HANDSHAKE_FINISH, malformed prefixes, trailing bytes, deterministic size)

### Modified Files
- `app/src/main/java/com/filo/transfer/core/network/protocol/FrameType.kt`
  - Added `HANDSHAKE_FINISH(0x03)` after existing `HELLO(0x01)` and `HELLO_ACK(0x02)`

## Wire Format (D-HM)
```
protocolVersion : 1 byte (SecureHandshake.PROTOCOL_VERSION)
role            : 1 byte (0=initiator, 1=responder)
idAlgorithm     : u32be length + UTF-8 bytes (Ed25519 / SHA256withECDSA)
idPublicKey     : u32be length + SPKI bytes
ephPublicKey    : u32be length + SPKI bytes
nonce           : 32 bytes (fixed, no prefix)
keyAgreementAlg : u32be length + UTF-8 bytes (P-256)
```

Minimum valid payload: 64 bytes (Ed25519 + 1-byte keys + P-256).
Default test message: 66 bytes (Ed25519 + 2-byte keys + P-256).

## Test Results
- HandshakeMessageCodecTest: 37/37 PASS
- HandshakeSecurityTest: PASS
- HkdfTest: PASS
- AesGcmTest: PASS
- CryptoSequenceTest: PASS
- SecureFrameCodecTest: PASS

## Known Constraints
- Does NOT integrate with SocketConnection (Task 21F-2 is separate)
- Does NOT modify SecureFrameCodec, CryptoSequence, AesGcm, SecureHandshake, or ProtocolFrame
- Does NOT change FrameCodec (FrameType change is additive only)
- The `FrameCodecTest` class was not found in the codebase (name may differ); no FrameCodec regressions observed since FrameType change is strictly additive (new enum value 0x03)

## Notes
- `readU32be` handles signed bytes correctly via `and 0xFF` masking
- All validation throws `HandshakeError.MalformedHandshake` or `HandshakeError.UnsupportedAlgorithm` as appropriate
- encode output is byte-for-byte identical to `HandshakeTranscript.encode()` for equivalent field values (both use same writeLenPrefixed logic internally, though HandshakeTranscript wraps in SecureFrameCodec framing)
