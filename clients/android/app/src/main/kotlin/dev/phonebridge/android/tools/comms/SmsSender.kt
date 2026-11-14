package dev.phonebridge.android.tools.comms

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import dev.phonebridge.android.tools.support.Perms
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Sends an SMS through the platform SmsManager and waits for the radio's per-part "sent" results.
 *
 * Each send registers its own not-exported runtime receiver for a unique action; the sent
 * PendingIntents are explicit (our package) and immutable, so no other app can trigger or alter
 * them. The caller has checked SEND_SMS.
 */
internal class SmsSender(private val context: Context) {
    sealed interface Outcome {
        /** Every part was accepted by the radio. */
        data class Sent(val parts: Int) : Outcome

        /** No final result within the wait; the message may still go out. */
        data class Pending(val parts: Int, val confirmedParts: Int) : Outcome

        /** The radio reported [code] for part [failedPart] (0-based). */
        data class Failed(val parts: Int, val failedPart: Int, val code: Int, val confirmedParts: Int) : Outcome
    }

    private class PartResult(val part: Int, val code: Int)

    /** Number of SMS parts [message] needs (multipart messages are billed per part). */
    fun partCount(message: String): Int = divide(smsManager(), message).size

    suspend fun send(destination: String, message: String): Outcome {
        val manager = smsManager()
        val parts = divide(manager, message)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val results = Channel<PartResult>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                results.trySend(PartResult(intent.getIntExtra(EXTRA_PART, -1), resultCode))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        var handedToRadio = false
        try {
            parts.indices.mapTo(sentIntents) { part ->
                val intent = Intent(action).setPackage(context.packageName).putExtra(EXTRA_PART, part)
                PendingIntent.getBroadcast(context, part, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            }
            withContext(Dispatchers.IO) {
                try {
                    manager.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
                } catch (e: SecurityException) {
                    throw ToolException(
                        "Android refused to send the SMS (SEND_SMS permission missing or restricted). " +
                            "Ask the user to open the PhoneBridge app > Settings > Permissions & access and grant the SMS permission.",
                    )
                } catch (e: IllegalArgumentException) {
                    throw ToolException("Android rejected the SMS: the number or the message is invalid.")
                }
            }
            handedToRadio = true
            return awaitResults(results, parts.size)
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
            results.close()
            // Results that arrive after we stop waiting simply find no receiver.
            if (!handedToRadio) sentIntents.forEach { it.cancel() }
        }
    }

    private suspend fun awaitResults(results: ReceiveChannel<PartResult>, count: Int): Outcome {
        val codes = arrayOfNulls<Int>(count)
        var failure: PartResult? = null
        withTimeoutOrNull(SENT_TIMEOUT_MS) {
            while (codes.any { it == null }) {
                // Some radios only report the last part. Once it is OK, give the others a short grace period.
                val next = if (codes[count - 1] == SmsResultCodes.OK) {
                    withTimeoutOrNull(LAST_PART_GRACE_MS) { results.receive() } ?: break
                } else {
                    results.receive()
                }
                if (next.part !in 0 until count || codes[next.part] != null) continue
                codes[next.part] = next.code
                if (next.code != SmsResultCodes.OK) {
                    failure = next
                    break
                }
            }
        }
        val confirmed = codes.count { it == SmsResultCodes.OK }
        failure?.let { return Outcome.Failed(count, it.part, it.code, confirmed) }
        return when {
            codes[count - 1] == SmsResultCodes.OK -> Outcome.Sent(count)
            else -> Outcome.Pending(count, confirmed)
        }
    }

    private fun divide(manager: SmsManager, message: String): ArrayList<String> {
        val parts = try {
            manager.divideMessage(message)
        } catch (e: RuntimeException) {
            null
        }
        return if (parts.isNullOrEmpty()) arrayListOf(message) else parts
    }

    /** The default SMS SIM; with "ask every time" on multi-SIM phones, the first active SIM. */
    private fun smsManager(): SmsManager {
        val subscriptionId = subscriptionId()
        val hasSubscription = subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
                ?: throw ToolException("This device cannot send SMS (no SMS service).")
            if (hasSubscription) base.createForSubscriptionId(subscriptionId) else base
        } else {
            @Suppress("DEPRECATION")
            if (hasSubscription) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
        }
    }

    @SuppressLint("MissingPermission") // READ_PHONE_STATE is checked just before use.
    private fun subscriptionId(): Int {
        val default = SmsManager.getDefaultSmsSubscriptionId()
        if (default != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return default
        if (!Perms.has(context, Manifest.permission.READ_PHONE_STATE)) return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val active = try {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            null
        }
        return active?.minByOrNull { it.simSlotIndex }?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    companion object {
        /** How long to wait for the radio's sent status (docs/TOOLS.md: up to 30 s). */
        const val SENT_TIMEOUT_MS = 30_000L
        private const val LAST_PART_GRACE_MS = 3_000L
        private const val EXTRA_PART = "dev.phonebridge.android.extra.SMS_PART"
    }
}
