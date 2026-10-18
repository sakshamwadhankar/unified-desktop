package dev.unifieddesktop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager

/**
 * Result of the accessory permission dialog (manager 21:55, user path - no adb). The
 * attach activity requests the permission while the link service is already running as a
 * foreground service; on grant this opens the accessory and hands the FD to it.
 */
class Phase4AccessoryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        @Suppress("DEPRECATION")
        val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
        Phase4Log.say(ctx, "ACC permission result granted=$granted m=${acc?.manufacturer} model=${acc?.model}")
        if (granted && acc != null) Phase4Aoa.handOff(ctx, acc)
    }
}
