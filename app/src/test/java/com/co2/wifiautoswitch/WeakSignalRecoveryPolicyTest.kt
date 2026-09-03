package com.co2.wifiautoswitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Location-off fallback's decision logic: the plain edge-triggered reset, Scenario 1
 * (out of range of every saved network), and Scenario 2 (connected, but only to a weak network),
 * including the degradation settle-window debounce, the absolute-floor restore, and the two-phase
 * dead-end escalation (silent restore -> last-ditch reset -> notify).
 *
 * Default policy under test: poorThresholdRssi=-75, absoluteFloorRssi=-100,
 * degradationFraction=0.25, degradationSettleWindowMs=10_000, deadEndPhaseWaitMs=30_000.
 */
class WeakSignalRecoveryPolicyTest {

    private val ssid = "HomeWifi"

    // --- Baseline behavior: above threshold, and the plain edge-trigger --------------------

    @Test
    fun `strong signal does nothing`() {
        val policy = WeakSignalRecoveryPolicy()
        val action = policy.evaluate(isConnected = true, rssi = -60, currentSsid = ssid, nowMs = 0)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `first drop below threshold resets once, excluding the current ssid`() {
        val policy = WeakSignalRecoveryPolicy()
        val action = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)
        assertEquals(RecoveryAction.ResetExcluding(ssid), action)
    }

    @Test
    fun `second evaluation still below threshold does not reset again, just records baseline`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // first reset

        val action = policy.evaluate(isConnected = true, rssi = -78, currentSsid = ssid, nowMs = 1_000)

