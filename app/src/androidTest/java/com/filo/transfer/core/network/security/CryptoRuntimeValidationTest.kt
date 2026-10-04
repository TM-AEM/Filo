package com.filo.transfer.core.network.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.AlgorithmParameterSpec

/**
 * Android instrumented tests for runtime crypto validation.
 *
 * Validates Ed25519 and P-256 support in AndroidKeyStore,
 * and the createKeyGenSpec reflection path.
 *
 * Run: ./gradlew connectedDebugAndroidTest
 * Requires a physical device or emulator (API 30+).
 */
@RunWith(AndroidJUnit4::class)
class CryptoRuntimeValidationTest {

    private val tag = "CryptoValidation"
    private val apiKeyLevel: Int
        get() = android.os.Build.VERSION.SDK_INT

    // ── 1. Ed25519 AndroidKeyStore Test ──────────────────────────────────

    @Test
    fun `Ed25519 AndroidKeyStore key generation`() {
        val alias = "filo_test_ed25519_${System.currentTimeMillis()}"
        try {
            Log.i(tag, "API_LEVEL=$apiKeyLevel")

            // Case A: Algorithm unavailable
            val kpg: KeyPairGenerator
            try {
                kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
                Log.i(tag, "Ed25519 getInstance: PASS")
            } catch (e: Exception) {
                Log.w(tag, "Ed25519 getInstance: FAIL ${e::class.simpleName} - ${e.message}")
                throw e
            }

            // Case B: Initialization
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build()
            try {
                kpg.initialize(spec)
                Log.i(tag, "Ed25519 initialize: PASS")
            } catch (e: Exception) {
                Log.w(tag, "Ed25519 initialize: FAIL ${e::class.simpleName} - ${e.message}")
                throw e
            }

            // Case C/D: Key generation
            val keyPair: KeyPair
            try {
                keyPair = kpg.generateKeyPair()
                Log.i(tag, "Ed25519 key generation: PASS")
            } catch (e: Exception) {
                Log.w(tag, "Ed25519 key generation: FAIL ${e::class.simpleName} - ${e.message}")
                throw e
            }

            assertNotNull("Private key must be present", keyPair.private)
            assertNotNull("Public key must be present", keyPair.public)
            Log.i(tag, "Ed25519 key algorithm: ${keyPair.private.algorithm}")

            // Signing
            val testMessage = "Filo-AndroidKeyStore-Ed25519-runtime-test".toByteArray()
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(keyPair.private)
            sig.update(testMessage)
            val signature = sig.sign()
            Log.i(tag, "Ed25519 signing: PASS (sig length=${signature.size})")

            // Verification
            val ver = Signature.getInstance("Ed25519")
            ver.initVerify(keyPair.public)
            ver.update(testMessage)
            assertTrue("Ed25519 verification must succeed", ver.verify(signature))
            Log.i(tag, "Ed25519 verification: PASS")

        } finally {
            deleteKey(alias)
            Log.i(tag, "Ed25519 cleanup: PASS")
        }
    }

    // ── 2. P-256 AndroidKeyStore Test ───────────────────────────────────

