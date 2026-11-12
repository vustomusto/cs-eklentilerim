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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private suspend fun getDocumentWithCf(url: String, referer: String? = null): Document {
        val response = app.get(
            url,
            headers = defaultHeaders,
            referer = referer ?: "$mainUrl/"
        )

        if (response.code in listOf(403, 503) || response.text.contains("Just a moment", ignoreCase = true)) {
            return app.get(
                url,
                headers = defaultHeaders,
                referer = referer ?: "$mainUrl/",
                interceptor = cloudflareKiller
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
                val response = app.get(testDomain, headers = defaultHeaders)
                if (response.code in 200..399 || response.text.contains("dizipal", ignoreCase = true)) {
                    val finalUrl = response.url.trimEnd('/')
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
        "/diziler/son-bolumler" to "Son Bölümler",
        "/diziler" to "Yeni Diziler",
        "/filmler" to "Yeni Filmler",
        "/koleksiyon/netflix" to "Netflix İçerikleri",
        "/koleksiyon/exxen" to "Exxen İçerikleri",
        "/koleksiyon/blutv" to "BluTV İçerikleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = getActiveUrl()
        val url = if (page == 1) {
            "$baseUrl${request.data}"
        } else {
            val separator = if (request.data.contains("?")) "&" else "?"
            "$baseUrl${request.data}${separator}page=$page"
        }

        val document = getDocumentWithCf(url, baseUrl)

        val items = document.select(
            "div.episode-item, article.type2 ul li, div.type2 ul li, li.w-item, article.movie-item, div.series-item"
        ).mapNotNull { element ->
            element.toMainPageResult(baseUrl)
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toMainPageResult(baseUrl: String): SearchResponse? {
        val seriesTitle = this.selectFirst("div.name, span.title, h2, div.title")?.text()?.trim()
        val epTitle = this.selectFirst("div.episode, span.episode")?.text()?.trim()

        val fullTitle = when {
            seriesTitle != null && epTitle != null -> "$seriesTitle - $epTitle"
            seriesTitle != null -> seriesTitle
            else -> this.selectFirst("a")?.attr("title")?.trim() ?: return null
        }

        val rawHref = this.selectFirst("a")?.attr("href") ?: return null
        val href = fixUrlWithBase(rawHref, baseUrl)

        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.ifEmpty { null }
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
            doc.select("a").forEach { aTag ->
                val title = aTag.selectFirst("span.title, div.title, span")?.text()?.trim() ?: aTag.text().trim()
                val href = aTag.attr("href").takeIf { it.isNotEmpty() }?.let { fixUrlWithBase(it, baseUrl) } ?: return@forEach
                val poster = fixUrlNull(aTag.selectFirst("img")?.attr("src"))
                val isMovie = href.contains("/film/")

                if (title.isNotEmpty()) {
                    if (isMovie) {
                        results.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
                    } else {
                        results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster })
                    }
                }
            }
        } catch (e: Exception) {
            // Yedek aramaya geç
        }

        if (results.isEmpty()) {
            val searchDoc = getDocumentWithCf("$baseUrl/arama?q=$query", baseUrl)
            searchDoc.select("article.type2 ul li, div.episode-item, li.w-item").mapNotNullTo(results) {
                it.toMainPageResult(baseUrl)
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getActiveUrl()
        var targetUrl = url
        var document = getDocumentWithCf(targetUrl, baseUrl)

        if (targetUrl.contains("-bolum") || targetUrl.contains("/sezon-")) {
            val seriesPageHref = document.selectFirst("div.series-info a, a.series-title, div.top-content a[href*='/dizi/']")
                ?.attr("href")
            if (seriesPageHref != null) {
                targetUrl = fixUrlWithBase(seriesPageHref, baseUrl)
                document = getDocumentWithCf(targetUrl, baseUrl)
            }
        }

        val title = document.selectFirst(
            "div.cover h5, div.series-title h1, div.top-content h1, h1.title, h1"
        )?.text()?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.cover img, div.poster img")?.attr("data-src")?.ifEmpty { null }
                ?: document.selectFirst("div.cover img, div.poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst("div.summary p, div.series-summary p, div.summary")?.text()?.trim()
        val year = document.select("div.col-md-6 div, ul.extra-info li")
            .firstOrNull { it.text().contains("Yapım Yılı", ignoreCase = true) }
            ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val tags = document.select("div.genres a, ul.genres li a").map { it.text().trim() }

        val episodes = document.select("div.episode-item, div.bolumler a, ul.episodes-list li").mapNotNull { ep ->
            val aTag = if (ep.tagName() == "a") ep else ep.selectFirst("a")
            val epHref = aTag?.attr("href")?.let { fixUrlWithBase(it, baseUrl) } ?: return@mapNotNull null

            val epTitle = ep.selectFirst("div.name, span.ep-title, div.title")?.text()?.trim()
                ?: aTag.text().trim().ifEmpty { "Bölüm" }

            val epNumText = ep.selectFirst("div.episode, span.episode")?.text() ?: epHref
            val seasonNum = Regex("""(\d+)\.\s*Sezon|sezon-(\d+)""", RegexOption.IGNORE_CASE)
                .find(epNumText)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
            val episodeNum = Regex("""(\d+)\.\s*Bölüm|bolum-(\d+)""", RegexOption.IGNORE_CASE)
                .find(epNumText)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

            newEpisode(epHref) {
                this.name = epTitle
                this.season = seasonNum
                this.episode = episodeNum
            }
        }

        val isMovie = episodes.isEmpty() || targetUrl.contains("/film/")

        return if (isMovie) {
            newMovieLoadResponse(title, targetUrl, TvType.Movie, targetUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
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

        val iframeUrl = document.selectFirst(
            "div#vast_new iframe, div.series-player-container iframe, div.player-container iframe, iframe[src*='player'], iframe"
        )?.attr("src")?.takeIf { it.isNotEmpty() }?.let { fixUrlWithBase(it, baseUrl) } ?: return false

        var foundLinks = false

        try {
            val iframeResponse = app.get(
                iframeUrl,
                headers = defaultHeaders,
                referer = "$baseUrl/"
            ).text

            val subRegex = Regex("""\[([^\]]+)\](https?://[^\s"',]+\.vtt)""")
            subRegex.findAll(iframeResponse).forEach { match ->
                val subLang = match.groupValues[1]
                val subUrl = match.groupValues[2]
                subtitleCallback(SubtitleFile(subLang, subUrl))
            }

            val m3u8Regex = Regex("""(?:file|source)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            val m3u8Url = m3u8Regex.find(iframeResponse)?.groupValues?.get(1)?.replace("\\/", "/")

            if (!m3u8Url.isNullOrEmpty()) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = iframeUrl,
                    headers = mapOf("Origin" to baseUrl)
                ).forEach(callback)
                foundLinks = true
            }
        } catch (e: Exception) {
            // 2. aşamaya geç
        }

        if (!foundLinks) {
            try {
                val resolved = app.get(
                    iframeUrl,
                    referer = data,
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""\.m3u8(\?|$)"""),
                        additionalUrls = listOf(Regex("""\.vtt(\?|$)"""))
                    )
                )
                if (resolved.url.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        source = "$name (WebView)",
                        streamUrl = resolved.url,
                        referer = iframeUrl
                    ).forEach(callback)
                    foundLinks = true
                }
            } catch (e: Exception) {
                // 3. aşamaya geç
            }
        }

        if (!foundLinks) {
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        return true
    }

    private fun fixUrlWithBase(url: String, baseDomain: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$baseDomain/${url.trimStart('/')}"
    }
}
