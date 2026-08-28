package org.stypox.dicio.skills.carfu

import org.json.JSONObject
import org.stypox.dicio.io.session.WeatherWhen
import org.stypox.dicio.util.ConnectionUtils
import java.util.Locale

/**
 * Open-Meteo geocoding + forecast. Documented public API, no secret key.
 * https://open-meteo.com/en/docs
 */
object CarfuWeatherClient {
    const val TIMEOUT_MS = 8_000
    const val DEFAULT_CITY = "Hà Nội"
    const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
    const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

    data class Snapshot(
        val city: String,
        val tempC: Double?,
        val weatherCode: Int?,
        val precipitationMm: Double?,
        val tomorrowCode: Int?,
        val tomorrowTempC: Double?,
        val tomorrowRainChance: Int?,
    )

    fun geocodeUrl(city: String): String {
        val q = ConnectionUtils.urlEncode(city)
        return "$GEOCODE_URL?name=$q&count=1&language=vi&format=json"
    }

    fun forecastUrl(latitude: Double, longitude: Double): String {
        return FORECAST_URL +
            "?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,weather_code,precipitation" +
            "&daily=weather_code,temperature_2m_max,precipitation_probability_max" +
            "&timezone=Asia%2FHo_Chi_Minh&forecast_days=2"
    }

    fun parseGeocode(json: String): Triple<String, Double, Double>? {
        val root = JSONObject(json)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.getJSONObject(0)
        val name = first.optString("name").ifBlank { return null }
        val lat = first.optDouble("latitude", Double.NaN)
        val lon = first.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return Triple(name, lat, lon)
    }

    fun parseForecast(city: String, json: String): Snapshot? {
        val root = JSONObject(json)
        val current = root.optJSONObject("current")
        val daily = root.optJSONObject("daily")
        val dailyCodes = daily?.optJSONArray("weather_code")
        val dailyMax = daily?.optJSONArray("temperature_2m_max")
        val dailyRain = daily?.optJSONArray("precipitation_probability_max")
        return Snapshot(
            city = city,
            tempC = current?.optDouble("temperature_2m"),
            weatherCode = current?.optInt("weather_code"),
            precipitationMm = current?.optDouble("precipitation"),
            tomorrowCode = dailyCodes?.optInt(1),
            tomorrowTempC = dailyMax?.optDouble(1),
            tomorrowRainChance = dailyRain?.optInt(1),
        )
    }

    fun speak(snapshot: Snapshot, whenValue: WeatherWhen, rainAsk: Boolean): String {
        val city = snapshot.city
        if (whenValue == WeatherWhen.TOMORROW || rainAsk) {
            val desc = describe(snapshot.tomorrowCode)
            val rain = snapshot.tomorrowRainChance
            val temp = snapshot.tomorrowTempC?.let { "${it.toInt()} độ" }
            return if (rainAsk) {
                if ((rain ?: 0) >= 40) {
                    "Ngày mai $city có mưa, xác suất $rain phần trăm."
                } else {
                    "Ngày mai $city $desc, xác suất mưa ${rain ?: 0} phần trăm."
                }
            } else {
                buildString {
                    append("Ngày mai $city $desc")
                    if (temp != null) append(", $temp")
                    if (rain != null) append(", xác suất mưa $rain phần trăm")
                    append('.')
                }
            }
        }
        val desc = describe(snapshot.weatherCode)
        val temp = snapshot.tempC?.let { "${it.toInt()} độ" } ?: ""
        return listOf("Hôm nay $city", temp, desc)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("vi")) else it.toString() } +
            "."
    }

    fun describe(code: Int?): String = when (code) {
        0 -> "trời quang"
        1, 2 -> "trời nắng nhẹ"
        3 -> "trời nhiều mây"
        45, 48 -> "có sương mù"
        51, 53, 55, 56, 57 -> "mưa phùn"
        61, 63, 65, 66, 67, 80, 81, 82 -> "có mưa"
        71, 73, 75, 77, 85, 86 -> "có tuyết"
        95, 96, 99 -> "có dông"
        else -> "thời tiết bình thường"
    }
}
