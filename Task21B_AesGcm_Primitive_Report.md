# TASK 21B — AES-256-GCM PRIMITIVE REPORT

Date: 2026-10-04
Git operations: NONE

## 1. Execution Summary

STATUS=PASS_WITH_BASELINE_FAILURES

Implemented an isolated AES-256-GCM primitive using standard JCA `AES/GCM/NoPadding`. Added 14 focused JVM unit tests. All 14 passed. The primitive is not referenced by TCP, FrameCodec, handshake, resume, or reconnect.

Physical Android 12 / API 31 validation was not performed. This is JVM evidence only.

## 2. Implementation

Production files changed=
- `app/src/main/java/com/filo/transfer/core/network/security/crypto/AesGcm.kt` (new)

Test source changed=
- `app/src/test/java/com/filo/transfer/core/network/security/crypto/AesGcmTest.kt` (new)

Gradle changed=NO

Git operations=NONE

## 3. Cryptographic Contract

Algorithm=AES-256-GCM
KEY_SIZE=32 bytes
NONCE_SIZE=12 bytes
TAG_SIZE=16 bytes
Transformation=`AES/GCM/NoPadding`
Tag bits=128
Provider=JVM JCA (not AndroidKeyStore)

The primitive does not generate nonces, construct protocol AAD, manage sequence numbers, parse frames, or talk to TCP.

## 4. API Contract

```
object AesGcm
  encrypt(key, nonce, aad, plaintext) -> ciphertextAndTag
  decrypt(key, nonce, aad, ciphertextAndTag) -> plaintext
```

Output format: combined representation, ciphertext bytes followed by a 16-byte authentication tag. Decrypt consumes that same layout.

Invalid key length or nonce length: `IllegalArgumentException` (rejected before crypto). Keys are not hashed, truncated, padded, or otherwise transformed.

Caller owns key lifecycle. Caller arrays are not mutated. Working copies of key and nonce are zeroed after use.

## 5. Authentication Failure Behavior

Decrypt failures throw `AesGcmException("AUTHENTICATION_FAILED")`.

Fail closed. No partial plaintext is returned. Exceptions do not contain keys, plaintext, ciphertext, or nonces.

Covered: modified ciphertext, modified tag, modified AAD, wrong 32-byte key, ciphertext shorter than the tag.

## 6. Test Vector

Source: McGrew/Viega GCM Test Case 14 (AES-256, 96-bit IV, empty AAD). Same vector as NIST CAVP-style AES-GCM 256/96/128 materials.

```
key        = 0000000000000000000000000000000000000000000000000000000000000000
nonce      = 000000000000000000000000
aad        = (empty)
plaintext  = 00000000000000000000000000000000
ciphertext = cea7403d4d606b6e074ec5d3baf39d18
tag        = d0d1c8a799996bf0265b98b5d48ab919
```

TEST-01 asserts exact ciphertext and tag. TEST-06 decrypts the same vector.

TEST-05 additionally uses McGrew/Viega Test Case 16 (non-empty 20-byte AAD, 60-byte plaintext) for exact ciphertext/tag match plus round-trip decrypt.

## 7. Unit Test Results

Focused `AesGcmTest` (executed: `./gradlew :app:testDebugUnitTest --tests com.filo.transfer.core.network.security.crypto.AesGcmTest`):

TEST_TOTAL=14
TEST_PASS=14
TEST_FAIL=0
TEST_SKIPPED=0

All required TEST-01 through TEST-14 executed and passed.

## 8. Security Tests

ModifiedCiphertext=PASS (TEST-07)
ModifiedTag=PASS (TEST-08)
ModifiedAAD=PASS (TEST-09)
WrongKey=PASS (TEST-10)
InvalidKeyLength=PASS (TEST-11; 16-byte, 31-byte, empty rejected)
InvalidNonceLength=PASS (TEST-12; 11-byte, 13-byte, empty rejected)

## 9. Existing Security Tests

Executed: `./gradlew :app:testDebugUnitTest --tests com.filo.transfer.core.network.security.*`

| Suite | Tests | Result |
|---|---|---|
| AesGcmTest | 14 | PASS |
| HkdfTest | 9 | PASS |
| HandshakeSecurityTest | 25 | PASS |
| FilenameValidatorSecurityTest | 8 | PASS |

Security JVM total: 56 pass / 0 fail / 0 skipped.

Prior security JVM baseline was 42/42 (Hkdf 9 + Handshake 25 + FilenameValidator 8). Difference: +14 AesGcmTest, all pass.

## 10. Full JVM Regression

Executed: `./gradlew :app:testDebugUnitTest`

TOTAL=204
PASS=197
FAIL=7
SKIPPED=0

Gradle reported: `204 tests completed, 7 failed`

Failures (unchanged class from Task 20R.2):

1. `ExampleRobolectricTest` / classMethod — `UnsupportedOperationException` at `DefaultSdkProvider.java:170`
2. `GreetingScreenshotTest` / classMethod — same Robolectric SDK provider error
3. `FileMetadataResolverTest` / classMethod — same
4. `FileStreamProviderTest` / classMethod — same
5. `StorageProviderIntegrationTest` / classMethod — same
6. `SendViewModelTest` / clearing files resets selection — `AssertionError` at `SendViewModelTest.kt:120`
7. `TcpTransferIntegrationTest` / testChecksumMismatchRejectsFile — `NetworkError$IoError` caused by `SocketTimeoutException`

## 11. Comparison With Task 20R.2 Baseline

Baseline:
190 total / 183 pass / 7 fail

Current:
204 total / 197 pass / 7 fail

Difference: +14 tests, +14 passes, 0 new failures.

The 14 added tests are `AesGcmTest` TEST-01..TEST-14. The 7 failures are the same 5 Robolectric classMethod failures plus `SendViewModelTest` and `TcpTransferIntegrationTest`. No new regression.

## 12. Protocol Integration

TCP_CHANGED=NO
FrameCodec_CHANGED=NO
ProtocolFrame_CHANGED=NO
SecureHandshake_CHANGED=NO
Resume_CHANGED=NO
Reconnect_CHANGED=NO
IdentityManager_CHANGED=NO
Hkdf_CHANGED=NO

`AesGcm` is referenced only by `AesGcmTest`. Transport does not call it.

## 13. Runtime Limitation

Physical Android 12/API 31 validation was NOT performed.

These results are JVM unit-test evidence only. AndroidKeyStore behavior was not validated. F-20R-01 and F-20R-02 remain UNVERIFIED.

## 14. Production Impact

Production code changed=YES (`AesGcm.kt` only)
Test source changed=YES (`AesGcmTest.kt` only)
Gradle changed=NO
Git operations=NONE

## 15. Final Verdict

TASK_21B_STATUS=PASS_WITH_BASELINE_FAILURES
