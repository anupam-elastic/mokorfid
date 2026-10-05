package com.mokobara.mokoapp

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.mokobara.mokoapp.barcode.BarcodeHelper
import com.mokobara.mokoapp.databinding.ActivityMainBinding
import com.mokobara.mokoapp.uhf.UhfReaderHelper

/**
 * Home trigger quick-scan (C72 cannot run barcode + UHF on the bus at once):
 * 1) USN barcode first (≥2s) — USN always wins if found
 * 2) If no USN, hand bus to UHF and ramp 5→30 dBm for RFID
 * Result popup shows for 5s. Retrigger while busy → bottom error banner.
 */
class MainActivity : AppCompatActivity() {

    private enum class ScanPhase {
        IDLE,
        STARTING_USN,
        SCANNING_USN,
        STARTING_RFID,
        SCANNING_RFID,
        SHOWING
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var uhf: UhfReaderHelper
    private lateinit var barcode: BarcodeHelper

    private var phase = ScanPhase.IDLE
    private var barcodeReady = false
    private var uhfReady = false
    private var pendingUsnStart = false
    private var pendingRfidStart = false

    private var capturedUsn: String? = null
    private var capturedRfid: String? = null
    private var capturedRssi: String = ""
    private var capturedPower: Int = 0
    private var usnRejectVisible = false

    private val usnWindowRunnable = Runnable {
        if (phase != ScanPhase.SCANNING_USN && phase != ScanPhase.STARTING_USN) return@Runnable
        if (capturedUsn != null) return@Runnable
        if (usnRejectVisible) return@Runnable
        advanceToRfid()
    }

    private val hideResultRunnable = Runnable { clearResultOverlay() }

    private val hideErrorRunnable = Runnable {
        binding.errorBanner.visibility = View.GONE
    }

    private val sessionWatchdogRunnable = Runnable {
        if (phase == ScanPhase.IDLE || phase == ScanPhase.SHOWING) return@Runnable
        stopHardwareQuiet()
        if (capturedUsn == null && capturedRfid == null) {
            showErrorBanner(getString(R.string.home_no_scan))
        }
        phase = ScanPhase.IDLE
        binding.scanningBanner.visibility = View.GONE
        pendingUsnStart = false
        pendingRfidStart = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        HomeScreenHelper.ensureLauncherShortcut(this)

        uhf = UhfReaderHelper(uhfListener)
        barcode = BarcodeHelper(barcodeListener)

        binding.tileSingle.setOnClickListener {
            if (phase != ScanPhase.IDLE) {
                showBusyError()
                return@setOnClickListener
            }
            releaseScannersForNavigation {
                startActivity(Intent(this, SingleMapActivity::class.java))
            }
        }
        binding.tileBulk.setOnClickListener {
            if (phase != ScanPhase.IDLE) {
                showBusyError()
                return@setOnClickListener
            }
            releaseScannersForNavigation {
                startActivity(Intent(this, BulkMapActivity::class.java))
            }
        }
        binding.usnRejectScanAgainButton.setOnClickListener { dismissUsnRejectAndRescan() }
    }

    override fun onResume() {
        super.onResume()
        if (phase == ScanPhase.IDLE) {
            warmBarcode()
        }
    }

