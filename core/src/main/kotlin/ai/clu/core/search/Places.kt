package ai.clu.core.search

import kotlin.math.*

/** A place Clu can measure distances from: resolved on the device, never sent to a search provider. */
data class Place(val name: String, val lat: Double, val lon: Double, val country: String)

/**
 * The owner's location for questions like "which cyclone is heading towards us?", resolved offline: "lat, lon" or the
 * name of a major city (approximate coordinates; at the scale of storms and earthquakes, tens of kilometres don't
 * matter). A place that isn't known is reported as unknown rather than guessed.
 */
object Places {
    private val CITIES: List<Place> = listOf(
        // India
        Place("Mumbai", 19.08, 72.88, "India"), Place("Pune", 18.52, 73.86, "India"), Place("Delhi", 28.61, 77.21, "India"),
        Place("New Delhi", 28.61, 77.21, "India"), Place("Bengaluru", 12.97, 77.59, "India"), Place("Bangalore", 12.97, 77.59, "India"),
        Place("Chennai", 13.08, 80.27, "India"), Place("Kolkata", 22.57, 88.36, "India"), Place("Hyderabad", 17.39, 78.49, "India"),
        Place("Ahmedabad", 23.02, 72.57, "India"), Place("Surat", 21.17, 72.83, "India"), Place("Jaipur", 26.91, 75.79, "India"),
        Place("Lucknow", 26.85, 80.95, "India"), Place("Kanpur", 26.45, 80.33, "India"), Place("Nagpur", 21.15, 79.09, "India"),
        Place("Indore", 22.72, 75.86, "India"), Place("Bhopal", 23.26, 77.41, "India"), Place("Patna", 25.59, 85.14, "India"),
        Place("Visakhapatnam", 17.69, 83.22, "India"), Place("Vijayawada", 16.51, 80.65, "India"), Place("Bhubaneswar", 20.30, 85.82, "India"),
        Place("Puri", 19.81, 85.83, "India"), Place("Cuttack", 20.46, 85.88, "India"), Place("Kochi", 9.93, 76.27, "India"),
        Place("Thiruvananthapuram", 8.52, 76.94, "India"), Place("Kozhikode", 11.26, 75.78, "India"), Place("Mangaluru", 12.91, 74.86, "India"),
        Place("Goa", 15.50, 73.83, "India"), Place("Panaji", 15.50, 73.83, "India"), Place("Nashik", 20.00, 73.79, "India"),
        Place("Aurangabad", 19.88, 75.34, "India"), Place("Kolhapur", 16.70, 74.24, "India"), Place("Vadodara", 22.31, 73.18, "India"),
        Place("Rajkot", 22.30, 70.80, "India"), Place("Porbandar", 21.64, 69.61, "India"), Place("Dwarka", 22.24, 68.97, "India"),
        Place("Coimbatore", 11.02, 76.96, "India"), Place("Madurai", 9.93, 78.12, "India"), Place("Puducherry", 11.94, 79.81, "India"),
        Place("Guwahati", 26.14, 91.74, "India"), Place("Ranchi", 23.34, 85.31, "India"), Place("Raipur", 21.25, 81.63, "India"),
        Place("Chandigarh", 30.73, 76.78, "India"), Place("Amritsar", 31.63, 74.87, "India"), Place("Srinagar", 34.08, 74.80, "India"),
        Place("Dehradun", 30.32, 78.03, "India"), Place("Varanasi", 25.32, 82.97, "India"), Place("Agra", 27.18, 78.01, "India"),
        Place("Port Blair", 11.62, 92.73, "India"), Place("Kakinada", 16.99, 82.25, "India"), Place("Nellore", 14.44, 79.99, "India"),
        Place("Digha", 21.63, 87.51, "India"), Place("Haldia", 22.06, 88.07, "India"),
        // South and Southeast Asia
        Place("Dhaka", 23.81, 90.41, "Bangladesh"), Place("Chittagong", 22.36, 91.78, "Bangladesh"), Place("Cox's Bazar", 21.43, 92.01, "Bangladesh"),
        Place("Karachi", 24.86, 67.01, "Pakistan"), Place("Lahore", 31.55, 74.34, "Pakistan"), Place("Islamabad", 33.68, 73.05, "Pakistan"),
        Place("Colombo", 6.93, 79.86, "Sri Lanka"), Place("Kathmandu", 27.72, 85.32, "Nepal"), Place("Yangon", 16.84, 96.17, "Myanmar"),
        Place("Bangkok", 13.76, 100.50, "Thailand"), Place("Kuala Lumpur", 3.14, 101.69, "Malaysia"), Place("Singapore", 1.35, 103.82, "Singapore"),
        Place("Jakarta", -6.21, 106.85, "Indonesia"), Place("Manila", 14.60, 120.98, "Philippines"), Place("Hanoi", 21.03, 105.85, "Vietnam"),
        Place("Ho Chi Minh City", 10.82, 106.63, "Vietnam"), Place("Male", 4.18, 73.51, "Maldives"),
        // East Asia and Oceania
        Place("Hong Kong", 22.32, 114.17, "China"), Place("Shanghai", 31.23, 121.47, "China"), Place("Beijing", 39.90, 116.41, "China"),
        Place("Guangzhou", 23.13, 113.26, "China"), Place("Taipei", 25.03, 121.57, "Taiwan"), Place("Tokyo", 35.68, 139.69, "Japan"),
        Place("Osaka", 34.69, 135.50, "Japan"), Place("Okinawa", 26.21, 127.68, "Japan"), Place("Seoul", 37.57, 126.98, "South Korea"),
        Place("Sydney", -33.87, 151.21, "Australia"), Place("Brisbane", -27.47, 153.03, "Australia"), Place("Darwin", -12.46, 130.84, "Australia"),
        Place("Perth", -31.95, 115.86, "Australia"), Place("Cairns", -16.92, 145.77, "Australia"), Place("Auckland", -36.85, 174.76, "New Zealand"),
        Place("Suva", -18.14, 178.44, "Fiji"),
        // Middle East and Africa
        Place("Dubai", 25.20, 55.27, "United Arab Emirates"), Place("Abu Dhabi", 24.45, 54.38, "United Arab Emirates"), Place("Muscat", 23.59, 58.41, "Oman"),
        Place("Riyadh", 24.71, 46.68, "Saudi Arabia"), Place("Doha", 25.29, 51.53, "Qatar"), Place("Tehran", 35.69, 51.39, "Iran"),
        Place("Cairo", 30.04, 31.24, "Egypt"), Place("Nairobi", -1.29, 36.82, "Kenya"), Place("Lagos", 6.52, 3.38, "Nigeria"),
        Place("Johannesburg", -26.20, 28.05, "South Africa"), Place("Cape Town", -33.92, 18.42, "South Africa"), Place("Maputo", -25.97, 32.57, "Mozambique"),
        Place("Antananarivo", -18.88, 47.51, "Madagascar"), Place("Port Louis", -20.16, 57.50, "Mauritius"), Place("Saint-Denis", -20.88, 55.45, "Réunion"),
        // Europe
        Place("London", 51.51, -0.13, "United Kingdom"), Place("Paris", 48.86, 2.35, "France"), Place("Berlin", 52.52, 13.40, "Germany"),
        Place("Madrid", 40.42, -3.70, "Spain"), Place("Rome", 41.90, 12.50, "Italy"), Place("Amsterdam", 52.37, 4.90, "Netherlands"),
        Place("Istanbul", 41.01, 28.98, "Turkey"), Place("Athens", 37.98, 23.73, "Greece"), Place("Lisbon", 38.72, -9.14, "Portugal"),
        Place("Dublin", 53.35, -6.26, "Ireland"), Place("Moscow", 55.76, 37.62, "Russia"),
        // Americas
        Place("New York", 40.71, -74.01, "United States"), Place("Miami", 25.76, -80.19, "United States"), Place("Houston", 29.76, -95.37, "United States"),
        Place("New Orleans", 29.95, -90.07, "United States"), Place("Tampa", 27.95, -82.46, "United States"), Place("Washington", 38.91, -77.04, "United States"),
        Place("Los Angeles", 34.05, -118.24, "United States"), Place("San Francisco", 37.77, -122.42, "United States"), Place("Chicago", 41.88, -87.63, "United States"),
        Place("Honolulu", 21.31, -157.86, "United States"), Place("San Juan", 18.47, -66.11, "Puerto Rico"), Place("Havana", 23.11, -82.37, "Cuba"),
        Place("Toronto", 43.65, -79.38, "Canada"), Place("Vancouver", 49.28, -123.12, "Canada"), Place("Mexico City", 19.43, -99.13, "Mexico"),
        Place("Cancún", 21.16, -86.85, "Mexico"), Place("Acapulco", 16.85, -99.82, "Mexico"), Place("São Paulo", -23.55, -46.63, "Brazil"),
        Place("Rio de Janeiro", -22.91, -43.17, "Brazil"), Place("Buenos Aires", -34.60, -58.38, "Argentina"), Place("Lima", -12.05, -77.04, "Peru"),
        Place("Bogotá", 4.71, -74.07, "Colombia"), Place("Santiago", -33.45, -70.67, "Chile"),
    )

