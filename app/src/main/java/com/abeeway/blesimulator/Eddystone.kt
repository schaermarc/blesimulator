package com.abeeway.blesimulator

import android.os.ParcelUuid
import java.util.UUID

/**
 * Helpers for building Eddystone-UID advertising payloads.
 *
 * Eddystone-UID service data layout (20 bytes), per the Eddystone spec:
 *   byte  0       : frame type, 0x00 = UID
 *   byte  1       : ranging data (calibrated Tx power at 0 m), signed int8
 *   bytes 2..11   : 10-byte Namespace ID
 *   bytes 12..17  : 6-byte Instance ID
 *   bytes 18..19  : RFU (reserved), 0x00 0x00
 *
 * The whole thing is advertised as Service Data for the 16-bit Eddystone
 * service UUID 0xFEAA.
 */
object Eddystone {

    const val NAMESPACE_LEN = 10
    const val INSTANCE_LEN = 6

    /** 16-bit Eddystone service UUID 0xFEAA expressed as a full 128-bit UUID. */
    val SERVICE_PARCEL_UUID: ParcelUuid =
        ParcelUuid(UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB"))

    /**
     * Builds the 20-byte Eddystone-UID service data.
     *
     * @param namespace exactly 10 bytes
     * @param instance  exactly 6 bytes
     * @param txPower   calibrated RSSI at 0 m (e.g. -21 dBm). Stored as signed int8.
     */
    fun uidServiceData(namespace: ByteArray, instance: ByteArray, txPower: Int): ByteArray {
        require(namespace.size == NAMESPACE_LEN) {
            "Namespace must be $NAMESPACE_LEN bytes, was ${namespace.size}"
        }
        require(instance.size == INSTANCE_LEN) {
            "Instance must be $INSTANCE_LEN bytes, was ${instance.size}"
        }
        val data = ByteArray(20)
        data[0] = 0x00                       // UID frame type
        data[1] = txPower.toByte()           // ranging data
        System.arraycopy(namespace, 0, data, 2, NAMESPACE_LEN)
        System.arraycopy(instance, 0, data, 12, INSTANCE_LEN)
        // bytes 18..19 already 0x00 (RFU)
        return data
    }

    /** Parses a hex string (optionally containing spaces / "0x") into [expectedLen] bytes. */
    fun hexToBytes(hex: String, expectedLen: Int): ByteArray {
        val clean = hex.replace("0x", "", ignoreCase = true)
            .replace(" ", "")
            .replace(":", "")
            .replace("-", "")
        require(clean.length == expectedLen * 2) {
            "Expected ${expectedLen * 2} hex chars ($expectedLen bytes), got ${clean.length}"
        }
        require(clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Value contains non-hex characters"
        }
        return ByteArray(expectedLen) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    /**
     * Prefix applied to the low 16 bits of every Instance ID. The Abeeway
     * sniffer only reports the last two bytes of the Instance ID as the
     * beaconId, so the distinguishing value must live there. With this prefix
     * the IDs become 0x9001, 0x9002, … and the sniffer shows "9001", "9002", …
     */
    const val BEACON_ID_PREFIX = 0x9000

    /** The beaconId the sniffer reports for the n-th beacon (1-based), e.g. "9003". */
    fun beaconId(n: Int): String = "%04X".format(BEACON_ID_PREFIX + n)

    /**
     * Builds [count] Instance IDs as 6-byte big-endian values whose low two
     * bytes are [BEACON_ID_PREFIX] + index: 0x000000009001, 0x000000009002, …
     * All share the same Namespace ID, which is what we want for testing the
     * Abeeway BLE sniffer (it keys on the last two bytes).
     */
    fun sequentialInstances(count: Int): List<ByteArray> =
        (1..count).map { n ->
            val b = ByteArray(INSTANCE_LEN)
            var v = (BEACON_ID_PREFIX + n).toLong()
            for (i in INSTANCE_LEN - 1 downTo 0) {
                b[i] = (v and 0xFF).toByte()
                v = v shr 8
            }
            b
        }
}
