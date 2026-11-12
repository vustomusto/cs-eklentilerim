package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class FrameXTV : TmdbProvider() {
    override var name = "FrameXTV"
    override var mainUrl = "https://framextv.cc"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    // false olduğunda TmdbProvider doğrudan kendi TmdbLink JSON verisini üretir
    override val useMetaLoadResponse = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Gelen veriden tmdbId, imdbId, season ve episode bilgilerini esnek şekilde ayıkla
        var tmdbId: Int? = null
        var imdbId: String? = null
        var season: Int? = null
        var episode: Int? = null

        try {
            if (data.trim().startsWith("{")) {
                val json = JSONObject(data)
                tmdbId = json.optInt("tmdbID", 0).takeIf { it != 0 }
                    ?: json.optInt("tmdbId", 0).takeIf { it != 0 }
                    ?: json.optInt("id", 0).takeIf { it != 0 }

                imdbId = json.optString("imdbID").takeIf { it.startsWith("tt") }
                    ?: json.optString("imdbId").takeIf { it.startsWith("tt") }

                season = json.optInt("season", -1).takeIf { it > 0 }
                episode = json.optInt("episode", -1).takeIf { it > 0 }
            }
        } catch (e: Exception) {
            // JSON değilse URL içinden yakalamayı dene
        }

        // Eğer yalnızca IMDb ID (tt...) varsa TMDB API üzerinden tmdbId'yi bul
        if (tmdbId == null && !imdbId.isNullOrEmpty()) {
            try {
                val findUrl = "https://api.themoviedb.org/3/find/$imdbId?api_key=b030404650f279792a8d3287232358e3&external_source=imdb_id"
                val findJson = JSONObject(app.get(findUrl).text)
                val movieArr = findJson.optJSONArray("movie_results")
                val tvArr = findJson.optJSONArray("tv_results")
                if (movieArr != null && movieArr.length() > 0) {
                    tmdbId = movieArr.getJSONObject(0).optInt("id")
                } else if (tvArr != null && tvArr.length() > 0) {
                    tmdbId = tvArr.getJSONObject(0).optInt("id")
                }
            } catch (e: Exception) {
                // Devam et
            }
        }

        val isTv = season != null && episode != null

        // 2. SUNUCU A: FrameXTV Goblin III (Vixsrc.to) - Doğrudan Hızlı HLS Çıkarıcı
        if (tmdbId != null) {
            val vixUrl = if (isTv) {
                "https://vixsrc.to/tv/$tmdbId/$season/$episode"
            } else {
                "https://vixsrc.to/movie/$tmdbId"
            }
            extractVixsrc(vixUrl, callback)
        }

        // 3. SUNUCU B: FrameXTV WebView (Barbarian / Videasy & Archer / Hexa)
        if (tmdbId != null) {
            val webViewTargets = listOf(
                Triple(
                    "FrameXTV - Barbarian (Videasy)",
                    if (isTv) "https://player.videasy.net/tv/$tmdbId/$season/$episode" else "https://player.videasy.net/movie/$tmdbId",
                    "https://player.videasy.net"
                ),
                Triple(
                    "FrameXTV - Ana Oynatıcı",
                    if (isTv) "$mainUrl/embed/$tmdbId/$season/$episode?autoplay=1&server=barbarian" else "$mainUrl/embed/$tmdbId?autoplay=1&server=barbarian",
                    mainUrl
                )
            )

            webViewTargets.forEach { (serverName, targetUrl, originDomain) ->
                try {
                    val response = app.get(
                        targetUrl,
                        headers = browserHeaders,
                        referer = "$originDomain/",
                        interceptor = WebViewResolver(
                            interceptUrl = Regex("""(\.m3u8|\.mp4|/playlist/|master\.txt|index.*\.m3u8)"""),
                            additionalUrls = listOf(Regex("""\.(vtt|srt)"""))
                        )
                    )

                    val streamUrl = response.url
                    if (streamUrl != targetUrl && !streamUrl.endsWith("/embed/$tmdbId")) {
                        emitStreamLink(serverName, streamUrl, "$originDomain/", originDomain, callback)
                    }
                } catch (e: Exception) {
                    // Diğer sunucuya geç
                }
            }
        }

        // 4. SUNUCU C: CloudStream Yerleşik Yedek Çözücüler (VidSrc, AutoEmbed, MultiEmbed)
        val embedUrls = mutableListOf<String>()
        if (tmdbId != null) {
            if (isTv) {
                embedUrls.add("https://vidsrc.xyz/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
                embedUrls.add("https://vidsrc.to/embed/tv/$tmdbId/$season/$episode")
                embedUrls.add("https://autoembed.cc/embed/player.php?id=$tmdbId&s=$season&e=$episode")
                embedUrls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode")
            } else {
                embedUrls.add("https://vidsrc.xyz/embed/movie?tmdb=$tmdbId")
                embedUrls.add("https://vidsrc.to/embed/movie/$tmdbId")
                embedUrls.add("https://autoembed.cc/embed/player.php?id=$tmdbId")
                embedUrls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1")
            }
        } else if (!imdbId.isNullOrEmpty()) {
            if (isTv) {
                embedUrls.add("https://vidsrc.xyz/embed/tv?imdb=$imdbId&season=$season&episode=$episode")
            } else {
                embedUrls.add("https://vidsrc.xyz/embed/movie?imdb=$imdbId")
            }
        }

        embedUrls.forEach { embedUrl ->
            try {
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            } catch (e: Exception) {
                // Yedek extractor hata verirse atla
            }
        }

        return true
    }

    /**
     * FrameXTV'nin Goblin III sunucusundan (vixsrc.to) WebView beklemeden
     * doğrudan token ve expires parametrelerini okuyup 1080p m3u8 üretir.
     */
    private suspend fun extractVixsrc(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(url, headers = browserHeaders, referer = "https://vixsrc.to/").text

            val playlistUrl = Regex("""url\s*:\s*['"](https?://[^'"]+/playlist/[^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)
            val token = Regex("""['"]token['"]\s*:\s*['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)
            val expires = Regex("""['"]expires['"]\s*:\s*['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)

            if (playlistUrl != null && token != null && expires != null) {
                val sep = if (playlistUrl.contains("?")) "&" else "?"
                val finalM3u8 = "${playlistUrl}${sep}token=$token&expires=$expires&h=1"
                emitStreamLink(
                    "FrameXTV - Goblin III (1080p)",
                    finalM3u8,
                    "https://vixsrc.to/",
                    "https://vixsrc.to",
                    callback
                )
            }
        } catch (e: Exception) {
            // Vixsrc yanıt vermezse diğer sunucular devreye girer
        }
    }

    private suspend fun emitStreamLink(
        serverName: String,
        streamUrl: String,
        refererUrl: String,
        originUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val generated = try {
            M3u8Helper.generateM3u8(
                source = serverName,
                streamUrl = streamUrl,
                referer = refererUrl,
                headers = mapOf("Origin" to originUrl)
            )
        } catch (e: Exception) {
            emptyList()
        }

        if (generated.isNotEmpty()) {
            generated.forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = serverName,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = refererUrl
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("Origin" to originUrl)
                }
            )
        }
    }
}
