package ai.clu.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A local stand-in for Open-Meteo in tests (this environment cannot reach api.open-meteo.com): answers
 * `/v1/forecast` in Open-Meteo's response format with fixed values, so the data path (provider → CLU's weather
 * service → the experience on screen) can be checked end to end. It is test equipment, never used by CLU itself.
 */
object OpenMeteoStandIn {
    /** Open-Meteo's response shape for the fields CLU asks for (values fixed so the page can be checked against them). */
    fun forecast(): String {
        val hours = (0 until 24).joinToString(",") { "\"2026-11-04T%02d:00\"".format(it) }
        return """{"latitude":51.5,"longitude":-0.12,"timezone":"Europe/London",
          "current":{"time":"2026-11-04T12:00","temperature_2m":13.4,"apparent_temperature":11.9,"relative_humidity_2m":71,"precipitation":0.2,"weather_code":3,"wind_speed_10m":18.7,"wind_direction_10m":240,"wind_gusts_10m":33.1},
          "hourly":{"time":[$hours],"temperature_2m":[${(0 until 24).joinToString(",") { "${10 + it % 6}.5" }}],"precipitation_probability":[${(0 until 24).joinToString(",") { "${it * 3}" }}],"weather_code":[${(0 until 24).joinToString(",") { "3" }}]},
          "daily":{"time":["2026-11-04","2026-11-05","2026-11-06","2026-11-07","2026-11-08"],"weather_code":[3,61,2,0,80],"temperature_2m_max":[14.1,12.0,11.5,13.2,10.9],
            "temperature_2m_min":[8.2,7.5,6.1,5.9,6.4],"precipitation_probability_max":[20,80,10,0,60],
            "sunrise":["2026-11-04T07:00","2026-11-05T07:02","2026-11-06T07:04","2026-11-07T07:06","2026-11-08T07:08"],"sunset":["2026-11-04T16:30","2026-11-05T16:28","2026-11-06T16:27","2026-11-07T16:25","2026-11-08T16:24"]}}"""
    }


    fun start(): HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/v1/forecast") { ex -> val b = forecast().toByteArray(); ex.responseHeaders.set("Content-Type", "application/json"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        start()
    }
}
