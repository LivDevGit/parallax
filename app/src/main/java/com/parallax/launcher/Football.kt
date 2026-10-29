package com.parallax.launcher

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime

data class Team(val name: String, val logo: ImageBitmap?)

data class Match(
    val home: Team,
    val away: Team,
    val kickoff: Instant,
    val competition: String,
    val venue: String?,
    /** ESPN's game state: "pre", "in" (live) or "post". */
    val state: String,
    val homeScore: String?,
    val awayScore: String?,
    /** Match minute while live, e.g. "63'". */
    val clock: String?,
) {
    val isLive get() = state == "in"
}

/** FC Barcelona's next match in any competition, from ESPN's public (keyless) scoreboard API. */
object Football {

    private const val BARCELONA = 83
    private const val SCHEDULE =
        "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/$BARCELONA/schedule?fixture=true"

    private val logos = mutableMapOf<String, ImageBitmap?>()

    suspend fun nextBarcelonaMatch(): Match? = withContext(Dispatchers.IO) {
        runCatching {
            val events = fetchJson(SCHEDULE).getJSONArray("events")
            (0 until events.length())
                .map { toMatch(events.getJSONObject(it)) }
                .filter { it.state != "post" }
                .minByOrNull { it.kickoff }
        }.getOrNull()
    }

    private fun toMatch(event: JSONObject): Match {
        val competition = event.getJSONArray("competitions").getJSONObject(0)
        val competitors = competition.getJSONArray("competitors")
        val sides = (0 until competitors.length()).map { competitors.getJSONObject(it) }
        val home = sides.first { it.optString("homeAway") == "home" }
        val away = sides.first { it.optString("homeAway") == "away" }
        val status = competition.optJSONObject("status")
        return Match(
            home = toTeam(home),
            away = toTeam(away),
            // ESPN omits the seconds ("2026-10-31T20:00Z"), which Instant.parse rejects.
            kickoff = OffsetDateTime.parse(event.getString("date")).toInstant(),
            competition = event.optJSONObject("league")?.optString("name").orEmpty()
                .removePrefix("Spanish ")
                .replace("LALIGA", "LaLiga"),
            venue = competition.optJSONObject("venue")?.optString("fullName")?.ifBlank { null },
            state = status?.optJSONObject("type")?.optString("state") ?: "pre",
            homeScore = scoreOf(home),
            awayScore = scoreOf(away),
            clock = status?.optString("displayClock")?.ifBlank { null },
        )
    }

    // The score is an object on some endpoints and a plain string on others.
    private fun scoreOf(side: JSONObject): String? =
        side.optJSONObject("score")?.optString("displayValue")?.ifBlank { null }
            ?: side.optString("score").ifBlank { null }

    private fun toTeam(side: JSONObject): Team {
        val team = side.getJSONObject("team")
        val logoUrl = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")
        return Team(
            name = team.optString("shortDisplayName").ifBlank { team.optString("displayName") },
            logo = logoUrl?.let { url -> logos.getOrPut(url) { loadLogo(url) } },
        )
    }

    private fun loadLogo(url: String): ImageBitmap? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
            // ESPN serves 500px crests; a quarter of that is plenty for a widget.
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeStream(connection.inputStream, null, options)
                ?.also { it.prepareToDraw() }
                ?.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun fetchJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        return try {
            JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }
}
