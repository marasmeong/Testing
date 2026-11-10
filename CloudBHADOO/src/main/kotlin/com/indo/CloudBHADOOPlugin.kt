package com.indo

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CloudBHADOOPlugin : Plugin() {
    override fun load(context: Context) {
        registerVideoClickAction(SubsourceSearchAction())

        registerMainAPI(CloudBHADOO(context.applicationContext))

        openSettings = { ctx ->
            val prefs = ctx.getSharedPreferences(
                "cloudbhadoo_subsource",
                Context.MODE_PRIVATE
            )

            val input = EditText(ctx).apply {
                hint = "API Key Subsource"
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setText(prefs.getString("api_key", ""))
                setPadding(40, 30, 40, 30)
            }

            AlertDialog.Builder(ctx)
                .setTitle("Pengaturan CloudBHADOO")
                .setMessage("Masukkan API key Subsource untuk subtitle Indonesia otomatis.")
                .setView(input)
                .setPositiveButton("Simpan") { _, _ ->
                    prefs.edit()
                        .putString("api_key", input.text.toString().trim())
                        .apply()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }
}
