package com.abeeway.blesimulator

import android.os.Handler
import android.os.Looper

/**
 * Tiny in-process bus so [BeaconAdvertiserService] can push live status to
 * [MainActivity] without bound-service ceremony. All listener callbacks are
 * delivered on the main thread.
 */
object AdvertiserStatus {

    data class State(
        val running: Boolean = false,
        val activeAdvertisers: Int = 0,
        val totalBeacons: Int = 0,
        val cycles: Long = 0,
        val lastInstanceHex: String = "",
        val message: String = ""
    )

    fun interface Listener {
        fun onState(state: State)
    }

    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null

    @Volatile
    var state: State = State()
        private set

    fun setListener(l: Listener?) {
        listener = l
        if (l != null) main.post { l.onState(state) }
    }

    fun update(transform: (State) -> State) {
        state = transform(state)
        val snapshot = state
        main.post { listener?.onState(snapshot) }
    }
}
