package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

class FrameXTV : MainAPI() {
    override var name = "FrameXTV"
    override var mainUrl = "https://framextv.cc"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApi = "https://api.themoviedb.org/3"
    // Çalıştığı doğrulanan aktif TMDB API anahtarı
    private val apiKey = "62618fc620c9eede1546f6aa3f8b3a15"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "/trending/movie/week" to "Trend Filmler",
        "/trending/tv/week" to "Trend Diziler",
        "/movie/popular" to "Popüler Filmler",
        "/tv/popular" to "Popüler Diziler",
        "/movie/top_rated" to "En Çok Oy Alan Filmler",
        "/tv/top_rated" to "En Çok Oy Alan Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isTv = request.data.contains("/tv")
        val url = "$tmdbApi${request.data}?api_key=$apiKey&language=tr-TR&page=$page"
        val responseText = app.get(url, headers = browserHeaders).text
        var results = parseTmdbList(responseText, isTv)

        // Türkçe liste boş dönerse İngilizce yedek listeyi çek
        if (results.isEmpty()) {
            val fallbackUrl = "$tmdbApi${request.data}?api_key=$apiKey&language=en-US&page=$page"
            results = parseTmdbList(app.get(fallbackUrl, headers = browserHeaders).text, isTv)
        }

        return newHomePageResponse(request.name, results)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val urls = listOf(
            "$tmdbApi/search/multi?api_key=$apiKey&language=tr-TR&query=$encoded&page=1",
            "$tmdbApi/search/multi?api_key=$apiKey&language=en-US&query=$encoded&page=1"
        )

