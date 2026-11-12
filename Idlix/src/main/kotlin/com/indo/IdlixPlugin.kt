package com.indo

import android.content.Context
import kotlinx.coroutines.launch
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdlixPlugin : Plugin() {
    override fun load(context: Context) {
        val provider = Idlix()
        registerMainAPI(provider)

        openSettings = { _ ->
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Main
            ).launch {
                provider.openIdlixLoginWebView()
            }
        }
    }
}
