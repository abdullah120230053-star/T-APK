package com.dama.tahany.screenshot

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the loop-prevention ledger. The value is a newline-separated list
 * of SHA-256 hashes; it contains no filenames, paths, or image data.
 */
class ScreenshotLedgerStore(context: Context, private val ledgerKey: String = "hashes") {
    private val preferences =
        context.getSharedPreferences("tahany_screenshot_ledger", Context.MODE_PRIVATE)

    fun load(maxEntries: Int = 512): ScreenshotLedger {
        val ledger = ScreenshotLedger(maxEntries)
        preferences.getString(ledgerKey, null)
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.forEach(ledger::tryAdd)
        return ledger
    }

    fun save(ledger: ScreenshotLedger) {
        preferences.edit { putString(ledgerKey, ledger.snapshot().joinToString("\n")) }
    }
}
