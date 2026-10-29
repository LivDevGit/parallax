package com.parallax.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class WeatherInfo(
    val city: String,
    val temperature: Int,
    val high: Int,
    val low: Int,
    val symbol: String,
)

/** Today's weather from Open-Meteo (free, no API key), for the location the TV's IP address is in. */
object Weather {

    private var location: Triple<Double, Double, String>? = null

    suspend fun load(): WeatherInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val (lat, lon, city) = location ?: locate().also { location = it }
            val json = get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto",
            )
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            WeatherInfo(
                city = city,
                temperature = current.getDouble("temperature_2m").roundToInt(),
                high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                symbol = symbolFor(current.getInt("weather_code"), current.getInt("is_day") == 1),
            )
        }.getOrNull()
    }

    private fun locate(): Triple<Double, Double, String> {
        val json = get("https://ipwho.is/?fields=latitude,longitude,city")
        return Triple(json.getDouble("latitude"), json.getDouble("longitude"), json.getString("city"))
    }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        return try {
            JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    // WMO weather interpretation codes, as used by Open-Meteo.
    private fun symbolFor(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1, 2 -> if (day) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}
