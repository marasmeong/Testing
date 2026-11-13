package com.indo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.text.InputType
import android.widget.EditText
@CloudstreamPlugin
class MeongCloudPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(MeongCloud(context.applicationContext))

        openSettings = { ctx ->
            val subPrefs = ctx.getSharedPreferences(
                "cloudbhadoo_subsource", Context.MODE_PRIVATE
            )
            val tmdbPrefs = ctx.getSharedPreferences(
                "cloudbhadoo_tmdb", Context.MODE_PRIVATE
            )

            val layout = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }

            val subInput = EditText(ctx).apply {
                hint = "Subsource API Key"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setText(subPrefs.getString("api_key", ""))
            }

            val tmdbInput = EditText(ctx).apply {
                hint = "TMDB Read Access Token"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setText(tmdbPrefs.getString("read_token", ""))
            }

            layout.addView(subInput)
            layout.addView(tmdbInput)

            AlertDialog.Builder(ctx)
                .setTitle("Pengaturan CloudBHADOO")
                .setView(layout)
                .setPositiveButton("Simpan") { _, _ ->
                    subPrefs.edit()
                        .putString("api_key", subInput.text.toString().trim())
                        .apply()
                    tmdbPrefs.edit()
                        .putString("read_token", tmdbInput.text.toString().trim())
                        .apply()
                }
                .setNegativeButton("Batal", null)
                .show()
                .apply {
                    getButton(AlertDialog.BUTTON_POSITIVE).apply {
                            setTypeface(typeface, Typeface.BOLD)
                        }
                        .setTextColor(Color.WHITE)
                    getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                            setTypeface(typeface, Typeface.BOLD)
                        }
                        .setTextColor(Color.WHITE)
                    findViewById<android.widget.TextView>(
                        ctx.resources.getIdentifier(
                            "alertTitle", "id", "android"
                        )
                    )?.setTextColor(Color.WHITE)
                }
        }
    }
}
