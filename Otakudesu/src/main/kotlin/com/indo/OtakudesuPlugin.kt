package com.indo

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OtakudesuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Otakudesu(context.applicationContext))
        registerExtractorAPI(KrakenFiles())
        registerExtractorAPI(Filedon())

        openSettings = { ctx ->
            val prefs = ctx.getSharedPreferences(
                "otakudesu_tmdb", Context.MODE_PRIVATE
            )

            val input = EditText(ctx).apply {
                hint = "TMDB Read Access Token"
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setText(prefs.getString("read_token", ""))
                setPadding(32, 24, 32, 24)
            }

            AlertDialog.Builder(ctx)
                .setTitle("Pengaturan Otakudesu")
                .setMessage("Masukkan TMDB Read Access Token")
                .setView(input)
                .setPositiveButton("Simpan") { _, _ ->
                    prefs.edit()
                        .putString("read_token", input.text.toString().trim())
                        .apply()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }
}
