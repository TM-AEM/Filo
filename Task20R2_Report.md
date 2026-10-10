# TASK 20R.2 — ANDROID API 31 RUNTIME CRYPTO VALIDATION REPORT

Date: 2026-10-04
Status: BLOCKED_BY_ENVIRONMENT
Instrumentation tests: NOT EXECUTED
Git operations: NONE

---

## 1. Execution Summary

The API 31 emulator **did boot**:

- `sys.boot_completed=1` was observed
- `ro.build.version.sdk=31`
- `ro.build.version.release=12`
- `ro.product.cpu.abi=x86_64`
- ADB state=`device`

Instrumented crypto tests **did not execute**.

`./gradlew connectedDebugAndroidTest` failed before any `CryptoRuntimeValidationTest` method ran.

Failure class: **emulator/ADB/APK installation infrastructure**, not cryptographic test failure.

Observed install/runner failures:

1. Gradle: `Skipping device 'FiloApi31(AVD)' for ':app:': Unknown API Level`
2. Gradle XML: `Found 1 connected device(s), 0 of which were compatible.`
3. `adb install`: `IllegalStateException: Cannot access system provider: 'settings' before system providers are installed!`
4. Earlier: `InstallException: Failed to install-write all apks`
5. Earlier: `NullPointerException` in `StorageManagerService.allocateBytes`
6. Direct `am instrument`: `INSTRUMENTATION_FAILED` because the test APK was never installed
7. Intermittent: `cmd: Can't find service: package`
8. `dumpsys activity`: `Can't find service: activity`
9. `init.svc.system_server` often empty while zygote/installd reported running

Cause: no KVM. Emulator ran with `-accel off` (TCG). The guest reported boot_completed, but Android framework services (package, activity, settings providers) were incomplete/unstable. APK install and instrumentation never succeeded.

No Ed25519, P-256, createKeyGenSpec, or IdentityManager runtime crypto evidence was obtained.

---

## 2. Git State

```
Branch=main
HEAD=8f069b1
Source modifications=NONE
Git operations performed=NONE
```

