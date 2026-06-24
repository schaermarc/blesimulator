package com.abeeway.blesimulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that simulates many Eddystone-UID beacons from a single
 * radio by time-multiplexing.
 *
 * A phone's BLE chip can only run a few advertising sets at once, so 50
 * *simultaneous* beacons is not physically possible. Instead we rotate: each
 * advertising slot shows one Instance ID for [dwellMs], then switches to the
 * next. A sniffer that aggregates detections (like the Abeeway BLE sniffer)
 * sees every unique beacon within one sweep.
 *
 * Rotation uses the modern [BluetoothLeAdvertiser.startAdvertisingSet] API and
 * swaps the payload live with [AdvertisingSet.setAdvertisingData]. This avoids
 * the stop/start churn of the legacy advertiser, which races on many stacks
 * (notably Samsung) and dies with ALREADY_STARTED after the first cycle.
 */
class BeaconAdvertiserService : Service() {

    companion object {
        const val ACTION_START = "com.abeeway.blesimulator.START"
        const val ACTION_STOP = "com.abeeway.blesimulator.STOP"

        const val EXTRA_NAMESPACE = "namespace"   // 20-char hex (10 bytes)
        const val EXTRA_COUNT = "count"           // number of beacons
        const val EXTRA_DWELL_MS = "dwellMs"      // per-beacon advertise time
        const val EXTRA_CONCURRENCY = "concurrency"
        const val EXTRA_TX_POWER_LEVEL = "txPowerLevel"  // 0..3 (ultra-low..high)

        const val TAG = "BleSim"
        private const val CHANNEL_ID = "ble_simulator"
        private const val NOTIFICATION_ID = 1
    }

    private var advertiser: BluetoothLeAdvertiser? = null

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    private lateinit var params: AdvertisingSetParameters
    private var beacons: List<ByteArray> = emptyList()   // service-data payloads
    private var namespaceHex: String = ""
    private var dwellMs: Long = 250

    private var slots: List<Slot> = emptyList()
    private var advertisedCount: Long = 0

    /** One concurrent advertising set. */
    private inner class Slot(val id: Int) {
        @Volatile var set: AdvertisingSet? = null
        @Volatile var failed = false
        @Volatile var started = false
        var step = 0

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (status == ADVERTISE_SUCCESS && advertisingSet != null) {
                    set = advertisingSet
                    started = true
                    Log.i(TAG, "Slot $id started (txPower=$txPower)")
                    publishRunningStatus()
                } else {
                    failed = true
                    val msg = "Slot $id failed to start: ${decode(status)}"
                    Log.e(TAG, msg)
                    AdvertiserStatus.update { it.copy(message = msg) }
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                started = false
                set = null
            }

            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
                if (status != ADVERTISE_SUCCESS) {
                    Log.w(TAG, "Slot $id setAdvertisingData failed: ${decode(status)}")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            AdvertiserStatus.update { it.copy(message = "Foreground service error: ${e.message}") }
        }

        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = mgr.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is off. Turn it on and try again.")
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            fail("This device does not support BLE advertising.")
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            // Not fatal for a single advertiser, just note it.
            Log.w(TAG, "isMultipleAdvertisementSupported=false")
        }
        advertiser = adv

        Log.i(
            TAG,
            "caps: multiAdv=${adapter.isMultipleAdvertisementSupported} " +
                "extendedAdv=${adapter.isLeExtendedAdvertisingSupported} " +
                "maxAdvDataLen=${adapter.leMaximumAdvertisingDataLength}"
        )

        namespaceHex = intent.getStringExtra(EXTRA_NAMESPACE) ?: "0102030405060708090A"
        val count = intent.getIntExtra(EXTRA_COUNT, 50).coerceIn(1, 1000)
        dwellMs = intent.getIntExtra(EXTRA_DWELL_MS, 250).toLong().coerceIn(50, 10_000)
        val concurrency = intent.getIntExtra(EXTRA_CONCURRENCY, 1).coerceIn(1, 8)
        val txLevel = intent.getIntExtra(EXTRA_TX_POWER_LEVEL, 3).coerceIn(0, 3)
        // The Eddystone ranging-data byte advertises the calibrated power at 0 m,
        // so set it to the nominal dBm of the chosen radio Tx power level.
        val rangingDbm = nominalDbm(txLevel)

        val namespace = try {
            Eddystone.hexToBytes(namespaceHex, Eddystone.NAMESPACE_LEN)
        } catch (e: Exception) {
            fail("Invalid namespace: ${e.message}")
            return
        }
        beacons = Eddystone.sequentialInstances(count).map { instance ->
            Eddystone.uidServiceData(namespace, instance, rangingDbm)
        }

