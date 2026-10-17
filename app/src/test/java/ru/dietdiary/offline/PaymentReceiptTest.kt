package ru.dietdiary.offline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class PaymentReceiptTest {
    private val verifier = PaymentReceiptVerifier(publicKey)

    @Test fun validLiveReceiptWorksOffline() {
        val proof = verifier.verify(signed(claims()))
        assertEquals("42123", proof?.invoiceId)
        assertEquals("c6c4a677-bd61-43c3-9318-f074632ae5f1", proof?.licenseId)
        assertEquals(1_792_230_000_000L, proof?.purchasedAt)
    }

    @Test fun fingerprintMatchesServerDerDigest() {
        val expected = MessageDigest.getInstance("SHA-256").digest(keys.public.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, verifier.publicKeySha256)
        assertNull(PaymentReceiptVerifier("").publicKeySha256)
    }

    @Test fun modifyingPayloadWithoutSigningNeverGrantsPurchase() {
        val original = signed(claims())
        val changedPayload = encode(claims().put("invoiceId", "42124").toString().toByteArray())
        assertNull(verifier.verify(changedPayload + "." + original.substringAfter('.')))
    }

    @Test fun corruptSignatureAndAnotherSigningKeyAreRejected() {
        val original = signed(claims())
        val signature = Base64.getUrlDecoder().decode(original.substringAfter('.'))
        signature[signature.lastIndex] = (signature.last().toInt() xor 1).toByte()
        assertNull(verifier.verify(original.substringBefore('.') + "." + encode(signature)))
        assertNull(verifier.verify(signed(claims(), otherKeys)))
    }

    @Test fun evenAuthenticTestPaymentsDoNotDisableAds() {
        assertNull(verifier.verify(signed(claims().put("mode", "test"))))
    }

    @Test fun signedReceiptForAnotherProductPriceOrCurrencyIsRejected() {
        listOf("product" to "premium", "amount" to "1.49", "amount" to "149",
            "currency" to "USD", "currency" to "rub").forEach { (key, value) ->
            assertNull("Accepted $key=$value", verifier.verify(signed(claims().put(key, value))))
        }
    }

    @Test fun claimsMustHaveExactTypesAndIdentifiers() {
        listOf("version" to "1", "version" to true, "amount" to 149,
            "purchasedAt" to "1792230000000", "purchasedAt" to 0L,
            "invoiceId" to 42123, "invoiceId" to "-1", "invoiceId" to "042123",
            "licenseId" to "not-a-uuid").forEach { (key, value) ->
            assertNull("Accepted $key=$value", verifier.verify(signed(claims().put(key, value))))
        }
        assertNull(verifier.verify(signed(claims().put("extra", true))))
        assertNull(verifier.verify(signed(claims().apply { remove("amount") })))
        assertNull(verifier.verify(signedRaw(claims().toString().replace("\"version\":1", "\"version\":1.0"))))
    }

    @Test fun malformedTokensAndBooleanFlagsAreNotReceipts() {
        val original = signed(claims())
        listOf("true", "false", "{\"adFree\":true}", "", original + ".extra",
            original.substringBefore('.') + "=." + original.substringAfter('.'),
            " " + original, original + "\n").forEach { assertNull(verifier.verify(it)) }
        assertNull(verifier.verify(null))
        assertFalse(PaymentReceiptVerifier("not a public key").configured)
        assertNull(PaymentReceiptVerifier("").verify(original))
    }

    @Test fun signedReceiptIsPortableWithoutRecoveryCredentials() {
        val receipt = signed(claims())
        val data = AppData(emptyList(), settings = mapOf(AD_FREE_RECEIPT_SETTING to receipt))
        val encoded = DataJson.encode(data)
        val decoded = DataJson.decode(encoded)
        assertNotNull(verifier.verify(decoded.settings[AD_FREE_RECEIPT_SETTING]))
        assertFalse(encoded.contains("clientToken"))
        assertFalse(encoded.contains("idempotencyKey"))
    }

    @Test fun syncMergeCarriesReceiptAsASetting() {
        val purchased = AppData(emptyList(), settings = mapOf(AD_FREE_RECEIPT_SETTING to signed(claims())))
        val state = SyncMerge.initial(purchased, emptyList(), "device-a", 10_000L)
        val reloaded = SyncMerge.decode(SyncMerge.encode(purchased, state))
        assertNotNull(verifier.verify(reloaded.data.settings[AD_FREE_RECEIPT_SETTING]))
    }

    private fun claims() = JSONObject()
        .put("version", 1).put("product", "ad_free_lifetime").put("invoiceId", "42123")
        .put("amount", "149.00").put("currency", "RUB").put("purchasedAt", 1_792_230_000_000L)
        .put("mode", "live").put("licenseId", "c6c4a677-bd61-43c3-9318-f074632ae5f1")

    private fun signed(claims: JSONObject, pair: KeyPair = keys): String = signedRaw(claims.toString(), pair)

    private fun signedRaw(claims: String, pair: KeyPair = keys): String {
        val payload = encode(claims.toByteArray(Charsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(payload.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$payload.${encode(signature)}"
    }

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val otherKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    }
}
