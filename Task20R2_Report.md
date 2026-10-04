# TASK 20R.2 — ANDROID RUNTIME CRYPTO VALIDATION EXECUTION REPORT

Date: 2026-07-05
Status: INSTRUMENTED TESTS NOT EXECUTED
Reason: No Android device available; emulator cannot run in this environment.

---

## 1. Execution Summary

Task 20R.2 requires executing the Task 20R.1 instrumented crypto validation tests
on a real Android device to obtain runtime evidence for:

- F-20R-01: Ed25519 + AndroidKeyStore availability on API 30-33
- F-20R-02: `createKeyGenSpec` reflection path validity

**Outcome: INSTRUMENTED TESTS NOT EXECUTED.**

The Android emulator was installed and an API 31 x86_64 AVD was created, but the
emulator cannot run in this environment because:

1. **No KVM**: `/dev/kvm` does not exist. Hardware virtualization is not
   exposed in this container. The x86_64 emulator requires KVM and fails
   with: `ERROR: x86_64 emulation currently requires hardware acceleration!
   CPU acceleration status: /dev/kvm is not found: VT disabled in BIOS or
   KVM kernel module not loaded`.

2. **Software mode (TCQ) too slow**: The emulator was started with
   `-accel off` (pure TCG software emulation). After 15+ minutes of waiting,
   `sys.boot_completed` was never set to `1`. The ADB shell was
   unresponsive. The process eventually exited.

3. **Insufficient memory**: The system has 7.8 GB total RAM with only
   ~340 MB available. The 4 GB emulator allocation was not sustainable.

Both P2 findings remain **UNVERIFIED**.

---

## 2. Git State

```
HEAD: a886a18 (Task 20R.1 test + report)
Working tree: clean (Task18_Report.md, gradle wrapper files untracked)
No modifications to production source.
```

---

## 3. Device Information

No device was available. The following was attempted:

- `adb devices -l`: no devices attached
- `/opt/android-sdk/platform-tools/adb`: found, adb server started
- Emulator: installed via sdkmanager (863 MB)
- System image: `system-images;android-31;default;x86_64` installed
- AVD: `test_api31` created (API 31, x86_64, Pixel 6 device profile)
- Emulator startup: FAILED (no KVM, then OOM in software mode)

```
MODEL=Android SDK built for x86_64 (emulator, not booted)
ANDROID_VERSION=12 (not reached — boot incomplete)
API_LEVEL=31 (target, not verified at runtime)
ABI=x86_64
ADB=/opt/android-sdk/platform-tools/adb (daemon running, no device)
```

---

## 4. Ed25519 Result

**UNVERIFIED** — instrumented test not executed.

No runtime evidence for:
- `KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")`
- Key generation, signing, or verification on Android

Per Task 20R audit, Android documentation states Ed25519 AndroidKeyStore
support was added in **API 34**. If accurate, Ed25519 will fail on
API 30-33 devices. This remains an assumption pending runtime verification.

```
API_LEVEL: UNVERIFIED
ANDROID_VERSION: UNVERIFIED
PROVIDER: UNVERIFIED
ALGORITHM: Ed25519
KEY_GENERATION=UNVERIFIED
SIGNING=UNVERIFIED
VERIFICATION=UNVERIFIED
```

---

## 5. P-256 Result

**UNVERIFIED** — instrumented test not executed.

No runtime evidence for:
- `KeyPairGenerator.getInstance("EC", "AndroidKeyStore")` with
  `KeyGenParameterSpec.Builder("EC")`
- Curve size validation (secp256r1)
- Signing and verification on Android

P-256 is widely supported on Android. Runtime verification is still
required to confirm the specific API 30-33 behavior with the
`KeyGenParameterSpec` builder path.

```
API_LEVEL: UNVERIFIED
ANDROID_VERSION: UNVERIFIED
PROVIDER: UNVERIFIED
ALGORITHM: P-256 (EC)
KEY_GENERATION=UNVERIFIED
SIGNING=UNVERIFIED
VERIFICATION=UNVERIFIED
```

---

## 6. createKeyGenSpec Result

**UNVERIFIED** — instrumented test not executed.

No runtime evidence for:
- Whether the reflection constructor
  `KeyGenParameterSpec(String, int[], boolean[], boolean[], AlgorithmParameterSpec)`
  exists on the target API level
- Whether the reflection path in `IdentityManager` is functional

The standard Builder path (`KeyGenParameterSpec.Builder`) also remains
unverified at runtime.

```
Production reflection path: UNVERIFIED
Standard Builder path: UNVERIFIED
```

---

## 7. IdentityManager E2E Result

**UNVERIFIED** — instrumented test not executed.

