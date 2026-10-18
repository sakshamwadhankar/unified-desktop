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
        // User path (manager 21:55): the accessory attach itself starts the link service.
        try {
            startForegroundService(
                Intent(this, Phase4LinkService::class.java).setAction(Phase4LinkService.ACTION_START)
            )
        } catch (t: Throwable) {
            Phase4Log.say(this, "ACC service start failed: $t")
        }
        if (acc != null) {
            val um = getSystemService(UsbManager::class.java)
            if (!um.hasPermission(acc)) {
                val pi = android.app.PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent(this, Phase4AccessoryReceiver::class.java).setAction(ACTION_USB_PERMISSION),
                    android.app.PendingIntent.FLAG_MUTABLE
                )
                um.requestPermission(acc, pi)
                Phase4Log.say(this, "ACC permission requested")
            } else {
                Phase4Log.say(this, "ACC permission already granted")
                Phase4Aoa.handOff(this, acc)
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
