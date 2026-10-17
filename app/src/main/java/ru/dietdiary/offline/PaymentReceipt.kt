package ru.dietdiary.offline

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

internal const val AD_FREE_RECEIPT_SETTING = "adFreeReceipt"

internal data class AdFreeReceipt(val invoiceId: String, val licenseId: String, val purchasedAt: Long)

/**
 * An offline proof, never a preference flag. The server signs the ASCII base64url payload
 * with RSASSA-PKCS1-v1_5 / SHA-256; the APK contains only its X.509 SPKI public key.
 * No redirect, SDK result, test payment or imported Boolean can grant an entitlement.
 */
internal class PaymentReceiptVerifier(publicKeyBase64: String) {
    private val key: RSAPublicKey? = runCatching {
        require(publicKeyBase64.length in 300..16_384)
        val bytes = Base64.getDecoder().decode(publicKeyBase64)
        (KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes)) as RSAPublicKey)
            .also { require(it.modulus.bitLength() in 2048..8192) }
    }.getOrNull()

    val configured: Boolean get() = key != null
    val publicKeySha256: String? get() = key?.encoded?.let { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun verify(compact: String?): AdFreeReceipt? = runCatching {
        val publicKey = key ?: return null
        require(compact != null && compact.length in 100..12_000)
        val pieces = compact.split('.')
        require(pieces.size == 2)
        val payload = decodeCanonical(pieces[0])
        val signature = decodeCanonical(pieces[1])
        require(payload.size <= 4096 && signature.size == (publicKey.modulus.bitLength() + 7) / 8)
        val checked = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(pieces[0].toByteArray(Charsets.US_ASCII))
            verify(signature)
        }
        require(checked)
        val claims = JSONObject(payload.toString(Charsets.UTF_8))
        require(claims.keys().asSequence().toSet() == setOf(
            "version", "product", "invoiceId", "amount", "currency", "purchasedAt", "mode", "licenseId"
        ))
        require(exactLong(claims, "version") == 1L)
        require(exactString(claims, "product") == "ad_free_lifetime")
        require(exactString(claims, "amount") == "149.00")
        require(exactString(claims, "currency") == "RUB")
        require(exactString(claims, "mode") == "live")
        val invoiceId = exactString(claims, "invoiceId")
        require(invoiceId.matches(Regex("[1-9][0-9]{0,18}")))
        val licenseId = exactString(claims, "licenseId")
        require(UUID.fromString(licenseId).toString() == licenseId)
        val purchasedAt = exactLong(claims, "purchasedAt")
        require(purchasedAt in 1..253_402_300_799_999L)
        AdFreeReceipt(invoiceId, licenseId, purchasedAt)
    }.getOrNull()

    private fun decodeCanonical(value: String): ByteArray {
        require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9_-]+")))
        return Base64.getUrlDecoder().decode(value).also {
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value)
        }
    }

    private fun exactString(json: JSONObject, key: String) = (json.get(key) as? String)
        ?: throw IllegalArgumentException("Invalid receipt claim")

    private fun exactLong(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }
}
