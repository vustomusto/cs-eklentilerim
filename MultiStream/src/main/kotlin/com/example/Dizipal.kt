package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Dizipal : MainAPI() {
    override var name = "Dizipal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var mainUrl = "https://dizipal2149.com"
    private var resolvedUrl: String? = null
    private val cloudflareKiller by lazy { CloudflareKiller() }

    private fun extractBaseDomain(url: String, fallback: String): String {
        return Regex("""^(https?://[^/?#]+)""").find(url.trim())?.groupValues?.get(1) ?: fallback
    }

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
            return app.get(
                url,
                referer = referer ?: "$mainUrl/",
                interceptor = cloudflareKiller,
                timeout = 20L
            ).document
        }

        return response.document
    }

    private suspend fun getActiveUrl(): String {
        resolvedUrl?.let { return it }

        val startNumber = 2149
        val maxTries = 20

        for (i in 0..maxTries) {
            val testDomain = "https://dizipal${startNumber + i}.com"
            try {
                val response = app.get(testDomain, timeout = 4L)
                if (response.code in 200..399 || response.code in listOf(403, 503)) {
                    val activeBase = extractBaseDomain(response.url, testDomain)
                    val doc = if (response.code in listOf(403, 503)) {
                        app.get(activeBase, interceptor = cloudflareKiller, timeout = 15L).document
                    } else {
                        response.document
                    }

                    val hasLinks = doc.select("a[href*='/dizi/'], a[href*='/film/'], a[href*='bolum'], article.type2, div.episode-item").isNotEmpty()
                    if (hasLinks) {
                        resolvedUrl = activeBase
                        mainUrl = activeBase
                        return activeBase
                    }
                }
            } catch (e: Exception) {
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
            val items = extractItemsFromDocument(document, baseUrl, path.contains("/filmler"))
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun extractItemsFromDocument(
        document: Document,
        baseUrl: String,
        forceMovie: Boolean = false
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        val cardElements = document.select(
            "div.episode-item, article.type2 ul li, div.type2 ul li, li.w-item, " +
            "article.movie-item, div.series-item, div.poster, div.grid-box, div.flw-item, article"
        )

        cardElements.forEach { element ->
            element.toMainPageResult(baseUrl, forceMovie)?.let { item ->
                if (seenUrls.add(item.url)) {
                    results.add(item)
                }
            }
        }

        if (results.isEmpty()) {
            document.select("a[href]").forEach { aTag ->
                val rawHref = aTag.attr("href").trim()
                if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript")) return@forEach

                val img = aTag.selectFirst("img")
                    ?: aTag.parent()?.selectFirst("img")
                    ?: aTag.parent()?.parent()?.selectFirst("img")

                // İçinde poster resmi olan VEYA dizi/film/bölüm uzantısı olan tüm kartları kabul et
                val isMediaLink = img != null ||
                        rawHref.contains("/dizi/") ||
                        rawHref.contains("/film/") ||
                        rawHref.contains("bolum") ||
                        rawHref.contains("sezon")

                if (!isMediaLink) return@forEach

                val fullUrl = fixUrlWithBase(rawHref, baseUrl)
                // Menü linklerini atla
                val pathOnly = fullUrl.removePrefix(baseUrl).trim('/')
                if (pathOnly.isEmpty() || pathOnly in listOf("diziler", "filmler", "anasayfa", "iletisim", "kayit", "giris", "takvim")) {
                    return@forEach
                }

                if (!seenUrls.add(fullUrl)) return@forEach

                val rawTitle = aTag.selectFirst("div.name, span.title, span.name, h2, h3, h4")?.text()?.trim()
                    ?: aTag.attr("title").trim().ifEmpty { null }
                    ?: img?.attr("alt")?.trim()?.ifEmpty { null }
                    ?: aTag.text().trim()

                if (rawTitle.length < 2) return@forEach

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

                val isSeries = fullUrl.contains("/dizi/") || fullUrl.contains("bolum") || fullUrl.contains("sezon")
                if (forceMovie || !isSeries) {
                    results.add(newMovieSearchResponse(finalTitle, fullUrl, TvType.Movie) { this.posterUrl = poster })
                } else {
                    results.add(newTvSeriesSearchResponse(finalTitle, fullUrl, TvType.TvSeries) { this.posterUrl = poster })
                }
            }
        }

        return results
    }

    private fun Element.toMainPageResult(baseUrl: String, forceMovie: Boolean = false): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else this.selectFirst("a[href]") ?: return null
        val rawHref = aTag.attr("href").takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val href = fixUrlWithBase(rawHref, baseUrl)

        val pathOnly = href.removePrefix(baseUrl).trim('/')
        if (pathOnly.isEmpty() || pathOnly in listOf("diziler", "filmler", "anasayfa", "iletisim", "kayit", "giris")) {
            return null
        }

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

        // Dizipal'de diziler /dizi/ veya /bolum içerir; filmler ise /film/ veya doğrudan /film-adi şeklindedir
        val isSeries = href.contains("/dizi/") || href.contains("bolum") || href.contains("sezon")

        return if (forceMovie || !isSeries) {
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
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // 1. Yöntem: POST /api/search-autocomplete (CloudflareKiller ile)
        try {
            val responseText = app.post(
                "$baseUrl/api/search-autocomplete",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to baseUrl
                ),
                referer = "$baseUrl/",
                interceptor = cloudflareKiller,
                data = mapOf("query" to query.trim())
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
                    val isSeries = item.optString("type") == "series" || url.contains("/dizi/")

                    if (seen.add(url)) {
                        if (!isSeries) {
                            results.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
                        } else {
                            results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
                        }
                    }
                }
                if (results.isNotEmpty()) return results
            } else if (responseText.contains("<a")) {
                val doc = Jsoup.parse(responseText)
                doc.select("a[href]").forEach { aTag ->
                    val href = fixUrlWithBase(aTag.attr("href"), baseUrl)
                    val title = aTag.selectFirst("span.title, div.title, span")?.text()?.trim() ?: aTag.text().trim()
                    val poster = fixUrlNull(aTag.selectFirst("img")?.attr("src"))
                    if (title.isNotEmpty() && seen.add(href)) {
                        if (href.contains("/dizi/")) {
                            results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster })
                        } else {
                            results.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
                        }
                    }
                }
                if (results.isNotEmpty()) return results
            }
        } catch (e: Exception) {
            // GET aramasına geç
        }

        // 2. Yöntem: GET /arama?q= ve /?s=
        for (sUrl in listOf("$baseUrl/arama?q=$encoded", "$baseUrl/?s=$encoded", "$baseUrl/search?q=$encoded")) {
            try {
                val doc = getDocumentWithCf(sUrl, baseUrl)
                extractItemsFromDocument(doc, baseUrl).forEach {
                    if (seen.add(it.url)) results.add(it)
                }
                if (results.isNotEmpty()) break
            } catch (e: Exception) {
                continue
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
                "div.series-info a[href*='/dizi/'], a.series-title, div.top-content a[href*='/dizi/'], a[href*='/dizi/']"
            )?.attr("href")
            if (!seriesPageHref.isNullOrEmpty() && !seriesPageHref.contains("bolum")) {
                targetUrl = fixUrlWithBase(seriesPageHref, baseUrl)
                document = getDocumentWithCf(targetUrl, baseUrl)
            } else {
                val slugMatch = Regex("""(https?://[^/]+/dizi/[^/]+)""").find(targetUrl)?.groupValues?.get(1)
                if (slugMatch != null) {
                    targetUrl = slugMatch
                    document = getDocumentWithCf(targetUrl, baseUrl)
                }
            }
        }

        val title = document.selectFirst(
            "div.cover h5, div.series-title h1, div.top-content h1, div.header-info h1, h1.title, h1, h2.title"
        )?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("-")?.trim()
            ?: document.title().substringBefore(" izle").substringBefore("-").trim().ifEmpty { "İçerik" }

        val poster = fixUrlNull(
            document.selectFirst("div.cover img, div.poster img, div.series-poster img")?.attr("data-src")?.ifEmpty { null }
                ?: document.selectFirst("div.cover img, div.poster img, div.series-poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst("div.summary p, div.series-summary p, div.summary, meta[property=og:description]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }?.trim()

        val episodes = mutableListOf<Episode>()
        val seenEpUrls = mutableSetOf<String>()

        document.select("div.episode-item, div.bolumler a, ul.episodes-list li, a[href*='bolum']").forEach { ep ->
            val aTag = if (ep.tagName() == "a") ep else ep.selectFirst("a[href]") ?: return@forEach
            val epHref = aTag.attr("href").takeIf { it.isNotEmpty() && !it.startsWith("#") }
                ?.let { fixUrlWithBase(it, baseUrl) } ?: return@forEach

            if (!epHref.contains("bolum")) return@forEach
            if (!seenEpUrls.add(epHref)) return@forEach

            val combinedText = "${ep.text()} ${aTag.text()}"

            val seasonNum = Regex("""sezon[-/](\d+)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(combinedText)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            val episodeNum = Regex("""bolum[-/](\d+)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(combinedText)?.groupValues?.get(1)?.toIntOrNull()

            val cleanName = ep.selectFirst("div.name, span.ep-title, div.title")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: episodeNum?.let { "$it. Bölüm" }
                ?: "Bölüm"

            episodes.add(
                newEpisode(epHref) {
                    this.name = cleanName
                    this.season = seasonNum
                    this.episode = episodeNum
                }
            )
        }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        val isMovie = sortedEpisodes.isEmpty() || !targetUrl.contains("/dizi/")

        return if (isMovie) {
            newMovieLoadResponse(title, targetUrl, TvType.Movie, targetUrl) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, sortedEpisodes) {
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
        val pageHtml = document.outerHtml().replace("\\/", "/")
        var foundLinks = false

        // 1. Aşama: Film/Bölüm sayfasının kendi içinde doğrudan m3u8 / mp4 varsa yakala
        val directPageMatch = Regex("""(https?://[^\s"'<>\\]+(?:\.m3u8|master\.txt|/playlist/)[^\s"'<>\\]*)""").find(pageHtml)?.groupValues?.get(1)
        if (!directPageMatch.isNullOrEmpty()) {
            emitDirectLink(name, directPageMatch, data, baseUrl, callback)
            foundLinks = true
        }

        // 2. Aşama: Sayfadaki tüm iframe'leri topla ve tara
        val iframes = document.select("div#vast_new iframe, div.series-player-container iframe, div.player-container iframe, div#player iframe, iframe")
            .mapNotNull { it.attr("data-src").ifEmpty { it.attr("src") } }
            .filter { it.isNotEmpty() && !it.contains("google") && !it.contains("yandex") && !it.contains("facebook") && !it.startsWith("about:") }
            .map { fixUrlWithBase(it, baseUrl) }

        for (iframeUrl in iframes) {
            val iframeDomain = extractBaseDomain(iframeUrl, baseUrl)

            try {
                val iframeHtml = app.get(
                    iframeUrl,
                    referer = data,
                    interceptor = cloudflareKiller,
                    timeout = 10L
                ).text.replace("\\/", "/")

                val subRegex = Regex("""\[([^\]]+)\](https?://[^\s"',]+\.(?:vtt|srt))""")
                subRegex.findAll(iframeHtml).forEach { match ->
                    subtitleCallback(SubtitleFile(match.groupValues[1], match.groupValues[2]))
                }

                val matchUrl = Regex("""(?:file|source|url|src)\s*[:=]\s*["'](https?://[^"'\s<>]+(?:\.m3u8|master\.txt|/playlist/)[^"'\s<>]*)["']""")
                    .find(iframeHtml)?.groupValues?.get(1)
                    ?: Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""").find(iframeHtml)?.groupValues?.get(1)

                if (!matchUrl.isNullOrEmpty()) {
                    emitDirectLink(name, matchUrl, iframeUrl, iframeDomain, callback)
                    foundLinks = true
                }
            } catch (e: Exception) {
                // Devam et
            }

            // Yerleşik CloudStream çözücülerini de dene (VidMoly, Okru, Dood vb. gömülüyse)
            try {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                // Devam et
            }
        }

        // 3. Aşama: Hâlâ bulunamadıysa hem ilk iframe'i hem de ana film sayfasını WebView ile açıp ağ trafiğini yakala
        if (!foundLinks) {
            val autoPlayScript = """
                setInterval(() => {
                    const btn = document.querySelector('.jw-icon-display, .vjs-big-play-button, .plyr__control--overlaid, #vast_new, button, video');
                    if (btn) btn.click();
                    const vid = document.querySelector('video');
                    if (vid) vid.play();
                }, 700);
            """.trimIndent()

            val webViewTargets = (iframes.take(1) + listOf(data)).distinct()
            for (target in webViewTargets) {
                try {
                    val resolved = app.get(
                        target,
                        referer = baseUrl,
                        interceptor = WebViewResolver(
                            interceptUrl = Regex("""(\.m3u8|\.mp4|master\.txt|/playlist/)"""),
                            additionalUrls = listOf(Regex("""\.(vtt|srt)""")),
                            script = autoPlayScript,
                            scriptCallback = {},
                            useOkhttp = false,
                            timeout = 12_000L
                        )
                    )
                    if (resolved.url != target) {
                        val refDomain = extractBaseDomain(target, baseUrl)
                        emitDirectLink("$name (WebView)", resolved.url, target, refDomain, callback)
                        break
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }

        return true
    }

    private suspend fun emitDirectLink(
        sourceName: String,
        streamUrl: String,
        refererUrl: String,
        originUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = sourceName,
                url = streamUrl,
                type = if (streamUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            ) {
                this.referer = refererUrl
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "Origin" to originUrl,
                    "Referer" to refererUrl
                )
            }
        )
    }

    private fun fixUrlWithBase(url: String, baseDomain: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$baseDomain/${url.trimStart('/')}"
    }
}
