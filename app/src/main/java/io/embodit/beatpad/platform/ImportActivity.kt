package io.embodit.beatpad.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle

/**
 * T13「从文件导入…」: hosts the system document picker (needsCheck: com.android.documentsui PickActivity answers
 * ACTION_OPEN_DOCUMENT audio on the Swan B3110 and the emulator, `cmd package query-activities` 2026-11-12), copies
 * nothing itself — Graph decodes the chosen file into the app's private folder — and finishes. No picker → the
 * fallback folder Android/data/io.embodit.beatpad/files/Music.
 */
class ImportActivity : Activity() {
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = intent.getIntExtra("pad", 0)
        if (savedInstanceState != null) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/mpeg", "audio/ogg", "audio/mp4", "audio/aac", "audio/*"))
        try {
            AppLog.i("IMPORT picker open p${pad + 1}")
            @Suppress("DEPRECATION") startActivityForResult(pick, REQ)
        } catch (e: ActivityNotFoundException) {
            AppLog.w("IMPORT no picker: $e")
            Graph.from(this).importFromDropDir(pad)
            finish()
        }
    }

    @Deprecated("platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        AppLog.i("IMPORT picker result=$resultCode uri=${uri != null}")
        if (resultCode == RESULT_OK && uri != null) Graph.from(this).importUri(pad, uri)
        finish()
    }

    private companion object { const val REQ = 7 }
}