No runtime evidence for:
- `getOrCreateIdentity()` with default algorithm "Ed25519"
- Public key retrieval, fingerprint, signing, and verification
- Idempotency (second call returns same public key and fingerprint)

---

## 8. Instrumented Test Summary

```
TOTAL=7
PASS=0
FAIL=0
NOT_EXECUTED=7
```

No instrumented tests were executed. The 7 tests in
`CryptoRuntimeValidationTest.kt` compiled successfully (verified in
Task 20R.1) but require an Android device to run.

---

## 9. JVM Security Test Summary

All JVM security tests were executed and pass. No regression introduced
by Task 20R.1.

```
TOTAL=190 (full suite)
PASS=183
FAIL=7
```

Security-specific tests:

```
HkdfTest: 9 tests, 0 failures, 0 errors
HandshakeSecurityTest: 25 tests, 0 failures, 0 errors
FilenameValidatorSecurityTest: 8 tests, 0 failures, 0 errors
```

---

## 10. Failed Tests

All 7 failures are **baseline** (pre-existing, documented in prior tasks):

| Test | Classification |
|------|---------------|
| `ExampleRobolectricTest > classMethod` | baseline (Robolectric Java version) |
| `GreetingScreenshotTest > classMethod` | baseline (Robolectric Java version) |
| `FileMetadataResolverTest > classMethod` | baseline (Robolectric Java version) |
| `FileStreamProviderTest > classMethod` | baseline (Robolectric Java version) |
| `StorageProviderIntegrationTest > classMethod` | baseline (Robolectric Java version) |
| `SendViewModelTest > clearing files resets selection` | baseline (AssertionError) |
| `TcpTransferIntegrationTest > testChecksumMismatchRejectsFile` | baseline (SocketTimeoutException) |

**New failures introduced by Task 20R.1: 0**

---

## 11. Finding Resolution

### F-20R-01: Ed25519 AndroidKeyStore API Level

- **Previous severity**: P2 (UNVERIFIED)
- **Current status**: **UNVERIFIED** (no change)
- **Evidence obtained**: None. Instrumented tests not executed.
- **Assessment unchanged**: Android documentation states Ed25519
  AndroidKeyStore support was added in API 34. The project's minSdk is 30.
  On API 30-33 devices, Ed25519 key generation via AndroidKeyStore is
  expected to fail. This remains a documented assumption, not a
  verified runtime fact.

### F-20R-02: createKeyGenSpec Reflection Path

- **Previous severity**: P2 (UNVERIFIED)
- **Current status**: **UNVERIFIED** (no change)
- **Evidence obtained**: None. Instrumented tests not executed.
- **Assessment unchanged**: The reflection-based constructor lookup
  may reference a non-public or internal API that is not guaranteed
  to exist across all API levels. The standard
  `KeyGenParameterSpec.Builder` path is the recommended replacement.

---

## 12. Production Impact

No production code was modified in this task. No changes were made to
`IdentityManager`, `SecureHandshake`, or any other production source file.

The two P2 findings (F-20R-01, F-20R-02) remain unresolved. Until
runtime verification is performed on a real Android device:

- If F-20R-01 is confirmed: `IdentityManager` must switch default
  algorithm to P-256 or bump minSdk to 34.
- If F-20R-02 is confirmed: `createKeyGenSpec` must be replaced with
  `KeyGenParameterSpec.Builder`.

Neither resolution can be made without runtime evidence.

---

## 13. Task 21 Readiness

**BLOCKED**

Task 21 (transport encryption design) requires a confirmed working
identity generation path. The two P2 findings directly affect whether
the identity layer functions on target devices. Proceeding to transport
encryption before resolving F-20R-01 and F-20R-02 risks building the
transport layer on top of a broken identity foundation.

Task 21 can begin in **parallel** (design work only, no code) if the
team accepts the risk of a later identity-layer fix. But any
**implementation** of transport encryption should wait for the
identity layer to be verified or fixed.

---

## 14. Required Next Engineering Task

**Task 20R.3: Execute instrumented crypto validation tests on a real
Android device (API 31) and report runtime evidence for F-20R-01 and
F-20R-02.**

This task must be performed in an environment with:
- A physical Android device (API 30-33 preferred, API 34 also useful)
  or an emulator with KVM support
- Sufficient memory (≥ 4 GB free)
- ADB and the project's Gradle wrapper

The tests from Task 20R.1 (`CryptoRuntimeValidationTest.kt`) are ready
to execute. No new test code is needed. The command is:

```
./gradlew connectedDebugAndroidTest
```

The result will determine:
- Whether Ed25519 AndroidKeyStore works on the tested API level
- Whether the `createKeyGenSpec` reflection path is functional
- What changes, if any, are required in `IdentityManager`

This task must complete before Task 21 implementation can begin.
