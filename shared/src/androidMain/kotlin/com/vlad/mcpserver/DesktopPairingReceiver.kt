package com.vlad.mcpserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class DesktopPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val pair = validateDesktopPairing(DesktopPairing(
                "https://localhost:8766",
                intent.getStringExtra("key").orEmpty(),
                intent.getStringExtra("fingerprint").orEmpty()
            ))
            val code = intent.getStringExtra("code").orEmpty()
            require(code.matches(Regex("[0-9]{2}"))) { "Invalid confirmation code." }
            val encrypted = encryptSecret(pair.key) ?: error("Cannot protect USB pairing key.")
            check(context.getSharedPreferences("desktop-usb-offer", Context.MODE_PRIVATE).edit()
                .clear().putString("key", encrypted).putString("fingerprint", pair.fingerprint)
                .putString("code", code).putLong("expires", SystemClock.elapsedRealtime() + 120_000).commit()) {
                "Cannot save USB pairing offer."
            }
            resultCode = 1
            resultData = "USB pairing ready"
        } catch (failure: Exception) {
            android.util.Log.e("DesktopPairing", "Cannot accept USB pairing offer", failure)
            resultCode = 0
            resultData = "USB pairing rejected"
        }
    }
}
