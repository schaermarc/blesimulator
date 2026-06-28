package com.abeeway.blesimulator

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.abeeway.blesimulator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val intervalMode: Boolean
        get() = binding.modeToggle.checkedButtonId == R.id.btnIntervalMode

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                beginAdvertising()
            } else {
                toast("Bluetooth permission is required to advertise.")
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isBluetoothOn()) startWithPermissions()
            else toast("Bluetooth must be on to advertise.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.namespaceInput.setText("626C756B626561636F6E")
        binding.countInput.setText("20")
        binding.dwellInput.setText("250")
        binding.concurrencyInput.setText("1")
        binding.intervalInput.setText("2000")
        binding.windowInput.setText("6000")
        binding.repsInput.setText("2")
        binding.txPowerSpinner.setSelection(TX_POWER_MEDIUM) // -7 dBm by default

        binding.modeToggle.check(R.id.btnIntervalMode)
        binding.modeToggle.addOnButtonCheckedListener { _, _, _ -> onModeChanged() }

        val recompute = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateComputedPlan()
        }
        binding.countInput.addTextChangedListener(recompute)
        binding.intervalInput.addTextChangedListener(recompute)
        binding.windowInput.addTextChangedListener(recompute)
        binding.repsInput.addTextChangedListener(recompute)

        binding.startButton.setOnClickListener { onStartClicked() }
        binding.stopButton.setOnClickListener { onStopClicked() }

        onModeChanged()

        AdvertiserStatus.setListener { render(it) }
        render(AdvertiserStatus.state)
    }

    override fun onDestroy() {
        AdvertiserStatus.setListener(null)
        super.onDestroy()
    }

    private fun onModeChanged() {
        val interval = intervalMode
        binding.dwellModeGroup.visibility = if (interval) View.GONE else View.VISIBLE
        binding.intervalModeGroup.visibility = if (interval) View.VISIBLE else View.GONE
        updateComputedPlan()
    }

    /** Live readout of the auto-computed dwell / advertisers for interval mode. */
    private fun updateComputedPlan() {
        if (!intervalMode) return
        val count = binding.countInput.text.toString().toIntOrNull()
        val interval = binding.intervalInput.text.toString().toIntOrNull()
        val window = binding.windowInput.text.toString().toIntOrNull()
        val reps = binding.repsInput.text.toString().toIntOrNull()
        if (count == null || count < 1 || interval == null || interval < 1 ||
            window == null || window < 1 || reps == null || reps < 1
        ) {
            binding.computedText.text = "Enter beacon count, interval, scan window, and min broadcasts."
            return
        }
        val plan = IntervalPlanner.planForWindow(count, interval, window, reps)
        val actualReps = plan.broadcastsPerWindow(window)
        val sb = StringBuilder()
        sb.append("Auto: dwell ${plan.dwellMs} ms · ${plan.advertisers} advertiser(s)\n")
        sb.append("Each beacon every ~${plan.effectiveIntervalMs} ms ")
        sb.append("→ ${actualReps}× per ${window} ms window")
        if (plan.effectiveIntervalMs < interval) {
            sb.append("\nInterval tightened from ${interval} ms to fit ≥${reps}× per window.")
        }
        if (actualReps < reps) {
            sb.append("\n⚠ Can't fit ${reps}× for $count beacons in ${window} ms " +
                "(max ${IntervalPlanner.MAX_ADVERTISERS} advertisers). Increase the " +
                "window, lower the beacon count, or lower the min broadcasts.")
        }
        binding.computedText.text = sb.toString()
    }

    private fun onStartClicked() {
        val nsHex = binding.namespaceInput.text.toString().trim()
        try {
            Eddystone.hexToBytes(nsHex, Eddystone.NAMESPACE_LEN)
        } catch (e: Exception) {
            toast("Namespace: ${e.message}")
            return
        }
        val count = binding.countInput.text.toString().toIntOrNull()
        if (count == null || count < 1) {
            toast("Enter a valid beacon count (>= 1).")
            return
        }
        if (intervalMode) {
            val interval = binding.intervalInput.text.toString().toIntOrNull()
            val window = binding.windowInput.text.toString().toIntOrNull()
            val reps = binding.repsInput.text.toString().toIntOrNull()
            if (interval == null || interval < 1) {
                toast("Enter a valid beacon interval (ms).")
                return
            }
            if (window == null || window < 1) {
                toast("Enter a valid sniffer scan window (ms).")
                return
            }
            if (reps == null || reps < 1) {
                toast("Enter a valid min broadcasts per window (>= 1).")
                return
            }
        }

        if (!isBluetoothOn()) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startWithPermissions()
    }

    private fun startWithPermissions() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) beginAdvertising()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun beginAdvertising() {
        val count = binding.countInput.text.toString().toInt()

        // Resolve dwell + advertisers from the active mode.
        val dwellMs: Int
        val concurrency: Int
        if (intervalMode) {
            val interval = binding.intervalInput.text.toString().toInt()
            val window = binding.windowInput.text.toString().toInt()
            val reps = binding.repsInput.text.toString().toInt()
            val plan = IntervalPlanner.planForWindow(count, interval, window, reps)
            dwellMs = plan.dwellMs
            concurrency = plan.advertisers
        } else {
            dwellMs = binding.dwellInput.text.toString().toIntOrNull() ?: 250
            concurrency = binding.concurrencyInput.text.toString().toIntOrNull() ?: 1
        }

        val intent = Intent(this, BeaconAdvertiserService::class.java).apply {
            action = BeaconAdvertiserService.ACTION_START
            putExtra(BeaconAdvertiserService.EXTRA_NAMESPACE, binding.namespaceInput.text.toString().trim())
            putExtra(BeaconAdvertiserService.EXTRA_COUNT, count)
            putExtra(BeaconAdvertiserService.EXTRA_DWELL_MS, dwellMs)
            putExtra(BeaconAdvertiserService.EXTRA_CONCURRENCY, concurrency)
            putExtra(BeaconAdvertiserService.EXTRA_TX_POWER_LEVEL, binding.txPowerSpinner.selectedItemPosition)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onStopClicked() {
        val intent = Intent(this, BeaconAdvertiserService::class.java).apply {
            action = BeaconAdvertiserService.ACTION_STOP
        }
        startService(intent)
    }

    private fun render(state: AdvertiserStatus.State) {
        binding.startButton.isEnabled = !state.running
        binding.stopButton.isEnabled = state.running
        setInputsEnabled(!state.running)

        val sb = StringBuilder()
        sb.append(if (state.running) "● RUNNING\n" else "○ Idle\n")
        if (state.running || state.totalBeacons > 0) {
            sb.append("Beacons: ${state.totalBeacons}\n")
            sb.append("Active advertisers: ${state.activeAdvertisers}\n")
            sb.append("Full sweeps: ${state.cycles}\n")
            if (state.lastInstanceHex.isNotEmpty()) {
                sb.append("Last beaconId: ${state.lastInstanceHex}\n")
            }
        }
        if (state.message.isNotEmpty()) sb.append("\n${state.message}")
        binding.statusText.text = sb.toString()
    }

    private fun setInputsEnabled(enabled: Boolean) {
        binding.namespaceInput.isEnabled = enabled
        binding.countInput.isEnabled = enabled
        binding.dwellInput.isEnabled = enabled
        binding.concurrencyInput.isEnabled = enabled
        binding.intervalInput.isEnabled = enabled
        binding.windowInput.isEnabled = enabled
        binding.repsInput.isEnabled = enabled
        binding.txPowerSpinner.isEnabled = enabled
        binding.btnDwellMode.isEnabled = enabled
        binding.btnIntervalMode.isEnabled = enabled
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
    }

    private fun isBluetoothOn(): Boolean {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return mgr.adapter?.isEnabled == true
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        // Spinner positions, must match the tx_power_levels string-array order.
        private const val TX_POWER_MEDIUM = 2
    }
}
