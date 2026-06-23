# Eddystone Beacon Simulator

An Android app that simulates many **Eddystone-UID** beacons that all share one
**Namespace ID** but each have a **unique Instance ID** — built to exercise the
**Abeeway BLE sniffer** (or any aggregating BLE scanner).

By default it simulates **50 beacons**: namespace `0102030405060708090A`,
instance IDs `0x000000009001 … 0x000000009032`.

> **beaconId prefix `9`:** The Abeeway sniffer only reports the **last two
> bytes** of the Instance ID as the `beaconId`. To make those values
> recognisable, the low two bytes are `0x9000 + n`, so the sniffer shows
> `9001`, `9002`, … `9032` (beacon #3 → `"beaconId": "9003"`). The prefix is
> the `BEACON_ID_PREFIX` constant in `Eddystone.kt`.

## How it works (important)

A phone's Bluetooth radio can only run a **handful** of advertising sets at once
(often 1–8, depending on the chipset). Broadcasting 50 *simultaneous* beacons is
physically impossible on commodity hardware.

So the app **time-multiplexes**: each advertising slot broadcasts one Instance
ID for a short *dwell* time, then switches to the next. A sniffer that
aggregates detections over time (like the Abeeway sniffer) therefore sees all 50
unique beacons within one sweep.

- **Dwell (ms)** — how long each beacon is advertised before rotating. Lower =
  faster sweep, but each beacon is on-air for less time.
- **Parallel advertisers** — how many beacons to broadcast at the same instant.
  More = a full sweep finishes sooner. If the hardware refuses extra advertisers
  (`TOO_MANY_ADVERTISERS`), those slots self-retire and the remaining ones
  automatically re-partition the beacon set, so **every** beacon is still covered.

Example: 50 beacons, dwell 250 ms, 1 advertiser → a full sweep takes
`50 × 250 ms ≈ 12.5 s`. With 4 working advertisers it drops to ~3 s.

Advertising runs in a **foreground service**, so it keeps going with the screen
off until you press **Stop**.

## Eddystone-UID frame

Each beacon advertises Service Data for the 16-bit Eddystone UUID `0xFEAA`:

| Bytes | Field        | Value                                  |
|-------|--------------|----------------------------------------|
| 0     | Frame type   | `0x00` (UID)                           |
| 1     | Ranging data | calibrated Tx power @ 0 m (default −21) |
| 2–11  | Namespace ID | 10 bytes, shared by all beacons        |
| 12–17 | Instance ID  | 6 bytes, low 2 bytes = `0x9000 + n`    |
| 18–19 | RFU          | `0x00 0x00`                            |

Advertised non-connectable, which fits the Eddystone payload within the 31-byte
legacy advertising limit.

## Build

Open in Android Studio (Giraffe+), or from the command line:

```bash
# point at your SDK if not already configured
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK Platform 34, Build-Tools 34.0.0 (AGP 8.5).

## Install & run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Launch **Eddystone Beacon Simulator**.
2. (Optional) edit the Namespace ID, beacon count, dwell, and parallel advertisers.
3. Tap **Start**. Grant the *Nearby devices* permission and enable Bluetooth when
   prompted.
4. Point the Abeeway sniffer at it — it should report 50 distinct Eddystone-UID
   beacons sharing the namespace.
5. Tap **Stop** when done.

## Requirements on the device

- Android 5.0+ (API 21). Tested target is Android 14 (API 34).
- A device whose Bluetooth chipset supports **BLE peripheral / advertising mode**
  (most modern phones do; a few older/budget ones don't).
- Bluetooth turned on; *Nearby devices* (BLUETOOTH_ADVERTISE) permission granted.

## Project layout

```
app/src/main/java/com/abeeway/blesimulator/
  Eddystone.kt                 # UID frame + Instance ID generation
  BeaconAdvertiserService.kt   # foreground service, rotation + self-healing
  AdvertiserStatus.kt          # service → UI status bus
  NotificationCompatBuilder.kt # foreground notification
  MainActivity.kt              # UI, permissions, Bluetooth enable flow
```
