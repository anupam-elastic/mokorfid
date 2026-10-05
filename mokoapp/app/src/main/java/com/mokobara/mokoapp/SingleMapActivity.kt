package com.mokobara.mokoapp

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mokobara.mokoapp.barcode.BarcodeHelper
import com.mokobara.mokoapp.databinding.ActivitySingleMapBinding
import com.mokobara.mokoapp.uhf.UhfReaderHelper

/**
 * Auto flow: USN barcode → choose set size (1/2/3) → scan that many unique RFIDs → Save.
 * On C72, barcode and UHF must not own the bus at the same time.
 */
class SingleMapActivity : AppCompatActivity() {

    private enum class Phase { USN, SET_SELECT, RFID, DONE }

    private data class ScannedRfid(
        val epc: String,
        val rssi: String,
        val powerDbm: Int
    )

    private lateinit var binding: ActivitySingleMapBinding
    private lateinit var uhf: UhfReaderHelper
    private lateinit var barcode: BarcodeHelper

    private var phase: Phase = Phase.USN
    private var uhfReady = false
    private var barcodeReady = false
    private var pendingRfid = false
    private var usnSecondsLeft = 0
    private var currentRfidPower = 0
    private var lockedRfidPower = 0
    private var targetRfidCount = 1
    private val scannedRfids = mutableListOf<ScannedRfid>()

    private val usnWindowStopRunnable = Runnable {
        if (phase == Phase.USN && barcode.isScanning()) {
            barcode.stopScan()
            Toast.makeText(this, R.string.single_map_usn_timeout, Toast.LENGTH_SHORT).show()
        }
    }

