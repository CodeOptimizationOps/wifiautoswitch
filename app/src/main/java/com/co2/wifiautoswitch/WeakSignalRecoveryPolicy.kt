package com.co2.wifiautoswitch

import kotlin.math.abs

/**
 * What the caller should do in response to an [WeakSignalRecoveryPolicy.evaluate] call. The
 * policy itself never touches WifiManager — it only decides; the caller (WifiAutoSwitchService)
 * is responsible for actually calling resetAllSuggestionsTogether()/suggestAllSavedNetworks()/
 * posting or cancelling the dead-end notification.
 */
sealed class RecoveryAction {
    /** Nothing to do this cycle. */
    object None : RecoveryAction()

    /** Remove every saved network's suggestion, then re-add all except [ssid]. */
    data class ResetExcluding(val ssid: String?) : RecoveryAction()

    /** Re-suggest every saved network, with nothing excluded. Fires immediately/silently on
     *  entering a dead end — no user-facing notification yet, since this might resolve itself
     *  quickly on its own. This is a plain add, not a churn — meaningful here because it's
     *  putting back a network that was actually excluded. */
    object RestoreAll : RecoveryAction()

    /** One last-ditch nudge before giving up: a genuine remove-all/readd-all churn (not just an
     *  add — everything is already suggested by this point, so only actually removing and
     *  re-adding can prompt the OS to reconsider). Fires once, [WeakSignalRecoveryPolicy]'s first
     *  wait period after entering a dead end. */
    object LastDitchReset : RecoveryAction()

    /** The last-ditch reset didn't help either — held in the dead end for a second full wait
     *  period after it. Tell the user, since the app has genuinely exhausted what it can try on
     *  its own. Fires once per dead-end streak. */
    object NotifyDeadEnd : RecoveryAction()
}

/**
 * The location-off fallback's decision logic (Scenarios 1 and 2), extracted into a plain,
 * Android-framework-free class so it can be unit tested deterministically — the caller supplies
 * connectivity/RSSI readings and the current time instead of this class reading them itself.
 *
 * Scenario 1 (out of range of every saved network): if a reset just excluded the current weak
 * network and we're then found fully disconnected, that combination means the exclusion likely
 * left nothing else in range either. All saved suggestions are restored (nothing stays
 * permanently excluded) and further resets are paused until we reconnect to anything.
 *
 * Scenario 2 (connected, but only to a weak network): after the first reset, the RSSI we
 * reconnect at becomes a baseline. Further resets only fire once the signal degrades 25% past
 * that baseline — debounced by [degradationSettleWindowMs] so a single noisy reading can't
 * trigger one — or stop altogether once it hits [absoluteFloorRssi], restoring every suggestion
 * (same reasoning as Scenario 1) since nothing is gained from repeating a reset at rock bottom.
 *
 * Either dead-end (Scenario 1's pause, or the Scenario 2 floor) starts a two-phase, silent
 * countdown rather than immediately alarming the user:
 *  1. [RecoveryAction.RestoreAll] fires immediately (silent).
 *  2. If still stuck after [deadEndPhaseWaitMs], one [RecoveryAction.LastDitchReset] fires — a
 *     genuine churn, since a plain add does nothing when everything's already suggested.
 *  3. If still stuck after another [deadEndPhaseWaitMs] past that, a single
 *     [RecoveryAction.NotifyDeadEnd] fires.
 * Recovering (reconnecting for Scenario 1, or a fresh reset firing for Scenario 2) at any point
 * cancels whichever phase is pending.
 *
 * Recovering above [poorThresholdRssi] at any point re-arms everything for a fresh streak.
 */
