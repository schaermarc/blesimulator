package com.abeeway.blesimulator

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.abeeway.blesimulator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        binding.namespaceInput.setText("0102030405060708090A")
        binding.countInput.setText("50")
        binding.dwellInput.setText("250")
        binding.concurrencyInput.setText("1")

        binding.startButton.setOnClickListener { onStartClicked() }
        binding.stopButton.setOnClickListener { onStopClicked() }

        AdvertiserStatus.setListener { render(it) }
        render(AdvertiserStatus.state)
    }

    override fun onDestroy() {
        AdvertiserStatus.setListener(null)
        super.onDestroy()
    }

    private fun onStartClicked() {
        // Validate inputs before touching Bluetooth.
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
        val intent = Intent(this, BeaconAdvertiserService::class.java).apply {
            action = BeaconAdvertiserService.ACTION_START
            putExtra(
                BeaconAdvertiserService.EXTRA_NAMESPACE,
                binding.namespaceInput.text.toString().trim()
            )
            putExtra(
                BeaconAdvertiserService.EXTRA_COUNT,
                binding.countInput.text.toString().toInt()
            )
            putExtra(
                BeaconAdvertiserService.EXTRA_DWELL_MS,
                binding.dwellInput.text.toString().toIntOrNull() ?: 250
            )
            putExtra(
                BeaconAdvertiserService.EXTRA_CONCURRENCY,
                binding.concurrencyInput.text.toString().toIntOrNull() ?: 1
            )
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
                sb.append("Last instance: #${state.lastInstanceHex}\n")
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
}
