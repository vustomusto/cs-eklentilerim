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
    private var lastErrorInfo: String = ""

    // Cloudflare çerezlerini ve Android WebView User-Agent bilgisini otomatik yönetir
    private val cloudflareKiller by lazy { CloudflareKiller() }

    /**
     * URL içinden yalnızca "https://domain.com" kısmını alır,
     * "/anasayfa" gibi yönlendirme uzantılarının linkleri bozmasını engeller.
     */
    private fun extractBaseDomain(url: String, fallback: String): String {
        return Regex("""^(https?://[^/?#]+)""").find(url.trim())?.groupValues?.get(1) ?: fallback
    }

    /**
     * Özel masaüstü User-Agent göndermeden doğrudan CloudflareKiller ile istek atar.
     */
    private suspend fun getDocumentWithCf(url: String, referer: String? = null): Document {
        val response = app.get(
            url,
            referer = referer ?: "$mainUrl/",
            timeout = 10L
        )

        if (response.code in listOf(403, 503) ||
            response.text.contains("Just a moment", ignoreCase = true) ||
            response.text.contains("Attention Required", ignoreCase = true)
        ) {
            val cfResponse = app.get(
                url,
                referer = referer ?: "$mainUrl/",
                interceptor = cloudflareKiller,
                timeout = 20L
            )
            lastErrorInfo = "HTTP ${cfResponse.code} (${extractBaseDomain(cfResponse.url, url)})"
            return cfResponse.document
        }

        lastErrorInfo = "HTTP ${response.code} (${extractBaseDomain(response.url, url)})"
        return response.document
    }

    /**
     * 2149'dan başlayarak hızlı tarama yapar ve gerçekten içerik barındıran
     * aktif domaini bulur.
     */
    private suspend fun getActiveUrl(): String {
        resolvedUrl?.let { return it }

        val startNumber = 2149
        val maxTries = 15 // 2149 - 2164 arası hızlı kontrol

        for (i in 0..maxTries) {
            val testDomain = "https://dizipal${startNumber + i}.com"
            try {
                val response = app.get(testDomain, timeout = 4L)
                // Site yanıt veriyorsa (200 OK veya 403/503 Cloudflare koruması aktifse)
                if (response.code in 200..399 || response.code in listOf(403, 503)) {
                    val activeBase = extractBaseDomain(response.url, testDomain)
                    val doc = if (response.code in listOf(403, 503)) {
                        app.get(activeBase, interceptor = cloudflareKiller, timeout = 15L).document
                    } else {
                        response.document
                    }

                    val items = extractItemsFromDocument(doc, activeBase)
                    if (items.isNotEmpty() || doc.select("a[href*='/dizi/'], a[href*='/film/'], a[href*='bolum']").isNotEmpty()) {
                        resolvedUrl = activeBase
                        mainUrl = activeBase
                        return activeBase
                    }
                }
            } catch (e: Exception) {
                lastErrorInfo = "${e.javaClass.simpleName} ($testDomain)"
                continue
            }
        }

        return mainUrl
    }

    override val mainPage = mainPageOf(
        "" to "Ana Sayfa (Son Eklenenler)",
        "/diziler/son-bolumler" to "Son Bölümler",
        "/diziler" to "Yeni Diziler",
        "/filmler" to "Yeni Filmler",
        "/koleksiyon/netflix" to "Netflix",
        "/koleksiyon/exxen" to "Exxen"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = getActiveUrl()
        val path = request.data

        if (path.isEmpty() && page > 1) {
            return newHomePageResponse(request.name, emptyList())
        }

        val targetUrl = when {
            path.isEmpty() -> baseUrl
            page == 1 -> "$baseUrl$path"
            else -> {
                val sep = if (path.contains("?")) "&" else "?"
                "$baseUrl$path${sep}page=$page"
            }
        }

        return try {
            val document = getDocumentWithCf(targetUrl, baseUrl)
            val items = extractItemsFromDocument(document, baseUrl)

            // Eğer ilk sayfada hiç içerik çıkmadıysa boş ekran yerine teşhis kartı göster
            if (items.isEmpty() && page == 1 && path.isEmpty()) {
                val pageTitle = document.title().ifEmpty { "Başlık Yok" }
                val debugItem = newMovieSearchResponse(
                    "⚠️ Durum: $lastErrorInfo | Başlık: $pageTitle",
                    baseUrl,
                    TvType.Movie
                )
                newHomePageResponse(request.name, listOf(debugItem))
            } else {
                newHomePageResponse(request.name, items)
            }
        } catch (e: Exception) {
            if (page == 1 && path.isEmpty()) {
                val errorItem = newMovieSearchResponse(
                    "⚠️ Bağlantı Engeli (DoH/VPN Açın): ${e.javaClass.simpleName} - $baseUrl",
                    baseUrl,
                    TvType.Movie
                )
                newHomePageResponse(request.name, listOf(errorItem))
            } else {
                newHomePageResponse(request.name, emptyList())
            }
        }
    }

    private fun extractItemsFromDocument(document: Document, baseUrl: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        // 1. Katman: Standart Dizipal kart seçicileri
        val cardElements = document.select(
            "div.episode-item, article.type2 ul li, div.type2 ul li, li.w-item, " +
            "article.movie-item, div.series-item, div.poster, div.grid-box, div.flw-item"
        )

        cardElements.forEach { element ->
            element.toMainPageResult(baseUrl)?.let { item ->
                if (seenUrls.add(item.url)) {
                    results.add(item)
                }
            }
        }

        // 2. Katman: Sayfadaki tüm dizi, film ve bölüm bağlantılarını tarayan genel yakalayıcı
        if (results.isEmpty()) {
            document.select("a[href]").forEach { aTag ->
                val rawHref = aTag.attr("href").trim()
                if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript")) return@forEach

                val isMediaLink = rawHref.contains("/dizi/") ||
                        rawHref.contains("/film/") ||
                        rawHref.contains("bolum") ||
                        rawHref.contains("sezon")

                if (!isMediaLink) return@forEach

                val fullUrl = fixUrlWithBase(rawHref, baseUrl)
                if (!seenUrls.add(fullUrl)) return@forEach

                val img = aTag.selectFirst("img")
                    ?: aTag.parent()?.selectFirst("img")
                    ?: aTag.parent()?.parent()?.selectFirst("img")

                val rawTitle = aTag.selectFirst("div.name, span.title, span.name, h2, h3, h4")?.text()?.trim()
                    ?: aTag.attr("title").trim().ifEmpty { null }
                    ?: img?.attr("alt")?.trim()?.ifEmpty { null }
                    ?: aTag.text().trim()

                if (rawTitle.length < 2 || rawTitle.lowercase() in listOf("diziler", "filmler", "son bölümler", "anasayfa")) {
                    return@forEach
                }

                val epSub = aTag.selectFirst("div.episode, span.episode")?.text()?.trim()
                val finalTitle = if (!epSub.isNullOrEmpty() && !rawTitle.contains(epSub)) {
                    "$rawTitle - $epSub"
                } else {
                    rawTitle
                }

                val poster = fixUrlNull(
                    img?.attr("data-src")?.ifEmpty { null }
                        ?: img?.attr("data-lazy-src")?.ifEmpty { null }
                        ?: img?.attr("src")
                )

                val isMovie = fullUrl.contains("/film/")
                if (isMovie) {
                    results.add(newMovieSearchResponse(finalTitle, fullUrl, TvType.Movie) { this.posterUrl = poster })
                } else {
                    results.add(newTvSeriesSearchResponse(finalTitle, fullUrl, TvType.TvSeries) { this.posterUrl = poster })
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
            seriesTitle != null && epTitle != null && !seriesTitle.contains(epTitle) -> "$seriesTitle - $epTitle"
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
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                ),
                referer = "$baseUrl/",
                interceptor = cloudflareKiller,
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
            // Standart arama sayfasına geç
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

            val epNumText = "${ep.text()} $epHref"
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
                referer = "$baseUrl/",
                interceptor = cloudflareKiller
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