Untracked (pre-existing, unrelated): `Task18_Report.md`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`.

Production source: unmodified.

---

## 3. Emulator

```
AVD=FiloApi31
ANDROID_VERSION=12
API_LEVEL=31
ABI=x86_64
ADB=device (while running; later exited)
KVM=unavailable (/dev/kvm missing)
BOOT_COMPLETED=1 (observed)
BOOT_COMPLETED_PROPERTY=1
PACKAGE_SERVICE=intermittent (found then missing)
```

Emulator command used:

```
/opt/android-sdk/emulator/emulator -avd FiloApi31 -no-window -no-audio -no-boot-anim -accel off -gpu swiftshader_indirect -memory 1536 -cores 2
```

Log: `Boot completed in 1916559 ms` (~32 minutes). Later the emulator process exited; ADB now shows no devices.

---

## 4. Ed25519 AndroidKeyStore

```
Provider=NOT_EXECUTED
KeyPairGenerator=NOT_EXECUTED
Initialization=NOT_EXECUTED
Generation=NOT_EXECUTED
Private key usable=NOT_EXECUTED
Public key usable=NOT_EXECUTED
Signing=NOT_EXECUTED
Verification=NOT_EXECUTED
Cleanup=NOT_EXECUTED
```

F-20R-01=UNVERIFIED

No API 31 runtime crypto result. Do not classify as supported or unsupported.

---

## 5. P-256 AndroidKeyStore

```
Provider=NOT_EXECUTED
KeyPairGenerator=NOT_EXECUTED
Initialization=NOT_EXECUTED
Generation=NOT_EXECUTED
Curve=NOT_EXECUTED
Signing=NOT_EXECUTED
Verification=NOT_EXECUTED
Cleanup=NOT_EXECUTED
```

---

## 6. createKeyGenSpec

```
Production reflection path=NOT_EXECUTED
Standard Builder path=NOT_EXECUTED
```

F-20R-02=UNVERIFIED

The reflection path was never reached. Builder success was also not obtained.

---

## 7. IdentityManager E2E

```
First getOrCreateIdentity=NOT_EXECUTED
Key creation=NOT_EXECUTED
Public key retrieval=NOT_EXECUTED
Fingerprint=NOT_EXECUTED
Signing=NOT_EXECUTED
Verification=NOT_EXECUTED
Second getOrCreateIdentity=NOT_EXECUTED
Stable public key=NOT_EXECUTED
Stable fingerprint=NOT_EXECUTED
```

No production exception was captured because IdentityManager never ran on the device.

---

## 8. Instrumented Tests

From generated XML `TEST-TestRunner-_app-.xml`:

```
TOTAL=1
PASS=0
FAIL=1
SKIPPED=0
```

Failure:

- testcase: `: No compatible devices connected.`
- message: `Found 1 connected device(s), 0 of which were compatible.`

This is **not** a CryptoRuntimeValidationTest method. Zero cryptographic tests executed.

---

## 9. JVM Security Tests

Full suite:

```
TOTAL=190
PASS=183
FAIL=7
```

Security-specific:

```
HkdfTest: 9 tests, 0 failures
HandshakeSecurityTest: 25 tests, 0 failures
FilenameValidatorSecurityTest: 8 tests, 0 failures
Security subtotal: 42 PASS, 0 FAIL
```

Failed JVM tests:

1. `ExampleRobolectricTest > classMethod` — UnsupportedOperationException DefaultSdkProvider.java:170
2. `GreetingScreenshotTest > classMethod` — UnsupportedOperationException DefaultSdkProvider.java:170
3. `FileMetadataResolverTest > classMethod` — UnsupportedOperationException DefaultSdkProvider.java:170
4. `FileStreamProviderTest > classMethod` — UnsupportedOperationException DefaultSdkProvider.java:170
5. `StorageProviderIntegrationTest > classMethod` — UnsupportedOperationException DefaultSdkProvider.java:170
6. `SendViewModelTest > clearing files resets selection` — AssertionError SendViewModelTest.kt:120
7. `TcpTransferIntegrationTest > testChecksumMismatchRejectsFile` — SocketTimeoutException / IoError TcpTransferIntegrationTest.kt:382

---

## 10. Regression Analysis

Confirmed existing baseline failures:

- 5 Robolectric Java-version failures
- SendViewModelTest assertion
- TcpTransferIntegrationTest SocketTimeoutException

New failures introduced by Task 20R.1: **0** (JVM)

Unrelated failures: none beyond baseline JVM list

Environment failures:

- Gradle connected tests: Unknown API Level / no compatible devices
- APK install: settings provider not installed / package service missing
- Instrumentation: test APK never installed

Undetermined: none for JVM. Runtime crypto: UNVERIFIED due to environment.

---

## 11. Updated Findings

```
F-20R-01=UNVERIFIED
F-20R-02=UNVERIFIED
F-20R-03=unchanged (no new runtime evidence)
F-20R-04=unchanged (no new runtime evidence)
F-20R-05=unchanged (no new runtime evidence)
```

Statuses were not changed to VERIFIED_* because no instrumented crypto test executed.

---

## 12. Production Impact

No production code was modified.

Filo still defaults to Ed25519 + AndroidKeyStore with a reflection-based KeyGenParameterSpec. Whether that works on API 31 remains unknown.

This environment cannot complete APK install on the TCG emulator, so minSdk 30 compatibility of the identity layer is still unproven.

Do not claim Ed25519 is unsupported on API 31. Do not claim it is supported. Do not generalize to API 30-33.

---

## 13. Task 21 Readiness

**BLOCKED**

Required runtime evidence was not obtained. Transport encryption should not start until Ed25519 / P-256 / createKeyGenSpec are executed on a real device or KVM emulator.

---

## 14. Required Next Task

**Task 20R.3: Run `./gradlew connectedDebugAndroidTest` on a physical Android API 31 device (or a KVM-capable emulator) and record CryptoRuntimeValidationTest results for F-20R-01 and F-20R-02.**

Do not implement it in this task.
