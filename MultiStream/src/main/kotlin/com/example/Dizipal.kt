package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Dizipal : MainAPI() {
    override var name = "Dizipal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var mainUrl = "https://dizipal2149.com"
    private var resolvedUrl: String? = null
    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private suspend fun getDocumentWithCf(url: String, referer: String? = null): Document {
        val response = app.get(
            url,
            headers = defaultHeaders,
            referer = referer ?: "$mainUrl/",
            timeout = 8L
        )

        if (response.code in listOf(403, 503) || response.text.contains("Just a moment", ignoreCase = true)) {
            return app.get(
                url,
                headers = defaultHeaders,
                referer = referer ?: "$mainUrl/",
                interceptor = cloudflareKiller,
                timeout = 15L
            ).document
        }

        return response.document
    }

    /**
     * Sadece HTTP 200 dönmesine değil, sayfa içinde gerçekten dizi/film/bölüm
     * bağlantıları olup olmadığına bakarak aktif adresi doğrular.
     */
    private suspend fun getActiveUrl(): String {
        resolvedUrl?.let { return it }

        val startNumber = 2149
        val maxTries = 35 // 2149 - 2184 arası kontrol eder

        for (i in 0..maxTries) {
            val testDomain = "https://dizipal${startNumber + i}.com"
            try {
                val response = app.get(testDomain, headers = defaultHeaders, timeout = 4L)
                val doc = if (response.code in listOf(403, 503)) {
                    app.get(testDomain, headers = defaultHeaders, interceptor = cloudflareKiller, timeout = 10L).document
                } else {
                    response.document
                }

                // Sayfada gerçek içerik kartları veya bağlantıları var mı kontrol et
                val hasContent = doc.select(
                    "div.episode-item, article.type2, a[href*='/dizi/'], a[href*='/film/'], a[href*='bolum'], div.poster, article"
                ).size > 2

                if (hasContent) {
                    val finalUrl = response.url.trimEnd('/').let {
                        if (it.startsWith("http")) it.substringBefore("/dizi").substringBefore("/film") else testDomain
                    }
                    resolvedUrl = finalUrl
                    mainUrl = finalUrl
                    return finalUrl
                }
            } catch (e: Exception) {
                continue
            }
        }
        return mainUrl
    }

    override val mainPage = mainPageOf(
        "" to "Son Eklenenler (Ana Sayfa)",
        "/diziler/son-bolumler" to "Son Bölümler",
        "/diziler" to "Yeni Diziler",
        "/filmler" to "Yeni Filmler",
        "/koleksiyon/netflix" to "Netflix İçerikleri",
        "/koleksiyon/exxen" to "Exxen İçerikleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = getActiveUrl()
        val path = request.data
        val url = when {
            path.isEmpty() && page == 1 -> baseUrl
            path.isEmpty() && page > 1 -> return newHomePageResponse(request.name, emptyList())
            page == 1 -> "$baseUrl$path"
            else -> {
                val sep = if (path.contains("?")) "&" else "?"
                "$baseUrl$path${sep}page=$page"
            }
        }

        val document = try {
            getDocumentWithCf(url, baseUrl)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val items = extractItemsFromDocument(document, baseUrl)
        return newHomePageResponse(request.name, items)
    }

    /**
     * Hem klasik Dizipal temasını hem de yeni nesil klon temaları
     * tarayan çok katmanlı içerik çıkarıcı.
     */
    private fun extractItemsFromDocument(document: Document, baseUrl: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        // 1. Katman: Bilinen tüm kart seçicileri
        val cardElements = document.select(
            "div.episode-item, article.type2 ul li, div.type2 ul li, li.w-item, " +
            "article.movie-item, div.series-item, div.poster, div.grid-box, " +
            "div.flw-item, article.item, div.item, article"
        )

        cardElements.forEach { element ->
            element.toMainPageResult(baseUrl)?.let { item ->
                if (seenUrls.add(item.url)) {
                    results.add(item)
                }
            }
        }

        // 2. Katman (Evrensel Yedek): Eğer tema değişmişse ve 1. katman boş döndüyse,
        // içinde resim (poster) barındıran tüm dizi/film/bölüm linklerini yakala
        if (results.isEmpty()) {
            document.select("a[href]").forEach { aTag ->
                val rawHref = aTag.attr("href")
                if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.contains("javascript")) return@forEach

                val isContentLink = rawHref.contains("/dizi/") ||
                        rawHref.contains("/film/") ||
                        rawHref.contains("bolum") ||
                        rawHref.contains("sezon") ||
                        rawHref.contains("/watch/") ||
                        rawHref.contains("/izle/")

                val img = aTag.selectFirst("img") ?: aTag.parent()?.selectFirst("img")
                if (!isContentLink && img == null) return@forEach

                val fullUrl = fixUrlWithBase(rawHref, baseUrl)
                if (fullUrl == baseUrl || fullUrl == "$baseUrl/") return@forEach

                val title = aTag.attr("title").trim().ifEmpty {
                    img?.attr("alt")?.trim().orEmpty().ifEmpty {
                        aTag.text().trim().ifEmpty {
                            aTag.parent()?.selectFirst("h2, h3, h4, span.title, div.name")?.text()?.trim().orEmpty()
                        }
                    }
                }

                if (title.length < 2 || title.lowercase() in listOf("anasayfa", "diziler", "filmler", "giriş", "kayıt")) {
                    return@forEach
                }

                val poster = fixUrlNull(
                    img?.attr("data-src")?.ifEmpty { null }
                        ?: img?.attr("data-lazy-src")?.ifEmpty { null }
                        ?: img?.attr("src")
                )

                if (seenUrls.add(fullUrl)) {
                    val isMovie = fullUrl.contains("/film/") || fullUrl.contains("/movie")
                    if (isMovie) {
                        results.add(newMovieSearchResponse(title, fullUrl, TvType.Movie) { this.posterUrl = poster })
                    } else {
                        results.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) { this.posterUrl = poster })
                    }
                }
            }
        }

        return results
    }

    private fun Element.toMainPageResult(baseUrl: String): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else this.selectFirst("a[href]") ?: return null
        val rawHref = aTag.attr("href").takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val href = fixUrlWithBase(rawHref, baseUrl)

        val imgElement = this.selectFirst("img")
        val seriesTitle = this.selectFirst("div.name, span.title, h2, h3, h4, div.title, span.name")?.text()?.trim()
            ?: aTag.attr("title").trim().ifEmpty { null }
            ?: imgElement?.attr("alt")?.trim()?.ifEmpty { null }

        val epTitle = this.selectFirst("div.episode, span.episode, span.ep")?.text()?.trim()

        val fullTitle = when {
            seriesTitle != null && epTitle != null && !seriesTitle.contains(epTitle) -> "$seriesTitle -$epTitle"
            seriesTitle != null -> seriesTitle
            else -> aTag.text().trim().takeIf { it.length > 1 } ?: return null
        }

        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.ifEmpty { null }
                ?: imgElement?.attr("data-lazy-src")?.ifEmpty { null }
                ?: imgElement?.attr("src")
        )

        val isMovie = href.contains("/film/") || href.contains("/movies/")

        return if (isMovie) {
            newMovieSearchResponse(fullTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(fullTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val baseUrl = getActiveUrl()
        val results = mutableListOf<SearchResponse>()

        try {
            val responseText = app.post(
                "$baseUrl/api/search-autocomplete",
                headers = defaultHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                ),
                referer = "$baseUrl/",
                data = mapOf("query" to query)
            ).text.trim()

            if (responseText.startsWith("{")) {
                val jsonObject = JSONObject(responseText)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = jsonObject.optJSONObject(key) ?: continue
                    val title = item.optString("title").takeIf { it.isNotEmpty() } ?: continue
                    val rawUrl = item.optString("url").takeIf { it.isNotEmpty() } ?: continue
                    val url = fixUrlWithBase(rawUrl, baseUrl)
                    val poster = item.optString("poster").takeIf { it.isNotEmpty() }?.let { fixUrlNull(it) }
                    val type = item.optString("type")
                    val isMovie = type == "movie" || url.contains("/film/")

                    if (isMovie) {
                        results.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
                    } else {
                        results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
                    }
                }
                if (results.isNotEmpty()) return results
            }

            val doc = Jsoup.parse(responseText)
            results.addAll(extractItemsFromDocument(doc, baseUrl))
        } catch (e: Exception) {
            // Standart aramaya geç
        }

        if (results.isEmpty()) {
            for (searchPath in listOf("/arama?q=$query", "/?s=$query", "/search?q=$query")) {
                try {
                    val searchDoc = getDocumentWithCf("$baseUrl$searchPath", baseUrl)
                    val items = extractItemsFromDocument(searchDoc, baseUrl)
                    if (items.isNotEmpty()) {
                        results.addAll(items)
                        break
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getActiveUrl()
        var targetUrl = url
        var document = getDocumentWithCf(targetUrl, baseUrl)

        if (targetUrl.contains("bolum") || targetUrl.contains("sezon")) {
            val seriesPageHref = document.selectFirst(
                "div.series-info a, a.series-title, div.top-content a[href*='/dizi/'], a[href*='/dizi/']"
            )?.attr("href")
            if (seriesPageHref != null) {
                targetUrl = fixUrlWithBase(seriesPageHref, baseUrl)
                document = getDocumentWithCf(targetUrl, baseUrl)
            }
        }

        val title = document.selectFirst(
            "div.cover h5, div.series-title h1, div.top-content h1, h1.title, h1, h2.title"
        )?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("-")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.cover img, div.poster img, div.series-poster img")?.attr("data-src")?.ifEmpty { null }
                ?: document.selectFirst("div.cover img, div.poster img, div.series-poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst("div.summary p, div.series-summary p, div.summary, meta[property=og:description]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }?.trim()

        val episodes = mutableListOf<Episode>()
        val seenEpUrls = mutableSetOf<String>()

        document.select("div.episode-item, div.bolumler a, ul.episodes-list li, a[href*='bolum'], a[href*='sezon']").forEach { ep ->
            val aTag = if (ep.tagName() == "a") ep else ep.selectFirst("a[href]") ?: return@forEach
            val epHref = aTag.attr("href").takeIf { it.isNotEmpty() && !it.startsWith("#") }
                ?.let { fixUrlWithBase(it, baseUrl) } ?: return@forEach

            if (!seenEpUrls.add(epHref)) return@forEach

            val epTitle = ep.selectFirst("div.name, span.ep-title, div.title")?.text()?.trim()
                ?: aTag.text().trim().ifEmpty { "Bölüm" }

            val epNumText = "${ep.text()}$epHref"
            val seasonNum = Regex("""(\d+)\.\s*Sezon|sezon-(\d+)""", RegexOption.IGNORE_CASE)
                .find(epNumText)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
            val episodeNum = Regex("""(\d+)\.\s*Bölüm|bolum-(\d+)""", RegexOption.IGNORE_CASE)
                .find(epNumText)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

            episodes.add(
                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = episodeNum
                }
            )
        }

        val isMovie = episodes.isEmpty() || targetUrl.contains("/film/")

        return if (isMovie) {
            newMovieLoadResponse(title, targetUrl, TvType.Movie, targetUrl) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseUrl = getActiveUrl()
        val document = getDocumentWithCf(data, baseUrl)

        val iframeElement = document.selectFirst(
            "div#vast_new iframe, div.series-player-container iframe, div.player-container iframe, iframe[src*='player'], iframe[src*='embed'], iframe[data-src], iframe"
        )
        val rawIframe = iframeElement?.attr("data-src")?.ifEmpty { null } ?: iframeElement?.attr("src")
        val iframeUrl = rawIframe?.takeIf { it.isNotEmpty() }?.let { fixUrlWithBase(it, baseUrl) } ?: data

        var foundLinks = false

        try {
            val iframeResponse = app.get(
                iframeUrl,
                headers = defaultHeaders,
                referer = "$baseUrl/"
            ).text

            val subRegex = Regex("""\[([^\]]+)\](https?://[^\s"',]+\.vtt)""")
            subRegex.findAll(iframeResponse).forEach { match ->
                subtitleCallback(SubtitleFile(match.groupValues[1], match.groupValues[2]))
            }

            val m3u8Regex = Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""")
            val m3u8Url = m3u8Regex.find(iframeResponse.replace("\\/", "/"))?.groupValues?.get(1)

            if (!m3u8Url.isNullOrEmpty()) {
                emitM3u8(name, m3u8Url, iframeUrl, baseUrl, callback)
                foundLinks = true
            }
        } catch (e: Exception) {
            // WebView aşamasına geç
        }

        if (!foundLinks) {
            try {
                val resolved = app.get(
                    iframeUrl,
                    referer = data,
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""(\.m3u8|\.mp4|master\.txt|/playlist/)"""),
                        additionalUrls = listOf(Regex("""\.vtt"""))
                    )
                )
                if (resolved.url != iframeUrl) {
                    emitM3u8("$name (WebView)", resolved.url, iframeUrl, baseUrl, callback)
                    foundLinks = true
                }
            } catch (e: Exception) {
                // Extractor aşamasına geç
            }
        }

        if (!foundLinks && iframeUrl != data) {
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun emitM3u8(
        sourceName: String,
        streamUrl: String,
        refererUrl: String,
        originUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val generated = try {
            M3u8Helper.generateM3u8(
                source = sourceName,
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
                    source = sourceName,
                    name = sourceName,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = refererUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Origin" to originUrl)
                }
            )
        }
    }

    private fun fixUrlWithBase(url: String, baseDomain: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$baseDomain/${url.trimStart('/')}"
    }
}
