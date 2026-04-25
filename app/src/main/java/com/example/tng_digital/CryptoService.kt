package com.example.tng_digital

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoService {

    private const val KEY_ALIAS = "tng_digital_signing_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun getOrCreateKeyPair(): KeyPair {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        val privateKey = ks.getKey(KEY_ALIAS, null) as PrivateKey
        val publicKey = ks.getCertificate(KEY_ALIAS).publicKey
        return KeyPair(publicKey, privateKey)
    }

    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun publicKeyToBase64(publicKey: PublicKey): String =
        Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    fun publicKeyFromBase64(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun sign(data: String, privateKey: PrivateKey): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    fun verify(data: String, signatureB64: String, publicKeyB64: String): Boolean {
        return try {
            val pubKey = publicKeyFromBase64(publicKeyB64)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pubKey)
            sig.update(data.toByteArray(Charsets.UTF_8))
            sig.verify(Base64.decode(signatureB64, Base64.NO_WRAP))
        } catch (e: Exception) {
            false
        }
    }

    fun deriveSessionKey(myPrivateKey: PrivateKey, theirPublicKeyB64: String): SecretKey {
        val theirPublicKey = publicKeyFromBase64(theirPublicKeyB64)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivateKey)
        ka.doPhase(theirPublicKey, true)
        val sharedSecret = ka.generateSecret()
        val derived = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        return SecretKeySpec(derived, "AES")
    }

    fun encrypt(plaintext: String, sessionKey: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedB64: String, sessionKey: SecretKey): String {
        val combined = Base64.decode(encryptedB64, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    fun sha256Hash(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64.encodeToString(digest.digest(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}
