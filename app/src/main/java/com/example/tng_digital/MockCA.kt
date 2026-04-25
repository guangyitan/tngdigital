package com.example.tng_digital

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * MockCA simulates an offline Certificate Authority.
 *
 * In production, both devices would have the same CA public key bundled in the APK
 * (from the real backend CA). Here, each device generates its own CA key pair on
 * first launch (stored in SharedPreferences). The CA public key is exchanged
 * out-of-band through the QR payload and certificate fields so each side can
 * validate the other's cert — identical to how a pre-cached CA public key works.
 *
 * Canonical cert data strings:
 *   Vendor : "vendor_id=V|device_id=D|public_key=K|merchant_name=N|expiry=E"
 *   Consumer: "consumer_id=C|device_id=D|public_key=K|expiry=E|max_spend=M"
 */
object MockCA {

    private const val PREFS_NAME  = "tng_mock_ca_prefs"
    private const val PREFS_PUB   = "ca_pub_b64"
    private const val PREFS_PRIV  = "ca_priv_b64"

    private var keyPair: KeyPair? = null

    fun init(context: Context) {
        if (keyPair != null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pubB64  = prefs.getString(PREFS_PUB,  null)
        val privB64 = prefs.getString(PREFS_PRIV, null)
        if (pubB64 != null && privB64 != null) {
            runCatching {
                val pub  = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(Base64.decode(pubB64,  Base64.NO_WRAP)))
                val priv = KeyFactory.getInstance("EC")
                    .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                keyPair = KeyPair(pub, priv)
            }
        }
        if (keyPair == null) {
            val kpg = KeyPairGenerator.getInstance("EC")
                .also { it.initialize(ECGenParameterSpec("secp256r1")) }
            val kp = kpg.generateKeyPair()
            prefs.edit()
                .putString(PREFS_PUB,  Base64.encodeToString(kp.public.encoded,  Base64.NO_WRAP))
                .putString(PREFS_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
                .apply()
            keyPair = kp
        }
    }

    val publicKeyB64: String
        get() = Base64.encodeToString(keyPair!!.public.encoded, Base64.NO_WRAP)

    fun sign(certData: String): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair!!.private)
        sig.update(certData.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    fun verify(certData: String, caSignatureB64: String, caPublicKeyB64: String): Boolean =
        runCatching {
            if (caPublicKeyB64.isBlank() || caSignatureB64.isBlank()) return@runCatching false
            val pub = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(caPublicKeyB64, Base64.NO_WRAP)))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pub)
            sig.update(certData.toByteArray(Charsets.UTF_8))
            sig.verify(Base64.decode(caSignatureB64, Base64.NO_WRAP))
        }.getOrDefault(false)

    fun vendorCertData(
        vendorId: String, deviceId: String, publicKey: String,
        merchantName: String, expiry: String
    ) = "vendor_id=$vendorId|device_id=$deviceId|public_key=$publicKey|merchant_name=$merchantName|expiry=$expiry"

    fun consumerCertData(
        consumerId: String, deviceId: String, publicKey: String,
        expiry: String, maxSpend: Double
    ) = "consumer_id=$consumerId|device_id=$deviceId|public_key=$publicKey|expiry=$expiry|max_spend=$maxSpend"
}