        assertEquals(RecoveryAction.None, action)
        assertEquals(-78, policy.snapshot().rssiAtLastReconnect)
    }

    @Test
    fun `staying at the same weak signal never resets again`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // reset
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 1_000) // baseline = -80

        // Same signal, repeatedly, for a long time — no further action.
        repeat(20) { i ->
            val action = policy.evaluate(
                isConnected = true,
                rssi = -80,
                currentSsid = ssid,
                nowMs = 2_000L + i * 5_000L
            )
            assertEquals("iteration $i", RecoveryAction.None, action)
        }
    }

    @Test
    fun `recovering above threshold restores the excluded network and re-arms a fresh trigger`() {
        // If the connection recovers without ever fully disconnecting, Scenario 1's disconnect-
        // triggered restore never gets a chance to run — this is the only other place the
        // excluded network can get restored, so it must not be skipped.
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // reset, excludes ssid

        val recovered = policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 1_000)
        assertEquals(RecoveryAction.RestoreAll, recovered)

        val droppedAgain = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 2_000)
        assertEquals(RecoveryAction.ResetExcluding(ssid), droppedAgain)
    }

    // --- Scenario 1: out of range of every saved network ------------------------------------

    @Test
    fun `disconnecting without ever having reset does nothing`() {
        val policy = WeakSignalRecoveryPolicy()
        val action = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 0)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `disconnecting right after a reset restores everything and pauses`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // reset

        val action = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 1_000)

        assertEquals(RecoveryAction.RestoreAll, action)
        assertTrue(policy.snapshot().isPausedAwaitingAnyConnection)
    }

    @Test
    fun `staying disconnected does not repeat the restore, only within the first wait`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 1_000) // restores, pauses

        // Within the first 30s wait (default), every subsequent evaluation is a no-op — in
        // particular the restore never repeats.
        repeat(5) { i ->
            val action = policy.evaluate(
                isConnected = false,
                rssi = 0,
                currentSsid = null,
                nowMs = 2_000L + i * 5_000L // up to 22_000
            )
            assertEquals("iteration $i", RecoveryAction.None, action)
        }
    }

    @Test
    fun `reconnecting after the scenario 1 pause resumes fresh monitoring`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 1_000)

        // Reconnected, but to something still strong — should just resume quietly.
        val strongReconnect = policy.evaluate(isConnected = true, rssi = -55, currentSsid = "OtherWifi", nowMs = 2_000)
        assertEquals(RecoveryAction.None, strongReconnect)
        assertTrue(!policy.snapshot().isPausedAwaitingAnyConnection)
        assertTrue(!policy.snapshot().resetFiredForCurrentPoorStreak)
    }

    @Test
    fun `reconnecting after the scenario 1 pause still weak triggers a fresh reset`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 1_000)

        // Reconnected, but still weak — a brand new streak, so it resets again from scratch.
        val weakReconnect = policy.evaluate(isConnected = true, rssi = -82, currentSsid = "OtherWifi", nowMs = 2_000)
        assertEquals(RecoveryAction.ResetExcluding("OtherWifi"), weakReconnect)
    }

    // --- Scenario 2: connected, but only to a weak network -----------------------------------

    @Test
    fun `degrading past baseline does not reset immediately, starts settle window`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // reset
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 1_000) // baseline = -80
        // degradedThreshold = -80 - (80 * 0.25) = -100... use a shallower baseline so the floor
        // doesn't interfere: baseline -76 -> degradedThreshold = -76 - 19 = -95.

        val policy2 = WeakSignalRecoveryPolicy()
        policy2.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy2.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76

        val firstDegraded = policy2.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 2_000)
        assertEquals(RecoveryAction.None, firstDegraded)
    }

    @Test
    fun `degradation held short of the settle window does not reset`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76
        policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 2_000) // degraded, timer starts at 2_000

        // Still degraded, but only 9s have passed (< 10s settle window).
        val action = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 10_999)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `degradation held for the full settle window resets again with a fresh baseline`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76
        policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 2_000) // degraded, timer starts

        val action = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 12_000)
        assertEquals(RecoveryAction.ResetExcluding(ssid), action)
        // Baseline cleared so the next evaluation re-captures a fresh one.
        assertEquals(null, policy.snapshot().rssiAtLastReconnect)
    }

    @Test
    fun `recovering back within band before the settle window cancels the pending reset`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76
        policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 2_000) // degraded, timer starts at 2_000

        // Recovers back within the degradation band before the settle window elapses.
        val recovered = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 3_000)
        assertEquals(RecoveryAction.None, recovered)
        assertEquals(null, policy.snapshot().degradedSinceMs)

        // Degrades again well past the old timer's original deadline (12_000) — if the timer
        // hadn't been cancelled, this would have incorrectly fired already. A fresh 10s window
        // is required from this new degradation instead.
        val redegraded = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 11_500)
        assertEquals(RecoveryAction.None, redegraded)

        val firesAfterFreshWindow = policy.evaluate(isConnected = true, rssi = -96, currentSsid = ssid, nowMs = 21_500)
        assertEquals(RecoveryAction.ResetExcluding(ssid), firesAfterFreshWindow)
    }

    @Test
    fun `hitting the absolute floor restores all suggestions once and stops resetting`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76

        val firstFloorHit = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000)
        assertEquals(RecoveryAction.RestoreAll, firstFloorHit)

        // Staying at/below the floor, within the first wait, does not repeat the restore.
        repeat(5) { i ->
            val action = policy.evaluate(
                isConnected = true,
                rssi = -100 - i,
                currentSsid = ssid,
                nowMs = 3_000L + i * 1_000L
            )
            assertEquals("iteration $i", RecoveryAction.None, action)
        }
    }

    @Test
    fun `floor restore flag resets so a later exclusion under a fresh streak restores again`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000)
        policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000) // restores at floor

        // Recover fully, then drop again — a brand new streak.
        policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 3_000) // re-arm
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 4_000) // reset
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 5_000) // new baseline = -76

        val secondFloorHit = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 6_000)
        assertEquals(RecoveryAction.RestoreAll, secondFloorHit)
    }

    @Test
    fun `dropping straight to the floor from the reconnect baseline still restores`() {
        // No intermediate "degraded but not at floor" reading — floor check must be evaluated
        // independently of the degradedThreshold branch, not require passing through it first.
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // reset
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 1_000) // baseline = -80

        val action = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000)
        assertEquals(RecoveryAction.RestoreAll, action)
    }

    @Test
    fun `disconnecting while at the floor still falls into scenario 1`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000)
        policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000) // restores at floor

        val action = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 3_000)
        assertEquals(RecoveryAction.RestoreAll, action)
        assertTrue(policy.snapshot().isPausedAwaitingAnyConnection)
    }

    // --- Delayed dead-end escalation: silent restore -> last-ditch reset -> notify -----------

    @Test
    fun `scenario 1 does not last-ditch before the first wait elapses`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0) // reset
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 1_000) // restores, phase 1 starts at 1_000

        val tooSoon = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 30_999)
        assertEquals(RecoveryAction.None, tooSoon)
    }

    @Test
    fun `scenario 1 last-ditch resets after the first wait, then notifies after the second`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 1_000) // phase 1 starts at 1_000

        val lastDitch = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 31_000)
        assertEquals(RecoveryAction.LastDitchReset, lastDitch)

        // Second wait not up yet — no notification.
        val tooSoon = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 60_999)
        assertEquals(RecoveryAction.None, tooSoon)

        val notified = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 61_000)
        assertEquals(RecoveryAction.NotifyDeadEnd, notified)

        // Doesn't fire again on subsequent evaluations, still disconnected.
        val again = policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 90_000)
        assertEquals(RecoveryAction.None, again)
    }

    @Test
    fun `reconnecting before the first wait cancels the whole escalation`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 1_000) // phase 1 starts at 1_000

        // Reconnects (strong) well before the first wait would have elapsed.
        policy.evaluate(isConnected = true, rssi = -55, currentSsid = "OtherWifi", nowMs = 5_000)

        // Even past where the original phase's deadline would have landed, nothing fires — the
        // timer was cancelled by the reconnect, not just paused.
        val action = policy.evaluate(isConnected = true, rssi = -55, currentSsid = "OtherWifi", nowMs = 70_000)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `reconnecting between the last-ditch reset and the notify also cancels it`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 1_000) // phase 1 starts at 1_000
        policy.evaluate(isConnected = false, rssi = 0, currentSsid = null, nowMs = 31_000) // last-ditch fires, phase 2 starts

        // Reconnects (strong) during phase 2, well before its deadline.
        policy.evaluate(isConnected = true, rssi = -55, currentSsid = "OtherWifi", nowMs = 35_000)

        val action = policy.evaluate(isConnected = true, rssi = -55, currentSsid = "OtherWifi", nowMs = 90_000)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `scenario 2 floor does not last-ditch before the first wait elapses`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76
        policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000) // restores at floor, phase 1 starts at 2_000

        val tooSoon = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 31_999)
        assertEquals(RecoveryAction.None, tooSoon)
    }

    @Test
    fun `scenario 2 floor last-ditch resets after the first wait, then notifies after the second`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000)
        policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000) // phase 1 starts at 2_000

        val lastDitch = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 32_000)
        assertEquals(RecoveryAction.LastDitchReset, lastDitch)

        val tooSoon = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 61_999)
        assertEquals(RecoveryAction.None, tooSoon)

        val notified = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 62_000)
        assertEquals(RecoveryAction.NotifyDeadEnd, notified)

        val again = policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 90_000)
        assertEquals(RecoveryAction.None, again)
    }

    @Test
    fun `recovering above threshold cancels a pending floor escalation`() {
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000)
        policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000) // phase 1 starts at 2_000

        policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 5_000) // fully recovers

        val action = policy.evaluate(isConnected = true, rssi = -50, currentSsid = ssid, nowMs = 70_000)
        assertEquals(RecoveryAction.None, action)
    }

    @Test
    fun `a fresh reset after the settle window cancels a pending floor escalation`() {
        // Guards against the dead-end timer surviving into an unrelated later streak.
        val policy = WeakSignalRecoveryPolicy()
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 0)
        policy.evaluate(isConnected = true, rssi = -76, currentSsid = ssid, nowMs = 1_000) // baseline = -76
        policy.evaluate(isConnected = true, rssi = -100, currentSsid = ssid, nowMs = 2_000) // floor phase 1 starts at 2_000

        // Recovers just enough to leave the floor but is still below the poor threshold — the
        // baseline stays -76 (only a full recovery or a fresh reset changes it), so -99 is still
        // past that baseline's degraded threshold (-95) and holds for the settle window.
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 3_000)
        policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 4_000)
        policy.evaluate(isConnected = true, rssi = -99, currentSsid = ssid, nowMs = 5_000) // degraded, settle timer starts
        val reset = policy.evaluate(isConnected = true, rssi = -99, currentSsid = ssid, nowMs = 15_000)
        assertEquals(RecoveryAction.ResetExcluding(ssid), reset)

        // Original floor phase's deadline (2_000 + 30_000 = 32_000) has passed, but the reset
        // above should have cancelled it — nothing should fire here.
        val action = policy.evaluate(isConnected = true, rssi = -80, currentSsid = ssid, nowMs = 33_000)
        assertEquals(RecoveryAction.None, action)
    }

    // --- Null SSID (real SSID not readable via the per-device API) --------------------------

    @Test
    fun `null current ssid still resets, just excludes nothing`() {
        val policy = WeakSignalRecoveryPolicy()
        val action = policy.evaluate(isConnected = true, rssi = -80, currentSsid = null, nowMs = 0)
        assertEquals(RecoveryAction.ResetExcluding(null), action)
    }
}
