package com.dama.tahany.screenshot

/**
 * Bounded, thread-safe set of SHA-256 values already sent or received.
 *
 * The ledger is deliberately small and in-memory-ish: it is persisted by
 * [ScreenshotLedgerStore] so a process restart does not immediately echo the
 * last screenshot back to the other device.
 */
class ScreenshotLedger(private val maxEntries: Int = 512) {
    private val entries = LinkedHashSet<String>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun contains(sha256: String): Boolean = entries.contains(normalize(sha256))

    @Synchronized
    fun tryAdd(sha256: String): Boolean {
        val normalized = normalize(sha256)
        if (!entries.add(normalized)) return false
        while (entries.size > maxEntries) {
            val oldest = entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        return true
    }

    @Synchronized
    fun remove(sha256: String): Boolean = entries.remove(normalize(sha256))

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun size(): Int = entries.size

    private fun normalize(value: String): String = value.trim().lowercase()
}

/**
 * Two separate questions, one rule: a hash may cross the link at most once.
 *
 * Incoming screenshots are claimed before they are saved. Outgoing ones are
 * claimed only when the file is not already in Tahany's own receive folder.
 * If a transfer fails, call [rollback] so a retry is possible.
 */
class ScreenshotLoopGuard(
    private val ledger: ScreenshotLedger,
    private val ownFolderName: String = "Tahany",
) {
    fun claimOutgoing(relativePath: String?, sha256: String): Boolean {
        if (relativePath?.contains(ownFolderName, ignoreCase = true) == true) return false
        return ledger.tryAdd(sha256)
    }

    fun claimIncoming(sha256: String): Boolean = ledger.tryAdd(sha256)

    fun rollback(sha256: String) {
        ledger.remove(sha256)
    }

    fun isKnown(sha256: String): Boolean = ledger.contains(sha256)
}