    override fun onPause() {
        if (phase != ScanPhase.IDLE && phase != ScanPhase.SHOWING) {
            cancelSessionTimers()
            stopHardwareQuiet()
            phase = ScanPhase.IDLE
            binding.scanningBanner.visibility = View.GONE
            pendingUsnStart = false
            pendingRfidStart = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        cancelSessionTimers()
        binding.root.removeCallbacks(hideResultRunnable)
        binding.root.removeCallbacks(hideErrorRunnable)
        barcode.release()
        uhf.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isScanTrigger(keyCode) && event?.repeatCount == 0) {
            onTriggerPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onTriggerPressed() {
        if (isUsnRejectVisible()) {
            dismissUsnRejectAndRescan()
            return
        }
        when (phase) {
            ScanPhase.IDLE -> startHomeScan()
            ScanPhase.SHOWING -> {
                binding.root.removeCallbacks(hideResultRunnable)
                binding.resultOverlay.visibility = View.GONE
                phase = ScanPhase.IDLE
                startHomeScan()
            }
            else -> showBusyError()
        }
    }

    private fun startHomeScan() {
        phase = ScanPhase.STARTING_USN
        pendingUsnStart = true
        pendingRfidStart = false
        capturedUsn = null
        capturedRfid = null
        capturedRssi = ""
        capturedPower = 0
        hideErrorBannerImmediate()
        usnRejectVisible = false
        binding.resultOverlay.visibility = View.GONE
        binding.usnRejectOverlay.visibility = View.GONE
        binding.scanningBanner.visibility = View.VISIBLE
        binding.scanningBanner.text = getString(R.string.home_scanning_usn)

        cancelSessionTimers()
        binding.root.postDelayed(usnWindowRunnable, MIN_SCAN_MS)
        binding.root.postDelayed(sessionWatchdogRunnable, SESSION_MAX_MS)

        if (uhf.isReady() || uhf.isOpening()) {
            uhf.free {
                if (phase != ScanPhase.STARTING_USN) return@free
                beginUsnAfterBusReady()
            }
        } else {
            beginUsnAfterBusReady()
        }
    }

    private fun beginUsnAfterBusReady() {
        if (phase != ScanPhase.STARTING_USN) return
        DeviceBusHandoff.prepareForBarcode(this)
        if (barcode.isReady()) {
            tryBeginUsnScan()
        } else {
            barcode.init(this)
        }
    }

    /** Release UHF and warm barcode while result popup is visible — faster next trigger. */
    private fun prepForNextHomeScan() {
        if (isFinishing || isDestroyed) return
        uhf.stopScan(quiet = true)
        if (uhf.isReady() || uhf.isOpening()) {
            uhf.free {
                if (isFinishing || isDestroyed) return@free
                DeviceBusHandoff.prepareForBarcode(this@MainActivity)
                if (!barcode.isReady()) {
                    barcode.init(this@MainActivity)
                }
            }
        } else if (!barcode.isReady()) {
            DeviceBusHandoff.prepareForBarcode(this)
            barcode.init(this)
        }
    }

    private fun isUsnRejectVisible(): Boolean {
        return usnRejectVisible && binding.usnRejectOverlay.visibility == View.VISIBLE
    }

    private fun warmBarcode() {
        DeviceBusHandoff.prepareForBarcode(this)
        if (!barcode.isReady()) {
            barcode.init(this)
        }
    }

    private fun tryBeginUsnScan() {
        if (phase != ScanPhase.STARTING_USN || !pendingUsnStart) return
        if (!barcodeReady || !barcode.isReady()) return

        pendingUsnStart = false
        phase = ScanPhase.SCANNING_USN
        binding.scanningBanner.text = getString(R.string.home_scanning_usn)
        barcode.startScan()
    }

    private fun advanceToRfid() {
        if (capturedUsn != null) return
        if (usnRejectVisible) return
        if (phase != ScanPhase.SCANNING_USN && phase != ScanPhase.STARTING_USN) return

        phase = ScanPhase.STARTING_RFID
        pendingRfidStart = true
        binding.scanningBanner.text = getString(R.string.home_scanning_rfid)

        barcode.close {
            if (phase != ScanPhase.STARTING_RFID) return@close
            if (capturedUsn != null) return@close
            DeviceBusHandoff.prepareForUhf(this@MainActivity)
            if (uhf.isReady()) {
                tryBeginRfidScan()
            } else {
                uhf.init(this@MainActivity)
            }
        }
    }

    private fun tryBeginRfidScan() {
        if (phase != ScanPhase.STARTING_RFID || !pendingRfidStart) return
        if (capturedUsn != null) return
        if (!uhfReady || !uhf.isReady()) return

        pendingRfidStart = false
        phase = ScanPhase.SCANNING_RFID
        binding.scanningBanner.text = getString(R.string.home_scanning_rfid)
        uhf.scanSingleTag(reportIfEmpty = false)
    }

    private fun onUsn(data: String) {
        if (phase != ScanPhase.STARTING_USN && phase != ScanPhase.SCANNING_USN) return
        if (capturedUsn != null) return
        if (UsnScanRules.looksLikeEanNotUsn(data)) {
            showUsnRejectPopup()
            return
        }
        capturedUsn = data
        cancelSessionTimers()
        stopHardwareQuiet()
        showUsnResult(data)
    }

    private fun showUsnRejectPopup() {
        usnRejectVisible = true
        binding.usnRejectOverlay.visibility = View.VISIBLE
        binding.scanningBanner.visibility = View.GONE
    }

    private fun dismissUsnRejectAndRescan() {
        usnRejectVisible = false
        binding.usnRejectOverlay.visibility = View.GONE
        if (phase != ScanPhase.STARTING_USN && phase != ScanPhase.SCANNING_USN) return
        binding.scanningBanner.visibility = View.VISIBLE
        binding.scanningBanner.text = getString(R.string.home_scanning_usn)
        binding.root.removeCallbacks(usnWindowRunnable)
        binding.root.postDelayed(usnWindowRunnable, MIN_SCAN_MS)
        if (barcode.isReady() && !barcode.isScanning()) {
            barcode.startScan()
        } else if (!barcode.isReady()) {
            beginUsnAfterBusReady()
        }
    }

    private fun onRfid(epc: String, rssi: String, powerDbm: Int) {
        if (phase != ScanPhase.STARTING_RFID && phase != ScanPhase.SCANNING_RFID) return
        if (capturedUsn != null) return
        capturedRfid = epc
        capturedRssi = rssi
        capturedPower = powerDbm
        cancelSessionTimers()
        stopHardwareQuiet()
        showRfidResult(epc, rssi, powerDbm)
    }

    private fun onUhfScanEndedEmpty() {
        if (phase != ScanPhase.SCANNING_RFID && phase != ScanPhase.STARTING_RFID) return
        if (capturedUsn != null || capturedRfid != null) return
        stopHardwareQuiet()
        phase = ScanPhase.IDLE
        binding.scanningBanner.visibility = View.GONE
        cancelSessionTimers()
        showErrorBanner(getString(R.string.home_no_scan))
        prepForNextHomeScan()
    }

    private fun showUsnResult(data: String) {
        phase = ScanPhase.SHOWING
        binding.scanningBanner.visibility = View.GONE
        binding.resultKindText.text = getString(R.string.home_result_usn)
        binding.resultValueText.text = data
        binding.resultMetaText.visibility = View.GONE
        binding.resultOverlay.visibility = View.VISIBLE
        binding.root.removeCallbacks(hideResultRunnable)
        binding.root.postDelayed(hideResultRunnable, RESULT_SHOW_MS)
        prepForNextHomeScan()
    }

    private fun showRfidResult(epc: String, rssi: String, powerDbm: Int) {
        phase = ScanPhase.SHOWING
        binding.scanningBanner.visibility = View.GONE
        binding.resultKindText.text = getString(R.string.home_result_rfid)
        binding.resultValueText.text = epc
        val meta = buildString {
            append(getString(R.string.home_result_power, powerDbm))
            if (rssi.isNotBlank() && rssi != "—") {
                append(" · ")
                append(getString(R.string.home_result_rssi, rssi))
            }
        }
        binding.resultMetaText.text = meta
        binding.resultMetaText.visibility = View.VISIBLE
        binding.resultOverlay.visibility = View.VISIBLE
        binding.root.removeCallbacks(hideResultRunnable)
        binding.root.postDelayed(hideResultRunnable, RESULT_SHOW_MS)
        prepForNextHomeScan()
    }

    private fun clearResultOverlay() {
        binding.resultOverlay.visibility = View.GONE
        phase = ScanPhase.IDLE
        if (!barcode.isReady()) {
            warmBarcode()
        }
    }

    private fun showBusyError() {
        showErrorBanner(getString(R.string.home_scan_busy))
    }

    private fun showErrorBanner(message: String) {
        binding.errorBanner.text = message
        binding.errorBanner.visibility = View.VISIBLE
        binding.root.removeCallbacks(hideErrorRunnable)
        binding.root.postDelayed(hideErrorRunnable, ERROR_SHOW_MS)
    }

    private fun hideErrorBannerImmediate() {
        binding.root.removeCallbacks(hideErrorRunnable)
        binding.errorBanner.visibility = View.GONE
    }

    private fun stopHardwareQuiet() {
        try {
            barcode.stopScan()
        } catch (_: Throwable) {
        }
        try {
            uhf.stopScan(quiet = true)
        } catch (_: Throwable) {
        }
    }

    private fun cancelSessionTimers() {
        binding.root.removeCallbacks(usnWindowRunnable)
        binding.root.removeCallbacks(sessionWatchdogRunnable)
    }

    private fun releaseScannersForNavigation(then: () -> Unit) {
        cancelSessionTimers()
        stopHardwareQuiet()
        var pending = 2
        val done = {
            pending -= 1
            if (pending <= 0) then()
        }
        uhf.free { done() }
        barcode.close { done() }
        binding.root.postDelayed({
            if (pending > 0) {
                pending = 0
                then()
            }
        }, 1500L)
    }

    private val uhfListener = object : UhfReaderHelper.Listener {
        override fun onStatus(ready: Boolean, message: String) {
            uhfReady = ready
            if (ready &&
                (phase == ScanPhase.STARTING_RFID || phase == ScanPhase.SCANNING_RFID)
            ) {
                tryBeginRfidScan()
            }
        }

        override fun onScanning(active: Boolean) = Unit

        override fun onPower(powerDbm: Int) {
            if (phase != ScanPhase.SCANNING_RFID) return
            binding.scanningBanner.text =
                getString(R.string.home_scanning_rfid) + "  ·  " + powerDbm + " dBm"
        }

        override fun onTag(epc: String, rssi: String, powerDbm: Int) {
            onRfid(epc, rssi, powerDbm)
        }

        override fun onScanFinishedEmpty() {
            onUhfScanEndedEmpty()
        }

        override fun onError(message: String) {
            if (phase != ScanPhase.STARTING_RFID && phase != ScanPhase.SCANNING_RFID) return
            if (capturedUsn != null) return
            if (message.contains("not ready", ignoreCase = true) ||
                message.contains("init", ignoreCase = true)
            ) {
                if (!uhf.isOpening()) uhf.init(this@MainActivity)
            }
        }
    }

    private val barcodeListener = object : BarcodeHelper.Listener {
        override fun onStatus(ready: Boolean, message: String) {
            barcodeReady = ready
            if (ready && phase == ScanPhase.STARTING_USN) {
                tryBeginUsnScan()
            }
        }

        override fun onScanning(active: Boolean) = Unit

        override fun onBarcode(data: String) {
            onUsn(data)
        }

        override fun onError(message: String) {
            if (phase != ScanPhase.SCANNING_USN && phase != ScanPhase.STARTING_USN) return
            if (capturedUsn != null) return
            if (message.contains("timeout", ignoreCase = true)) {
                if (barcode.isReady() && !barcode.isScanning() && phase == ScanPhase.SCANNING_USN) {
                    barcode.startScan()
                }
            }
        }
    }

    private fun isScanTrigger(keyCode: Int): Boolean {
        return keyCode == 139 ||
            keyCode == 280 ||
            keyCode == 293 ||
            keyCode == KeyEvent.KEYCODE_F9 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_R1
    }

    companion object {
        private const val MIN_SCAN_MS = 2_000L
        private const val RESULT_SHOW_MS = 5_000L
        private const val ERROR_SHOW_MS = 2_500L
        private const val SESSION_MAX_MS = 14_000L
    }
}