    @Test
    fun `P-256 AndroidKeyStore key generation`() {
        val alias = "filo_test_p256_${System.currentTimeMillis()}"
        try {
            Log.i(tag, "API_LEVEL=$apiKeyLevel")

            val kpg = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            Log.i(tag, "P-256 getInstance: PASS")

            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests("SHA-256")
                .build()
            kpg.initialize(spec)
            Log.i(tag, "P-256 initialize: PASS")

            val keyPair: KeyPair
            try {
                keyPair = kpg.generateKeyPair()
                Log.i(tag, "P-256 key generation: PASS")
            } catch (e: Exception) {
                Log.w(tag, "P-256 key generation: FAIL ${e::class.simpleName} - ${e.message}")
                throw e
            }

            assertNotNull("Private key must be present", keyPair.private)
            assertNotNull("Public key must be present", keyPair.public)

            // Verify curve
            val ecKey = keyPair.public as ECPublicKey
            val curveField = ecKey.params.javaClass.getMethod("getField").invoke(ecKey.params) as java.security.spec.EllipticCurve
            val fieldSize = (curveField.javaClass.getMethod("getFieldSize").invoke(curveField)) as Int
            Log.i(tag, "P-256 field size: $fieldSize")
            assertEquals(256, fieldSize)

            // Signing
            val testMessage = "Filo-AndroidKeyStore-P256-runtime-test".toByteArray()
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(keyPair.private)
            sig.update(testMessage)
            val signature = sig.sign()
            Log.i(tag, "P-256 signing: PASS (sig length=${signature.size})")

            // Verification
            val ver = Signature.getInstance("SHA256withECDSA")
            ver.initVerify(keyPair.public)
            ver.update(testMessage)
            assertTrue("P-256 verification must succeed", ver.verify(signature))
            Log.i(tag, "P-256 verification: PASS")

        } finally {
            deleteKey(alias)
            Log.i(tag, "P-256 cleanup: PASS")
        }
    }

    // ── 3. P-256 default curve check ─────────────────────────────────────

    @Test
    fun `P-256 default EC curve field size`() {
        val alias = "filo_test_p256_default_${System.currentTimeMillis()}"
        try {
            val kpg = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests("SHA-256")
                .build()
            kpg.initialize(spec)
            val keyPair = kpg.generateKeyPair()

            val ecKey = keyPair.public as ECPublicKey
            val curveField = ecKey.params.javaClass.getMethod("getField").invoke(ecKey.params) as java.security.spec.EllipticCurve
            val fieldSize = (curveField.javaClass.getMethod("getFieldSize").invoke(curveField)) as Int
            Log.i(tag, "Default EC curve field size: $fieldSize")
            assertEquals("Default EC curve must be P-256 (256-bit)", 256, fieldSize)

            Log.i(tag, "P-256 default curve test: PASS")
        } finally {
            deleteKey(alias)
        }
    }

    // ── 4. createKeyGenSpec Reflection Validation ───────────────────────

    @Test
    fun `createKeyGenSpec reflection path validation`() {
        // Replicate the exact reflection from IdentityManager.createKeyGenSpec()
        val alias = "filo_test_reflection_${System.currentTimeMillis()}"
        try {
            Log.i(tag, "Testing createKeyGenSpec reflection path")

            val cls = Class.forName("android.security.keystore.KeyGenParameterSpec")
            Log.i(tag, "Class found: ${cls.name}")

            // List available constructors for diagnosis
            val constructors = cls.constructors
            Log.i(tag, "Constructor count: ${constructors.size}")
            constructors.forEach { c ->
                Log.i(tag, "  ctor: ${c.parameterTypes.joinToString(", ")}")
            }

            // The production code looks up this constructor:
            // (String, int[], boolean[], boolean[], AlgorithmParameterSpec)
            val ctorFound = constructors.find { c ->
                c.parameterTypes.size == 5 &&
                    c.parameterTypes[0] == String::class.java &&
                    c.parameterTypes[1] == intArrayOf(0).javaClass &&
                    c.parameterTypes[2] == booleanArrayOf(false).javaClass &&
                    c.parameterTypes[3] == booleanArrayOf(false).javaClass &&
                    c.parameterTypes[4] == AlgorithmParameterSpec::class.java
            }

            if (ctorFound == null) {
                Log.e(tag, "createKeyGenSpec 5-arg array ctor: NOT FOUND")
                Log.i(tag, "Falling back to standard Builder path")
                testStandardBuilderPath(alias)
                return
            }

            Log.i(tag, "createKeyGenSpec 5-arg array ctor: FOUND")
            val spec = ctorFound.newInstance(
                alias,
                intArrayOf(KeyProperties.PURPOSE_SIGN),
                booleanArrayOf(false),
                booleanArrayOf(false),
                null
            ) as AlgorithmParameterSpec
            Log.i(tag, "Spec created: ${spec::class.simpleName}")

            val kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
            kpg.initialize(spec)
            val keyPair = kpg.generateKeyPair()
            assertNotNull("Key must be generated", keyPair.private)

            val msg = "Filo-Reflection-Test".toByteArray()
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(keyPair.private)
            sig.update(msg)
            val signature = sig.sign()
            val ver = Signature.getInstance("Ed25519")
            ver.initVerify(keyPair.public)
            ver.update(msg)
            assertTrue(ver.verify(signature))
            Log.i(tag, "createKeyGenSpec full path: PASS")
        } finally {
            deleteKey(alias)
        }
    }

