# Task 21D — SecureFrameCodec Report

**Status**: COMPLETE
**No Git operations were performed** — all changes are untracked working-tree files only.

## Files Changed

| File | Action |
|------|--------|
| `app/src/main/java/com/filo/transfer/core/network/security/crypto/SecureFrameCodec.kt` | NEW (spec-aligned in this pass) |
| `app/src/test/java/com/filo/transfer/core/network/security/crypto/SecureFrameCodecTest.kt` | NEW (61 tests) |
| `Task21D_SecureFrameCodec_Report.md` | NEW (this report) |

No other source file was touched. In particular `AesGcm.kt`, `CryptoSequence.kt`, `IdentityManager.kt`,
`Hkdf.kt`, `SecureHandshake.kt`, `FrameCodec.kt`, `ProtocolFrame.kt`, `FramePayloads.kt`,
`FrameType.kt`, `ProtocolConstants.kt`, `Sender.kt`, `Receiver.kt`, `Transport.kt`, and
`Resume.kt`/`Reconnect.kt` are all unchanged (verified via `git status --porcelain`: none of them
appear as modified).

## Spec Alignment (Task 21A authoritative wire spec)

The final codec matches the Task21A specification exactly:

- **Header layout**: `MAGIC(4) || VER(1) || TYPE(1) || CRYPTO_SEQ(8 BE) || CT_LEN(4 BE) || CT||TAG(16)`.
  `VER` is a single byte with value `2` (`"VER(2)"` appearing in some drafts was a misread of the
  value, not the size — confirmed against `Task21A_SecureFrame_Specification.md`, which is the
  authoritative source). Total header = 18 bytes.
- **AAD** = `VER(1)||TYPE(1)||CRYPTO_SEQ(8)||CT_LEN(4)` = 14 bytes; MAGIC intentionally excluded.
- **CT_LEN semantics**: CT_LEN is the length of the whole `ciphertext || tag` blob,
  `16 <= CT_LEN <= MAX_FRAME_PAYLOAD` (262144). Maximum inner plaintext = 262128 bytes. The codec
  previously used plaintext-only CT_LEN; this pass aligned it with the spec, including the AAD
  (which carries the same wire value) so encode/decode stay mutually consistent.
- **Pre-decryption sequence validation** ("No out-of-order decrypt"): CRYPTO_SEQ is range-checked
  (`0..CRYPTO_SEQUENCE_MAX` where `CRYPTO_SEQUENCE_MAX = 2^63 - 2 = 9223372036854775806`) and
  strict-order-checked against the receive state **before** any AES-GCM work. Duplicates/replays,
  gaps, negative values, and values above MAX throw `SequenceViolationException` (new top-level
  `IOException` in the codec file; `NetworkError` is sealed and cannot be extended from the crypto
  package). The message is generic — no sequence/key/nonce/ct/pt details.
- **Advance-only-on-success**: `CryptoReceiveSequence.validateAndAdvance` is called only after GCM
  authentication succeeds; on any failure (structural or GCM) the receive sequence is untouched.
- **Handshake-type prohibition**: `HELLO`/`HELLO_ACK` are rejected at encode time
  (`IllegalArgumentException`) and at decode time (`NetworkError.InvalidFrame`, before GCM),
  per Task21A section 4 / invariant 13. `HANDSHAKE_FINISH` (0x03) is not yet a known `FrameType`
  and is rejected by the unknown-type check.
- **Fail-closed decode order** (Task21A section 12): minimum size → MAGIC → VERSION → TYPE
  (known + not post-session handshake) → CRYPTO_SEQ (range + strict order, no advance) →
  CT_LEN (16..MAX; <16 → `InvalidFrame`, >MAX → `OversizedPayload`) → exact size consistency →
  GCM authenticate/decrypt → advance sequence.

## Test Suite Results (exact counts from XML)

### Focused: `SecureFrameCodecTest`
```
TOTAL=61  PASS=61  FAIL=0  ERR=0  SKIP=0
```
(`app/build/test-results/testDebugUnitTest/TEST-com.filo.transfer.core.network.security.crypto.SecureFrameCodecTest.xml`)

Breakdown by section:

| Section | Tests | Content |
|---|---|---|
| F (format) | 11 (F01–F11) | MAGIC/VERSION/TYPE/SEQ/CT_LEN/big-endian/length/AAD assertions |
| E (round-trip) | 6 (E01–E06) | normal/empty/small/large/binary round trips, type independence |
| A (auth) | 10 (A01–A05, A04b, A06–A09) | field mutations and wrong-key/nonce/tag/plaintext |
| D (decrypt) | 2 (D01–D02) | GCM authentication failure paths |
| S (sequence) | 12 (S01–S12) | forward sequence, dup/replay/gap, transactionality, overflow, send-failure |
| B (bounds) | 10 (B01–B10) | truncation, oversize, negative CT_LEN, min-blob boundary |
| I (isolation) | 4 (I01–I04) | plaintext `FrameCodec` behavior, input immutability, output copy |
| P (protocol) | 6 (P01–P06) | handshake-type prohibition, boundary encode/decode limits |

### Security suite: `com.filo.transfer.core.network.security.*`
```
TOTAL=150  PASS=150  FAIL=0  ERR=0
- FilenameValidatorSecurityTest: 8/8
- AesGcmTest: 14/14
- CryptoSequenceTest: 33/33
- SecureFrameCodecTest: 61/61
- HandshakeSecurityTest: 25/25
- HkdfTest: 9/9
```
The 89 non-codec security tests (14+33+8+25+9) all pass — **no security regression**.

### Full JVM suite: `:app:testDebugUnitTest`
```
TOTAL=298  PASS=291  FAIL=7
```
All 7 failures are the known pre-existing baseline (same identities as the 21B/21C baseline of 7
failures; none are in crypto, security, codec, sender, receiver, transport, handshake, resume, or
reconnect):

| # | Test | Error | Note |
|---|------|-------|------|
| 1 | `com.filo.transfer.ExampleRobolectricTest` / `classMethod` | `UnsupportedOperationException: Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21` | env (JDK), pre-existing |
| 2 | `com.filo.transfer.GreetingScreenshotTest` / `classMethod` | same Robolectric sandbox error | env, pre-existing |
| 3 | `com.filo.transfer.core.storage.FileMetadataResolverTest` / `classMethod` | same Robolectric sandbox error | env, pre-existing |
| 4 | `com.filo.transfer.core.storage.FileStreamProviderTest` / `classMethod` | same Robolectric sandbox error | env, pre-existing |
| 5 | `com.filo.transfer.core.storage.StorageProviderIntegrationTest` / `classMethod` | same Robolectric sandbox error | env, pre-existing |
| 6 | `com.filo.transfer.ui.send.SendViewModelTest` / `clearing files resets selection` | `AssertionError: expected:<0> but was:<4>` | pre-existing |
| 7 | `com.filo.transfer.core.network.transport.TcpTransferIntegrationTest` / `testChecksumMismatchRejectsFile` | `NetworkError$IoError: Failed to read frame magic header` | **flaky**: passes 8/8 in isolated runs; fails only under full-suite load. Uses the plaintext codec/transport only (HELLO protocol + `TransferReceiver`), no secure-codec involvement |

## Protocol Validation vs GCM Authentication (failure categorization)

This pass corrected the earlier misclassification. Two distinct failure classes:

**Protocol validation (parser) failures — rejected BEFORE AES-GCM, no GCM call:**
- Short/truncated/extra-byte frames → `NetworkError.InvalidFrame` (B01, B02, B03, B04)
- CT_LEN below 16 or inconsistent with actual size → `InvalidFrame` (B05, B09, B10, A05)
- CT_LEN above MAX (262144) → `NetworkError.OversizedPayload` (B08)
- Unsupported VER → `NetworkError.ProtocolVersionMismatch` (A02, B06)
- Bad MAGIC → `InvalidFrame` (B07)
- Unknown / post-session-forbidden TYPE → `InvalidFrame` (P02, P03)
- CRYPTO_SEQ out of range or out of strict order (duplicate, replay, gap, negative, > MAX)
  → `SequenceViolationException`, **before** any decryption (A04, S01–S11)
- HELLO/HELLO_ACK at encode time → `IllegalArgumentException` (P01)

**GCM authentication failures — valid structure, failed cryptographic authentication:**
- TYPE mutated to a *different valid* type code (MANIFEST→DATA_CHUNK, both in AAD) →
  `AesGcmException("AUTHENTICATION_FAILED")` (A03)
