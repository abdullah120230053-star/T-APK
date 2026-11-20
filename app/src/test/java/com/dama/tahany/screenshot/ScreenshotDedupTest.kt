package com.dama.tahany.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotDedupTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun oneHashCrossesTheLinkOnlyOnce() {
        val ledger = ScreenshotLedger()
        val guard = ScreenshotLoopGuard(ledger)

        assertTrue(guard.claimOutgoing("Pictures/Screenshots/shot.png", hashA))
        assertFalse(guard.claimOutgoing("Pictures/Screenshots/shot.png", hashA))
        assertFalse(guard.claimIncoming(hashA))
    }

    @Test
    fun receivedScreenshotIsNotSentBack() {
        val phone = ScreenshotLoopGuard(ScreenshotLedger())
        val tablet = ScreenshotLoopGuard(ScreenshotLedger())

        // Phone creates a screenshot and sends it.
        assertTrue(phone.claimOutgoing("Pictures/Screenshots/shot-1.png", hashA))
        // Tablet receives and saves it.
        assertTrue(tablet.claimIncoming(hashA))
        // The tablet's watcher must not send it back, even if it appears in
        // Pictures/Screenshots.
        assertFalse(tablet.claimOutgoing("Pictures/Screenshots/shot-1.png", hashA))
        // And the phone must not accept an echo.
        assertFalse(phone.claimIncoming(hashA))
    }

    @Test
    fun ownReceiveFolderIsNeverAnOutgoingSource() {
        val guard = ScreenshotLoopGuard(ScreenshotLedger())
        assertFalse(guard.claimOutgoing("Pictures/Tahany/shot-1.png", hashA))
    }

    @Test
    fun differentImagesAreBothAccepted() {
        val ledger = ScreenshotLedger()
        assertTrue(ledger.tryAdd(hashA))
        assertTrue(ledger.tryAdd(hashB))
        assertEquals(2, ledger.size())
    }

    @Test
    fun rollbackAllowsRetryAfterFailedTransfer() {
        val guard = ScreenshotLoopGuard(ScreenshotLedger())
        assertTrue(guard.claimOutgoing("Pictures/Screenshots/a.png", hashA))
        guard.rollback(hashA)
        assertTrue(guard.claimOutgoing("Pictures/Screenshots/a.png", hashA))
    }

    @Test
    fun ledgerEvictsOldestEntriesAtTheLimit() {
        val ledger = ScreenshotLedger(maxEntries = 2)
        val first = "1".repeat(64)
        val second = "2".repeat(64)
        val third = "3".repeat(64)

        assertTrue(ledger.tryAdd(first))
        assertTrue(ledger.tryAdd(second))
        assertTrue(ledger.tryAdd(third))

        assertEquals(2, ledger.size())
        assertFalse(ledger.contains(first))
        assertTrue(ledger.contains(second))
        assertTrue(ledger.contains(third))
    }
}
