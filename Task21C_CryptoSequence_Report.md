# TASK 21C — CRYPTO SEQUENCE AND NONCE STATE REPORT

Date: 2026-10-05
Git operations: NONE

## 1. STATUS

STATUS=PASS_WITH_BASELINE_FAILURES

Implemented isolated crypto sequence / nonce-state layer per Task 21A specification.
All 33 focused tests pass. No TCP/handshake/codec integration performed.

Physical Android 12 / API 31 validation was not performed. This is JVM evidence only.

## 2. Files Changed

Production:
- `app/src/main/java/com/filo/transfer/core/network/security/crypto/CryptoSequence.kt` (new)

Test:
- `app/src/test/java/com/filo/transfer/core/network/security/crypto/CryptoSequenceTest.kt` (new)

Unchanged:
- `AesGcm.kt` (Task 21B)
- `IdentityManager.kt`
- `SecureHandshake.kt`
- `FrameCodec.kt`
- All TCP transport files
- `build.gradle.kts`

## 3. Protocol Constants

```
INITIATOR_TO_RESPONDER = 0x00000001
RESPONDER_TO_INITIATOR = 0x00000002
DIR_ENCODING = 4 bytes big-endian
SEQ_ENCODING = 8 bytes big-endian
NONCE = DIR4 || SEQ8
NONCE_SIZE = 12 bytes (matches AesGcm.NONCE_SIZE)
```

## 4. Sequence Semantics

- Initial sequence = 0
- First outbound encrypted frame uses sequence 0
- Strict monotonic progression: each call to nextSequence() returns current value then increments
- No gaps allowed
- No reuse of sequence numbers within a session
- No automatic wraparound from Long.MAX_VALUE to Long.MIN_VALUE
- Overflow guard: after returning Long.MAX_VALUE, next call throws IllegalStateException with "overflow" in message
- Negative sequences never produced; buildNonce rejects negative input with IllegalArgumentException

## 5. Receiver Semantics

CryptoReceiveSequence enforces strict expected+1 validation:
- Initial expected = 0
- Accepts only exact expected sequence
- Advances currentExpected only on success
- Does NOT advance on rejection
- Rejects duplicates, older sequences, future sequences, negative sequences
- State persists across rejections; caller must supply correct sequence to resume

## 6. Test Results

### Focused CryptoSequenceTest
TEST_TOTAL=33
TEST_PASS=33
TEST_FAIL=0
TEST_SKIPPED=0

Tests cover:
- Direction constants and fromInt validation
- SequenceAndNonce data class
- Initial state and monotonic progression (T01-T04)
- Nonce length and big-endian encoding (T05-T08)
- Direction separation (T09-T11)
- Nonce uniqueness over 0..1000 range (T12)
- Large sequence encoding correctness (near Long.MAX_VALUE)
- Overflow guard (T21)
- Negative sequence prevention (T22)
- Nonce immutability / caller mutation safety (T23)
- Fresh instance isolation (T24)
- Receiver: accept 0, accept 1 after 0 (R01-R02)
- Receiver: reject duplicate, old, future, negative (R03-R05, R08)
- Receiver: no state advance on rejection (R06, R09, R10, R11)
- Receiver: continue correctly after rejected frame (R07)

### Existing Security Tests
| Suite | Tests | Result |
|---|---|---|
| AesGcmTest | 14 | PASS |
| HkdfTest | 9 | PASS |
| HandshakeSecurityTest | 25 | PASS |
| FilenameValidatorSecurityTest | 8 | PASS |

Security JVM total: 89 pass / 0 fail / 0 skipped.
Prior baseline: 56/56 (Hkdf 9 + Handshake 25 + FilenameValidator 8 + AesGcm 14 from 21B).
Difference: +33 CryptoSequenceTest, all pass.

### Full JVM Regression
TOTAL=237
PASS=230
FAIL=7
SKIPPED=0

Same 7 baseline failures as Task 21B:
1. ExampleRobolectricTest :: classMethod
2. GreetingScreenshotTest :: classMethod
3. FileMetadataResolverTest :: classMethod
4. FileStreamProviderTest :: classMethod
5. StorageProviderIntegrationTest :: classMethod
6. SendViewModelTest :: clearing files resets selection
7. TcpTransferIntegrationTest :: testChecksumMismatchRejectsFile

## 7. Baseline Comparison

Baseline (Task 21B):
204 total / 197 pass / 7 fail

Current (Task 21C):
237 total / 230 pass / 7 fail

Difference: +33 tests (all CryptoSequenceTest), +33 passes, 0 new failures.

## 8. Regression Assessment

No new failures introduced. All 7 failures are pre-existing baseline failures from Task 20R.2.
No production code was modified except the two new files listed above.

## 9. Integration Status

TCP integration = NOT IMPLEMENTED
FrameCodec integration = NOT IMPLEMENTED
SecureHandshake integration = NOT IMPLEMENTED
Sender integration = NOT IMPLEMENTED
Receiver integration = NOT IMPLEMENTED

The CryptoSequence and CryptoReceiveSequence classes are available for future use by Task 21D+.

## 10. Security Limitations

Task 21C does NOT provide:
- Encryption (provided by AesGcm, Task 21B)
- Authentication (provided by AES-GCM tag verification)
- Key exchange (provided by SecureHandshake, Task 21)
- Replay protection across sessions (each new session must create new CryptoSequence instances)

Task 21C provides deterministic per-direction cryptographic sequence/nonce state:
- Guarantees nonce uniqueness within a direction via monotonic sequence
- Guarantees direction separation via explicit DIR4 encoding
- Prevents nonce reuse within a secure session
- Does not claim global replay protection

## 11. Final Verification

- AesGcmTest: 14/14 (unchanged from Task 21B)
- Security JVM: 89/89 (was 56/56, +33 new)
- No TCP integration occurred
- No plaintext protocol behavior changed
- No Git operations were performed

TASK_21C_STATUS=PASS_WITH_BASELINE_FAILURES
