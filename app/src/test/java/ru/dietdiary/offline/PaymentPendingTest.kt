package ru.dietdiary.offline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PaymentPendingTest {
    @Test fun crashBeforeFirstResponseReplaysSameOrderAndRecoveryToken() {
        val original = PendingPayment.create("rustore", "https://payments.example.com")
        val recovered = PendingPayment.decode(original.encode())
        assertEquals(original, recovered)
        assertEquals(original.requestBody(), recovered.requestBody())
        assertNull(recovered.invoiceId)
        assertEquals(32, Base64.getUrlDecoder().decode(recovered.clientToken).size)
        assertEquals(setOf("store", "idempotencyKey", "clientToken"),
            JSONObject(recovered.requestBody()).keys().asSequence().toSet())
    }

    @Test fun reopeningPendingCheckoutKeepsItsInvoiceAndSignedForm() {
        val original = PendingPayment.create("googlePlay", "https://payments.example.com").copy(
            invoiceId = "87654321", checkoutUrl = "https://auth.robokassa.ru/Merchant/Index.aspx",
            postParams = "MerchantLogin=example&OutSum=149.00&InvId=87654321&SignatureValue=example",
            successUrl = "https://payments.example.com/robokassa/success",
            failUrl = "https://payments.example.com/robokassa/fail", mode = "test")
        val recovered = PendingPayment.decode(original.encode())
        assertEquals(original, recovered)
        assertEquals(original.idempotencyKey, recovered.idempotencyKey)
        assertEquals(original.clientToken, recovered.clientToken)
    }

    @Test fun separatePurchasesHaveUnrelatedCredentials() {
        val first = PendingPayment.create("huawei", "https://payments.example.com")
        val second = PendingPayment.create("huawei", "https://payments.example.com")
        assertNotEquals(first.idempotencyKey, second.idempotencyKey)
        assertNotEquals(first.clientToken, second.clientToken)
    }

    @Test fun originsRejectCredentialsPathsAndNonHttps() {
        assertTrue(validPaymentOrigin("https://payments.example.com"))
        listOf("http://payments.example.com", "https://payments.example.com/", "https://user@payments.example.com",
            "https://payments.example.com:444", "https://payments.example.com/api", "https://payments.example.com?q=1",
            "https://payments.example.com#fragment", "https://").forEach { assertFalse(it, validPaymentOrigin(it)) }
        assertTrue(validCheckoutUrl("https://auth.robokassa.ru/Merchant/Index.aspx"))
        assertFalse(validCheckoutUrl("https://auth.robokassa.ru.evil.example/Merchant/Index.aspx"))
        assertFalse(validCheckoutUrl("https://auth.robokassa.ru@evil.example/Merchant/Index.aspx"))
        assertFalse(validCheckoutUrl("https://auth.robokassa.ru/Merchant/%49ndex.aspx"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptedRecoveryTokenDoesNotCreateAnotherOrder() {
        val original = PendingPayment.create("rustore", "https://payments.example.com")
        PendingPayment.decode(JSONObject(original.encode()).put("clientToken", "short").toString())
    }
}
