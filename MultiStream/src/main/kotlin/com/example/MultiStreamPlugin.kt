package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MultiStreamPlugin : Plugin() {
    override fun load(context: Context) {
        // Eklenti yüklendiğinde her iki kaynağı da CloudStream'e kaydeder
        registerMainAPI(Dizipal())
        registerMainAPI(FrameXTV())
    }
}