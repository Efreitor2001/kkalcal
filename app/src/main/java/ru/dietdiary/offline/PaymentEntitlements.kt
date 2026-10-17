package ru.dietdiary.offline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Updated synchronously whenever a local import, cloud merge or purchase changes the diary. */
internal object PaymentEntitlements {
    private val verifier by lazy { PaymentReceiptVerifier(BuildConfig.PAYMENT_PUBLIC_KEY_BASE64) }
    private var inspected: String? = null
    var adFree by mutableStateOf(false)
        private set

    fun verify(receipt: String?): AdFreeReceipt? = verifier.verify(receipt)

    @Synchronized
    fun refresh(receipt: String?) {
        if (receipt == inspected) return
        inspected = receipt
        val entitled = verify(receipt) != null
        if (adFree == entitled) return
        adFree = entitled
        DietAds.entitlementChanged(entitled)
    }

    /** Restoring an older diary or a forged setting must not erase an existing valid purchase. */
    fun preservingPurchase(current: AppData, candidate: AppData): AppData {
        val existing = current.settings[AD_FREE_RECEIPT_SETTING] ?: return candidate
        if (candidate.settings[AD_FREE_RECEIPT_SETTING] == existing) return candidate
        return if (verify(existing) != null && verify(candidate.settings[AD_FREE_RECEIPT_SETTING]) == null)
            candidate.copy(settings = candidate.settings + (AD_FREE_RECEIPT_SETTING to existing))
        else candidate
    }
}
