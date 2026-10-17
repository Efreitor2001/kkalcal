package ru.dietdiary.robokassa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobokassaUrlPolicyTest {
    @Test fun checkoutRequiresExactHttpsOriginAndEndpoint() {
        assertTrue(RobokassaUrlPolicy.isCheckoutUrl(
            "https://auth.robokassa.ru/Merchant/Index.aspx?MerchantLogin=shop&SignatureValue=abc"
        ))
        listOf(
            "http://auth.robokassa.ru/Merchant/Index.aspx",
            "https://auth.robokassa.ru.evil.example/Merchant/Index.aspx",
            "https://evil.example@auth.robokassa.ru/Merchant/Index.aspx",
            "https://auth.robokassa.ru:8443/Merchant/Index.aspx",
            "https://auth.robokassa.ru/Merchant%2FIndex.aspx",
            "https://auth.robokassa.ru/Merchant/Index.aspx#other",
            "javascript:alert(1)",
            "file:///Merchant/Index.aspx"
        ).forEach { assertFalse(it, RobokassaUrlPolicy.isCheckoutUrl(it)) }
    }

    @Test fun callbackDoesNotUseVulnerablePrefixMatching() {
        val expected = "https://diet.example/payment/success?state=abc"
        assertTrue(RobokassaUrlPolicy.matchesRedirect(
            "https://diet.example/payment/success?state=abc&InvId=12", expected
        ))
        listOf(
            "https://diet.example.evil.example/payment/success?state=abc",
            "https://diet.example/payment/success-other?state=abc",
            "https://diet.example/payment/success?state=wrong",
            "https://diet.example/payment/success",
            "http://diet.example/payment/success?state=abc"
        ).forEach { assertFalse(it, RobokassaUrlPolicy.matchesRedirect(it, expected)) }
    }

    @Test fun bankHandoffAcceptsSbpAndRejectsArbitraryIntentTargets() {
        assertTrue(RobokassaUrlPolicy.isBankTarget("bank100000000111://qr.nspk.ru/AB123"))
        assertTrue(RobokassaUrlPolicy.isBankTarget("sberpay://invoicing/v2?order=123"))
        assertFalse(RobokassaUrlPolicy.isBankTarget("bank100000000111://evil.example/AB123"))
        assertFalse(RobokassaUrlPolicy.isBankTarget("javascript:alert(1)"))
        assertFalse(RobokassaUrlPolicy.isBankTarget("file:///etc/passwd"))
        assertFalse(RobokassaUrlPolicy.isBankTarget("tel:88005553535"))
        assertFalse(RobokassaUrlPolicy.isBankTarget("intent://arbitrary"))
        assertTrue(RobokassaUrlPolicy.isTrustedBankLaunchOrigin("https://auth.robokassa.ru/x"))
        assertFalse(RobokassaUrlPolicy.isTrustedBankLaunchOrigin("https://evil-robokassa.ru/x"))
        assertFalse(RobokassaUrlPolicy.isTrustedBankLaunchOrigin("http://auth.robokassa.ru/x"))
    }

    @Test fun issuerPageMayUseFragmentsButNotCleartextOrFileAccess() {
        assertTrue(RobokassaUrlPolicy.isHttpsUrl("https://issuer.example/challenge#step2"))
        assertFalse(RobokassaUrlPolicy.isHttpsUrl("http://issuer.example/challenge"))
        assertFalse(RobokassaUrlPolicy.isHttpsUrl("content://issuer.example/challenge"))
    }

    @Test fun accidentalMerchantCredentialsAreRejected() {
        assertTrue(RobokassaUrlPolicy.hasNoMerchantCredentials(
            "MerchantLogin=shop&SignatureValue=abc&OutSum=149.00&IsTest=1"
        ))
        assertFalse(RobokassaUrlPolicy.hasNoMerchantCredentials("Password1=secret"))
        assertFalse(RobokassaUrlPolicy.hasNoMerchantCredentials("passWORD%232=secret"))
        assertFalse(RobokassaUrlPolicy.hasNoMerchantCredentials("merchantPass1=secret"))
        assertFalse(RobokassaUrlPolicy.hasNoMerchantCredentials("Password%ZZ=bad"))
    }
}
