# Why there is no iPhone version

**Short answer: an iPhone cannot simulate Eddystone-UID beacons.** This is an
Apple platform restriction, not a gap in this project — so there is no iOS app,
and the Android app remains the Eddystone tester.

## The technical reason

Eddystone-UID carries everything that matters — the frame type, the 10-byte
Namespace ID, and the 6-byte Instance ID — inside the **Service Data** for the
16-bit Eddystone service UUID `0xFEAA`:

```
0xFEAA service data: [00][txPower][ 10-byte namespace ][ 6-byte instance ][0000]
```

On Android, `AdvertiseData.Builder().addServiceData(...)` puts those bytes on the
air, which is exactly what the Abeeway sniffer reads.

On iOS, **CoreBluetooth does not let an app advertise service data at all.**
`CBPeripheralManager.startAdvertising(_:)` documents that it honors only two keys:

| Advertisement key                       | Supported in `startAdvertising`? |
|-----------------------------------------|----------------------------------|
| `CBAdvertisementDataLocalNameKey`       | ✅ yes                           |
| `CBAdvertisementDataServiceUUIDsKey`    | ✅ yes                           |
| `CBAdvertisementDataServiceDataKey`     | ❌ ignored                       |
| `CBAdvertisementDataManufacturerDataKey`| ❌ ignored                       |

Any other key — including service data and manufacturer data — is silently
dropped. An iPhone can announce that it *supports* the `0xFEAA` service UUID, but
it cannot attach the namespace/instance payload that makes a packet an Eddystone
beacon. Without that payload there is nothing for the sniffer to decode.

There is no public API, entitlement, or background mode that changes this. The
only ways around it are outside a normal app: jailbreak, an MFi/External
Accessory hardware peripheral, or driving separate BLE hardware.

## What iOS *can* advertise (and why it doesn't help here)

- **iBeacon** — Apple's own format (a 16-byte Proximity UUID + 2-byte Major +
  2-byte Minor), advertised via `CLBeaconRegion.peripheralData(withMeasuredPower:)`.
  This is a **different protocol** from Eddystone. It would only be useful if the
  Abeeway sniffer were configured to detect iBeacon, and even then iOS will only
  advertise it **in the foreground** and **one region at a time** (so 20 unique
  beacons would have to be time-multiplexed by rotating Major/Minor). It still
  would not be Eddystone.

## If you need real Eddystone from "a small device"

Use hardware that has an open BLE stack, for example:

- A **Nordic nRF52** dev board (e.g. nRF52840 dongle) flashed with an Eddystone
  beacon example — emits genuine `0xFEAA` service data.
- A **Raspberry Pi** with BlueZ (`bluetoothctl` / `hcitool`-based advertising).
- Any **Android** phone — which is exactly what the app in this repo does.

## Bottom line

The Android app in this repository is the supported way to simulate Eddystone-UID
beacons for the Abeeway sniffer. An equivalent iPhone app is not possible because
iOS forbids the advertising payload Eddystone requires.
