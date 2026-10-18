package dev.unifieddesktop.app

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle

/**
 * AOA attachment landing activity (Phase 4, item 8). Android launches this when the PC
 * switches the phone into accessory mode and our filter matches; it asks for the
 * one-tap accessory permission and logs everything it sees. The actual TLS session over
 * the accessory streams is delivered by the link service in item 8.
 */
class Phase4AccessoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
        Phase4Log.say(
            this,
            "ACC_ATTACHED m=${acc?.manufacturer} model=${acc?.model} v=${acc?.version}" +
                " granted=${if (acc == null) "?" else (getSystemService(UsbManager::class.java).hasPermission(acc)).toString()}"
        )
        if (acc != null) {
            val um = getSystemService(UsbManager::class.java)
            if (!um.hasPermission(acc)) {
                val pi = android.app.PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                    android.app.PendingIntent.FLAG_MUTABLE
                )
                um.requestPermission(acc, pi)
                Phase4Log.say(this, "ACC permission requested")
            } else {
                Phase4Log.say(this, "ACC permission already granted")
            }
        }
        // jump back to the harness so the session flow can continue on one screen
        startActivity(
            Intent(this, HarnessActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.unifieddesktop.app.p4.USB_PERMISSION"
    }
}
