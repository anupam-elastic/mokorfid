package com.mokobara.mokoapp

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mokobara.mokoapp.databinding.ActivityMainBinding
import com.mokobara.mokoapp.uhf.UhfReaderHelper

class MainActivity : AppCompatActivity(), UhfReaderHelper.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var uhf: UhfReaderHelper

    private var lastEpc: String = ""
    private var lastRssi: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        uhf = UhfReaderHelper(this)

        binding.scanButton.setOnClickListener { startScan() }
        binding.clearButton.setOnClickListener { clearResult() }

        setStatusChip(ready = false, text = getString(R.string.status_connecting))
        uhf.init(this)
    }

    override fun onDestroy() {
        uhf.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isScanTrigger(keyCode) && event?.repeatCount == 0) {
            startScan()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isScanTrigger(keyCode)) {
            // Single-tag mode uses a timed window; no need to stop on key up.
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startScan() {
        if (!uhf.isReady()) {
            Toast.makeText(this, R.string.toast_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (uhf.isScanning()) {
            Toast.makeText(this, R.string.toast_busy, Toast.LENGTH_SHORT).show()
            return
        }
        uhf.scanSingleTag()
    }

    private fun clearResult() {
        lastEpc = ""
        lastRssi = ""
        binding.epcText.text = getString(R.string.epc_placeholder)
        binding.rssiText.text = getString(R.string.rssi_label)
    }

    override fun onStatus(ready: Boolean, message: String) {
        if (ready) {
            setStatusChip(true, getString(R.string.status_online))
        } else {
            setStatusChip(false, getString(R.string.status_offline))
        }
    }

    override fun onScanning(active: Boolean) {
        binding.scanButton.isEnabled = !active
        if (active) {
            setStatusChip(true, getString(R.string.status_scanning))
            binding.scanTitle.text = getString(R.string.status_scanning)
        } else {
            binding.scanTitle.text = getString(R.string.scan_hint_title)
            if (uhf.isReady()) {
                setStatusChip(true, getString(R.string.status_online))
            }
        }
    }

    override fun onTag(epc: String, rssi: String) {
        lastEpc = epc
        lastRssi = rssi
        binding.epcText.text = epc
        binding.rssiText.text = "${getString(R.string.rssi_label)}: $rssi"
        Toast.makeText(this, "Tag: $epc", Toast.LENGTH_SHORT).show()
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (message.contains("init", ignoreCase = true)) {
            setStatusChip(false, getString(R.string.status_offline))
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

    /**
     * Common Chainway C72 pistol / side scan key codes.
     * Soft Scan button always works even if OEM mapping differs.
     */
    private fun isScanTrigger(keyCode: Int): Boolean {
        return keyCode == 139 ||
            keyCode == 280 ||
            keyCode == 293 ||
            keyCode == KeyEvent.KEYCODE_F9 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_R1
    }
}
