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

    /** Re-suggest every saved network, with nothing excluded. Fires either as the scheduled,
     *  unconditional re-inclusion of a previously-excluded network, or as the last-ditch churn's
     *  cleanup — either way it's a plain add, safe to call even when everything's already
     *  suggested. */
    object RestoreAll : RecoveryAction()

    /** One last-ditch nudge before giving up: a genuine remove-all/readd-all churn (not just an
     *  add — everything is already suggested by this point, so only actually removing and
     *  re-adding can prompt the OS to reconsider). Fires once, [WeakSignalRecoveryPolicy]'s first
     *  wait period into a poor streak that hasn't recovered. */
    object LastDitchReset : RecoveryAction()

    /** The last-ditch reset didn't help either — the current connection has stayed poor for a
     *  second full wait period past it. Tell the user, since the app has genuinely exhausted
     *  what it can try on its own. Fires once per streak. */
    object NotifyDeadEnd : RecoveryAction()
}

/**
 * The location-off fallback's decision logic, extracted into a plain, Android-framework-free
 * class so it can be unit tested deterministically — the caller supplies connectivity/RSSI
 * readings and the current time instead of this class reading them itself.
 *
 * Key constraint this is built around: once a network's suggestion is excluded, its RSSI can
 * never be observed again — we're not connected to it, and can't scan for it without Location.
 * So re-including an excluded network can *only* be time-based, never "wait for its signal to
 * recover" (that condition can never become true from our side). Every [RecoveryAction.ResetExcluding]
 * therefore schedules an unconditional [RecoveryAction.RestoreAll] exactly
 * [excludedNetworkReincludeDelayMs] later, regardless of what happens to the current connection
 * in between — Android's own system-level scanning (not gated by our Location limitations) then
 * decides on its own whether the re-included network is actually usable again.
 *
 * Separately, the *currently connected* network's RSSI — which we can always observe — drives:
 *  - The initial exclusion, and further exclusions once the signal degrades another
 *    [degradationFraction] past wherever it was when we last reconnected, debounced by
 *    [degradationSettleWindowMs] so a single noisy reading can't trigger one.
 *  - Once at [absoluteFloorRssi], no more exclusions are attempted (nothing left to gain from
 *    repeating one at rock bottom) — but the streak continues, so the escalation below still runs.
 *  - A streak that hasn't recovered for [deadEndPhaseWaitMs] gets one [RecoveryAction.LastDitchReset]
 *    (a genuine churn, not just an add — everything's already suggested by then). If that doesn't
 *    help within another [deadEndPhaseWaitMs], a single [RecoveryAction.NotifyDeadEnd] fires.
 *
 * Recovering above [poorThresholdRssi] at any point ends the streak and its escalation timer
 * (the pending re-inclusion timer, if any, is unaffected — it always runs to completion).
 */