class WeakSignalRecoveryPolicy(
    val poorThresholdRssi: Int = -75,
    private val absoluteFloorRssi: Int = -100,
    private val degradationFraction: Double = 0.25,
    private val degradationSettleWindowMs: Long = 10_000L,
    private val deadEndPhaseWaitMs: Long = 30_000L
) {
    private var resetFiredForCurrentPoorStreak = false
    private var isPausedAwaitingAnyConnection = false
    private var rssiAtLastReconnect: Int? = null
    private var degradedSinceMs: Long? = null
    private var hasRestoredAllAtFloor = false
    private var deadEndPhaseStartedAtMs: Long? = null
    private var hasLastDitchFired = false
    private var hasNotifiedDeadEnd = false

    /** Exposed only for tests to assert on internal state without reaching into private fields. */
    internal fun snapshot() = State(
        resetFiredForCurrentPoorStreak,
        isPausedAwaitingAnyConnection,
        rssiAtLastReconnect,
        degradedSinceMs,
        hasRestoredAllAtFloor,
        deadEndPhaseStartedAtMs,
        hasLastDitchFired,
        hasNotifiedDeadEnd
    )

    internal data class State(
        val resetFiredForCurrentPoorStreak: Boolean,
        val isPausedAwaitingAnyConnection: Boolean,
        val rssiAtLastReconnect: Int?,
        val degradedSinceMs: Long?,
        val hasRestoredAllAtFloor: Boolean,
        val deadEndPhaseStartedAtMs: Long?,
        val hasLastDitchFired: Boolean,
        val hasNotifiedDeadEnd: Boolean
    )

    /**
     * @param isConnected whether the device currently has an active Wi-Fi connection at all.
     * @param rssi the current connection's signal strength in dBm; ignored when [isConnected] is
     *   false (there's nothing to read).
     * @param currentSsid the current connection's real SSID if known, else null; only used as the
     *   exclusion target on a [RecoveryAction.ResetExcluding].
     * @param nowMs current time in milliseconds, for the settle-window and dead-end debounces.
     */
    fun evaluate(isConnected: Boolean, rssi: Int, currentSsid: String?, nowMs: Long): RecoveryAction {
        if (!isConnected) {
            if (!isPausedAwaitingAnyConnection && resetFiredForCurrentPoorStreak) {
                isPausedAwaitingAnyConnection = true
                enterDeadEnd(nowMs)
                return RecoveryAction.RestoreAll
            }
            if (isPausedAwaitingAnyConnection) {
                return progressDeadEnd(nowMs)
            }
            return RecoveryAction.None
        }

        if (isPausedAwaitingAnyConnection) {
            resumeNormalMonitoring()
        }

        if (rssi >= poorThresholdRssi) {
            // Recovered — re-arm so the next drop starts a fresh streak.
            resetFiredForCurrentPoorStreak = false
            rssiAtLastReconnect = null
            degradedSinceMs = null
            hasRestoredAllAtFloor = false
            exitDeadEnd()
            return RecoveryAction.None
        }

        if (!resetFiredForCurrentPoorStreak) {
            // First drop below threshold in this streak.
            resetFiredForCurrentPoorStreak = true
            return RecoveryAction.ResetExcluding(currentSsid)
        }

        if (rssiAtLastReconnect == null) {
            // First evaluation since that reset while still below threshold — the signal we
            // actually reconnected at (same network or a different one). Scenario 2 measures
            // further degradation from here.
            rssiAtLastReconnect = rssi
            return RecoveryAction.None
        }

        val baseline = rssiAtLastReconnect!!
        val degradedThreshold = baseline - (abs(baseline) * degradationFraction).toInt()

        return when {
            rssi <= absoluteFloorRssi -> {
                if (!hasRestoredAllAtFloor) {
                    hasRestoredAllAtFloor = true
                    enterDeadEnd(nowMs)
                    RecoveryAction.RestoreAll
                } else {
                    progressDeadEnd(nowMs)
                }
            }
            rssi <= degradedThreshold -> {
                val since = degradedSinceMs
                when {
                    since == null -> {
                        degradedSinceMs = nowMs
                        RecoveryAction.None
                    }
                    nowMs - since >= degradationSettleWindowMs -> {
                        // Degradation held for the full settle window — worth trying again. This
                        // reset introduces a fresh exclusion, so a later floor-hit under it should
                        // restore (and eventually notify) again if it comes to that.
                        rssiAtLastReconnect = null
                        degradedSinceMs = null
                        hasRestoredAllAtFloor = false
                        exitDeadEnd()
                        RecoveryAction.ResetExcluding(currentSsid)
                    }
                    else -> RecoveryAction.None // still within the settle window
                }
            }
            else -> {
                // Back within the degradation band — cancel any in-progress settle window.
                degradedSinceMs = null
                RecoveryAction.None
            }
        }
    }

    private fun enterDeadEnd(nowMs: Long) {
        deadEndPhaseStartedAtMs = nowMs
        hasLastDitchFired = false
        hasNotifiedDeadEnd = false
    }

    private fun exitDeadEnd() {
        deadEndPhaseStartedAtMs = null
        hasLastDitchFired = false
        hasNotifiedDeadEnd = false
    }

    /** Advances the two-phase dead-end countdown: last-ditch reset first, then notify. */
    private fun progressDeadEnd(nowMs: Long): RecoveryAction {
        if (hasNotifiedDeadEnd) return RecoveryAction.None
        val phaseStart = deadEndPhaseStartedAtMs ?: return RecoveryAction.None
        if (nowMs - phaseStart < deadEndPhaseWaitMs) return RecoveryAction.None

        return if (!hasLastDitchFired) {
            hasLastDitchFired = true
            deadEndPhaseStartedAtMs = nowMs // restart the countdown for the second wait
            RecoveryAction.LastDitchReset
        } else {
            hasNotifiedDeadEnd = true
            RecoveryAction.NotifyDeadEnd
        }
    }

    private fun resumeNormalMonitoring() {
        isPausedAwaitingAnyConnection = false
        resetFiredForCurrentPoorStreak = false
        rssiAtLastReconnect = null
        degradedSinceMs = null
        hasRestoredAllAtFloor = false
        exitDeadEnd()
    }
}