    @Test
    fun `createKeyGenSpec standard Builder path`() {
        testStandardBuilderPath("filo_test_builder_${System.currentTimeMillis()}")
    }

    private fun testStandardBuilderPath(alias: String) {
        try {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build()
            Log.i(tag, "Builder spec created: ${spec::class.simpleName}")

            try {
                val kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
                kpg.initialize(spec)
                val kp = kpg.generateKeyPair()
                assertNotNull("Ed25519 key generated", kp.private)
                Log.i(tag, "Ed25519 Builder path: PASS")
            } catch (e: Exception) {
                Log.w(tag, "Ed25519 Builder path: FAIL ${e::class.simpleName}")

                val kpgEc = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
                val specEc = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests("SHA-256")
                    .build()
                kpgEc.initialize(specEc)
                val kp = kpgEc.generateKeyPair()
                val ecKey = kp.public as ECPublicKey
                val curveField = ecKey.params.javaClass.getMethod("getField").invoke(ecKey.params) as java.security.spec.EllipticCurve
                val fieldSize = (curveField.javaClass.getMethod("getFieldSize").invoke(curveField)) as Int
                assertEquals("Default curve must be P-256", 256, fieldSize)
                Log.i(tag, "P-256 Builder path: PASS (field size=$fieldSize)")
            } finally {
                deleteKey(alias)
            }
        } catch (e: Exception) {
            Log.e(tag, "Builder path failed: ${e::class.simpleName} - ${e.message}")
            throw e
        }
    }

    // ── 5. IdentityManager End-to-End ────────────────────────────────────

    @Test
    fun `IdentityManager end-to-end first and second call`() {
        val im = com.filo.transfer.core.network.security.crypto.IdentityManager

        val kp1 = im.getOrCreateIdentity()
        assertNotNull("KeyPair must not be null", kp1)
        assertNotNull("Private key must not be null", kp1.private)
        assertNotNull("Public key must not be null", kp1.public)

        val pubBytes1 = im.identityPublicKey
        val fingerprint1 = im.fingerprint
        Log.i(tag, "Identity: algorithm=${im.identityAlgorithm}, fp_len=${fingerprint1.length}")

        val testMsg = "Filo-IdentityManager-E2E".toByteArray()
        val sig1 = im.sign(testMsg)
        assertTrue("Sign must not return null", sig1 != null)
        val sig1NonNull = sig1!!

        val alg = im.identityAlgorithm
        val verified = im.verifySignature(pubBytes1, alg, testMsg, sig1NonNull)
        assertTrue("Signature must verify", verified)

        val kp2 = im.getOrCreateIdentity()
        val pubBytes2 = im.identityPublicKey
        val fingerprint2 = im.fingerprint

        assertArrayEquals("Public key must be stable", pubBytes1, pubBytes2)
        assertEquals("Fingerprint must be stable", fingerprint1, fingerprint2)

        val sig2 = im.sign(testMsg)
        assertTrue("Second sign must not return null", sig2 != null)
        val sig2NonNull = sig2!!
        val verified2 = im.verifySignature(pubBytes2, alg, testMsg, sig2NonNull)
        assertTrue("Second signature must verify", verified2)

        Log.i(tag, "IdentityManager E2E: PASS")
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun deleteKey(alias: String) {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(alias)) {
                val method = KeyStore::class.java.getMethod("deleteAlias", String::class.java)
                method.invoke(ks, alias)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to delete alias $alias: ${e.message}")
        }
    }
}