    private val LATLON = Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$")

    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
        .replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** "Pune", "pune, india", "18.52, 73.86" → a place; anything else → null (unknown, never guessed). */
    fun resolve(text: String?): Place? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        LATLON.find(t)?.let { m ->
            val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return Place("%.2f, %.2f".format(java.util.Locale.ROOT, lat, lon), lat, lon, "")
        }
        val n = norm(t).removePrefix("i am in ").removePrefix("i'm in ").removePrefix("we are in ").removePrefix("in ").removePrefix("near ")
        return CITIES.firstOrNull { norm(it.name) == n }
            ?: CITIES.firstOrNull { c -> n.split(' ').windowed(norm(c.name).split(' ').size, partialWindows = false).any { it.joinToString(" ") == norm(c.name) } }
    }

    fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0; val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    /** Compass direction (16 points, in words) from a to b. */
    fun direction(aLat: Double, aLon: Double, bLat: Double, bLon: Double): String {
        val y = sin(Math.toRadians(bLon - aLon)) * cos(Math.toRadians(bLat))
        val x = cos(Math.toRadians(aLat)) * sin(Math.toRadians(bLat)) - sin(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * cos(Math.toRadians(bLon - aLon))
        val deg = (Math.toDegrees(atan2(y, x)) + 360) % 360
        return listOf("north", "north-northeast", "northeast", "east-northeast", "east", "east-southeast", "southeast", "south-southeast",
            "south", "south-southwest", "southwest", "west-southwest", "west", "west-northwest", "northwest", "north-northwest")[((deg + 11.25) / 22.5).toInt() % 16]
    }
}
