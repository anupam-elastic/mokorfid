package com.mokobara.mokoapp.uhf

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mokobara.mokoapp.DeviceBusHandoff
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.deviceapi.entity.UHFTAGInfo
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around Chainway [RFIDWithUHFUART] for C72 handheld UHF.
 * Single-tag: ramp 5→max (200ms dwell), stop on first read.
 * Multi-tag: one ramp, longer dwell per level, collect distinct EPCs until count met.
 * Supports free/re-init so barcode can own the bus during USN phase.
 */
class UhfReaderHelper(
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(ready: Boolean, message: String)
        fun onScanning(active: Boolean)
        fun onPower(powerDbm: Int)
        fun onTag(epc: String, rssi: String, powerDbm: Int)
        fun onError(message: String)
        /** Called when a scan ends without [onTag], unless quietly cancelled. */
        fun onScanFinishedEmpty() {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor = Executors.newSingleThreadExecutor()
    private var reader: RFIDWithUHFUART? = null
    private var appContext: Context? = null
    private val ready = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)
    private val cancelQuiet = AtomicBoolean(false)
    private var scanFuture: Future<*>? = null
    private var reportIfEmpty = true

    fun init(context: Context) {
        appContext = context.applicationContext
        released.set(false)
        ensureExecutor()
        openInternal()
    }

    fun isReady(): Boolean = ready.get()

    fun isOpening(): Boolean = opening.get()

    fun isScanning(): Boolean = scanning.get()

    /**
     * Ramp power from [MIN_POWER] to [maxPower] (capped at [MAX_POWER]), dwelling
     * [POWER_DWELL_MS] at each level. Stops as soon as a valid EPC is read.
     *
     * C72 rejects setPower while inventory is running (~1.2s timeout), so each step after
     * the first does stop → setPower → startInventory.
     */
    fun scanSingleTag(
        reportIfEmpty: Boolean = true,
        excludeEpcs: Set<String> = emptySet(),
        maxPower: Int = MAX_POWER
    ) {
        if (released.get()) return
        if (!ready.get()) {
            post { listener.onError("UHF not ready") }
            return
        }
        if (!scanning.compareAndSet(false, true)) {
            post { listener.onError("Scan already running") }
            return
        }

        this.reportIfEmpty = reportIfEmpty
        cancelQuiet.set(false)
        val excluded = excludeEpcs.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val rampCeiling = maxPower.coerceIn(MIN_POWER, MAX_POWER)
        post { listener.onScanning(true) }

        ensureExecutor()
        scanFuture = executor.submit {
            if (released.get()) {
                scanning.set(false)
                post { listener.onScanning(false) }
                return@submit
            }
            val uhf = reader
            if (uhf == null) {
                scanning.set(false)
                post { listener.onScanning(false) }
                post { listener.onError("UHF not ready") }
                return@submit
            }

            var best: UHFTAGInfo? = null
            var successPower = MIN_POWER
            var inventoryStarted = false

            try {
                if (!applyPower(uhf, MIN_POWER)) {
                    post { listener.onError("Could not set UHF power") }
                    return@submit
                }
                post { listener.onPower(MIN_POWER) }

                if (!uhf.startInventoryTag()) {
                    try {
                        uhf.stopInventory()
                    } catch (_: Throwable) {
                    }
                    post { listener.onError("Could not start inventory") }
                    return@submit
                }
                inventoryStarted = true

                for (power in MIN_POWER..rampCeiling) {
                    if (!scanning.get()) break

                    if (power > MIN_POWER) {
                        if (!restartInventoryAtPower(uhf, power)) {
                            post { listener.onError("Could not set UHF power to $power") }
                            best = null
                            break
                        }
                        inventoryStarted = true
                    }
                    post { listener.onPower(power) }

                    val levelBest = pollForTag(uhf, POWER_DWELL_MS, excluded)
                    if (levelBest != null) {
                        best = levelBest
                        successPower = power
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "scan error", t)
                post { listener.onError(t.message ?: "Scan failed") }
            } finally {
                if (inventoryStarted) {
                    try {
                        uhf.stopInventory()
                    } catch (t: Throwable) {
                        Log.w(TAG, "stopInventory: ${t.message}")
                    }
                }
                scanning.set(false)
                post { listener.onScanning(false) }
            }

            if (cancelQuiet.getAndSet(false)) {
                return@submit
            }

            val tag = best
            if (tag == null) {
                if (reportIfEmpty) {
                    post { listener.onError("No tag found. Hold closer and try again.") }
                }
                post { listener.onScanFinishedEmpty() }
            } else {
                val epc = tag.getEPC()?.trim().orEmpty()
                if (epc.isEmpty()) {
                    if (reportIfEmpty) {
                        post { listener.onError("No tag found. Hold closer and try again.") }
                    }
                    post { listener.onScanFinishedEmpty() }
                } else {
                    val rssi = tag.getRssi()?.trim().orEmpty().ifEmpty { "—" }
                    val lockedPower = successPower
                    post { listener.onTag(epc, rssi, lockedPower) }
                }
            }
        }
    }

    /**
     * One inventory session: ramp [MIN_POWER]→[maxPower], dwelling [dwellMs] at each level.
     * Reports each new EPC via [Listener.onTag] (respecting [excludeEpcs] and tags already
     * reported this session). Stops when [tagCount] new tags are collected or ramp ends.
     */
    fun scanUntilTags(
        tagCount: Int,
        reportIfEmpty: Boolean = true,
        excludeEpcs: Set<String> = emptySet(),
        maxPower: Int = MAX_POWER,
        dwellMs: Long = MULTI_TAG_POWER_DWELL_MS
    ) {
        if (tagCount <= 0) return
        if (released.get()) return
        if (!ready.get()) {
            post { listener.onError("UHF not ready") }
            return
        }
        if (!scanning.compareAndSet(false, true)) {
            post { listener.onError("Scan already running") }
            return
        }

        this.reportIfEmpty = reportIfEmpty
        cancelQuiet.set(false)
        val excluded = excludeEpcs.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val sessionSeen = excluded.toMutableSet()
        val initialSeenSize = sessionSeen.size
        val rampCeiling = maxPower.coerceIn(MIN_POWER, MAX_POWER)
        val needNew = tagCount
        post { listener.onScanning(true) }

        ensureExecutor()
        scanFuture = executor.submit {
            if (released.get()) {
                scanning.set(false)
                post { listener.onScanning(false) }
                return@submit
            }
            val uhf = reader
            if (uhf == null) {
                scanning.set(false)
                post { listener.onScanning(false) }
                post { listener.onError("UHF not ready") }
                return@submit
            }

            var inventoryStarted = false

            try {
                if (!applyPower(uhf, MIN_POWER)) {
                    post { listener.onError("Could not set UHF power") }
                    return@submit
                }
                post { listener.onPower(MIN_POWER) }

                if (!uhf.startInventoryTag()) {
                    try {
                        uhf.stopInventory()
                    } catch (_: Throwable) {
                    }
                    post { listener.onError("Could not start inventory") }
                    return@submit
                }
                inventoryStarted = true

                powerLoop@ for (power in MIN_POWER..rampCeiling) {
                    if (!scanning.get()) break

                    if (power > MIN_POWER) {
                        if (!restartInventoryAtPower(uhf, power)) {
                            post { listener.onError("Could not set UHF power to $power") }
                            break
                        }
                        inventoryStarted = true
                    }
                    post { listener.onPower(power) }

                    pollForNewTags(
                        uhf = uhf,
                        dwellMs = dwellMs,
                        sessionSeen = sessionSeen,
                        needNew = needNew,
                        initialSeenSize = initialSeenSize
                    ) { info ->
                        val epc = info.getEPC()?.trim().orEmpty()
                        if (epc.isEmpty()) return@pollForNewTags
                        val rssi = info.getRssi()?.trim().orEmpty().ifEmpty { "—" }
                        post { listener.onTag(epc, rssi, power) }
                    }

                    if (sessionSeen.size - initialSeenSize >= needNew) break@powerLoop
                }
            } catch (t: Throwable) {
                Log.e(TAG, "multi scan error", t)
                post { listener.onError(t.message ?: "Scan failed") }
            } finally {
                if (inventoryStarted) {
                    try {
                        uhf.stopInventory()
                    } catch (t: Throwable) {
                        Log.w(TAG, "stopInventory: ${t.message}")
                    }
                }
                scanning.set(false)
                post { listener.onScanning(false) }
            }

            if (cancelQuiet.getAndSet(false)) {
                return@submit
            }

            if (sessionSeen.size - initialSeenSize < needNew) {
                if (reportIfEmpty) {
                    val remaining = needNew - (sessionSeen.size - initialSeenSize)
                    post {
                        listener.onError(
                            "Need $remaining more tag${if (remaining == 1) "" else "s"}. " +
                                "Hold closer and scan again."
                        )
                    }
                }
                post { listener.onScanFinishedEmpty() }
            }
        }
    }

    private fun pollForTag(
        uhf: RFIDWithUHFUART,
        dwellMs: Long,
        excludeEpcs: Set<String> = emptySet()
    ): UHFTAGInfo? {
        val deadline = System.currentTimeMillis() + dwellMs

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
            if (excludeEpcs.contains(epc.uppercase())) continue

            return info
        }
        return null
    }

    /** Collect distinct new EPCs during [dwellMs]; returns how many were newly seen. */
    private fun pollForNewTags(
        uhf: RFIDWithUHFUART,
        dwellMs: Long,
        sessionSeen: MutableSet<String>,
        needNew: Int,
        initialSeenSize: Int,
        onNewTag: (UHFTAGInfo) -> Unit
    ): Int {
        val deadline = System.currentTimeMillis() + dwellMs
        var added = 0

        while (System.currentTimeMillis() < deadline && scanning.get()) {
            if (sessionSeen.size - initialSeenSize >= needNew) break
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
            val key = epc.uppercase()
            if (sessionSeen.contains(key)) continue

            sessionSeen.add(key)
            added++
            onNewTag(info)
        }
        return added
    }

    private fun applyPower(uhf: RFIDWithUHFUART, power: Int): Boolean {
        return try {
            uhf.setPower(power)
        } catch (t: Throwable) {
            Log.w(TAG, "setPower($power): ${t.message}")
            false
        }
    }

    /** stop → setPower → start. Avoids mid-inventory setPower timeouts on C72. */
    private fun restartInventoryAtPower(uhf: RFIDWithUHFUART, power: Int): Boolean {
        try {
            uhf.stopInventory()
        } catch (t: Throwable) {
            Log.w(TAG, "stopInventory before power $power: ${t.message}")
        }
        try {
            Thread.sleep(POWER_SETTLE_MS)
        } catch (_: InterruptedException) {
        }
        if (!applyPower(uhf, power)) {
            Log.w(TAG, "setPower($power) after stop failed")
            return false
        }
        return try {
            uhf.startInventoryTag()
        } catch (t: Throwable) {
            Log.e(TAG, "restart inventory after power $power", t)
            false
        }
    }

    fun stopScan(quiet: Boolean = false) {
        if (quiet) cancelQuiet.set(true)
        if (!scanning.getAndSet(false)) return
        scanFuture?.cancel(true)
        submit {
            try {
                reader?.stopInventory()
            } catch (t: Throwable) {
                Log.w(TAG, "stopScan: ${t.message}")
            } finally {
                post { listener.onScanning(false) }
            }
        }
    }

    /**
     * Power down UHF without destroying the helper (barcode can take the bus).
     */
    fun free(onFreed: (() -> Unit)? = null) {
        scanning.set(false)
        scanFuture?.cancel(true)
        if (!ready.get() && reader == null && !opening.get()) {
            post {
                listener.onStatus(false, "UHF freed")
                onFreed?.invoke()
            }
            return
        }
        submit {
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
                opening.set(false)
                post {
                    listener.onStatus(false, "UHF freed")
                    onFreed?.invoke()
                }
            }
        }
    }

    fun reopen() {
        released.set(false)
        ensureExecutor()
        openInternal()
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

    fun release() {
        released.set(true)
        scanning.set(false)
        scanFuture?.cancel(true)
        submit {
            try {
                reader?.stopInventory()
            } catch (_: Throwable) {
            }
            try {
                reader?.free()
            } catch (t: Throwable) {
                Log.w(TAG, "release free: ${t.message}")
            } finally {
                reader = null
                ready.set(false)
                opening.set(false)
                post { listener.onStatus(false, "UHF released") }
            }
        }
        executor.shutdown()
    }

    private fun openInternal() {
        val context = appContext
        if (context == null) {
            post { listener.onError("UHF context missing") }
            return
        }
        if (!opening.compareAndSet(false, true)) {
            Log.i(TAG, "UHF open already in progress")
            return
        }
        submit {
            try {
                if (released.get()) return@submit
                if (reader != null && ready.get()) {
                    post { listener.onStatus(true, "UHF ready") }
                    return@submit
                }
                try {
                    try {
                        reader?.stopInventory()
                    } catch (_: Throwable) {
                    }
                    try {
                        reader?.free()
                    } catch (_: Throwable) {
                    }
                    reader = null
                    ready.set(false)

                    var uhf: RFIDWithUHFUART? = null
                    var ok = false
                    for (attempt in 1..INIT_RETRIES) {
                        if (released.get()) return@submit
                        // Re-release bus before every attempt — Keyboard Helper / UHF demo
                        // can reclaim ttyS1 between retries.
                        try {
                            DeviceBusHandoff.prepareForUhf(context)
                        } catch (t: Throwable) {
                            Log.w(TAG, "prepareForUhf: ${t.message}")
                        }
                        val waitMs = if (attempt == 1) BUS_SETTLE_MS else BUS_RETRY_MS
                        try {
                            Thread.sleep(waitMs)
                        } catch (_: InterruptedException) {
                        }
                        try {
                            uhf = RFIDWithUHFUART.getInstance()
                            ok = uhf.init(context)
                            Log.i(TAG, "UHF init attempt $attempt => $ok")
                            if (ok) break
                            try {
                                uhf.free()
                            } catch (_: Throwable) {
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "UHF init attempt $attempt error: ${t.message}")
                            ok = false
                        }
                    }

                    if (!ok || uhf == null) {
                        ready.set(false)
                        reader = null
                        post { listener.onStatus(false, "UHF init failed") }
                        post {
                            listener.onError(
                                "UHF init failed. Close stock UHF app / Keyboard Helper RFID and retry."
                            )
                        }
                        return@submit
                    }

                    try {
                        uhf.setPower(MIN_POWER)
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
            } finally {
                opening.set(false)
            }
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    companion object {
        private const val TAG = "UhfReaderHelper"
        const val MIN_POWER = 5
        const val MAX_POWER = 30
        const val POWER_DWELL_MS = 200L
        /** Dwell per power step when collecting a set of 2/3 tags in one ramp. */
        const val MULTI_TAG_POWER_DWELL_MS = 500L
        /** Brief pause after stopInventory before setPower. */
        private const val POWER_SETTLE_MS = 40L
        /** First wait after barcode / Keyboard Helper release. */
        private const val BUS_SETTLE_MS = 1500L
        private const val BUS_RETRY_MS = 1000L
        private const val INIT_RETRIES = 5
    }
}
