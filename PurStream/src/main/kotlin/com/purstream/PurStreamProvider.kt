package com.purstream

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class PurStreamProvider : MainAPI() {
    override var name = "PurStream"
    override var mainUrl = "https://purstream.tech"
    override var lang = "fr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiUrl = "https://api.purstream.tech/api/v1"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
    private val apiHeaders = mapOf("User-Agent" to userAgent)
    private val invisible = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]")
    private val seRegex = Regex("/S(\\d+)/E(\\d+)", RegexOption.IGNORE_CASE)

    // ---------- MAIN PAGE ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) return newHomePageResponse(emptyList(), false)
        val lists = mutableListOf<HomePageList>()

        try {
            val r = app.get("$apiUrl/last-released-movies/12", headers = apiHeaders)
                .parsedSafe<ApiResponse<List<MediaItem>>>()
            val items = r?.data?.items
            if (!items.isNullOrEmpty()) {
                lists.add(HomePageList("Derniers ajouts", items.map { it.toSearch() }, false))
            }
        } catch (_: Exception) {
        }

        try {
            val r = app.get("$apiUrl/carousels/home", headers = apiHeaders)
                .parsedSafe<ApiResponse<List<Carousel>>>()
            r?.data?.items.orEmpty().forEach { c ->
                val movies = c.movies?.items.orEmpty().map { it.toSearch() }
                if (movies.isNotEmpty()) {
                    lists.add(HomePageList(c.title ?: "PurStream", movies, false))
                }
            }
        } catch (_: Exception) {
        }

        return newHomePageResponse(lists, false)
    }

    // ---------- SEARCH ----------
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return try {
            val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val text = app.get("$apiUrl/search-bar/search/$q", headers = apiHeaders).text
            val items = tryParseJson<ApiResponse<ApiSearchItems>>(text)?.data?.items
            val list: List<MediaItem> = if (items != null) {
                items.movies.orEmpty() + items.items.orEmpty()
            } else {
                tryParseJson<ApiResponse<List<MediaItem>>>(text)?.data?.items.orEmpty()
            }
            list.map { it.toSearch() }.toNewSearchResponseList()
        } catch (_: Exception) {
            null
        }
    }

    private fun clean(s: String?): String = (s ?: "PurStream").replace(invisible, "")

    private fun MediaItem.toSearch(): SearchResponse {
        val poster = largePosterPath ?: smallPosterPath
        val y = releaseDate?.take(4)?.toIntOrNull()
        return if (type == "tv") {
            newTvSeriesSearchResponse(
                clean(title), id.toString(),
                if (isAnime == true) TvType.Anime else TvType.TvSeries
            ) {
                this.posterUrl = poster
                this.year = y
            }
        } else {
            newMovieSearchResponse(
                clean(title), id.toString(),
                if (isAnime == true) TvType.Anime else TvType.Movie
            ) {
                this.posterUrl = poster
                this.year = y
            }
        }
    }

    // ---------- LOAD ----------
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val s = app.get("$apiUrl/media/$url/sheet", headers = apiHeaders)
                .parsedSafe<ApiResponse<SheetItem>>()?.data?.items ?: return null

            val type = when {
                s.isAnime == true -> TvType.Anime
                s.type == "tv" -> TvType.TvSeries
                else -> TvType.Movie
            }
            val title = clean(s.title)
            val y = s.releaseDate?.take(4)?.toIntOrNull()
            val tagList = s.categories?.mapNotNull { it.name }
            val dur = s.runtime?.minutes
            val score = s.note?.tmdb?.average

            if (s.type != "tv") {
                val link = s.urls?.firstOrNull()?.url ?: ""
                newMovieLoadResponse(title, url, type, link) {
                    this.plot = s.overview
                    this.posterUrl = s.posters?.large
                    this.backgroundPosterUrl = s.posters?.wallpaper
                    this.tags = tagList
                    this.year = y
                    this.duration = dur
                    if (score != null) this.score = Score.from10(score)
                }
            } else {
                val grouped = s.urls.orEmpty()
                    .mapNotNull { v ->
                        val m = seRegex.find(v.url ?: "") ?: return@mapNotNull null
                        val se = m.groupValues[1].toInt() to m.groupValues[2].toInt()
                        se to SourceEntry(v.name, v.url ?: "")
                    }
                    .groupBy({ it.first }, { it.second })

                val episodes = grouped.entries
                    .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                    .map { (se, versions) ->
                        newEpisode(versions.toJson()) {
                            this.season = se.first
                            this.episode = se.second
                            this.name = "Épisode ${se.second}"
                        }
                    }

                newTvSeriesLoadResponse(title, url, type, episodes) {
                    this.plot = s.overview
                    this.posterUrl = s.posters?.large
                    this.backgroundPosterUrl = s.posters?.wallpaper
                    this.tags = tagList
                    this.year = y
                    this.duration = dur
                    if (score != null) this.score = Score.from10(score)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- LINKS ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources: List<SourceEntry> =
            if (data.trimStart().startsWith("[")) {
                tryParseJson<List<SourceEntry>>(data).orEmpty()
            } else {
                listOf(SourceEntry(null, data))
            }

        val hdrs = mapOf("Origin" to mainUrl, "User-Agent" to userAgent)

        for (s in sources) {
            val link = s.url ?: continue
            if (link.isBlank()) continue

            callback(
                newExtractorLink(name, s.name ?: name, link, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.headers = hdrs
                    this.quality = Qualities.P720.value
                }
            )

            val base = link.substringBefore("master.m3u8")
            subtitleCallback(SubtitleFile("Français", base + "subs_fre.vtt"))
            subtitleCallback(SubtitleFile("English", base + "subs_eng.vtt"))
        }
        return true
    }
}