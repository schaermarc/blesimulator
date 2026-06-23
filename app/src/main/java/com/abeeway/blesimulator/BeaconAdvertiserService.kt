package com.abeeway.blesimulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Foreground service that simulates many Eddystone-UID beacons from a single
 * radio by time-multiplexing.
 *
 * A phone's BLE chip can only run a few advertising sets at once (often 1–8),
 * so 50 *simultaneous* beacons is not physically possible. Instead we rotate:
 * each advertising slot shows one Instance ID for [dwellMs], then switches to
 * the next. A sniffer that aggregates detections (like the Abeeway BLE
 * sniffer) sees every unique beacon within one sweep.
 *
 * Running several slots in parallel speeds up a full sweep. If the hardware
 * refuses a slot (TOO_MANY_ADVERTISERS) we permanently retire it and the
 * remaining slots automatically re-partition the beacon set, so coverage of
 * all beacons is always preserved.
 */
class BeaconAdvertiserService : Service() {

    companion object {
        const val ACTION_START = "com.abeeway.blesimulator.START"
        const val ACTION_STOP = "com.abeeway.blesimulator.STOP"

        const val EXTRA_NAMESPACE = "namespace"   // 20-char hex (10 bytes)
        const val EXTRA_COUNT = "count"           // number of beacons
        const val EXTRA_DWELL_MS = "dwellMs"      // per-beacon advertise time
        const val EXTRA_CONCURRENCY = "concurrency"
        const val EXTRA_TX_POWER = "txPower"      // ranging data (dBm)

        private const val CHANNEL_ID = "ble_simulator"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var advertiser: BluetoothLeAdvertiser
    private var available = false

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    private lateinit var settings: AdvertiseSettings
    private var beacons: List<ByteArray> = emptyList()   // service-data payloads
    private var namespaceHex: String = ""
    private var dwellMs: Long = 250

    private lateinit var slots: List<Slot>
    private var advertisedCount: Long = 0

    /** One concurrent advertiser. [failed] slots are retired and skipped. */
    private inner class Slot(val id: Int) : AdvertiseCallback() {
        var failed = false
        var started = false
        var step = 0

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS && id != 0) {
                // Retire this slot; survivors re-partition on the next tick.
                failed = true
                AdvertiserStatus.update {
                    it.copy(message = "Slot $id retired (hardware advertiser limit reached)")
                }
            } else if (id == 0) {
                AdvertiserStatus.update {
                    it.copy(message = "Advertising failed (code $errorCode). Is Bluetooth on?")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = mgr.adapter
        val adv = adapter?.bluetoothLeAdvertiser
        if (adapter != null && adapter.isEnabled && adv != null) {
            advertiser = adv
            available = true
        }
        workerThread = HandlerThread("ble-rotation").also { it.start() }
        worker = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAdvertisingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startAdvertising(intent)
        }
        return START_STICKY
    }

    private fun startAdvertising(intent: Intent) {
        startForegroundCompat()

        if (!available) {
            AdvertiserStatus.update {
                it.copy(
                    running = false,
                    message = "BLE advertising unavailable. Enable Bluetooth and grant the " +
                        "Nearby devices / BLUETOOTH_ADVERTISE permission."
                )
            }
            return
        }

        namespaceHex = intent.getStringExtra(EXTRA_NAMESPACE)
            ?: "0102030405060708090A"
        val count = intent.getIntExtra(EXTRA_COUNT, 50).coerceIn(1, 1000)
        dwellMs = intent.getIntExtra(EXTRA_DWELL_MS, 250).toLong().coerceIn(50, 10_000)
        val concurrency = intent.getIntExtra(EXTRA_CONCURRENCY, 1).coerceIn(1, 8)
        val txPower = intent.getIntExtra(EXTRA_TX_POWER, -21)

        val namespace = Eddystone.hexToBytes(namespaceHex, Eddystone.NAMESPACE_LEN)
        beacons = Eddystone.sequentialInstances(count).map { instance ->
            Eddystone.uidServiceData(namespace, instance, txPower)
        }

        settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        slots = (0 until concurrency).map { Slot(it) }
        advertisedCount = 0

        AdvertiserStatus.update {
            it.copy(
                running = true,
                totalBeacons = count,
                activeAdvertisers = concurrency,
                cycles = 0,
                lastInstanceHex = "",
                message = "Advertising ${count} Eddystone-UID beacons " +
                    "(namespace $namespaceHex)"
            )
        }

        worker.removeCallbacksAndMessages(null)
        worker.post(rotateRunnable)
    }

    private val rotateRunnable = object : Runnable {
        override fun run() {
            val active = slots.filterNot { it.failed }
            val n = active.size
            if (n == 0) {
                AdvertiserStatus.update {
                    it.copy(running = false, message = "No advertisers available.")
                }
                return
            }

            var lastInstance = ""
            // Partition by modulo so the union of all slots covers every beacon.
            active.forEachIndexed { ord, slot ->
                val subset = ArrayList<Int>()
                var j = ord
                while (j < beacons.size) { subset.add(j); j += n }
                if (subset.isEmpty()) return@forEachIndexed

                val beaconIndex = subset[slot.step % subset.size]
                slot.step++
                reprogram(slot, beacons[beaconIndex])
                advertisedCount++
                lastInstance = "%06d".format(beaconIndex + 1)
            }

            val sweeps = if (beacons.isEmpty()) 0L else advertisedCount / beacons.size
            AdvertiserStatus.update {
                it.copy(
                    activeAdvertisers = n,
                    cycles = sweeps,
                    lastInstanceHex = lastInstance
                )
            }

            worker.postDelayed(this, dwellMs)
        }
    }

    private fun reprogram(slot: Slot, serviceData: ByteArray) {
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(Eddystone.SERVICE_PARCEL_UUID)
            .addServiceData(Eddystone.SERVICE_PARCEL_UUID, serviceData)
            .build()
        try {
            if (slot.started) advertiser.stopAdvertising(slot)
            advertiser.startAdvertising(settings, data, slot)
            slot.started = true
        } catch (e: Exception) {
            AdvertiserStatus.update { it.copy(message = "Advertise error: ${e.message}") }
        }
    }

    private fun stopAdvertisingAndSelf() {
        worker.removeCallbacksAndMessages(null)
        if (available && ::slots.isInitialized) {
            slots.forEach { slot ->
                if (slot.started) {
                    try {
                        advertiser.stopAdvertising(slot)
                    } catch (_: Exception) {
                    }
                    slot.started = false
                }
            }
        }
        AdvertiserStatus.update {
            it.copy(running = false, activeAdvertisers = 0, message = "Stopped.")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Simulator",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Eddystone beacon advertising" }
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompatBuilder.build(this, CHANNEL_ID)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