class WeakSignalRecoveryPolicy(
    val poorThresholdRssi: Int = -75,
    private val absoluteFloorRssi: Int = -100,
    private val degradationFraction: Double = 0.25,
    private val degradationSettleWindowMs: Long = 10_000L,
    private val excludedNetworkReincludeDelayMs: Long = 15_000L,
    private val deadEndPhaseWaitMs: Long = 30_000L
) {
    // Pending automatic re-inclusion — purely time-based, see class doc for why.
    private var excludedSsid: String? = null
    private var excludedAtMs: Long? = null

    // Tracks the currently-connected network's ongoing quality within the current poor streak.
    private var streakStartedAtMs: Long? = null
    private var rssiAtLastReconnect: Int? = null
    private var degradedSinceMs: Long? = null
    private var hasLastDitchFired = false
    private var hasNotifiedDeadEnd = false

    /** Exposed only for tests to assert on internal state without reaching into private fields. */
    internal fun snapshot() = State(
        excludedSsid,
        excludedAtMs,
        streakStartedAtMs,
        rssiAtLastReconnect,
        degradedSinceMs,
        hasLastDitchFired,
        hasNotifiedDeadEnd
    )

    internal data class State(
        val excludedSsid: String?,
        val excludedAtMs: Long?,
        val streakStartedAtMs: Long?,
        val rssiAtLastReconnect: Int?,
        val degradedSinceMs: Long?,
        val hasLastDitchFired: Boolean,
        val hasNotifiedDeadEnd: Boolean
    )

    /**
     * @param isConnected whether the device currently has an active Wi-Fi connection at all.
     * @param rssi the current connection's signal strength in dBm; ignored when [isConnected] is
     *   false (there's nothing to read).
     * @param currentSsid the current connection's real SSID if known, else null; only used as the
     *   exclusion target on a [RecoveryAction.ResetExcluding].
     * @param nowMs current time in milliseconds, for the settle-window, re-include and dead-end
     *   timers.
     */
    fun evaluate(isConnected: Boolean, rssi: Int, currentSsid: String?, nowMs: Long): RecoveryAction {
        // Pending re-inclusion always takes priority — time-based, so it applies regardless of
        // connect state, and regardless of anything else going on in the current streak.
        checkPendingReinclude(nowMs)?.let { return it }

        // Dead-end escalation is also purely about streak duration, not connect state.
        checkDeadEndEscalation(nowMs)?.let { return it }

        if (!isConnected) {
            // Nothing else to observe while disconnected.
            return RecoveryAction.None
        }

        if (rssi >= poorThresholdRssi) {
            // Recovered — end the streak. The re-include timer (if one is still pending) is
            // untouched: it always runs to completion regardless of what the connection does.
            endStreak()
            return RecoveryAction.None
        }

        if (streakStartedAtMs == null) {
            // First drop below threshold — start a new streak and exclude immediately.
            streakStartedAtMs = nowMs
            return excludeAndScheduleReinclude(currentSsid, nowMs)
        }

        if (rssiAtLastReconnect == null) {
            // First evaluation since that exclusion while still below threshold — the signal we
            // actually reconnected at (same network or a different one). Further exclusions are
            // measured against this baseline.
            rssiAtLastReconnect = rssi
            return RecoveryAction.None
        }

        if (rssi <= absoluteFloorRssi) {
            // Rock bottom — no point excluding again and again. The dead-end escalation above is
            // still running off streakStartedAtMs regardless, so this doesn't block that.
            return RecoveryAction.None
        }

        val baseline = rssiAtLastReconnect!!
        val degradedThreshold = baseline - (abs(baseline) * degradationFraction).toInt()

        return when {
            rssi <= degradedThreshold -> {
                val since = degradedSinceMs
                when {
                    since == null -> {
                        degradedSinceMs = nowMs
                        RecoveryAction.None
                    }
                    nowMs - since >= degradationSettleWindowMs -> {
                        // Degradation held for the full settle window — worth excluding again.
                        rssiAtLastReconnect = null
                        degradedSinceMs = null
                        excludeAndScheduleReinclude(currentSsid, nowMs)
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

    private fun excludeAndScheduleReinclude(ssid: String?, nowMs: Long): RecoveryAction {
        // Any earlier pending re-inclusion is superseded: resetAllSuggestionsTogether always
        // re-adds everything except the new exclusion, so a previously-excluded (different) ssid
        // is already back by the time this runs — nothing further needed for it.
        excludedSsid = ssid
        excludedAtMs = if (ssid != null) nowMs else null // nothing actually excluded when ssid is unknown
        return RecoveryAction.ResetExcluding(ssid)
    }

    private fun checkPendingReinclude(nowMs: Long): RecoveryAction? {
        val since = excludedAtMs ?: return null
        if (nowMs - since < excludedNetworkReincludeDelayMs) return null
        excludedSsid = null
        excludedAtMs = null
        return RecoveryAction.RestoreAll
    }

    private fun checkDeadEndEscalation(nowMs: Long): RecoveryAction? {
        if (hasNotifiedDeadEnd) return null
        val streakStart = streakStartedAtMs ?: return null
        val elapsed = nowMs - streakStart
        if (!hasLastDitchFired) {
            if (elapsed < deadEndPhaseWaitMs) return null
            hasLastDitchFired = true
            // A full churn with nothing excluded — any separately-pending re-inclusion is now
            // redundant, since everything is already back.
            excludedSsid = null
            excludedAtMs = null
            return RecoveryAction.LastDitchReset
        }
        if (elapsed < deadEndPhaseWaitMs * 2) return null
        hasNotifiedDeadEnd = true
        return RecoveryAction.NotifyDeadEnd
    }

    private fun endStreak() {
        streakStartedAtMs = null
        rssiAtLastReconnect = null
        degradedSinceMs = null
        hasLastDitchFired = false
        hasNotifiedDeadEnd = false
    }
}
