package com.co2.wifiautoswitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the Location-off fallback's decision logic: exclude-on-drop, the purely time-based
 * re-inclusion of whatever was excluded (the only thing that can ever work, since an excluded
 * network's RSSI can never be observed again — see WeakSignalRecoveryPolicy's class doc), the
 * degradation settle-window debounce, the absolute floor, and the two-phase dead-end escalation.
 *
 * Default policy under test: poorThresholdRssi=-75, absoluteFloorRssi=-100,
 * degradationFraction=0.25, degradationSettleWindowMs=10_000,
 * excludedNetworkReincludeDelayMs=15_000, deadEndPhaseWaitMs=30_000.
 */
class WeakSignalRecoveryPolicyTest {

    private val ssid = "BedroomWifi"

    // --- Baseline behavior -------------------------------------------------------------------

    @Test
    fun `strong signal does nothing`() {
        val policy = WeakSignalRecoveryPolicy()
        val action = policy.evaluate(isConnected = true, rssi = -60, currentSsid = ssid, nowMs = 0)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `first drop below threshold excludes the current ssid`() {
        val policy = WeakSignalRecoveryPolicy()
        val action = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)
        assertEquals(RecoveryAction.ResetExcluding(ssid), action)
    }

    @Test
    fun `second evaluation still below threshold does not exclude again, just records baseline`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // excludes

        val action = policy.evaluate(isConnected = true, rssi = -78, currentSsid = ssid, nowMs = 1_000)