- CRYPTO_SEQ mutated with the receiver already aligned to the new value → `AesGcmException`
  (A04b); when the receiver is not aligned, the pre-check rejects it first (A04)
- Tag byte, wrong key, wrong nonce, plaintext byte flips → `AesGcmException` (A06–A09, D01, D02)

No test in this suite claims "parser rejection = AES-GCM failure". Truncation and malformed-CT_LEN
tests assert `InvalidFrame`/`OversizedPayload` (structural), not GCM exceptions.

**Structurality note on CT_LEN mutation tests**: a *structurally valid* CT_LEN mutation
(consistent with exact size) is not constructible — inflating CT_LEN by 1 makes the frame
size-inconsistent, which the exact-size check rejects as `InvalidFrame` before GCM. This is the
correct fail-closed behavior and is exactly what B05 asserts. The only way CT_LEN mutation reaches
GCM is via AAD binding on a structurally consistent frame, which is covered by A04b (SEQUENCE in
AAD) and A03 (TYPE in AAD). CT_LEN itself is bound to GCM via the AAD on the valid-path round
trips (F05, F06, E-series, P04–P05).

**Required sequence transactionality test (passed)** — S09, "GCM failure must not advance sequence":
1. Fresh `CryptoReceiveSequence` (expected 0);
2. Forge a seq-0 frame with the wrong key → GCM authentication fails;
3. Receive sequence still expected 0;
4. Forge a seq-1 frame with the correct key → GCM fails AND strict-order rejection (expected 0);
5. Receive sequence still 0;
6. Valid seq-0 frame with the correct key → decodes, expected advances to 1;
7. Valid seq-1 frame with the correct key → decodes, expected advances to 2.

**Send-failure semantics (S12)**: a failed encode (31-byte key → `AesGcmException`) does not reuse
the consumed sequence — the next successful encode uses sequence 1 (0 was consumed), preserving
forward-only behavior.

**Overflow (S10, S11)**: `seq = Long.MAX_VALUE` (encode) and `seq = -1` (raw-frame decode) are
rejected as sequence violations / overflow, never serialized into a nonce. `CRYPTO_SEQUENCE_MAX`
(2^63−2) is enforced on both encode and decode; `MAX+1` (i.e. 2^63−1) is rejected before use.

## Boundary Behavior

- Smallest frame = 18 (header) + 16 (empty-ct tag) = 34 bytes; round-trips at min blob (P04).
- Maximum plaintext = 262128; round-trips at max (P05); 262129 rejected at encode (P06, IAE).
- CT_LEN < 16 → `InvalidFrame` (B10); CT_LEN > 262144 → `OversizedPayload` (B08).

## Security Properties

- No API, log, exception, or constant leaks keys, nonces, ciphertext, or plaintext.
  `SequenceViolationException` carries a generic session-termination message only.
- All rejection paths are fail-closed: malformed input is rejected before decryption; GCM
  authentication failure never releases plaintext.
- The plaintext `FrameCodec` path is untouched and verified unchanged (I01).
- 89/89 non-codec security tests pass; 61/61 codec tests pass. **No security regression.**

## Integration Status

The codec remains **pre-integration**: it is not yet wired into `Sender`/`Receiver`/`Transport`,
and the existing handshake flow (`HELLO`/`HELLO_ACK`, KeyStore-backed ECDH) is unchanged. No
sender/receiver/transport/resume/reconnect file was modified.

## Verification Commands

```
./gradlew :app:compileDebugUnitTestKotlin --offline
./gradlew :app:testDebugUnitTest --tests "com.filo.transfer.core.network.security.crypto.SecureFrameCodecTest" --offline --no-configuration-cache
./gradlew :app:testDebugUnitTest --tests "com.filo.transfer.core.network.security.*" --offline --no-configuration-cache
./gradlew :app:testDebugUnitTest --offline --no-configuration-cache
```
Results inspected via `app/build/test-results/testDebugUnitTest/TEST-*.xml` (not terminal summary alone).

## Next Steps (out of scope for this pass)

- Wire `SecureFrameCodec` into the post-handshake sender/receiver data path (new task).
- Add `HANDSHAKE_FINISH` to `FrameType` and add it to the post-session forbidden set when the
  handshake task lands.
- Investigate the flaky `TcpTransferIntegrationTest.testChecksumMismatchRejectsFile` (full-suite
  timing) in a dedicated transport task; do not modify it here.