        for (u in urls) {
            try {
                val text = app.get(u, headers = browserHeaders).text
                parseTmdbList(text, null).forEach {
                    if (seen.add(it.url)) results.add(it)
                }
                if (results.isNotEmpty()) break
            } catch (e: Exception) {
                continue
            }
        }
        return results
    }

    private fun parseTmdbList(jsonText: String, defaultIsTv: Boolean?): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        try {
            val root = JSONObject(jsonText)
            val arr = root.optJSONArray("results") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val mediaType = item.optString("media_type")
                if (mediaType == "person") continue

                val id = item.optInt("id", 0).takeIf { it != 0 } ?: continue
                val isTv = when {
                    mediaType == "tv" -> true
                    mediaType == "movie" -> false
                    defaultIsTv != null -> defaultIsTv
                    else -> item.has("name") || item.has("first_air_date")
                }

                val title = (if (isTv) item.optString("name") else item.optString("title"))
                    .takeIf { it.isNotEmpty() && it != "null" }
                    ?: item.optString("original_title").takeIf { it.isNotEmpty() && it != "null" }
                    ?: item.optString("original_name").takeIf { it.isNotEmpty() && it != "null" }
                    ?: continue

                val posterPath = item.optString("poster_path").takeIf { it.isNotEmpty() && it != "null" }
                val posterUrl = posterPath?.let { "$imageBase$it" }
                val itemUrl = if (isTv) "$mainUrl/tv/$id" else "$mainUrl/movie/$id"

                if (isTv) {
                    list.add(newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    })
                } else {
                    list.add(newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        } catch (e: Exception) {
            // Hata durumunda boş dön
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/")
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        val typePath = if (isTv) "tv" else "movie"
        val detailUrl = "$tmdbApi/$typePath/$tmdbId?api_key=$apiKey&language=tr-TR&append_to_response=external_ids"
        val detailJson = JSONObject(app.get(detailUrl, headers = browserHeaders).text)

        val title = (if (isTv) detailJson.optString("name") else detailJson.optString("title"))
            .takeIf { it.isNotEmpty() && it != "null" }
            ?: detailJson.optString("original_name").takeIf { it.isNotEmpty() && it != "null" }
            ?: detailJson.optString("original_title").ifEmpty { "İçerik #$tmdbId" }

        val overview = detailJson.optString("overview").takeIf { it.isNotEmpty() && it != "null" }
        val posterPath = detailJson.optString("poster_path").takeIf { it.isNotEmpty() && it != "null" }
        val posterUrl = posterPath?.let { "$imageBase$it" }

        val releaseDate = if (isTv) detailJson.optString("first_air_date") else detailJson.optString("release_date")
        val year = releaseDate.take(4).toIntOrNull()

        val imdbId = detailJson.optString("imdb_id").takeIf { it.startsWith("tt") }
            ?: detailJson.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
            ?: ""

        if (!isTv) {
            val dataJson = JSONObject().apply {
                put("tmdbID", tmdbId)
                put("imdbID", imdbId)
            }.toString()

            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = posterUrl
                this.plot = overview
                this.year = year
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasonsArray = detailJson.optJSONArray("seasons")

            if (seasonsArray != null) {
                for (i in 0 until seasonsArray.length()) {
                    val sObj = seasonsArray.optJSONObject(i) ?: continue
                    val seasonNum = sObj.optInt("season_number", 0)
                    val epCount = sObj.optInt("episode_count", 0)
                    if (seasonNum <= 0 || epCount <= 0) continue

                    for (epNum in 1..epCount) {
                        val epDataJson = JSONObject().apply {
                            put("tmdbID", tmdbId)
                            put("imdbID", imdbId)
                            put("season", seasonNum)
                            put("episode", epNum)
                        }.toString()

                        episodes.add(
                            newEpisode(epDataJson) {
                                this.name = "$seasonNum. Sezon $epNum. Bölüm"
                                this.season = seasonNum
                                this.episode = epNum
                            }
                        )
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = overview
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val json = try {
            JSONObject(data)
        } catch (e: Exception) {
            return false
        }

        val tmdbId = json.optInt("tmdbID", 0).takeIf { it != 0 } ?: return false
        val imdbId = json.optString("imdbID").takeIf { it.startsWith("tt") }
        val season = json.optInt("season", -1).takeIf { it > 0 }
        val episode = json.optInt("episode", -1).takeIf { it > 0 }
        val isTv = season != null && episode != null

        // 1. Goblin III (Vixsrc.to) - Hızlı Doğrudan 1080p HLS
        val vixUrl = if (isTv) {
            "https://vixsrc.to/tv/$tmdbId/$season/$episode"
        } else {
            "https://vixsrc.to/movie/$tmdbId"
        }
        extractVixsrc(vixUrl, callback)

        // 2. CloudStream Yerleşik Hızlı Çözücüler
        val embedUrls = mutableListOf<String>()
        if (isTv) {
            embedUrls.add("https://vidsrc.xyz/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
            embedUrls.add("https://vidsrc.to/embed/tv/$tmdbId/$season/$episode")
            embedUrls.add("https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
            embedUrls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode")
        } else {
            embedUrls.add("https://vidsrc.xyz/embed/movie?tmdb=$tmdbId")
            embedUrls.add("https://vidsrc.to/embed/movie/$tmdbId")
            embedUrls.add("https://vidsrc.me/embed/movie?tmdb=$tmdbId")
            embedUrls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1")
        }

        if (!imdbId.isNullOrEmpty()) {
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
                // Devam et
            }
        }

        // 3. FrameXTV WebView (Otomatik Play tıklama scripti ile)
        val autoPlayScript = """
            setInterval(() => {
                const btn = document.querySelector('button, .play-button, .jw-icon-display, video');
                if (btn) btn.click();
                const vid = document.querySelector('video');
                if (vid) vid.play();
            }, 700);
        """.trimIndent()

        val targetEmbed = if (isTv) {
            "$mainUrl/embed/$tmdbId/$season/$episode?autoplay=1&server=barbarian"
        } else {
            "$mainUrl/embed/$tmdbId?autoplay=1&server=barbarian"
        }

        try {
            val response = app.get(
                targetEmbed,
                headers = browserHeaders,
                referer = "$mainUrl/",
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(\.m3u8|\.mp4|/playlist/|master\.txt)"""),
                    additionalUrls = listOf(Regex("""\.(vtt|srt)""")),
                    script = autoPlayScript,
                    scriptCallback = {},
                    useOkhttp = false,
                    timeout = 10_000L
                )
            )

            val streamUrl = response.url
            if (streamUrl != targetEmbed && !streamUrl.contains("/embed/")) {
                emitDirectStream("FrameXTV - Barbarian", streamUrl, "$mainUrl/", mainUrl, callback)
            }
        } catch (e: Exception) {
            // Zaman aşımı olursa geç
        }

        return true
    }

    private suspend fun extractVixsrc(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(url, headers = browserHeaders, referer = "https://vixsrc.to/", timeout = 8L).text.replace("\\/", "/")

            val playlistUrl = Regex("""url\s*:\s*['"](https?://[^'"]+/playlist/[^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)
            val token = Regex("""['"]?token['"]?\s*:\s*['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)
            val expires = Regex("""['"]?expires['"]?\s*:\s*['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)

            if (playlistUrl != null && token != null && expires != null) {
                val sep = if (playlistUrl.contains("?")) "&" else "?"
                val finalM3u8 = "${playlistUrl}${sep}token=$token&expires=$expires&h=1"
                emitDirectStream("FrameXTV - Goblin III (1080p)", finalM3u8, url, "https://vixsrc.to", callback)
                return
            }

            val directM3u8 = Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""").find(html)?.groupValues?.get(1)
            if (directM3u8 != null) {
                emitDirectStream("FrameXTV - Goblin III", directM3u8, url, "https://vixsrc.to", callback)
            }
        } catch (e: Exception) {
            // Diğer sunuculara geç
        }
    }

    private suspend fun emitDirectStream(
        serverName: String,
        streamUrl: String,
        refererUrl: String,
        originUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = serverName,
                url = streamUrl,
                type = if (streamUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            ) {
                this.referer = refererUrl
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "Origin" to originUrl,
                    "Referer" to refererUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
            }
        )
    }
}
