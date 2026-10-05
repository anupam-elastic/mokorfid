package com.mokobara.mokoapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * C72 Keyboard Helper / ScanService / stock UHF demo can hold the barcode+UHF UART.
 * Release them before switching modules.
 */
object DeviceBusHandoff {
    private const val TAG = "DeviceBusHandoff"

    private const val PKG_SCANNER = "com.rscja.scanner"
    /** Stock Chainway UHF demo — commonly leaves /dev/ttyS1 locked. */
    private const val PKG_UHF_DEMO = "com.rscja.ht"

    private const val ACTION_STOP = "com.rscja.scanner.action.STOP_BARCODE_RFID"
    private const val ACTION_CLOSE = "com.rscja.scanner.action.CLOSE_BARCODE_RFID"
    private const val ACTION_DISABLE = "com.rscja.scanner.action.DISABLE_FUNCTION_BARCODE_RFID"
    private const val ACTION_OPEN = "com.rscja.scanner.action.OPEN_BARCODE_RFID"
    private const val ACTION_ENABLE = "com.rscja.scanner.action.ENABLE_FUNCTION_BARCODE_RFID"
    private const val ACTION_STOP_BARCODE_SERVICE = "com.rscja.service.stop_barcode"
    private const val ACTION_CONTINUOUS = "com.rscja.scanner.action.CONTINUOUS_SCAN_BARCODE_RFID"
    private const val ACTION_KE_STOP = "ACTION_KE_STOP"
    private const val ACTION_2DS_DISABLE = "ACTION_2DS_DISABLE"
    private const val ACTION_SCAN_RELEASE = "com.rscja.scanner.action.SCAN_RELEASESCAN"

    /** Release Keyboard Helper / ScanService / UHF demo so our app can open UHF. */
    fun prepareForUhf(context: Context) {
        Log.i(TAG, "prepareForUhf: releasing scanner bus")
        killBackgroundHolders(context)
        // Turn off continuous scan modes that keep re-opening UHF.
        sendContinuousOff(context)
        send(context, ACTION_STOP)
        send(context, ACTION_CLOSE)
        send(context, ACTION_DISABLE)
        send(context, ACTION_KE_STOP)
        send(context, ACTION_2DS_DISABLE)
        send(context, ACTION_SCAN_RELEASE)
        send(context, ACTION_STOP_BARCODE_SERVICE)
    }

    /** Allow system barcode helper again when returning to USN scanning. */
    fun prepareForBarcode(context: Context) {
        Log.i(TAG, "prepareForBarcode: enabling scanner helper")
        send(context, ACTION_ENABLE)
        send(context, ACTION_OPEN)
    }

    private fun killBackgroundHolders(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // Best-effort: drops background UHF demo that locks ttyS1 after tests.
            am.killBackgroundProcesses(PKG_UHF_DEMO)
            Log.i(TAG, "killBackgroundProcesses $PKG_UHF_DEMO")
        } catch (t: Throwable) {
            Log.w(TAG, "killBackgroundHolders: ${t.message}")
        }
    }

    private fun sendContinuousOff(context: Context) {
        try {
            val intent = Intent(ACTION_CONTINUOUS).setPackage(PKG_SCANNER)
            // Chainway receivers accept several extra shapes; send common ones.
            intent.putExtra("value", false)
            intent.putExtra("continuescan", false)
            intent.putExtra("continuous", false)
            intent.putExtra("enable", false)
            context.sendBroadcast(intent)
            context.sendBroadcast(Intent(ACTION_CONTINUOUS).putExtra("value", false))
        } catch (t: Throwable) {
            Log.w(TAG, "sendContinuousOff failed: ${t.message}")
        }
    }

    private fun send(context: Context, action: String) {
        try {
            val intent = Intent(action).setPackage(PKG_SCANNER)
            context.sendBroadcast(intent)
            // Also send without package for older receivers.
            context.sendBroadcast(Intent(action))
        } catch (t: Throwable) {
            Log.w(TAG, "broadcast $action failed: ${t.message}")
        }
    }
}