    private val countdownTickRunnable = object : Runnable {
        override fun run() {
            if (phase != Phase.USN || !barcode.isScanning()) {
                stopCountdownUi()
                return
            }
            if (usnSecondsLeft <= 0) {
                stopCountdownUi()
                return
            }
            binding.usnCountdownText.visibility = View.VISIBLE
            binding.usnCountdownText.text =
                getString(R.string.single_map_usn_countdown, usnSecondsLeft)
            usnSecondsLeft -= 1
            binding.root.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySingleMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        uhf = UhfReaderHelper(uhfListener)
        barcode = BarcodeHelper(barcodeListener)

        binding.backButton.setOnClickListener { finish() }
        binding.clearButton.setOnClickListener { restartFlow() }
        binding.saveButton.setOnClickListener {
            Toast.makeText(this, R.string.toast_save_demo, Toast.LENGTH_SHORT).show()
        }
        binding.setOneButton.setOnClickListener { onSetSelected(1) }
        binding.setTwoButton.setOnClickListener { onSetSelected(2) }
        binding.setThreeButton.setOnClickListener { onSetSelected(3) }
        binding.usnRejectScanAgainButton.setOnClickListener { dismissUsnRejectAndRescan() }

        updatePhaseUi()
        barcode.init(this)
    }

    override fun onDestroy() {
        stopUsnScanSession()
        barcode.release()
        uhf.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isScanTrigger(keyCode) && event?.repeatCount == 0) {
            if (binding.usnRejectOverlay.visibility == View.VISIBLE) {
                dismissUsnRejectAndRescan()
                return true
            }
            when (phase) {
                Phase.USN -> maybeStartUsnScan(force = true)
                Phase.RFID -> maybeStartRfidScan(force = true)
                Phase.SET_SELECT, Phase.DONE -> { /* no-op */ }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private val uhfListener = object : UhfReaderHelper.Listener {
        override fun onStatus(ready: Boolean, message: String) {
            uhfReady = ready
            if (ready && pendingRfid && phase == Phase.RFID) {
                pendingRfid = false
                maybeStartRfidScan()
            } else if (phase == Phase.RFID) {
                updatePhaseUi()
            }
        }

        override fun onScanning(active: Boolean) {
            if (phase == Phase.RFID) {
                updatePhaseUi()
            }
        }

        override fun onPower(powerDbm: Int) {
            if (phase != Phase.RFID) return
            currentRfidPower = powerDbm
            binding.subtitleText.text = getString(R.string.single_map_rfid_power, powerDbm)
            binding.rfidStepStatus.visibility = View.VISIBLE
            val progressIndex = (scannedRfids.size + 1).coerceAtMost(targetRfidCount)
            binding.rfidStepStatus.text = getString(
                R.string.single_map_step_rfid_progress,
                progressIndex,
                targetRfidCount
            )
            showRfidPowerCountup(powerDbm)
            setStatusChip(true, getString(R.string.status_waiting_rfid))
        }

        override fun onTag(epc: String, rssi: String, powerDbm: Int) {
            if (phase != Phase.RFID) return
            val normalized = epc.trim()
            if (normalized.isEmpty()) return
            if (scannedRfids.any { it.epc.equals(normalized, ignoreCase = true) }) {
                return
            }

            scannedRfids.add(ScannedRfid(normalized, rssi, powerDbm))
            lockedRfidPower = powerDbm
            currentRfidPower = powerDbm
            refreshRfidField()
            Toast.makeText(
                this@SingleMapActivity,
                "RFID ${scannedRfids.size}/$targetRfidCount: $normalized @ ${powerDbm}dBm",
                Toast.LENGTH_SHORT
            ).show()

            if (scannedRfids.size >= targetRfidCount) {
                phase = Phase.DONE
                pendingRfid = false
                uhf.stopScan(quiet = true)
                updatePhaseUi()
            } else {
                updatePhaseUi()
                if (targetRfidCount == 1) {
                    maybeStartRfidScan()
                }
            }
        }

        override fun onScanFinishedEmpty() {
            if (phase != Phase.RFID) return
            if (scannedRfids.size >= targetRfidCount) return
            updatePhaseUi()
        }

        override fun onError(message: String) {
            Toast.makeText(this@SingleMapActivity, message, Toast.LENGTH_SHORT).show()
            if (phase == Phase.RFID) {
                updatePhaseUi()
            }
        }
    }

    private val barcodeListener = object : BarcodeHelper.Listener {
        override fun onStatus(ready: Boolean, message: String) {
            barcodeReady = ready
            if (ready && phase == Phase.USN && binding.usnRejectOverlay.visibility != View.VISIBLE) {
                maybeStartUsnScan()
            } else if (phase == Phase.USN) {
                updatePhaseUi()
            }
        }

        override fun onScanning(active: Boolean) {
            if (phase != Phase.USN) {
                if (!active) stopUsnScanSession()
                return
            }
            if (active) {
                startUsnScanSession()
            } else {
                stopUsnScanSession()
            }
            updatePhaseUi()
        }

        override fun onBarcode(data: String) {
            stopUsnScanSession()
            if (UsnScanRules.looksLikeEanNotUsn(data)) {
                binding.usnField.setText("")
                showUsnRejectPopup()
                return
            }
            binding.usnField.setText(data)
            Toast.makeText(this@SingleMapActivity, "USN: $data", Toast.LENGTH_SHORT).show()
            showSetSelect()
        }

        override fun onError(message: String) {
            if (phase != Phase.USN) return
            stopUsnScanSession()
            if (message.contains("timeout", ignoreCase = true)) {
                if (!message.contains("10s", ignoreCase = true)) {
                    Toast.makeText(
                        this@SingleMapActivity,
                        R.string.single_map_usn_timeout,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (!message.contains("cancel", ignoreCase = true)) {
                Toast.makeText(this@SingleMapActivity, message, Toast.LENGTH_SHORT).show()
            }
            updatePhaseUi()
        }
    }

    private fun showUsnRejectPopup() {
        if (phase != Phase.USN) phase = Phase.USN
        binding.setSelectOverlay.visibility = View.GONE
        binding.usnRejectOverlay.visibility = View.VISIBLE
        updatePhaseUi()
    }

    private fun dismissUsnRejectAndRescan() {
        binding.usnRejectOverlay.visibility = View.GONE
        binding.usnField.setText("")
        updatePhaseUi()
        maybeStartUsnScan(force = true)
    }

    private fun showSetSelect() {
        phase = Phase.SET_SELECT
        targetRfidCount = 1
        scannedRfids.clear()
        lockedRfidPower = 0
        currentRfidPower = 0
        hideRfidPower()
        stopUsnScanSession()
        updatePhaseUi()
        // Free barcode bus while user picks set — RFID starts after selection.
        barcode.close()
    }

    private fun onSetSelected(count: Int) {
        if (phase != Phase.SET_SELECT) return
        targetRfidCount = count.coerceIn(1, 3)
        scannedRfids.clear()
        advanceToRfid()
    }

    private fun advanceToRfid() {
        phase = Phase.RFID
        pendingRfid = true
        lockedRfidPower = 0
        currentRfidPower = 0
        hideRfidPower()
        stopUsnScanSession()
        updatePhaseUi()
        barcode.close {
            if (phase != Phase.RFID) return@close
            DeviceBusHandoff.prepareForUhf(this@SingleMapActivity)
            uhf.init(this@SingleMapActivity)
        }
    }

    private fun maybeStartUsnScan(force: Boolean = false) {
        if (phase != Phase.USN) return
        if (!barcodeReady || !barcode.isReady()) {
            if (force) {
                Toast.makeText(this, R.string.toast_barcode_not_ready, Toast.LENGTH_SHORT).show()
            }
            if (force) barcode.reopen()
            return
        }
        if (barcode.isScanning() || uhf.isScanning()) return
        if (!force && binding.usnField.text?.isNotBlank() == true) return
        barcode.startScan()
    }

    private fun startUsnScanSession() {
        cancelUsnWindowTimer()
        cancelCountdownTicks()
        usnSecondsLeft = (USN_SCAN_WINDOW_MS / 1_000L).toInt()
        binding.usnCountdownText.visibility = View.VISIBLE
        binding.usnCountdownText.text =
            getString(R.string.single_map_usn_countdown, usnSecondsLeft)
        usnSecondsLeft -= 1
        binding.root.postDelayed(countdownTickRunnable, 1_000L)
        binding.root.postDelayed(usnWindowStopRunnable, USN_SCAN_WINDOW_MS)
    }

    private fun stopUsnScanSession() {
        cancelUsnWindowTimer()
        stopCountdownUi()
    }

    private fun cancelCountdownTicks() {
        binding.root.removeCallbacks(countdownTickRunnable)
    }

    private fun stopCountdownUi() {
        cancelCountdownTicks()
        usnSecondsLeft = 0
        binding.usnCountdownText.visibility = View.GONE
        binding.usnCountdownText.text = ""
    }

    private fun cancelUsnWindowTimer() {
        binding.root.removeCallbacks(usnWindowStopRunnable)
    }

    private fun showRfidPowerCountup(powerDbm: Int) {
        binding.rfidPowerText.visibility = View.VISIBLE
        binding.rfidPowerText.setTextColor(ContextCompat.getColor(this, R.color.amber))
        binding.rfidPowerText.text = getString(R.string.single_map_rfid_power_level, powerDbm)
    }

    private fun showRfidPowerLocked(powerDbm: Int) {
        binding.rfidPowerText.visibility = View.VISIBLE
        binding.rfidPowerText.setTextColor(ContextCompat.getColor(this, R.color.teal))
        binding.rfidPowerText.text = getString(R.string.single_map_rfid_power_locked, powerDbm)
    }

    private fun hideRfidPower() {
        binding.rfidPowerText.visibility = View.GONE
        binding.rfidPowerText.text = ""
    }

    private fun refreshRfidField() {
        if (scannedRfids.isEmpty()) {
            binding.rfidField.setText("")
            binding.rssiText.visibility = View.GONE
            return
        }
        val styled = SpannableStringBuilder()
        scannedRfids.forEachIndexed { index, tag ->
            if (index > 0) styled.append('\n')
            val line = "${tag.epc}  ${tag.powerDbm} dBm"
            val start = styled.length
            styled.append(line)
            styled.setSpan(
                ForegroundColorSpan(rfidPowerColor(tag.powerDbm)),
                start,
                styled.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.rfidField.setText(styled)
        binding.rssiText.visibility = View.VISIBLE
        binding.rssiText.text = scannedRfids.joinToString(" · ") { tag ->
            "${getString(R.string.rssi_label)} ${tag.rssi}"
        }
    }

    /** Lower TX power = closer/stronger read confidence. */
    private fun rfidPowerColor(powerDbm: Int): Int {
        val colorRes = when {
            powerDbm <= 7 -> R.color.rfid_power_good
            powerDbm <= 10 -> R.color.rfid_power_mid
            else -> R.color.rfid_power_high
        }
        return ContextCompat.getColor(this, colorRes)
    }

    private fun maybeStartRfidScan(force: Boolean = false) {
        if (phase != Phase.RFID) return
        if (scannedRfids.size >= targetRfidCount) return
        if (!uhfReady || !uhf.isReady()) {
            pendingRfid = true
            if (!uhf.isReady() && !uhf.isOpening()) {
                DeviceBusHandoff.prepareForUhf(this)
                uhf.init(this)
            }
            if (force) {
                Toast.makeText(this, R.string.toast_uhf_connecting, Toast.LENGTH_SHORT).show()
            }
            updatePhaseUi()
            return
        }
        if (uhf.isScanning()) return
        pendingRfid = false
        currentRfidPower = 0
        val exclude = scannedRfids.map { it.epc }.toSet()
        val remaining = targetRfidCount - scannedRfids.size
        if (targetRfidCount > 1) {
            uhf.scanUntilTags(
                tagCount = remaining,
                excludeEpcs = exclude,
                maxPower = SINGLE_MAP_MAX_RFID_POWER,
                dwellMs = UhfReaderHelper.MULTI_TAG_POWER_DWELL_MS
            )
        } else {
            uhf.scanSingleTag(
                excludeEpcs = exclude,
                maxPower = SINGLE_MAP_MAX_RFID_POWER
            )
        }
        updatePhaseUi()
    }

    private fun restartFlow() {
        stopUsnScanSession()
        uhf.stopScan(quiet = true)
        pendingRfid = false
        lockedRfidPower = 0
        currentRfidPower = 0
        targetRfidCount = 1
        scannedRfids.clear()
        hideRfidPower()
        binding.setSelectOverlay.visibility = View.GONE
        binding.usnRejectOverlay.visibility = View.GONE
        phase = Phase.USN
        binding.usnField.setText("")
        binding.rfidField.setText("")
        binding.rssiText.visibility = View.GONE
        binding.rssiText.text = getString(R.string.rssi_label)
        updatePhaseUi()

        if (uhf.isReady() || uhf.isOpening()) {
            uhf.free {
                if (isFinishing || phase != Phase.USN) return@free
                resumeUsnAfterClear()
            }
        } else {
            resumeUsnAfterClear()
        }
    }

    private fun resumeUsnAfterClear() {
        if (isFinishing || phase != Phase.USN) return
        if (barcode.isReady()) {
            maybeStartUsnScan(force = true)
        } else {
            DeviceBusHandoff.prepareForBarcode(this)
            barcode.reopen()
        }
    }

    private fun updatePhaseUi() {
        when (phase) {
            Phase.USN -> {
                binding.setSelectOverlay.visibility = View.GONE
                binding.saveButton.visibility = View.GONE
                binding.rfidCard.alpha = 0.45f
                binding.usnCard.alpha = 1f

                binding.usnStepStatus.visibility = View.VISIBLE
                binding.rfidStepStatus.visibility = View.GONE
                hideRfidPower()

                binding.rfidField.hint = getString(R.string.hint_rfid_locked)
                if (binding.rfidField.text?.isNotBlank() == true) {
                    binding.rfidField.setText("")
                }

                when {
                    !barcodeReady -> {
                        stopCountdownUi()
                        binding.subtitleText.text = getString(R.string.single_map_connecting)
                        binding.usnStepStatus.text = getString(R.string.single_map_step_usn_connecting)
                        setStatusChip(false, getString(R.string.status_connecting))
                    }
                    barcode.isScanning() -> {
                        binding.subtitleText.text = getString(R.string.single_map_waiting_usn)
                        binding.usnStepStatus.text = getString(R.string.single_map_step_usn_scanning)
                        binding.usnField.hint = getString(R.string.hint_usn_scanning)
                        setStatusChip(true, getString(R.string.status_waiting_usn))
                    }
                    else -> {
                        stopCountdownUi()
                        binding.subtitleText.text = getString(R.string.single_map_usn_idle)
                        binding.usnStepStatus.text = getString(R.string.single_map_step_usn_idle)
                        binding.usnField.hint = getString(R.string.hint_usn_idle)
                        setStatusChip(true, getString(R.string.status_waiting_usn))
                    }
                }
            }
            Phase.SET_SELECT -> {
                stopCountdownUi()
                binding.saveButton.visibility = View.GONE
                binding.usnCard.alpha = 1f
                binding.rfidCard.alpha = 0.45f
                binding.usnStepStatus.visibility = View.VISIBLE
                binding.usnStepStatus.text = getString(R.string.single_map_step_usn_done)
                binding.rfidStepStatus.visibility = View.GONE
                hideRfidPower()
                binding.subtitleText.text = getString(R.string.single_map_choose_set)
                setStatusChip(true, getString(R.string.single_map_set_title))
                binding.setSelectOverlay.visibility = View.VISIBLE
            }
            Phase.RFID -> {
                stopCountdownUi()
                binding.setSelectOverlay.visibility = View.GONE
                binding.saveButton.visibility = View.GONE
                binding.usnCard.alpha = 0.85f
                binding.rfidCard.alpha = 1f

                binding.usnStepStatus.visibility = View.VISIBLE
                binding.rfidStepStatus.visibility = View.VISIBLE
                binding.usnStepStatus.text = getString(R.string.single_map_step_usn_done)
                binding.rfidField.hint = getString(R.string.hint_rfid_waiting)

                val nextIndex = scannedRfids.size + 1
                when {
                    !uhfReady -> {
                        hideRfidPower()
                        binding.subtitleText.text = getString(R.string.single_map_waiting_uhf)
                        binding.rfidStepStatus.text = getString(R.string.single_map_step_rfid_connecting)
                        setStatusChip(false, getString(R.string.status_connecting))
                    }
                    uhf.isScanning() -> {
                        if (currentRfidPower > 0) {
                            showRfidPowerCountup(currentRfidPower)
                            binding.subtitleText.text =
                                getString(R.string.single_map_rfid_power, currentRfidPower)
                        } else {
                            binding.subtitleText.text = getString(
                                R.string.single_map_rfid_progress,
                                nextIndex,
                                targetRfidCount
                            )
                        }
                        binding.rfidStepStatus.text = getString(
                            R.string.single_map_step_rfid_progress,
                            nextIndex,
                            targetRfidCount
                        )
                        setStatusChip(true, getString(R.string.status_waiting_rfid))
                    }
                    else -> {
                        hideRfidPower()
                        binding.subtitleText.text = getString(
                            R.string.single_map_rfid_progress,
                            nextIndex,
                            targetRfidCount
                        )
                        binding.rfidStepStatus.text = getString(
                            R.string.single_map_step_rfid_progress,
                            nextIndex,
                            targetRfidCount
                        )
                        setStatusChip(true, getString(R.string.status_waiting_rfid))
                    }
                }
            }
            Phase.DONE -> {
                stopCountdownUi()
                binding.setSelectOverlay.visibility = View.GONE
                binding.usnCard.alpha = 1f
                binding.rfidCard.alpha = 1f
                binding.saveButton.visibility = View.VISIBLE
                binding.subtitleText.text =
                    getString(R.string.single_map_hint_save_multi, scannedRfids.size)
                binding.usnStepStatus.visibility = View.VISIBLE
                binding.rfidStepStatus.visibility = View.VISIBLE
                binding.usnStepStatus.text = getString(R.string.single_map_step_usn_done)
                binding.rfidStepStatus.text = getString(
                    R.string.single_map_rfid_progress,
                    scannedRfids.size,
                    targetRfidCount
                ) + " ✓"
                if (lockedRfidPower > 0) {
                    showRfidPowerLocked(lockedRfidPower)
                }
                refreshRfidField()
                setStatusChip(true, getString(R.string.status_ready))
            }
        }
    }

    private fun setStatusChip(ready: Boolean, text: String) {
        binding.statusChip.text = text
        val color = if (ready) {
            ContextCompat.getColor(this, R.color.online)
        } else {
            ContextCompat.getColor(this, R.color.offline)
        }
        binding.statusChip.setTextColor(color)
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
        private const val USN_SCAN_WINDOW_MS = 10_000L
        /** Single mapping never ramps UHF TX above this (dBm). */
        private const val SINGLE_MAP_MAX_RFID_POWER = 15
    }
}
