package ru.dietdiary.offline

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.dietdiary.robokassa.RobokassaCheckout

@Composable
internal fun PaymentSettingsCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val payments = remember { Payments.get(context) }
    val active = PaymentEntitlements.adFree
    LaunchedEffect(active) { if (!active) payments.refreshAvailability() }
    Panel {
        Text(tr(if (active) R.string.payment_active else R.string.payment_title),
            fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(tr(when {
            active -> R.string.payment_active_help
            payments.availability?.available == true -> R.string.payment_price_help
            else -> R.string.payment_restore_help_short
        }), color = Muted)
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("open_payments")) {
            Text(tr(if (!active && payments.availability?.available == true)
                R.string.payment_open_purchase else R.string.payment_manage))
        }
    }
}

@Composable
internal fun PaymentScreen(onBack: () -> Unit, onCloud: () -> Unit, onPrivacy: () -> Unit) {
    val context = LocalContext.current
    val payments = remember { Payments.get(context) }
    val scope = rememberCoroutineScope()
    val active = PaymentEntitlements.adFree
    var notice by remember { mutableStateOf<String?>(null) }
    val checkout = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        DietAds.suppressNextEntry()
        val outcome = RobokassaCheckout.readOutcome(result.resultCode, result.data)
        scope.launch {
            payments.checkPending(poll = outcome == RobokassaCheckout.Outcome.RETURNED,
                pendingMessage = when (outcome) {
                    RobokassaCheckout.Outcome.CANCELLED -> R.string.payment_cancelled
                    RobokassaCheckout.Outcome.ERROR -> R.string.payment_open_failed
                    RobokassaCheckout.Outcome.RETURNED -> R.string.payment_waiting
                })
        }
    }
    LaunchedEffect(active) {
        if (!active) payments.refreshAvailability()
        if (payments.hasPending) payments.checkPending()
    }

    fun openOffer() {
        try {
            DietAds.suppressNextEntry()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://diet.efreitor2001.ru/offer/")))
        } catch (_: Exception) { notice = tr(R.string.data_page_open_failed) }
    }

    Column(Modifier.fillMaxSize().testTag("payment_screen").verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { BackNavigationLabel(tr(R.string.data_back)) }
        PageTitle(tr(R.string.payment_title), tr(R.string.payment_free_features))
        if (active) {
            Panel {
                Text(tr(R.string.payment_active), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("payment_active"))
                Text(tr(R.string.payment_active_help), color = Muted)
                Text(tr(R.string.payment_backup_reminder), color = Muted)
            }
        } else {
            if (payments.checkingAvailability || payments.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (payments.availability?.available == true) {
                Panel {
                    Text(tr(R.string.payment_price), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(tr(R.string.payment_price_help), color = Muted)
                    Text(tr(R.string.payment_provider), color = Muted)
                    if (payments.availability?.mode == "test") {
                        Text(tr(R.string.payment_test_notice), color = MaterialTheme.colorScheme.error)
                    }
                    Button(enabled = payments.canBuy, modifier = Modifier.fillMaxWidth().testTag("payment_buy"), onClick = {
                        scope.launch {
                            val order = payments.prepareCheckout() ?: return@launch
                            try {
                                DietAds.suppressNextEntry()
                                checkout.launch(RobokassaCheckout.createIntent(context,
                                    order.checkoutUrl!!, order.successUrl!!, order.failUrl!!, order.postParams))
                            } catch (_: Exception) { payments.checkoutCouldNotOpen() }
                        }
                    }) {
                        Text(tr(if (payments.hasPending) R.string.payment_continue else R.string.payment_buy))
                    }
                    Text(tr(R.string.payment_offer_acceptance), style = MaterialTheme.typography.bodySmall, color = Muted)
                    TextButton(onClick = ::openOffer, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.payment_offer)) }
                    TextButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.core_privacy)) }
                }
            } else if (!payments.checkingAvailability) {
                Panel {
                    Text(tr(payments.availabilityMessage), color = Muted)
                    if (BuildConfig.PAYMENT_ENABLED) {
                        OutlinedButton(enabled = !payments.busy, onClick = { scope.launch { payments.refreshAvailability() } },
                            modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.payment_retry_availability)) }
                    }
                }
            }
            if (payments.hasPending || payments.messageResource != R.string.payment_idle) {
                Panel {
                    Text(tr(payments.messageResource), modifier = Modifier.testTag("payment_status"))
                    payments.invoiceId?.let { Text(tr(R.string.payment_order_id, it), color = Muted,
                        style = MaterialTheme.typography.bodySmall) }
                    if (payments.hasPending) {
                        OutlinedButton(enabled = !payments.busy, onClick = { scope.launch { payments.checkPending(poll = true) } },
                            modifier = Modifier.fillMaxWidth().testTag("payment_check")) { Text(tr(R.string.payment_check)) }
                    }
                }
            }
        }
        Panel {
            Text(tr(R.string.payment_restore_title), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(tr(R.string.payment_restore_help), color = Muted)
            OutlinedButton(onClick = onCloud, modifier = Modifier.fillMaxWidth().testTag("payment_restore")) {
                Text(tr(R.string.payment_restore_cloud))
            }
            Text(tr(R.string.payment_restore_file_help), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    if (notice != null) AlertDialog(onDismissRequest = { notice = null }, title = { Text(tr(R.string.data_opening_page)) },
        text = { Text(notice!!) }, confirmButton = { TextButton(onClick = { notice = null }) { Text(tr(R.string.data_got_it)) } })
}