        assertEquals(RecoveryAction.None, action)
        assertEquals(-78, policy.snapshot().rssiAtLastReconnect)
    }

    // --- The core fix: re-inclusion is purely time-based --------------------------------------

    @Test
    fun `re-inclusion does not fire before the delay elapses`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // excludes at 0

        val tooSoon = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 14_999)
        assertEquals(RecoveryAction.None, tooSoon)
    }

    @Test
    fun `re-inclusion fires on schedule while still connected`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)

        val action = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 15_000)
        assertEquals(RecoveryAction.RestoreAll, action)
        assertNull(policy.snapshot().excludedSsid)
    }

    @Test
    fun `re-inclusion fires on schedule even while fully disconnected`() {
        // This is the scenario that broke the old RSSI-based restore trigger: once a network is
        // excluded, we're not connected to it and can't scan for it, so there is no signal that
        // could ever tell us "it's fine now" — re-inclusion must not depend on one.
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // excludes at 0

        // Connection drops entirely shortly after (walked out of range of everything) and stays
        // disconnected right through where the re-include deadline lands.
        val tooSoon = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 14_999)
        assertEquals(RecoveryAction.None, tooSoon)

        val action = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 15_000)
        assertEquals(RecoveryAction.RestoreAll, action)
    }

    @Test
    fun `re-inclusion does not schedule when the ssid is unreadable`() {
        // excludingSsid=null on resetAllSuggestionsTogether excludes nothing at the WifiManager
        // level (nothing matches a null ssid), so there's nothing to schedule a re-include for.
        val policy = WeakSignalRecoveryPolicy()
        val action = policy.evaluate(isConnected = true, rssi = -80, currentSsid = null, nowMs = 0)
        assertEquals(RecoveryAction.ResetExcluding(null), action)
        assertNull(policy.snapshot().excludedAtMs)

        val later = policy.evaluate(isConnected = true, rssi = -80, currentSsid = null, nowMs = 20_000)
        assertEquals(RecoveryAction.None, later)
    }

    @Test
    fun `staying at the same weak signal past re-inclusion does not exclude again`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // excludes
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 1_000) // baseline = -80

        val reincluded = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 15_000)
        assertEquals(RecoveryAction.RestoreAll, reincluded)

        // Same signal as before, unchanged — no new exclusion just because re-inclusion happened.
        val after = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 16_000)
        assertEquals(RecoveryAction.None, after)
    }

    @Test
    fun `a fresh exclusion after recovery schedules its own re-inclusion`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // excludes, reincludes at 15_000
        policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 1_000) // recovers

        val droppedAgain = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 2_000)
        assertEquals(RecoveryAction.ResetExcluding(ssid), droppedAgain)
        assertEquals(2_000L, policy.snapshot().excludedAtMs)

        // The new exclusion's own 15s timer governs now, not the original one.
        val tooSoon = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 16_999)
        assertEquals(RecoveryAction.None, tooSoon)

        val action = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 17_000)
        assertEquals(RecoveryAction.RestoreAll, action)
    }

    @Test
    fun `recovering ends the streak but does not cancel a pending re-inclusion`() {
        // The re-inclusion timer always runs to completion regardless of what the connection
        // does in the meantime — recovering doesn't need to cancel it, since re-suggesting an
        // already-fine network is a harmless no-op.
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // excludes at 0

        val recovered = policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 1_000)
        assertEquals(RecoveryAction.None, recovered)

        val action = policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 15_000)
        assertEquals(RecoveryAction.RestoreAll, action)
    }

    // --- Degradation settle window ------------------------------------------------------------

    @Test
    fun `degrading past baseline does not exclude immediately, starts settle window`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76, degradedThreshold = -95

        val action = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 2_000)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `degradation held short of the settle window does not exclude`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76
        policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 2_000) // degraded, timer starts at 2_000

        val action = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 10_999)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `degradation held for the full settle window excludes again with a fresh baseline`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76
        policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 2_000) // degraded, timer starts

        val action = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 12_000)
        assertEquals(RecoveryAction.ResetExcluding(ssid), action)
        assertEquals(null, policy.snapshot().rssiAtLastReconnect)
        assertEquals(12_000L, policy.snapshot().excludedAtMs) // its own fresh re-include timer
    }

    @Test
    fun `recovering back within band before the settle window cancels the pending exclusion`() {
        // Kept entirely under the original exclusion's own 15s re-include deadline so that
        // unrelated timer doesn't interfere with what's being tested here.
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76
        policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 2_000) // degraded, timer starts at 2_000

        val recovered = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 3_000)
        assertEquals(RecoveryAction.None, recovered)
        assertEquals(null, policy.snapshot().degradedSinceMs)

        val redegraded = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 4_000) // fresh timer starts at 4_000
        assertEquals(RecoveryAction.None, redegraded)

        // Old timer's original deadline (12_000) — if it hadn't been cancelled, this would have
        // incorrectly fired already.
        val stillTooSoon = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 13_999)
        assertEquals(RecoveryAction.None, stillTooSoon)

        val firesAfterFreshWindow = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 14_000)
        assertEquals(RecoveryAction.ResetExcluding(ssid), firesAfterFreshWindow)
    }

    // --- Absolute floor: stops further exclusions, but the escalation clock keeps running -----

    @Test
    fun `hitting the absolute floor does not exclude, and does not repeat`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76

        repeat(5) { i ->
            val action = policy.evaluate(
                isConnected = true,
                rssi = -100 - i,
                currentSsid = ssid,
                nowMs = 2_000L + i * 1_000L
            )
            assertEquals("iteration $i", RecoveryAction.None, action)
        }
    }

    @Test
    fun `dropping straight to the floor from the reconnect baseline still does not exclude`() {
        // No intermediate "degraded but not at floor" reading — the floor check must be
        // evaluated independently, not require passing through the degradedThreshold branch.
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 1_000) // baseline = -80

        val action = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000)
        assertEquals(RecoveryAction.None, action)
    }

    // --- Delayed dead-end escalation: driven by streak duration, not connect/floor state -------

    @Test
    fun `escalation does not fire before the first wait elapses`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // streak starts at 0

        val tooSoon = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 14_999) // before its own re-include too
        assertEquals(RecoveryAction.None, tooSoon)
    }

    @Test
    fun `last-ditch resets after the first wait, then notifies after the second, while stuck at the floor`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0) // streak starts at 0
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76
        policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000) // at floor, no exclusion

        // Consume the original exclusion's re-include first (its own concern, unrelated to escalation).
        val reincluded = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 15_000)
        assertEquals(RecoveryAction.RestoreAll, reincluded)

        val lastDitch = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 30_000)
        assertEquals(RecoveryAction.LastDitchReset, lastDitch)
        assertNull("last-ditch clears any pending re-include, having just restored everything itself",
            policy.snapshot().excludedAtMs)

        val tooSoon = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 59_999)
        assertEquals(RecoveryAction.None, tooSoon)

        val notified = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 60_000)
        assertEquals(RecoveryAction.NotifyDeadEnd, notified)

        val again = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 90_000)
        assertEquals(RecoveryAction.None, again)
    }

    @Test
    fun `escalation still runs while fully disconnected`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // streak starts at 0

        // Drops entirely shortly after, and stays disconnected right through both escalation
        // deadlines (and the unrelated re-include deadline in between).
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 1_000)
        val reincluded = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 15_000)
        assertEquals(RecoveryAction.RestoreAll, reincluded)

        val lastDitch = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 30_000)
        assertEquals(RecoveryAction.LastDitchReset, lastDitch)

        val notified = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 60_000)
        assertEquals(RecoveryAction.NotifyDeadEnd, notified)
    }

    @Test
    fun `recovering before either escalation phase cancels the whole escalation`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // streak starts at 0

        policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 5_000) // recovers, ends streak

        // The original exclusion's own (unrelated) re-include timer is still separately pending —
        // consume it first so it doesn't collide with the escalation check below.
        val reincluded = policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 15_000)
        assertEquals(RecoveryAction.RestoreAll, reincluded)

        // Past where both escalation deadlines would have landed — nothing fires, since recovery
        // already ended the streak they were measured from.
        val action = policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 70_000)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `recovering between last-ditch and notify also cancels the notify`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // streak starts at 0
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 1_000) // baseline = -80
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 15_000) // re-include fires
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 30_000) // last-ditch fires

        policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 35_000) // recovers

        val action = policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 90_000)
        assertEquals(RecoveryAction.None, action)
    }

    // --- Walking back into range of an excluded network (the scenario that motivated this) ----

    @Test
    fun `walking back into range of the excluded network before it disconnects still works`() {
        // Bedroom-WiFi excluded while still weakly connected to it; the connection never fully
        // drops (recovers on its own, e.g. walking back toward it) before the re-include timer
        // would have fired anyway — either path restores it correctly.
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // excludes Bedroom-WiFi

        val action = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 15_000)
        assertEquals(RecoveryAction.RestoreAll, action)
    }

    @Test
    fun `walking back after fully disconnecting from the excluded network also works`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // excludes Bedroom-WiFi
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 3_000) // link actually fails

        val action = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 15_000)
        assertEquals(RecoveryAction.RestoreAll, action)
    }
}
