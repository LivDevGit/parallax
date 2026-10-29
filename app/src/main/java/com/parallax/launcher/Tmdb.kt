package com.parallax.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A Top Shelf image, plus a small blurred copy that glass widgets show through. */
class Backdrop(val image: ImageBitmap, val blurred: ImageBitmap)

/** A show or film to feature in the Top Shelf while its streaming app is focused. */
data class ShelfItem(
    val title: String,
    val overview: String,
    val year: String?,
    val isMovie: Boolean,
    val backdropUrl: String,
)

/**
 * Popular titles per streaming service in the user's region, from TMDB (themoviedb.org).
 * Android doesn't let a third-party launcher read other apps' content rows, so this stands in for real Top Shelf data.
 */
object Tmdb {

    private const val API = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p/w1280"
    private const val REGION = "RO"
    // A wide pool, shuffled, so the shelf rarely repeats; refreshed a few times a day as popularity shifts.
    private const val MAX_ITEMS = 30
    private const val PAGES = 2
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L

    /**
     * How to find a service on TMDB: by provider name in the user's region (several names cover rebrands),
     * or by a fixed provider id and region for services whose local catalogue TMDB doesn't track.
     */
    private class Provider(val displayName: String, val names: List<String>, val fixedId: Int? = null, val region: String = REGION)

    private val providersByPackage = mapOf(
        "com.netflix.ninja" to Provider("Netflix", listOf("Netflix")),
        "com.amazon.amazonvideo.livingroom" to Provider("Prime Video", listOf("Amazon Prime Video")),
        "com.disney.disneyplus" to Provider("Disney+", listOf("Disney Plus")),
        "com.wbd.stream" to Provider("HBO Max", listOf("HBO Max", "Max")),
        "com.skyshowtime.skyshowtime.google" to Provider("SkyShowtime", listOf("SkyShowtime")),
        // TMDB has no Romanian data for Apple TV+, but its catalogue is the same everywhere.
        "com.apple.atve.androidtv.appletv" to Provider("Apple TV", emptyList(), fixedId = 350, region = "US"),
    )

    private val token = BuildConfig.TMDB_TOKEN
    private val mutex = Mutex()
    private var providerIds: Map<String, Int>? = null
    private val itemsByPackage = mutableMapOf<String, List<ShelfItem>>()
    private val loadedAt = mutableMapOf<String, Long>()
    // Where each service's rotation is up to, so coming back to an app shows the next title rather than the first.
    private val cursors = mutableMapOf<String, Int>()
    // Room for the current and next backdrop of every service (~1.8 MB each at 1280x720 RGB_565).
    private val backdrops = LruCache<String, Backdrop>(16)

    fun supports(packageName: String): Boolean = token.isNotBlank() && packageName in providersByPackage

    fun providerName(packageName: String): String? = providersByPackage[packageName]?.displayName

    suspend fun itemsFor(packageName: String): List<ShelfItem> = withContext(Dispatchers.IO) {
        if (!supports(packageName)) return@withContext emptyList()
        mutex.withLock {
            val cached = itemsByPackage[packageName]
            val fresh = System.currentTimeMillis() - (loadedAt[packageName] ?: 0) < REFRESH_MS
            if (cached != null && fresh) return@withLock cached
            // Failures aren't cached, so the next focus tries again (e.g. the network wasn't up yet).
            runCatching {
                val provider = providersByPackage.getValue(packageName)
                val id = provider.fixedId ?: run {
                    val ids = providerIds ?: loadProviderIds().also { providerIds = it }
                    provider.names.firstNotNullOfOrNull { ids[it.lowercase()] }
                } ?: return@runCatching emptyList()
                val shows = (1..PAGES).flatMap { discover("tv", id, provider.region, it) }
                val movies = (1..PAGES).flatMap { discover("movie", id, provider.region, it) }
                interleave(shows, movies).distinctBy { it.backdropUrl }.take(MAX_ITEMS).shuffled()
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.also {
                itemsByPackage[packageName] = it
                loadedAt[packageName] = System.currentTimeMillis()
            } ?: cached ?: emptyList()
        }
    }

    fun cachedItems(packageName: String): List<ShelfItem>? = itemsByPackage[packageName]

    fun cachedBackdrop(url: String): Backdrop? = backdrops.get(url)

    /** Downloads the backdrops this service will show next, so focusing it is instant. */
    suspend fun preload(packageName: String, items: List<ShelfItem>) {
        val cursor = cursors[packageName] ?: 0
        for (offset in 0..1) backdrop(items[(cursor + offset) % items.size].backdropUrl)
    }

    /** The title this service's Top Shelf should show next. */
    fun current(packageName: String, items: List<ShelfItem>): ShelfItem = items[(cursors[packageName] ?: 0) % items.size]

    fun advance(packageName: String) {
        cursors[packageName] = (cursors[packageName] ?: 0) + 1
    }

    suspend fun backdrop(url: String): Backdrop? {
        backdrops.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 15_000
                try {
                    // RGB_565 halves memory; backdrops have no transparency and sit under a gradient anyway.
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    BitmapFactory.decodeStream(connection.inputStream, null, options)?.let { image ->
                        val blurred = Precomputed.blur(Bitmap.createScaledBitmap(image, 96, 54, true), radius = 3)
                        // Upload to the GPU in the background now, rather than mid-animation on first draw.
                        image.prepareToDraw()
                        blurred.prepareToDraw()
                        Backdrop(image.asImageBitmap(), blurred.asImageBitmap())
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()?.also { backdrops.put(url, it) }
        }
    }

    private fun loadProviderIds(): Map<String, Int> = buildMap {
        for (kind in listOf("tv", "movie")) {
            val results = fetchJson("$API/watch/providers/$kind?watch_region=$REGION").getJSONArray("results")
            for (i in 0 until results.length()) {
                val provider = results.getJSONObject(i)
                put(provider.getString("provider_name").lowercase(), provider.getInt("provider_id"))
            }
        }
    }

    private fun discover(kind: String, providerId: Int, region: String, page: Int): List<ShelfItem> {
        val results = fetchJson(
            "$API/discover/$kind?watch_region=$region&with_watch_providers=$providerId" +
                "&sort_by=popularity.desc&include_adult=false&language=en-US&page=$page",
        ).getJSONArray("results")
        val movie = kind == "movie"
        return (0 until results.length()).map { results.getJSONObject(it) }
            .filter { !it.isNull("backdrop_path") && it.optString("overview").isNotBlank() }
            .map {
                ShelfItem(
                    title = it.optString(if (movie) "title" else "name"),
                    overview = it.optString("overview"),
                    year = it.optString(if (movie) "release_date" else "first_air_date").take(4).ifBlank { null },
                    isMovie = movie,
                    backdropUrl = IMAGES + it.getString("backdrop_path"),
                )
            }
    }

    private fun interleave(a: List<ShelfItem>, b: List<ShelfItem>): List<ShelfItem> = buildList {
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let(::add)
            b.getOrNull(i)?.let(::add)
        }
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        return try {
            JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }
}