        // Legacy-mode PDU so every scanner (the Abeeway sniffer, other phones)
        // can see it. Non-connectable, non-scannable = ADV_NONCONN_IND.
        params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(radioTxLevel(txLevel))
            .build()

        slots = (0 until concurrency).map { Slot(it) }
        advertisedCount = 0

        AdvertiserStatus.update {
            it.copy(
                running = true,
                totalBeacons = count,
                activeAdvertisers = 0,
                cycles = 0,
                lastInstanceHex = "",
                message = "Starting $count Eddystone-UID beacons " +
                    "(namespace $namespaceHex, beaconId ${Eddystone.beaconId(1)}…" +
                    "${Eddystone.beaconId(count)})"
            )
        }

        // Kick off one advertising set per slot with its first payload.
        slots.forEachIndexed { ord, slot ->
            val firstIndex = ord            // modulo partition, first element
            if (firstIndex >= beacons.size) return@forEachIndexed
            val data = buildData(beacons[firstIndex])
            try {
                adv.startAdvertisingSet(params, data, null, null, null, slot.callback)
                Log.i(TAG, "Slot ${slot.id} startAdvertisingSet requested")
            } catch (e: Exception) {
                slot.failed = true
                Log.e(TAG, "Slot ${slot.id} startAdvertisingSet threw", e)
                AdvertiserStatus.update { it.copy(message = "Advertise error: ${e.message}") }
            }
        }

        worker.removeCallbacksAndMessages(null)
        worker.postDelayed(rotateRunnable, dwellMs)
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
                val set = slot.set ?: return@forEachIndexed   // not ready yet
                val subset = ArrayList<Int>()
                var j = ord
                while (j < beacons.size) { subset.add(j); j += n }
                if (subset.isEmpty()) return@forEachIndexed

                val beaconIndex = subset[slot.step % subset.size]
                slot.step++
                try {
                    set.setAdvertisingData(buildData(beacons[beaconIndex]))
                } catch (e: Exception) {
                    Log.w(TAG, "Slot ${slot.id} rotate failed", e)
                }
                advertisedCount++
                lastInstance = Eddystone.beaconId(beaconIndex + 1)
            }

            val sweeps = if (beacons.isEmpty()) 0L else advertisedCount / beacons.size
            if (lastInstance.isNotEmpty()) {
                AdvertiserStatus.update {
                    it.copy(cycles = sweeps, lastInstanceHex = lastInstance)
                }
            }

            worker.postDelayed(this, dwellMs)
        }
    }

    private fun publishRunningStatus() {
        val activeCount = slots.count { it.started && !it.failed }
        AdvertiserStatus.update {
            it.copy(
                running = true,
                activeAdvertisers = activeCount,
                message = "Advertising ${beacons.size} beacons across " +
                    "$activeCount advertiser(s)."
            )
        }
    }

    private fun buildData(serviceData: ByteArray): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(Eddystone.SERVICE_PARCEL_UUID)
            .addServiceData(Eddystone.SERVICE_PARCEL_UUID, serviceData)
            .build()

    private fun fail(message: String) {
        Log.e(TAG, message)
        AdvertiserStatus.update {
            it.copy(running = false, activeAdvertisers = 0, message = message)
        }
    }

    private fun stopAdvertisingAndSelf() {
        worker.removeCallbacksAndMessages(null)
        val adv = advertiser
        if (adv != null) {
            slots.forEach { slot ->
                if (slot.started) {
                    try {
                        adv.stopAdvertisingSet(slot.callback)
                    } catch (_: Exception) {
                    }
                    slot.started = false
                }
            }
        }
        slots = emptyList()
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

    /** Maps a 0..3 UI selection to an AdvertisingSetParameters Tx power level. */
    private fun radioTxLevel(level: Int): Int = when (level) {
        0 -> AdvertisingSetParameters.TX_POWER_ULTRA_LOW
        1 -> AdvertisingSetParameters.TX_POWER_LOW
        2 -> AdvertisingSetParameters.TX_POWER_MEDIUM
        else -> AdvertisingSetParameters.TX_POWER_HIGH
    }

    /** Nominal dBm for each Tx power level, used for the Eddystone ranging byte. */
    private fun nominalDbm(level: Int): Int = when (level) {
        0 -> -21
        1 -> -15
        2 -> -7
        else -> 1
    }

    private fun decode(status: Int): String = when (status) {
        AdvertisingSetCallback.ADVERTISE_SUCCESS -> "SUCCESS"
        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        else -> "code $status"
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE Simulator",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Eddystone beacon advertising" }
        nm.createNotificationChannel(channel)

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
