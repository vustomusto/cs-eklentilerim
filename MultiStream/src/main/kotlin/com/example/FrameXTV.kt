package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class FrameXTV : TmdbProvider() {
    override var name = "FrameXTV"
    override var mainUrl = "https://framextv.cc"
    override var lang = "en" // İçerikler çoklu dil ve altyazı desteklidir
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val useMetaLoadResponse = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // FrameXTV API dokümantasyonundaki aktif sunucu listesi
    private val servers = listOf(
        "barbarian" to "Barbarian I (4K/Multi-Sub)",
        "archer" to "Archer II (4K/Multi-Audio)",
        "goblin" to "Goblin III",
        "pekka" to "P.E.K.K.A IV",
        "wizard" to "Wizard V",
        "valkyrie" to "Valkyrie VI",
        "golem" to "Golem VII",
        "witch" to "Witch VIII"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mappedData = parseJson<TmdbLink>(data)
        val tmdbId = mappedData.tmdbID ?: return false

        // Film mi yoksa dizi bölümü mü olduğunu kontrol edip ilgili Embed URL'sini oluştur
        val baseEmbedUrl = if (mappedData.season == null || mappedData.episode == null) {
            "$mainUrl/embed/$tmdbId"
        } else {
            "$mainUrl/embed/$tmdbId/${mappedData.season}/${mappedData.episode}"
        }

        // İlk 4 ana sunucu için paralel olarak m3u8 akışlarını yakala
        servers.take(4).amap { (serverKey, serverName) ->
            val targetEmbedUrl = "$baseEmbedUrl?autoplay=1&server=$serverKey&origin=internal"

            try {
                // WebViewResolver, IFrame içindeki JS motoru çalıştığında oluşan .m3u8 ve .vtt trafiğini yakalar
                val response = app.get(
                    targetEmbedUrl,
                    referer = "$mainUrl/",
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""\.(m3u8|mp4)(\?|$)"""),
                        additionalUrls = listOf(Regex("""\.(vtt|srt)(\?|$)""")),
                        useOkhttp = false,
                        timeout = 15_000L
                    )
                )

                val streamUrl = response.url
                if (streamUrl.contains(".m3u8") || streamUrl.contains(".mp4")) {
                    M3u8Helper.generateM3u8(
                        source = "$name - $serverName",
                        streamUrl = streamUrl,
                        referer = "$mainUrl/",
                        headers = mapOf("Origin" to mainUrl)
                    ).forEach(callback)
                }
            } catch (e: Exception) {
                // Sunucu yanıt vermezse diğer sunucuya geç
            }
        }

        return true
    }
}