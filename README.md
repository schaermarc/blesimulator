# Eddystone Beacon Simulator

An Android app that simulates many **Eddystone-UID** beacons that all share one
**Namespace ID** but each have a **unique Instance ID** — built to exercise the
**Abeeway BLE sniffer** (or any aggregating BLE scanner).

It launches in **Interval mode** with sensible defaults: **20 beacons**,
namespace `626C756B626561636F6E`, each beacon broadcast at least **2×** within a
**6000 ms** sniffer scan window, at **−7 dBm** (Medium) Tx power. Instance IDs
run `0x000000009001 … 0x000000009014`. Everything is editable in the UI.

> **beaconId prefix `9`:** The Abeeway sniffer only reports the **last two
> bytes** of the Instance ID as the `beaconId`. To make those values
> recognisable, the low two bytes are `0x9000 + n`, so the sniffer shows
> `9001`, `9002`, … (beacon #3 → `"beaconId": "9003"`). The prefix is
> the `BEACON_ID_PREFIX` constant in `Eddystone.kt`.

## How it works (important)

A phone's Bluetooth radio can only run a **handful** of advertising sets at once
(often 1–8, depending on the chipset). Broadcasting 50 *simultaneous* beacons is
physically impossible on commodity hardware.

So the app **time-multiplexes**: each advertising slot broadcasts one Instance
ID for a short *dwell* time, then switches to the next. A sniffer that
aggregates detections over time (like the Abeeway sniffer) therefore sees all 50
unique beacons within one sweep.

### Two modes

The mode toggle at the top picks how the rotation is configured:

- **Dwell mode** (manual) — you set the **dwell** and the number of **parallel
  advertisers** directly.
  - *Dwell (ms)* — how long each beacon is advertised before rotating.
  - *Parallel advertisers* — how many beacons broadcast at the same instant.
    More = a full sweep finishes sooner. If the hardware refuses extra
    advertisers (`TOO_MANY_ADVERTISERS`), those slots self-retire and the
    remaining ones re-partition the beacon set, so **every** beacon is still
    covered.
  - Example: 50 beacons, dwell 250 ms, 1 advertiser → a full sweep takes
    `50 × 250 ms ≈ 12.5 s`. With 4 advertisers it drops to ~3 s.

- **Interval mode** (automatic) — you set the **sniffer scan window** (ms) and
  the **minimum number of broadcasts** each unique beacon must get within that
  window, plus the beacon count and Tx power. The app derives the required
  per-beacon interval (`window / repetitions`) and **computes the dwell and
  advertiser count** so every beacon is guaranteed to appear at least that many
  times in any scan window of that length. It shows the result live, including
  the actual broadcasts-per-window — and warns when the hardware can't meet the
  target. The plan picks the fewest advertisers (capped at 4) that keep the
  dwell at or above 100 ms — see `IntervalPlanner.kt`.
  - Why: a beacon recurring every `T` ms appears `floor(window / T)` times in a
    window of that length (worst-case phase). Requiring `T ≤ window / reps`
    therefore guarantees at least `reps` appearances.
  - Example: 20 beacons, 6000 ms window, 2 broadcasts → required interval
    3000 ms → 1 advertiser, dwell 150 ms, each beacon every ~3000 ms = 2× per
    window. 50 beacons, 2000 ms window, 2 broadcasts → required 1000 ms, but
    that needs 5 advertisers → capped to 4, effective ~1300 ms = only 1× per
    window (a warning is shown).

### Tx power

A **Tx power** selector controls the radio transmit power, which is what changes
the RSSI the sniffer reports:

| Level     | Radio power | Eddystone ranging byte |
|-----------|-------------|------------------------|
| Ultra low | −21 dBm     | −21 dBm                |
| Low       | −15 dBm     | −15 dBm                |
| Medium    | −7 dBm      | −7 dBm                 |
| High      | +1 dBm      | +1 dBm                 |

The chosen level also sets the Eddystone-UID *ranging data* byte (calibrated
power at 0 m) to the matching nominal value. Default is **Medium (−7 dBm)**.

Rotation uses the modern `BluetoothLeAdvertiser.startAdvertisingSet()` API and
swaps the payload live with `AdvertisingSet.setAdvertisingData()`. This avoids
the legacy `stopAdvertising()/startAdvertising()` churn, which races on many
stacks (notably Samsung) and silently dies after the first cycle. Sets are
advertised in **legacy PDU mode** so every scanner can see them.

Advertising runs in a **foreground service**, so it keeps going with the screen
off until you press **Stop**.

### Troubleshooting

If the sniffer sees nothing, watch the app's status text — it reports
"Advertising N beacons across K advertiser(s)" on success, or the exact failure
reason otherwise. For full detail, connect via USB and run:

```bash
adb logcat -s BleSim
```

You'll see capability info (`multiAdv`, `extendedAdv`, `maxAdvDataLen`), each
slot starting, and any failure code (e.g. `DATA_TOO_LARGE`, `ALREADY_STARTED`).
Make sure Bluetooth is on and the *Nearby devices* permission is granted.

## Eddystone-UID frame

Each beacon advertises Service Data for the 16-bit Eddystone UUID `0xFEAA`:

| Bytes | Field        | Value                                  |
|-------|--------------|----------------------------------------|
| 0     | Frame type   | `0x00` (UID)                           |
| 1     | Ranging data | calibrated Tx power @ 0 m (from the Tx power selector) |
| 2–11  | Namespace ID | 10 bytes, shared by all beacons        |
| 12–17 | Instance ID  | 6 bytes, low 2 bytes = `0x9000 + n`    |
| 18–19 | RFU          | `0x00 0x00`                            |

Advertised non-connectable, which fits the Eddystone payload within the 31-byte
legacy advertising limit.

## Minimum requirements to install & run

| Requirement | Minimum | Notes |
|-------------|---------|-------|
| Android version | **8.0 Oreo (API level 26)** | The app's `minSdk` is 26; older devices can't install it. Required by the `AdvertisingSet` advertising API. |
| Target / tested | Android 14 (API 34) | Builds against Android 16; verified on a Galaxy S23 (Android 16). |
| Bluetooth | **BLE 4.0+ with peripheral (advertising) support** | The chipset must be able to *advertise*, not just scan. Most phones from ~2015 on can; a few budget/older models can't — if advertising isn't supported the app shows "This device does not support BLE advertising." |
| Bluetooth state | Turned **on** | The app prompts to enable it if it's off. |
| Permission | **Nearby devices** (`BLUETOOTH_ADVERTISE`) | Granted at runtime on first Start. On Android 13+ the app also asks for notification permission for its foreground-service notice. |
| Storage | ~6 MB | APK is ~5.6 MB. |

No internet connection, account, or Google Play Services are required.

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
2. Pick **Dwell mode** or **Interval mode**, set the Tx power, and (optionally)
   edit the Namespace ID, beacon count, and the mode-specific timing fields.
3. Tap **Start**. Grant the *Nearby devices* permission and enable Bluetooth when
   prompted.
4. Point the Abeeway sniffer at it — it should report 50 distinct Eddystone-UID
   beacons sharing the namespace.
5. Tap **Stop** when done.

## Project layout

```
app/src/main/java/com/abeeway/blesimulator/
  Eddystone.kt                 # UID frame + Instance ID generation
  IntervalPlanner.kt           # interval -> (dwell, advertisers) computation
  BeaconAdvertiserService.kt   # foreground service, rotation + self-healing
  AdvertiserStatus.kt          # service → UI status bus
  NotificationCompatBuilder.kt # foreground notification
  MainActivity.kt              # UI, permissions, Bluetooth enable flow
```
