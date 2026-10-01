package com.mokobara.mokoapp.uhf

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.deviceapi.entity.UHFTAGInfo
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Thin wrapper around Chainway [RFIDWithUHFUART] for C72 handheld UHF.
 * Single-tag mode: short inventory, keep strongest RSSI, then stop.
 */
class UhfReaderHelper(
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(ready: Boolean, message: String)
        fun onScanning(active: Boolean)
        fun onTag(epc: String, rssi: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var reader: RFIDWithUHFUART? = null
    private val ready = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private var scanFuture: Future<*>? = null

    fun init(context: Context) {
        executor.execute {
            try {
                val uhf = RFIDWithUHFUART.getInstance()
                val ok = uhf.init(context.applicationContext)
                if (!ok) {
                    ready.set(false)
                    reader = null
                    post { listener.onStatus(false, "UHF init failed") }
                    post { listener.onError("UHF init failed. Use a Chainway C72 device.") }
                    return@execute
                }

                // Mid power: good for close single-tag mapping without max bleed.
                try {
                    uhf.setPower(DEFAULT_POWER)
                } catch (t: Throwable) {
                    Log.w(TAG, "setPower skipped: ${t.message}")
                }

                try {
                    uhf.setEPCMode()
                } catch (t: Throwable) {
                    Log.w(TAG, "setEPCMode skipped: ${t.message}")
                }

                reader = uhf
                ready.set(true)
                post { listener.onStatus(true, "UHF ready") }
            } catch (t: Throwable) {
                Log.e(TAG, "init error", t)
                ready.set(false)
                reader = null
                post { listener.onStatus(false, "UHF unavailable") }
                post { listener.onError(t.message ?: "UHF init error") }
            }
        }
    }

    fun isReady(): Boolean = ready.get()

    fun isScanning(): Boolean = scanning.get()

    /**
     * Run a short inventory and return the strongest tag (highest RSSI magnitude closest to 0 / least negative).
     */
    fun scanSingleTag(windowMs: Long = SCAN_WINDOW_MS) {
        if (!ready.get()) {
            post { listener.onError("UHF not ready") }
            return
        }
        if (!scanning.compareAndSet(false, true)) {
            post { listener.onError("Scan already running") }
            return
        }

        post { listener.onScanning(true) }

        scanFuture = executor.submit {
            val uhf = reader
            if (uhf == null) {
                scanning.set(false)
                post { listener.onScanning(false) }
                post { listener.onError("UHF not ready") }
                return@submit
            }

            var best: UHFTAGInfo? = null
            var bestScore = Double.NEGATIVE_INFINITY

            try {
                val started = uhf.startInventoryTag()
                if (!started) {
                    uhf.stopInventory()
                    post { listener.onError("Could not start inventory") }
                    return@submit
                }

                val deadline = System.currentTimeMillis() + windowMs
                while (System.currentTimeMillis() < deadline && scanning.get()) {
                    val info = uhf.readTagFromBuffer()
                    if (info == null) {
                        try {
                            Thread.sleep(20)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }

                    val epc = info.getEPC()?.trim().orEmpty()
                    if (epc.isEmpty()) continue

                    val score = rssiScore(info.getRssi())
                    if (score > bestScore) {
                        bestScore = score
                        best = info
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "scan error", t)
                post { listener.onError(t.message ?: "Scan failed") }
            } finally {
                try {
                    uhf.stopInventory()
                } catch (t: Throwable) {
                    Log.w(TAG, "stopInventory: ${t.message}")
                }
                scanning.set(false)
                post { listener.onScanning(false) }
            }

            val tag = best
            if (tag == null) {
                post { listener.onError("No tag found. Hold closer and try again.") }
            } else {
                val epc = tag.getEPC()?.trim().orEmpty()
                if (epc.isEmpty()) {
                    post { listener.onError("No tag found. Hold closer and try again.") }
                } else {
                    val rssi = tag.getRssi()?.trim().orEmpty().ifEmpty { "—" }
                    post { listener.onTag(epc, rssi) }
                }
            }
        }
    }

    fun stopScan() {
        if (!scanning.get()) return
        scanning.set(false)
        scanFuture?.cancel(true)
        executor.execute {
            try {
                reader?.stopInventory()
            } catch (t: Throwable) {
                Log.w(TAG, "stopScan: ${t.message}")
            } finally {
                post { listener.onScanning(false) }
            }
        }
    }

    fun release() {
        scanning.set(false)
        scanFuture?.cancel(true)
        executor.execute {
            try {
                reader?.stopInventory()
            } catch (_: Throwable) {
            }
            try {
                reader?.free()
            } catch (t: Throwable) {
                Log.w(TAG, "free: ${t.message}")
            } finally {
                reader = null
                ready.set(false)
                post { listener.onStatus(false, "UHF released") }
            }
        }
        executor.shutdown()
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    /**
     * Chainway RSSI is typically a negative dBm string (e.g. "-45.2").
     * Stronger signal = larger algebraic value (closer to 0).
     */
    private fun rssiScore(raw: String?): Double {
        if (raw.isNullOrBlank()) return Double.NEGATIVE_INFINITY
        val cleaned = raw.trim().replace("dBm", "", ignoreCase = true).trim()
        val value = cleaned.toDoubleOrNull() ?: return Double.NEGATIVE_INFINITY
        // Prefer less-negative values; if device reports positive magnitude, invert.
        return if (value > 0) -abs(value) else value
    }

    companion object {
        private const val TAG = "UhfReaderHelper"
        const val DEFAULT_POWER = 25
        const val SCAN_WINDOW_MS = 1500L
    }
}
