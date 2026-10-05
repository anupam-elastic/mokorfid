package com.mokobara.mokoapp.barcode

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rscja.barcode.BarcodeDecoder
import com.rscja.barcode.BarcodeFactory
import com.rscja.deviceapi.entity.BarcodeEntity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around Chainway [BarcodeDecoder] for C72 1D/2D barcode (USN).
 * Supports open/close cycles so UHF can take the bus after a USN read.
 */
class BarcodeHelper(
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(ready: Boolean, message: String)
        fun onScanning(active: Boolean)
        fun onBarcode(data: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor = Executors.newSingleThreadExecutor()
    private var decoder: BarcodeDecoder? = null
    private var appContext: Context? = null
    private val ready = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    fun init(context: Context) {
        appContext = context.applicationContext
        released.set(false)
        ensureExecutor()
        openInternal()
    }

    fun isReady(): Boolean = ready.get()

    fun isScanning(): Boolean = scanning.get()

    fun startScan() {
        if (released.get()) return
        if (!ready.get()) {
            post { listener.onError("Barcode not ready") }
            return
        }
        if (!scanning.compareAndSet(false, true)) {
            post { listener.onError("Scan already running") }
            return
        }

        post { listener.onScanning(true) }

        submit {
            if (released.get()) {
                scanning.set(false)
                post { listener.onScanning(false) }
                return@submit
            }
            val barcode = decoder
            if (barcode == null) {
                scanning.set(false)
                post { listener.onScanning(false) }
                post { listener.onError("Barcode not ready") }
                return@submit
            }
            try {
                val started = barcode.startScan()
                if (!started) {
                    scanning.set(false)
                    post { listener.onScanning(false) }
                    post { listener.onError("Could not start barcode scan") }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startScan error", t)
                scanning.set(false)
                post { listener.onScanning(false) }
                post { listener.onError(t.message ?: "Barcode scan failed") }
            }
        }
    }

    /**
     * Always stops the hardware scan and notifies [Listener.onScanning] false when a
     * scan window was active.
     */
    fun stopScan() {
        val wasScanning = scanning.getAndSet(false)
        submit {
            try {
                decoder?.stopScan()
            } catch (t: Throwable) {
                Log.w(TAG, "stopScan: ${t.message}")
            } finally {
                if (wasScanning) {
                    post { listener.onScanning(false) }
                }
            }
        }
    }

    /**
     * Stop + close the decoder so UHF can use the device bus.
     * Does not shut down the helper — call [reopen] to scan USN again.
     */
    fun close(onClosed: (() -> Unit)? = null) {
        scanning.set(false)
        submit {
            try {
                decoder?.stopScan()
            } catch (_: Throwable) {
            }
            try {
                decoder?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close: ${t.message}")
            } finally {
                decoder = null
                ready.set(false)
                post {
                    listener.onScanning(false)
                    listener.onStatus(false, "Barcode closed")
                    onClosed?.invoke()
                }
            }
        }
    }

    fun reopen() {
        released.set(false)
        ensureExecutor()
        openInternal()
    }

    fun release() {
        released.set(true)
        scanning.set(false)
        submit {
            try {
                decoder?.stopScan()
            } catch (_: Throwable) {
            }
            try {
                decoder?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "release close: ${t.message}")
            } finally {
                decoder = null
                ready.set(false)
                post { listener.onStatus(false, "Barcode released") }
            }
        }
        executor.shutdown()
    }

    private fun ensureExecutor() {
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
    }

    private fun submit(block: () -> Unit) {
        ensureExecutor()
        try {
            executor.execute(block)
        } catch (t: Throwable) {
            Log.w(TAG, "executor submit failed: ${t.message}")
        }
    }

    private fun openInternal() {
        val context = appContext
        if (context == null) {
            post { listener.onError("Barcode context missing") }
            return
        }
        submit {
            if (released.get()) return@submit
            // Already open
            if (decoder != null && ready.get()) {
                post { listener.onStatus(true, "Barcode ready") }
                return@submit
            }
            try {
                val barcode = BarcodeFactory.getInstance().barcodeDecoder
                val ok = barcode.open(context)
                if (!ok) {
                    ready.set(false)
                    decoder = null
                    post { listener.onStatus(false, "Barcode init failed") }
                    post { listener.onError("Barcode init failed. Use a Chainway C72 device.") }
                    return@submit
                }

                barcode.setDecodeCallback(object : BarcodeDecoder.DecodeCallback {
                    override fun onDecodeComplete(entity: BarcodeEntity?) {
                        val wasScanning = scanning.getAndSet(false)
                        if (wasScanning) {
                            post { listener.onScanning(false) }
                        }

                        if (entity == null) {
                            post { listener.onError("Barcode scan failed") }
                            return
                        }

                        val code = entity.resultCode
                        if (code != BarcodeDecoder.DECODE_SUCCESS) {
                            when (code) {
                                BarcodeDecoder.DECODE_TIMEOUT ->
                                    post { listener.onError("Barcode timeout. Try again.") }
                                BarcodeDecoder.DECODE_CANCEL -> { /* silent */ }
                                else ->
                                    post { listener.onError("Barcode scan failed") }
                            }
                            return
                        }

                        val data = entity.barcodeData?.trim().orEmpty()
                        if (data.isEmpty()) {
                            post { listener.onError("Empty barcode. Try again.") }
                        } else {
                            post { listener.onBarcode(data) }
                        }
                    }
                })

                try {
                    barcode.setTimeOut(BARCODE_DECODE_TIMEOUT_MS)
                } catch (t: Throwable) {
                    Log.w(TAG, "setTimeOut skipped: ${t.message}")
                }

                decoder = barcode
                ready.set(true)
                post { listener.onStatus(true, "Barcode ready") }
            } catch (t: Throwable) {
                Log.e(TAG, "open error", t)
                ready.set(false)
                decoder = null
                post { listener.onStatus(false, "Barcode unavailable") }
                post { listener.onError(t.message ?: "Barcode init error") }
            }
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    companion object {
        private const val TAG = "BarcodeHelper"
        const val BARCODE_DECODE_TIMEOUT_MS = 10_000
    }
}
