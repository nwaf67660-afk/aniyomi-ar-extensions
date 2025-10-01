package eu.kanade.tachiyomi.animeextension.ar.cimaleek

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.cimaleek.interceptor.WebViewResolver
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class Cimaleek : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "سيما ليك"

    override val baseUrl = "https://m.cimaleek.to"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // WebView resolver لاستخراج روابط الفيديو/الترجمة من صفحات الـ embed التي تحتاج جافاسكربت
    private val webViewResolver by lazy { WebViewResolver(headers) }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("div.data .title").text()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        return anime
    }

    override fun popularAnimeNextPageSelector(): String =
        "div.pagination div.pagination-num i#nextpagination"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending/page/$page/", headers)

    override fun popularAnimeSelector(): String = "div.film_list-wrap div.item"

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode =
        throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        val url = response.request.url.toString()
        if (url.contains("movies")) {
            val episode = SEpisode.create().apply {
                name = "مشاهدة"
                setUrlWithoutDomain("$url/watch/")
            }
            episodes.add(episode)
        } else {
            document.select(seasonListSelector()).parallelCatchingFlatMapBlocking { sElement ->
                val seasonNum = sElement.select("span.se-a").text()
                val seasonUrl = sElement.attr("href")
                val seasonPage = client.newCall(GET(seasonUrl, headers)).execute().asJsoup()
                seasonPage.select(episodeListSelector()).map { eElement ->
                    val episodeNum = eElement.select("span.serie").text().substringAfter("(")
                        .substringBefore(")")
                    val episodeUrl = eElement.attr("href")
                    val finalNum = ("$seasonNum.$episodeNum").toFloat()
                    val episodeTitle = "الموسم ${seasonNum.toInt()} الحلقة ${episodeNum.toInt()}"
                    val episode = SEpisode.create().apply {
                        name = episodeTitle
                        episode_number = finalNum
                        setUrlWithoutDomain("$episodeUrl/watch/")
                    }
                    episodes.add(episode)
                }
            }
        }
        return episodes.sortedBy { it.episode_number }.reversed()
    }

    override fun episodeListSelector(): String = "div.season-a ul.episodios li.episodesList a"

    private fun seasonListSelector(): String = "div.season-a ul.seas-list li.sealist a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url =
            document.select("div.ani_detail-stage div.film-poster img").attr("src")
        anime.title =
            document.select("div.anisc-more-info div.item:contains(الاسم) span:nth-child(3)").text()
        anime.author =
            document.select("div.anisc-more-info div.item:contains(البلد) span:nth-child(3)").text()
        anime.genre =
            document.select("div.anisc-detail div.item-list a").joinToString(", ") { it.text() }
        anime.description = document.select("div.anisc-detail div.film-description div.text").text()
        anime.status = if (document.select("div.anisc-detail div.item-list").text()
                .contains("افلام")
        ) {
            SAnime.COMPLETED
        } else {
            SAnime.UNKNOWN
        }
        return anime
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListSelector(): String = "div#servers-content div.server-item div"

    /**
     * هنا قمنا بتعديل طريقة استخراج الفيديو:
     * - نجمع عناصر السيرفرات من الصفحة.
     * - نحاول كل سيرفر بالتتابع (لا نجمع كل السيرفرات في نفس الوقت).
     * - عند إيجاد قائمة فيديوهات صالحة من سيرفر نرجعها فوراً.
     * هذا يقلل احتمالية إرجاع نتائج فارغة ويضمن المرور على السيرفرات حتى نجد واحد شغال.
     */
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        // سكربت للحصول على نسخة الـ ver أو أي باراميتر يحتاجه طلب lalaplayer
        val script = document.selectFirst("script:containsData(dtAjax)")?.data()
        val version = script?.substringAfter("ver\":\"")?.substringBefore("\"") ?: ""
        // اجمع كل عناصر السيرفر من الدوم
        val serverElements = document.select(videoListSelector())

        // headers مرجعي لـ embed/referrer
        val refererHeaders = headers.newBuilder().add("Referer", "$baseUrl/").build()

        // نجرب كل سيرفر بالتسلسل، نرجع أول نتيجة صالحة
        for (element in serverElements) {
            try {
                val videos = tryExtractFromServer(element, version, refererHeaders)
                if (videos.isNotEmpty()) {
                    return videos.sortedWith(
                        compareBy { it.quality.contains(preferredQuality()) },
                    ).reversed()
                }
            } catch (e: Exception) {
                // لو فشل السيرفر نتابع للسيرفر التالي بدل رمي الخطأ
                logger.warn("Cimaleek", "Server extraction failed, trying next server", e)
            }
        }

        // لو ما وجدنا شيء من أي سيرفر نرجع قائمة فارغة
        return emptyList()
    }

    private fun preferredQuality(): String {
        return preferences.getString("preferred_quality", "1080") ?: "1080"
    }

    /**
     * تحاول استخراج الفيديو من عنصر سيرفر مفرد:
     * - تبني طلب wp-json/lalaplayer/v2
     * - تطلب الـ iframe/embed ومن ثم تمرره للـ WebViewResolver
     * - تستخرج روابط mp4/m3u8 أو الترجمات
     */
    private fun tryExtractFromServer(element: Element, version: String, refererHeaders: Headers): List<Video> {
        // بناء رابط الـ API المستخدم لطلب الـ frame (كما في الموقع)
        val videoUrl = "$baseUrl/wp-json/lalaplayer/v2/".toHttpUrl().newBuilder().apply {
            addQueryParameter("p", element.attr("data-post"))
            addQueryParameter("t", element.attr("data-type"))
            addQueryParameter("n", element.attr("data-nume"))
            if (version.isNotBlank()) addQueryParameter("ver", version)
            addQueryParameter("rand", generateRandomString())
        }.build().toString()

        // نحاول جلب محتوى الـ frame. بعض الأحيان الـ frame يعيد JSON أو HTML.
        val videoFrameBody: String = try {
            client.newCall(GET(videoUrl, refererHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Failed to fetch video frame: ${resp.code}")
                }
                resp.body?.string() ?: ""
            }
        } catch (e: Exception) {
            throw IOException("Error requesting videoFrame: ${e.message}", e)
        }

        // نحاول استخراج embed_url من الـ response
        val embedUrl = extractEmbedUrl(videoFrameBody) ?: videoFrameBody.trim()

        if (embedUrl.isBlank()) {
            return emptyList()
        }

        // بعض الـ embedUrl قد يكون نسقًا مشابهاً لـ /b5/... أو رابط يحول لصفحة ويب
        val resolved = resolveEmbedUrl(embedUrl, refererHeaders)
        if (resolved.isBlank()) return emptyList()

        // إذا كان الرابط مباشرًا إلى mp4 أو m3u8 نتعامل معه مباشرة
        if (resolved.contains(".mp4")) {
            val v = Video(resolved, element.text(), resolved, headers = refererHeaders)
            return listOf(v)
        }

        if (resolved.contains(".m3u8")) {
            // نحاول استخراج قائمة اللعب من HLS
            val subtitleList = emptyList<Track>()
            return try {
                playlistUtils.extractFromHls(resolved, videoNameGen = { "${element.text()}: $it" }, subtitleList = subtitleList)
            } catch (e: Exception) {
                emptyList()
            }
        }

        // خلاف ذلك — قد يحتاج WebView resolution (جافاسكربت)
        val webViewResult = webViewResolver.getUrl(resolved, refererHeaders)
        val finalUrl = webViewResult.url
        val subtitle = webViewResult.subtitle

        if (finalUrl.isBlank()) return emptyList()

        // لو وجدنا mp4
        if (finalUrl.contains(".mp4")) {
            val v = Video(finalUrl, element.text(), finalUrl, headers = refererHeaders)
            return listOf(v)
        }

        // لو وجدنا m3u8
        if (finalUrl.contains(".m3u8")) {
            val subtitleList = if (subtitle.isNotBlank()) {
                listOf(Track(subtitle, "Arabic"))
            } else {
                emptyList()
            }
            return try {
                playlistUtils.extractFromHls(finalUrl, videoNameGen = { "${element.text()}: $it" }, subtitleList = subtitleList)
            } catch (e: Exception) {
                emptyList()
            }
        }

        return emptyList()
    }

    // يستخرج embed_url من نص الـ response الممكن أن يكون JSON أو HTML
    private fun extractEmbedUrl(body: String): String? {
        // غالباً يكون embed_url في JSON على شكل "embed_url":"..."
        val jsonCandidate = body.substringAfter("\"embed_url\":\"", missingDelimiterValue = "")
            .substringBefore("\"", missingDelimiterValue = "")
        if (jsonCandidate.isNotEmpty()) {
            // قد تحتوي على escape sequences
            return jsonCandidate.replace("\\/", "/")
        }

        // ممكن يكون في كود HTML <iframe src="...">
        val iframeCandidate = body.substringAfter("<iframe", missingDelimiterValue = "")
            .substringAfter("src=\"", missingDelimiterValue = "")
            .substringBefore("\"", missingDelimiterValue = "")
        if (iframeCandidate.isNotEmpty()) return iframeCandidate

        // لا شيء
        return null
    }

    // نحاول حل الـ embed URL — نتبع redirects إن احتاجنا، ونبسط بعض الحالات
    private fun resolveEmbedUrl(embedUrl: String, refererHeaders: Headers): String {
        // روابط نسبية (تبدأ بـ /) — نحولها لرابط كامل
        val resolved = try {
            if (embedUrl.startsWith("//")) {
                "https:$embedUrl"
            } else if (embedUrl.startsWith("/")) {
                baseUrl.toHttpUrl().newBuilder().encodedPath(embedUrl).build().toString()
            } else {
                embedUrl
            }
        } catch (e: Exception) {
            embedUrl
        }

        // نجرب عمل طلب GET ونتحقق إذا أعاد redirect نهائي
        return try {
            client.newCall(GET(resolved, refererHeaders)).execute().use { resp ->
                // إذا حصل redirect (okhttp يتبع عادة) نستخدم العنوان النهائي
                val final = resp.request.url.toString()
                final
            }
        } catch (e: Exception) {
            // لو فشل الطلب نرجع النص الأصلي ليجربه webViewResolver أو يستعمل كناتج مباشر
            resolved
        }
    }

    private fun generateRandomString(): String {
        val characters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val result = StringBuilder(16)
        for (i in 0 until 16) {
            val randomIndex = (Math.random() * characters.length).toInt()
            result.append(characters[randomIndex])
        }
        return result.toString()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.find { it is SectionFilter } as SectionFilter
        val categoryFilter = filterList.find { it is CategoryFilter } as CategoryFilter
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = baseUrl.toHttpUrl().newBuilder()
            if (sectionFilter.state != 0) {
                url.addPathSegment("category")
                url.addPathSegment(sectionFilter.toUriPart())
            } else if (categoryFilter.state != 0) {
                url.addPathSegment("genre")
                url.addPathSegment(genreFilter.toUriPart().lowercase())
            } else {
                throw Exception("من فضلك اختر قسم او نوع")
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            if (categoryFilter.state != 0) {
                url.addQueryParameter("type", categoryFilter.toUriPart())
            }
            GET(url.toString(), headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    // ============================ Filters =============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("هذا القسم يعمل لو كان البحث فارع"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("الفلتره تعمل فقط لو كان اقسام الموقع على 'اختر'"),
        CategoryFilter(),
        GenreFilter(),
    )

    private class SectionFilter : PairFilter(
        "اقسام الموقع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام اجنبي", "aflam-online"),
            Pair("افلام نتفليكس", "netflix-movies"),
            Pair("افلام هندي", "indian-movies"),
            Pair("افلام اسيوي", "asian-aflam"),
            Pair("افلام كرتون", "cartoon-movies"),
            Pair("افلام انمي", "anime-movies"),
            Pair("مسلسلات اجنبي", "english-series"),
            Pair("مسلسلات نتفليكس", "netflix-series"),
            Pair("مسلسلات اسيوي", "asian-series"),
            Pair("مسلسلات كرتون", "anime-series"),
            Pair("مسلسلات انمي", "netflix-anime"),
        ),
    )

    private class CategoryFilter : PairFilter(
        "النوع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام", "movies"),
            Pair("مسلسلات", "series"),
        ),
    )

    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "Action",
            "Adventure",
            "Animation",
            "Western",
            "Documentary",
            "Fantasy",
            "Science-fiction",
            "Romance",
            "Comedy",
            "Family",
            "Drama",
            "Thriller",
            "Crime",
            "Horror",
        ).sortedArray(),
    )

    open class SingleFilter(displayName: String, private val vals: Array<String>) :
        AnimeFilter.Select<String>(displayName, vals) {
        fun toUriPart() = vals[state]
    }

    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/recent/page/$page/", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    // =============================== Settings ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
