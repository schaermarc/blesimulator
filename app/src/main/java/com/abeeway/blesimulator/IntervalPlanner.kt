package com.abeeway.blesimulator

import kotlin.math.ceil

/**
 * Translates a desired per-beacon broadcast interval into a concrete
 * (dwell, advertiser-count) plan for the time-multiplexed advertiser.
 *
 * With [advertisers] parallel slots each dwelling [dwellMs], a full sweep of
 * `count` beacons takes `ceil(count / advertisers) * dwellMs`. We want that
 * sweep period to match the requested interval, so each unique beacon goes on
 * air once per interval.
 *
 * Constraints:
 *  - dwell can't drop below [MIN_DWELL_MS] or a beacon wouldn't get a full
 *    advertising event before rotating.
 *  - we won't request more than [MAX_ADVERTISERS] concurrent sets, which is a
 *    safe ceiling for commodity phones.
 *
 * When the target can't be met (too many beacons for too short an interval on
 * limited hardware), [Plan.effectiveIntervalMs] reports what is actually
 * achievable.
 */
object IntervalPlanner {

    const val MIN_DWELL_MS = 100
    const val MAX_ADVERTISERS = 4

    data class Plan(
        val dwellMs: Int,
        val advertisers: Int,
        val effectiveIntervalMs: Int
    ) {
        /** True when the achievable interval matches the requested one. */
        fun meetsTarget(targetMs: Int): Boolean = effectiveIntervalMs <= targetMs
    }

    fun plan(count: Int, intervalMs: Int): Plan {
        val n = count.coerceAtLeast(1)
        val interval = intervalMs.coerceAtLeast(MIN_DWELL_MS)

        // Smallest advertiser count so that ceil(n / k) * MIN_DWELL <= interval.
        val k = ceil(n.toDouble() * MIN_DWELL_MS / interval).toInt()
            .coerceIn(1, MAX_ADVERTISERS)

        val slotsEach = ceil(n.toDouble() / k).toInt().coerceAtLeast(1)
        val dwell = (interval / slotsEach).coerceAtLeast(MIN_DWELL_MS)
        val effective = slotsEach * dwell
        return Plan(dwell, k, effective)
    }
}
