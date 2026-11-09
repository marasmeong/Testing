package com.indo

import android.app.AlertDialog
import android.content.Context
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Idlix())

        openSettings = { ctx ->
            val siteUrl = "https://z2.idlixku.com"
            val cookies = CookieManager.getInstance()

            AlertDialog.Builder(ctx)
                .setTitle("Pengaturan Idlix")
                .setMessage(
                    "Status cookie: " +
                    if (cookies.getCookie(siteUrl).isNullOrBlank())
                        "Belum tersedia"
                    else
                        "Tersedia"
                )
                .setPositiveButton("Login Idlix") { _, _ ->
                    val webView = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        webViewClient = WebViewClient()
                    }

                    cookies.setAcceptCookie(true)
                    cookies.setAcceptThirdPartyCookies(webView, true)

                    val dialog = AlertDialog.Builder(ctx)
                        .setTitle("Login Idlix")
                        .setView(webView)
                        .setNeutralButton("Kembali", null)
                        .setNegativeButton("Tutup", null)
                        .create()

                    dialog.setOnDismissListener {
                        cookies.flush()
                        webView.destroy()
                    }

                    dialog.show()

                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        ?.setOnClickListener {
                            if (webView.canGoBack()) {
                                webView.goBack()
                            } else {
                                webView.loadUrl(siteUrl)
                            }
                        }

                    dialog.window?.clearFlags(
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    )

                    dialog.window?.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    )

                    webView.requestFocus()
                    webView.loadUrl(siteUrl)
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }
}
