"""What the conversation is about, for the server engine: the same explicit conversation state, follow-up resolution,
contextual re-hearing of misheard words, and official hazard/weather answers as the on-device agent
(core/src/main/kotlin/ai/clu/core/agent/ConversationContext.kt, search/Places.kt, search/Hazards.kt, HazardPolicy in
AgentController.kt). Standard library only (aiohttp only for the real network fetchers). Temporary conversational
context, never memory; nothing personal is sent anywhere (GDACS gets no location; Open-Meteo only a city's coordinates).
"""
from __future__ import annotations

import json
import math
import re
import time
import unicodedata
from dataclasses import asdict, dataclass, field
from decimal import ROUND_HALF_UP, Decimal

import clu_controller as cc

I = re.IGNORECASE


def _fmt(x: float, places: int = 0) -> str:
    """Java's String.format rounding (half up), so both engines print the same numbers."""
    return str(Decimal(repr(x)).quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP))


def now_ms() -> int:
    return int(time.time() * 1000)


# ------------------------------------------------------------------------------------------------ places (Places.kt)
@dataclass
class Place:
    name: str
    lat: float
    lon: float
    country: str


class Places:
    CITIES = [
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
    Place("Digha", 21.63, 87.51, "India"), Place("Haldia", 22.06, 88.07, "India"), Place("Dhaka", 23.81, 90.41, "Bangladesh"),
    Place("Chittagong", 22.36, 91.78, "Bangladesh"), Place("Cox's Bazar", 21.43, 92.01, "Bangladesh"), Place("Karachi", 24.86, 67.01, "Pakistan"),
    Place("Lahore", 31.55, 74.34, "Pakistan"), Place("Islamabad", 33.68, 73.05, "Pakistan"), Place("Colombo", 6.93, 79.86, "Sri Lanka"),
    Place("Kathmandu", 27.72, 85.32, "Nepal"), Place("Yangon", 16.84, 96.17, "Myanmar"), Place("Bangkok", 13.76, 100.50, "Thailand"),
    Place("Kuala Lumpur", 3.14, 101.69, "Malaysia"), Place("Singapore", 1.35, 103.82, "Singapore"), Place("Jakarta", -6.21, 106.85, "Indonesia"),
    Place("Manila", 14.60, 120.98, "Philippines"), Place("Hanoi", 21.03, 105.85, "Vietnam"), Place("Ho Chi Minh City", 10.82, 106.63, "Vietnam"),
    Place("Male", 4.18, 73.51, "Maldives"), Place("Hong Kong", 22.32, 114.17, "China"), Place("Shanghai", 31.23, 121.47, "China"),
    Place("Beijing", 39.90, 116.41, "China"), Place("Guangzhou", 23.13, 113.26, "China"), Place("Taipei", 25.03, 121.57, "Taiwan"),
    Place("Tokyo", 35.68, 139.69, "Japan"), Place("Osaka", 34.69, 135.50, "Japan"), Place("Okinawa", 26.21, 127.68, "Japan"),
    Place("Seoul", 37.57, 126.98, "South Korea"), Place("Sydney", -33.87, 151.21, "Australia"), Place("Brisbane", -27.47, 153.03, "Australia"),
    Place("Darwin", -12.46, 130.84, "Australia"), Place("Perth", -31.95, 115.86, "Australia"), Place("Cairns", -16.92, 145.77, "Australia"),
    Place("Auckland", -36.85, 174.76, "New Zealand"), Place("Suva", -18.14, 178.44, "Fiji"), Place("Dubai", 25.20, 55.27, "United Arab Emirates"),
    Place("Abu Dhabi", 24.45, 54.38, "United Arab Emirates"), Place("Muscat", 23.59, 58.41, "Oman"), Place("Riyadh", 24.71, 46.68, "Saudi Arabia"),
    Place("Doha", 25.29, 51.53, "Qatar"), Place("Tehran", 35.69, 51.39, "Iran"), Place("Cairo", 30.04, 31.24, "Egypt"),
    Place("Nairobi", -1.29, 36.82, "Kenya"), Place("Lagos", 6.52, 3.38, "Nigeria"), Place("Johannesburg", -26.20, 28.05, "South Africa"),
    Place("Cape Town", -33.92, 18.42, "South Africa"), Place("Maputo", -25.97, 32.57, "Mozambique"),
    Place("Antananarivo", -18.88, 47.51, "Madagascar"), Place("Port Louis", -20.16, 57.50, "Mauritius"),
    Place("Saint-Denis", -20.88, 55.45, "Réunion"), Place("London", 51.51, -0.13, "United Kingdom"), Place("Paris", 48.86, 2.35, "France"),
    Place("Berlin", 52.52, 13.40, "Germany"), Place("Madrid", 40.42, -3.70, "Spain"), Place("Rome", 41.90, 12.50, "Italy"),
    Place("Amsterdam", 52.37, 4.90, "Netherlands"), Place("Istanbul", 41.01, 28.98, "Turkey"), Place("Athens", 37.98, 23.73, "Greece"),
    Place("Lisbon", 38.72, -9.14, "Portugal"), Place("Dublin", 53.35, -6.26, "Ireland"), Place("Moscow", 55.76, 37.62, "Russia"),
    Place("New York", 40.71, -74.01, "United States"), Place("Miami", 25.76, -80.19, "United States"),
    Place("Houston", 29.76, -95.37, "United States"), Place("New Orleans", 29.95, -90.07, "United States"),
    Place("Tampa", 27.95, -82.46, "United States"), Place("Washington", 38.91, -77.04, "United States"),
    Place("Los Angeles", 34.05, -118.24, "United States"), Place("San Francisco", 37.77, -122.42, "United States"),
    Place("Chicago", 41.88, -87.63, "United States"), Place("Honolulu", 21.31, -157.86, "United States"),
    Place("San Juan", 18.47, -66.11, "Puerto Rico"), Place("Havana", 23.11, -82.37, "Cuba"), Place("Toronto", 43.65, -79.38, "Canada"),
    Place("Vancouver", 49.28, -123.12, "Canada"), Place("Mexico City", 19.43, -99.13, "Mexico"), Place("Cancún", 21.16, -86.85, "Mexico"),
    Place("Acapulco", 16.85, -99.82, "Mexico"), Place("São Paulo", -23.55, -46.63, "Brazil"), Place("Rio de Janeiro", -22.91, -43.17, "Brazil"),
    Place("Buenos Aires", -34.60, -58.38, "Argentina"), Place("Lima", -12.05, -77.04, "Peru"), Place("Bogotá", 4.71, -74.07, "Colombia"),
    Place("Santiago", -33.45, -70.67, "Chile"),
    ]
    LATLON = re.compile(r"^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,; ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$")

    @staticmethod
    def _norm(s: str) -> str:
        s = "".join(c for c in unicodedata.normalize("NFD", s) if not unicodedata.combining(c)).lower()
        s = re.sub(r"[^a-z0-9' ]", " ", s)
        return re.sub(r"\s+", " ", s).strip()

    @classmethod
    def resolve(cls, text: str | None) -> Place | None:
        """"Pune", "pune, india", "18.52, 73.86" -> a place; anything else -> None (unknown, never guessed)."""
        t = (text or "").strip()
        if not t:
            return None
        m = cls.LATLON.search(t)
        if m:
            lat, lon = float(m.group(1)), float(m.group(2))
            if -90 <= lat <= 90 and -180 <= lon <= 180:
                return Place(f"{_fmt(lat, 2)}, {_fmt(lon, 2)}", lat, lon, "")
        n = cls._norm(t)
        for p in ("i am in ", "i'm in ", "we are in ", "in ", "near "):
            n = n.removeprefix(p)
        for c in cls.CITIES:
            if cls._norm(c.name) == n:
                return c
        words = n.split(" ")
        for c in cls.CITIES:
            cn = cls._norm(c.name).split(" ")
            if any(words[i:i + len(cn)] == cn for i in range(0, len(words) - len(cn) + 1)):
                return c
        return None

    @staticmethod
    def distance_km(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> float:
        r = 6371.0
        d_lat, d_lon = math.radians(b_lat - a_lat), math.radians(b_lon - a_lon)
        h = math.sin(d_lat / 2) ** 2 + math.cos(math.radians(a_lat)) * math.cos(math.radians(b_lat)) * math.sin(d_lon / 2) ** 2
        return 2 * r * math.asin(math.sqrt(h))

    @staticmethod
    def direction(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> str:
        y = math.sin(math.radians(b_lon - a_lon)) * math.cos(math.radians(b_lat))
        x = (math.cos(math.radians(a_lat)) * math.sin(math.radians(b_lat))
             - math.sin(math.radians(a_lat)) * math.cos(math.radians(b_lat)) * math.cos(math.radians(b_lon - a_lon)))
        deg = (math.degrees(math.atan2(y, x)) + 360) % 360
        return ["north", "north-northeast", "northeast", "east-northeast", "east", "east-southeast", "southeast", "south-southeast",
                "south", "south-southwest", "southwest", "west-southwest", "west", "west-northwest", "northwest", "north-northwest"][int((deg + 11.25) / 22.5) % 16]


# ------------------------------------------------------------------------------------------------ hazards (Hazards.kt)
@dataclass
class HazardEvent:
    type: str
    name: str
    lat: float
    lon: float
    alert: str = ""
    severity: str = ""
    countries: str = ""
    source: str = ""
    updated: str = ""
    url: str = ""


@dataclass
class HazardCheck:
    type: str
    events: list[HazardEvent]
    checked_at: int
    provider: str = "GDACS"
    error: str | None = None


USER_AGENT = "Clu/1.7 (personal assistant)"


class Gdacs:
    """Current natural hazards from GDACS (UN / European Commission): the global list, so no location is ever sent."""
    BASE = "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH"

    @classmethod
    def url(cls, type_: str, now: int) -> str:
        day = lambda ms: time.strftime("%Y-%m-%d", time.gmtime(ms / 1000))  # noqa: E731
        return f"{cls.BASE}?eventlist={type_}&fromDate={day(now - 14 * 86_400_000)}&toDate={day(now)}&alertlevel=Green;Orange;Red"

    @staticmethod
    def parse(body: str, type_: str) -> list[HazardEvent]:
        if not body.strip():
            return []
        out = []
        for f in (json.loads(body).get("features") or []):
            p = f.get("properties") or {}

            def s(k: str) -> str:
                v = p.get(k)
                return str(v).strip() if isinstance(v, (str, int, float)) and not isinstance(v, bool) else ("true" if v is True else "false" if v is False else "")
            if s("eventtype") and s("eventtype").lower() != type_.lower():
                continue
            if s("iscurrent").lower() == "false":
                continue
            coords = (f.get("geometry") or {}).get("coordinates") if isinstance(f.get("geometry"), dict) else None
            try:
                lon, lat = float(coords[0]), float(coords[1])
            except (TypeError, ValueError, IndexError):
                continue
            sev = (p.get("severitydata") or {}).get("severitytext") or "" if isinstance(p.get("severitydata"), dict) else ""
            url = (p.get("url") or {}).get("report") or "" if isinstance(p.get("url"), dict) else ""
            name = s("description") or s("name") or s("eventname")
            if not name:
                continue
            out.append(HazardEvent(type_.upper(), cc.WebContent.clean(name), lat, lon, s("alertlevel"), cc.WebContent.clean(str(sev)),
                                   cc.WebContent.clean(s("country")), s("source"), s("datemodified") or s("todate"), str(url)))
        return out

    @classmethod
    def check(cls, type_: str, now: int, status: int | None, body: str, error: str | None = None) -> HazardCheck:
        if status is None:
            return HazardCheck(type_, [], now, error=f"GDACS could not be reached ({error or 'no connection'})")
        if status == 204 or (status == 404 and not body.strip()):
            return HazardCheck(type_, [], now)
        if status != 200:
            return HazardCheck(type_, [], now, error=f"GDACS returned HTTP {status}")
        try:
            return HazardCheck(type_, cls.parse(body, type_), now)
        except (ValueError, AttributeError, TypeError):
            return HazardCheck(type_, [], now, error="GDACS returned an unexpected response")

    @classmethod
    async def fetch(cls, type_: str, now: int | None = None) -> HazardCheck:
        import aiohttp
        now = now or now_ms()
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10), headers={"User-Agent": USER_AGENT, "Accept": "application/json"}) as s:
                async with s.get(cls.url(type_, now)) as r:
                    return cls.check(type_, now, r.status, await r.text())
        except Exception as e:  # no network, DNS, timeout
            return cls.check(type_, now, None, "", type(e).__name__)


class Ask:
    LOCATION, ARRIVAL, DIRECTION, DANGER, MORE, COMPARE = "LOCATION", "ARRIVAL", "DIRECTION", "DANGER", "MORE", "COMPARE"


class HazardAnswers:
    NEAR_KM = 1500.0
    WORDS = {"TC": "tropical cyclone", "EQ": "earthquake", "FL": "flood", "VO": "volcanic eruption", "WF": "wildfire", "DR": "drought"}

    @classmethod
    def noun(cls, type_: str) -> str:
        return cls.WORDS.get(type_, "hazard")

    @staticmethod
    def authority(e: HazardEvent) -> str:
        if e.type != "TC":
            return "your national disaster-management authority"
        if e.lat >= 0 and 40.0 <= e.lon <= 100.0:
            return "the India Meteorological Department (IMD)"
        if e.lat >= 0 and 100.0 <= e.lon <= 180.0:
            return "the Japan Meteorological Agency"
        if e.lat >= 0 and -180.0 <= e.lon <= -140.0:
            return "the Central Pacific Hurricane Center (NOAA)"
        if e.lat >= 0 and -140.0 <= e.lon <= 0.0:
            return "the US National Hurricane Center (NOAA)"
        if e.lat < 0 and 30.0 <= e.lon <= 90.0:
            return "Météo-France La Réunion"
        if e.lat < 0 and 90.0 <= e.lon <= 160.0:
            return "the Australian Bureau of Meteorology"
        if e.lat < 0 and (e.lon > 160.0 or e.lon < -120.0):
            return "the Fiji Meteorological Service"
        return "the national meteorological service"

    @staticmethod
    def alert_meaning(alert: str) -> str:
        return {"red": "Red, GDACS's highest alert: a severe humanitarian impact is likely",
                "orange": "Orange: a significant impact is possible",
                "green": "Green: limited impact is expected, though local conditions can still be dangerous"}.get(alert.lower(), "not given")

    @staticmethod
    def _km(d: float) -> str:
        return _fmt(d) if d < 100 else f"{int(d / 10) * 10:,}"

    @staticmethod
    def _position(e: HazardEvent) -> str:
        return f"{_fmt(abs(e.lat), 1)}°{'N' if e.lat >= 0 else 'S'} {_fmt(abs(e.lon), 1)}°{'E' if e.lon >= 0 else 'W'}"

    @classmethod
    def _relative(cls, e: HazardEvent, home: Place | None) -> str | None:
        if home is None:
            return None
        return f"about {cls._km(Places.distance_km(home.lat, home.lon, e.lat, e.lon))} km {Places.direction(home.lat, home.lon, e.lat, e.lon)} of {home.name}"

    @staticmethod
    def distance(e: HazardEvent, home: Place) -> float:
        return Places.distance_km(home.lat, home.lon, e.lat, e.lon)

    @staticmethod
    def _stamp(c: HazardCheck, e: HazardEvent | None) -> str:
        out = "(GDACS"
        if e and e.source.strip():
            out += f", based on {e.source} data"
        if e and e.updated.strip():
            out += f", updated {e.updated.replace('T', ' ')[:16]} UTC"
        return out + ".)"

    @classmethod
    def relevant(cls, c: HazardCheck, home: Place | None) -> list[HazardEvent]:
        if home is None:
            return list(c.events)
        return [e for e in sorted(c.events, key=lambda e: cls.distance(e, home))
                if cls.distance(e, home) <= cls.NEAR_KM or (home.country and home.country.lower() in e.countries.lower())]

    @classmethod
    def describe(cls, e: HazardEvent, home: Place | None) -> str:
        out = e.name
        rel = cls._relative(e, home)
        out += f" is {rel}, near {cls._position(e)}" if rel else f" is near {cls._position(e)}"
        if e.severity.strip():
            out += f"; {e.severity[:1].lower() + e.severity[1:]}"
        out += "."
        if e.alert.strip():
            out += f" GDACS alert level: {e.alert}."
        if e.countries.strip():
            out += f" Countries GDACS lists as affected: {e.countries}."
        return out

    @classmethod
    def answer(cls, c: HazardCheck, home: Place | None) -> str:
        n = cls.noun(c.type)
        if c.error is not None:
            why = c.error.split("GDACS ", 1)[1] if "GDACS " in c.error else c.error
            return (f"I couldn't reach GDACS, the official disaster-alert service, just now ({why.rstrip('.')}), "
                    f"so I can't tell you which {n} is relevant to you. Please check your national weather service's current warnings.")
        if not c.events:
            return f"GDACS lists no active {n}s right now. {cls._stamp(c, None)}"
        near = cls.relevant(c, home)
        if home is not None and not near:
            nearest = min(c.events, key=lambda e: cls.distance(e, home))
            return (f"No active {n} is within about {cls._km(cls.NEAR_KM)} km of {home.name}, according to GDACS. The nearest active one is {nearest.name}, "
                    f"{cls._relative(nearest, home)}. {cls._stamp(c, nearest)}")
        e = near[0]
        out = f"The active {n} nearest to {home.name}: " if home is not None else ""
        out += cls.describe(e, home)
        if e.type == "TC":
            out += f" The data I have doesn't include its direction of movement or a landfall time; {cls.authority(e)} issues the official forecasts and warnings for this area."
        if len(near) > 1:
            out += " GDACS also lists " + " and ".join(x.name for x in near[1:]) + "."
        return out + " " + cls._stamp(c, e)

    @classmethod
    def follow_up(cls, ask: str, c: HazardCheck, home: Place | None, previous: HazardCheck | None) -> str:
        rel_events = cls.relevant(c, home)
        e = rel_events[0] if rel_events else (c.events[0] if c.events else None)
        if e is None:
            return cls.answer(c, home)
        rel = cls._relative(e, home)
        auth = cls.authority(e)
        if ask == Ask.LOCATION:
            return f"{e.name} is near {cls._position(e)}{', ' + rel if rel else ''}. {cls._stamp(c, e)}"
        if ask in (Ask.ARRIVAL, Ask.DIRECTION):
            what = "when or whether it will reach you" if ask == Ask.ARRIVAL else "which way it is moving"
            return (f"The data I have gives its current position, not a forecast track, so I can't say {what} without guessing. "
                    f"Right now it is {rel or 'near ' + cls._position(e)}. {auth[:1].upper() + auth[1:]} publishes the forecast track and warnings. {cls._stamp(c, e)}")
        if ask == Ask.DANGER:
            return (f"GDACS rates {e.name} {cls.alert_meaning(e.alert)}." + (f" {e.severity}." if e.severity.strip() else "")
                    + (f" It is {rel}." if rel else "") + f" For what to do locally, follow {auth} and your local authorities. {cls._stamp(c, e)}")
        if ask == Ask.MORE:
            return " ".join(cls.describe(x, home) for x in c.events) + " " + cls._stamp(c, e)
        other = next((x for x in (previous.events if previous else []) if x.name != e.name), None) or next((x for x in c.events if x.name != e.name), None)
        if other is None:
            return f"I only have information on {e.name} in this conversation, so there's no earlier one to compare it with."

        def line(x: HazardEvent) -> str:
            r = cls._relative(x, home)
            return f"{x.name}: {x.severity.strip() or 'intensity not given'}, alert {x.alert.strip() or 'not given'}{', ' + r if r else ''}."
        return f"{line(e)} {line(other)} {cls._stamp(c, e)}"


@dataclass
class WeatherNow:
    place: str
    temperature_c: float | None
    humidity: int | None
    wind_kmh: float | None
    code: int | None
    min_c: float | None
    max_c: float | None
    rain_chance: int | None
    time: str
    checked_at: int
    error: str | None = None


class OpenMeteo:
    """Current conditions and today's forecast (Open-Meteo, no key): only the place's coordinates are sent."""
    BASE = "https://api.open-meteo.com/v1/forecast"

    @classmethod
    def url(cls, place: Place) -> str:
        return (f"{cls.BASE}?latitude={_fmt(place.lat, 2)}&longitude={_fmt(place.lon, 2)}"
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                "&timezone=auto&forecast_days=1")

    @staticmethod
    def parse(place: Place, now: int, status: int | None, body: str) -> WeatherNow:
        def fail(why: str) -> WeatherNow:
            return WeatherNow(place.name, None, None, None, None, None, None, None, "", now, error=why)
        if status is None:
            return fail("the weather service could not be reached")
        if status != 200:
            return fail(f"the weather service returned HTTP {status}")
        try:
            o = json.loads(body)
            c, d = o["current"], o.get("daily") or {}

            def first(k: str):
                v = d.get(k)
                return v[0] if isinstance(v, list) and v else None
            num = lambda v, t: t(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None  # noqa: E731
            return WeatherNow(place.name, num(c.get("temperature_2m"), float), num(c.get("relative_humidity_2m"), int), num(c.get("wind_speed_10m"), float),
                              num(c.get("weather_code"), int), num(first("temperature_2m_min"), float), num(first("temperature_2m_max"), float),
                              num(first("precipitation_probability_max"), int), str(c.get("time") or ""), now)
        except (ValueError, KeyError, TypeError, AttributeError):
            return fail("the weather service returned an unexpected response")

    @classmethod
    async def fetch(cls, place: Place, now: int | None = None) -> WeatherNow:
        import aiohttp
        now = now or now_ms()
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10), headers={"User-Agent": USER_AGENT}) as s:
                async with s.get(cls.url(place)) as r:
                    return cls.parse(place, now, r.status, await r.text())
        except Exception:
            return cls.parse(place, now, None, "")

    @staticmethod
    def describe(code: int | None) -> str | None:
        table = {0: "clear", 1: "mainly clear", 2: "partly cloudy", 3: "overcast", 45: "foggy", 48: "foggy", 51: "drizzly", 53: "drizzly", 55: "drizzly",
                 56: "freezing drizzle", 57: "freezing drizzle", 61: "rainy", 63: "rainy", 65: "raining heavily", 66: "freezing rain", 67: "freezing rain",
                 71: "snowy", 73: "snowy", 75: "snowy", 77: "snowy", 80: "showery", 81: "showery", 82: "violent showers", 85: "snow showers", 86: "snow showers",
                 95: "thunderstorms", 96: "thunderstorms with hail", 99: "thunderstorms with hail"}
        return table.get(code) if code is not None else None

    @classmethod
    def answer(cls, w: WeatherNow) -> str:
        if w.error is not None:
            return f"I couldn't get the weather for {w.place} just now ({w.error}), so I won't guess."
        parts = [p for p in (f"{_fmt(w.temperature_c)}°C" if w.temperature_c is not None else None, cls.describe(w.code)) if p]
        out = f"In {w.place} it's " + (" and ".join(parts) or "currently unreported")
        if w.humidity is not None:
            out += f", humidity {w.humidity}%"
        if w.wind_kmh is not None:
            out += f", wind {_fmt(w.wind_kmh)} km/h"
        out += "."
        if w.min_c is not None and w.max_c is not None:
            out += f" Today: {_fmt(w.min_c)} to {_fmt(w.max_c)}°C"
        if w.rain_chance is not None:
            out += f", {w.rain_chance}% chance of rain." if w.min_c is not None else f" Chance of rain today: {w.rain_chance}%."
        elif w.min_c is not None:
            out += "."
        return out + f" (Open-Meteo forecast data{', ' + w.time.replace('T', ' ') + ' local time' if w.time.strip() else ''}.)"


# ------------------------------------------------------------------------------------------------ hazard questions (HazardPolicy)
@dataclass
class Need:
    type: str
    near_owner: bool
    place: Place | None


class HazardPolicy:
    TYPES = [("TC", re.compile(r"\b(?:cyclones?|hurricanes?|typhoons?|tropical (?:storms?|depressions?|cyclones?)|deep depressions?|storms?)\b", I)),
             ("EQ", re.compile(r"\b(?:earthquakes?|quakes?|tremors?)\b", I)),
             ("FL", re.compile(r"\b(?:floods?|flooding)\b", I)),
             ("VO", re.compile(r"\b(?:volcano(?:es)?|volcanic|eruptions?)\b", I)),
             ("WF", re.compile(r"\b(?:wildfires?|forest fires?|bush ?fires?)\b", I)),
             ("WX", re.compile(r"\b(?:weather|temperature|raining|rain|forecast|humid(?:ity)?|windy|umbrella)\b", I))]
    CURRENT = re.compile(r"\b(?:which|any|is there|are there|where|heading|coming|approaching|towards?|near(?:by)?|close|now|currently|current|today|tonight|"
                         r"tomorrow|latest|active|us|our|me|my|here|hit|affect|outside|going on)\b", I)
    DEFINITION = re.compile(r"^\s*(?:what (?:is|are) (?:an? )?\w+\s*\??$|how (?:do|does|are|is) |why (?:do|does|are|is) |explain |define |what causes )", I)
    CREATIVE = re.compile(r"\b(?:write|compose|poems?|poetry|story|stories|song|lyrics|haiku|essay|joke|draw|paint|imagine|pretend)\b", I)
    NEAR = re.compile(r"\b(?:us|our|me|my|here|near(?:by)?|close|towards?|our way|this area|local|outside)\b", I)

    @classmethod
    def detect(cls, text: str) -> Need | None:
        t = text.strip()
        type_ = next((k for k, r in cls.TYPES if r.search(t)), None)
        if type_ is None or cls.DEFINITION.search(t) or cls.CREATIVE.search(t) or not cls.CURRENT.search(t):
            return None
        if re.search(r"\b(?:1[89]\d\d|20[01]\d)\b", t):
            return None
        place = Places.resolve(t)
        return Need(type_, place is None and (bool(cls.NEAR.search(t)) or type_ == "WX"), place)


# ------------------------------------------------------------------------------------------------ conversation state
@dataclass
class Topic:
    question: str
    subject: str | None = None
    entities: list[str] = field(default_factory=list)
    kind: str = "general"  # general | entity | current | hazard | weather
    answered: bool = True
    hazard: HazardCheck | None = None
    weather: WeatherNow | None = None
    turn: int = 0
    at: int = 0


@dataclass
class Pending:
    kind: str  # correction | ambiguity | location | subject | choice
    question: str
    options: list[str] = field(default_factory=list)
    literal: str = ""
    turn: int = 0
    at: int = 0


@dataclass
class ConversationState:
    turn: int = 0
    topic: Topic | None = None
    previous: Topic | None = None
    pending: Pending | None = None
    place: str | None = None

    def to_json(self) -> str:
        return json.dumps(asdict(self))

    @classmethod
    def from_json(cls, s: str | None) -> "ConversationState":
        try:
            d = json.loads(s or "{}")

            def topic(t):
                if not t:
                    return None
                h = t.get("hazard")
                w = t.get("weather")
                return Topic(**{**t, "hazard": HazardCheck(**{**h, "events": [HazardEvent(**e) for e in h["events"]]}) if h else None,
                                "weather": WeatherNow(**w) if w else None})
            return cls(d.get("turn", 0), topic(d.get("topic")), topic(d.get("previous")), Pending(**d["pending"]) if d.get("pending") else None, d.get("place"))
        except (ValueError, TypeError, KeyError):
            return cls()


# ------------------------------------------------------------------------------------------------ resolution
@dataclass
class Standalone:
    text: str


@dataclass
class FollowUp:
    question: str
    topic: Topic
    research: bool
    subject: str | None
    ask: str | None = None
    compare_with: Topic | None = None
    prompt: str = ""

    def __post_init__(self):
        self.prompt = self.prompt or self.question


@dataclass
class Clarify:
    text: str
    pending: Pending


@dataclass
class Resolved:
    resolution: object
    heard: str
    corrected: str | None = None
    place_from_reply: str | None = None


_WORD = re.compile(r"(?:[^\W_]|')+")


def words(t: str) -> list[str]:
    return _WORD.findall(t.lower())


class Phonetics:
    @staticmethod
    def key(word: str) -> str:
        w = "".join(c for c in word.lower() if c.isalpha())
        sb: list[str] = []

        def push(c: str):
            if not sb or sb[-1] != c:
                sb.append(c)
        i = 0
        while i < len(w):
            c, n = w[i], (w[i + 1] if i + 1 < len(w) else None)
            if c == "p" and n == "h":
                push("P"); i += 1
            elif c == "t" and n == "h":
                push("T"); i += 1
            elif c == "c" and n == "k":
                push("K"); i += 1
            elif c == "c" and n is not None and n in "eiy":
                push("S")
            elif c == "g" and n is not None and n in "ei":
                push("J")
            elif c in "ckqg":
                push("K")
            elif c == "x":
                push("K"); push("S")
            elif c in "sz":
                push("S")
            elif c in "bpfvw":
                push("P")
            elif c in "dt":
                push("T")
            elif c in "lr":
                push("L")
            elif c in "mn":
                push("N")
            elif c == "j":
                push("J")
            i += 1
        return "".join(sb)

    @staticmethod
    def syllables(word: str) -> int:
        w = "".join(c for c in word.lower() if c.isalpha())
        groups = len(re.findall(r"[aeiouy]+", w))
        return max(1, groups - (1 if w.endswith("e") and groups > 1 and not w.endswith("le") else 0))

    @staticmethod
    def _lcs(a: str, b: str) -> int:
        d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
        for i in range(1, len(a) + 1):
            for j in range(1, len(b) + 1):
                d[i][j] = d[i - 1][j - 1] + 1 if a[i - 1] == b[j - 1] else max(d[i - 1][j], d[i][j - 1])
        return d[len(a)][len(b)]

    @classmethod
    def similarity(cls, a: str, b: str) -> float:
        if a.lower() == b.lower():
            return 1.0
        ka, kb = cls.key(a), cls.key(b)
        if not ka or not kb:
            return 0.0
        skeleton = cls._lcs(ka, kb) / max(len(ka), len(kb))
        first = 1.0 if ka[0] == kb[0] or a[0].lower() == b[0].lower() else 0.0
        sa, sb = cls.syllables(a), cls.syllables(b)
        syl = 1.0 - abs(sa - sb) / max(sa, sb)
        return 0.6 * skeleton + 0.2 * first + 0.2 * syl


@dataclass
class Fix:
    frm: str
    to: str
    text: str
    silent: bool


class ContextualCorrection:
    ACTION = re.compile(r"\b(?:call|ring|dial|text|message|send|email|pay|transfer|buy|order|book|delete|remove|erase|unlock|open|turn off|switch off|cancel)\b", I)
    COMMON = {"what", "which", "where", "when", "will", "would", "could", "should", "there", "their", "about", "tell", "know", "this",
              "that", "with", "from", "have", "does", "doing", "going", "come", "coming", "heading", "towards", "toward", "near", "today", "now", "still",
              "dangerous", "danger", "safe", "arrive", "reach", "much", "many", "more", "very", "really", "please", "think", "look", "search", "find",
              "check", "just", "also", "like", "make", "made", "take", "give", "said", "says", "sure", "okay", "yeah", "right", "time", "here"}

    @staticmethod
    def _replace_word(text: str, frm: str, to: str) -> str:
        return re.sub(r"\b" + re.escape(frm) + r"\b", lambda _: to, text, flags=I)

    @classmethod
    def find(cls, text: str, topic: Topic) -> Fix | None:
        named = (any(any(c.isupper() for c in e) for e in topic.entities)
                 or any(m.group(1).isupper() for m in re.finditer(r"(?<=\S\s)([^\W\d_])", topic.question)))
        if topic.kind == "general" and not named:
            return None
        tokens = words(text)
        if len(tokens) > 14:
            return None
        about = topic.question + " " + (topic.subject or "") + " " + " ".join(topic.entities)
        vocab = words(about)
        terms = list(dict.fromkeys(w for w in vocab if len(w) >= 4 and w not in cls.COMMON and w not in cc.Grounding.FUNCTION))
        if not terms:
            return None
        unknown = [w for w in tokens if len(w) >= 4 and w not in vocab and w not in cls.COMMON and w not in cc.Grounding.FUNCTION]
        best = None
        for u in unknown:
            for term in terms:
                sim = Phonetics.similarity(u, term)
                if best is None or sim > best[2]:
                    best = (u, term, sim)
        f = cls._consequential(text, tokens, vocab, terms)
        if f is not None:
            m = re.search(r"\b" + re.escape(f.to) + r"\b", topic.question + " " + (topic.subject or ""), I)
            cased = m.group(0) if m else f.to
            return Fix(f.frm, cased, f.text.replace(f.to, cased), False)
        if best is None or best[2] < 0.6:
            return None
        frm, to, sim = best
        rest = [w for w in tokens if w != frm and len(w) >= 3 and w not in cc.Grounding.FUNCTION]
        frame = sum(1 for w in rest if w in vocab)
        strong = frame >= 2 or (frame >= 1 and sim >= 0.7)
        continues = all(w == frm for w in unknown) and len(tokens) <= 9
        if not strong and not continues:
            return None
        m = re.search(r"\b" + re.escape(to) + r"\b", about, I)
        original = m.group(0) if m else to
        fixed = cls._replace_word(text, frm, original)
        offer = original if original[0].isupper() and not topic.question.startswith(original) else f"the {to}"
        name_like = bool(re.search(r"(?<=\S\s)" + re.escape(frm[:1].upper() + frm[1:]) + r"\b", text))
        silent = not cls.ACTION.search(text) and not name_like and (strong or sim >= 0.7)
        return Fix(frm, offer, fixed, silent)

    @classmethod
    def _consequential(cls, text: str, tokens: list[str], vocab: list[str], terms: list[str]) -> Fix | None:
        if not cls.ACTION.search(text):
            return None
        cands = [(u, t, Phonetics.similarity(u, t)) for u in tokens if len(u) >= 3 and u not in vocab for t in terms]
        cands = [c for c in cands if c[2] >= 0.85]
        if not cands:
            return None
        u, t, _ = max(cands, key=lambda c: c[2])
        m = re.search(r"\b" + re.escape(t) + r"\b", " ".join(vocab), I)
        original = m.group(0) if m else t
        return Fix(u, original, cls._replace_word(text, u, original), False)


class FollowUpResolver:
    TOPIC_TURNS = 6
    TOPIC_MS = 60 * 60_000
    PENDING_TURNS = 2
    BARE = {"search", "google", "look", "lookup", "check", "find", "research", "browse", "verify", "confirm", "up", "out",
            "it", "that", "this", "them", "for", "on", "in", "the", "web", "internet", "online", "net", "and", "then", "tell", "let", "me", "know",
            "please", "pls", "can", "could", "would", "will", "you", "go", "ahead", "do", "a", "quick", "now", "again", "ok", "okay", "so", "clu", "just",
            "yes", "yeah", "sure", "try", "what", "say", "says", "show", "us", "to", "is", "there", "anything", "about", "info", "information"}
    RESEARCH_VERB = {"search", "google", "look", "lookup", "check", "find", "research", "browse", "verify", "confirm"}
    MORE = re.compile(r"^(?:(?:ok|okay|and|so|please|clu)[ ,]+)*(?:tell me more|more(?: details?| info(?:rmation)?)?|go on|continue|keep going|what else|anything else|"
                      r"elaborate|explain more|say more|and)(?: about (?:it|that|this|him|her|them))?[ .!?]*$", I)
    PRONOUN = re.compile(r"\b(?:it|its|it's|he|him|his|she|her|hers|they|them|their|this|that|those|these|there)\b", I)
    SINGULAR = re.compile(r"\b(?:it|its|it's|he|him|his|she|her|this|that)\b", I)
    PREVIOUS = re.compile(r"\b(?:the )?(?:previous|last|earlier|other|first) one\b|\bthe one before\b", I)
    WHAT_ABOUT = re.compile(r"^(?:and |so )?(?:what|how) about\s+(.+?)[ ?.!]*$", I)
    YES = re.compile(r"^(?:yes|yeah|yep|yup|right|correct|exactly|that's right|that one|sure|ok|okay)\b", I)
    NO = re.compile(r"^(?:no|nope|nah|not that|wrong|neither)\b", I)
    ASK_LOCATION = re.compile(r"\b(?:where|location|position|how far|distance|near(?:by)?|close)\b", I)
    ASK_ARRIVAL = re.compile(r"\b(?:when|arrive|arrival|reach|hit|landfall|make landfall|get here|come here|coming here)\b", I)
    ASK_DIRECTION = re.compile(r"\b(?:direction|heading|which way|moving|track|path|course|towards)\b", I)
    ASK_DANGER = re.compile(r"\b(?:dangerous|danger|strong|severe|serious|safe|risk|worried|threat|intense|intensity|category|bad)\b", I)
    ASK_COMPARE = re.compile(r"\b(?:compare|compared|versus|vs|difference|bigger|stronger|weaker|worse)\b", I)
    NAME = re.compile(r"(?<![^\W\d_])(?<!')[^\W\d_](?:[^\W\d_]|['-]){2,}")  # filtered to capitalised (Kotlin: \p{Lu}[\p{L}'-]{2,})

    @classmethod
    def topic_usable(cls, s: ConversationState, now: int) -> bool:
        return s.topic is not None and s.turn - s.topic.turn <= cls.TOPIC_TURNS and now - s.topic.at <= cls.TOPIC_MS

    @classmethod
    def is_bare_research(cls, text: str) -> bool:
        w = [x.removesuffix("'s") for x in words(text)]
        return bool(w) and len(w) <= 14 and all(x in cls.BARE for x in w) and any(x in cls.RESEARCH_VERB for x in w)

    @classmethod
    def is_more(cls, text: str) -> bool:
        return bool(cls.MORE.fullmatch(text.strip()))

    @classmethod
    def resolve(cls, text: str, s: ConversationState, via_voice: bool, now: int, command: bool = False) -> Resolved:
        t = text.strip()
        p = s.pending
        if p is not None and s.turn - p.turn <= cls.PENDING_TURNS and now - p.at <= 15 * 60_000:
            r = cls._answer_pending(t, p, s)
            if r is not None:
                return r
        topic = s.topic if cls.topic_usable(s, now) else None
        heard, corrected = t, None
        if via_voice and topic is not None:
            fix = ContextualCorrection.find(t, topic)
            if fix is not None:
                if fix.silent:
                    corrected = heard = fix.text
                else:
                    return Resolved(Clarify(f"Do you mean {fix.to}?", Pending("correction", fix.text, [fix.to], literal=t, turn=s.turn, at=now)), t)
        res = Standalone(heard) if command else cls._resolve_text(heard, topic, s)
        return Resolved(res, t, corrected)

    @classmethod
    def _resolve_text(cls, t: str, topic: Topic | None, s: ConversationState):
        if cls.is_bare_research(t):
            if topic is None:
                return Clarify("What would you like me to look up?", Pending("subject", "", turn=s.turn))
            return FollowUp(topic.question, topic, True, topic.subject, prompt=topic.question)
        if topic is None:
            return Standalone(t)
        if cls.is_more(t):
            return FollowUp(f"Tell me about {topic.subject}" if topic.subject else topic.question, topic, topic.kind != "general", topic.subject,
                            ask=Ask.MORE if topic.kind == "hazard" else None,
                            prompt=f"Tell me more about {topic.subject}." if topic.subject else "Tell me more about that.")
        head = {w for w in words((topic.subject or "") + " " + " ".join(topic.entities)) if len(w) >= 4}
        known = head | set(words(topic.question))
        names_topic = (any(m.group(1).lower() in head for m in re.finditer(r"\bthe\s+((?:[^\W_]|['-])+)", t, I))
                       and not any(len(w) >= 4 and w not in known and w not in ContextualCorrection.COMMON and w not in cc.Grounding.FUNCTION for w in words(t)))
        refers = bool(cls.PRONOUN.search(t) or cls.PREVIOUS.search(t) or cls.WHAT_ABOUT.fullmatch(t)) or names_topic
        if not refers or cls._has_own_subject(t, topic):
            return Standalone(t)
        if cls.PREVIOUS.search(t) or cls.ASK_COMPARE.search(t):
            other = s.previous if s.previous is not None and s.previous.subject != topic.subject else None
            if topic.kind == "hazard":
                return FollowUp(topic.question, topic, False, topic.subject, ask=Ask.COMPARE, compare_with=other)
            if other is not None and other.subject is not None and topic.subject is not None:
                return FollowUp(f"Compare {topic.subject} with {other.subject}", topic, False, topic.subject, compare_with=other)
        if cls.SINGULAR.search(t) and len(topic.entities) >= 2:
            return Clarify(f"Do you mean {', '.join(topic.entities[:-1])} or {topic.entities[-1]}?", Pending("ambiguity", t, list(topic.entities), turn=s.turn))
        if topic.kind == "weather":
            return FollowUp(topic.question, topic, True, topic.subject, prompt=t)
        if topic.kind == "hazard":
            ask = (Ask.DANGER if cls.ASK_DANGER.search(t) else Ask.ARRIVAL if cls.ASK_ARRIVAL.search(t) else
                   Ask.DIRECTION if cls.ASK_DIRECTION.search(t) else Ask.LOCATION if cls.ASK_LOCATION.search(t) else Ask.MORE)
            return FollowUp(topic.question, topic, False, topic.subject, ask=ask)
        subject = topic.subject
        if subject is None:
            return Standalone(t)
        m = cls.WHAT_ABOUT.fullmatch(t)
        sw = subject.lower().split(" ")
        extra = [w for w in cc.ResearchPolicy.content_words(m.group(1) if m else t) if w.lower() not in sw and not cls.PRONOUN.fullmatch(w)]
        return FollowUp((subject + " " + " ".join(extra)).strip(), topic, topic.kind != "general", subject, prompt=t)

    @classmethod
    def _has_own_subject(cls, t: str, topic: Topic) -> bool:
        topic_words = set(words(topic.question + " " + (topic.subject or "") + " " + " ".join(topic.entities)))
        names = [m.group(0).lower() for m in cls.NAME.finditer(t) if m.group(0)[0].isupper()]
        if t[:1].isupper():
            names = names[1:]
        return any(n not in topic_words and n not in cc.Grounding.FUNCTION for n in names)

    @classmethod
    def _answer_pending(cls, t: str, p: Pending, s: ConversationState) -> Resolved | None:
        w = words(t)
        if p.kind == "correction":
            if cls.YES.search(t) or any(all(x in w for x in words(o) if len(x) > 2) for o in p.options):
                return Resolved(cls._resolve_text(p.question, s.topic, s), t, corrected=p.question)
            if cls.NO.search(t):
                return Resolved(cls._resolve_text(p.literal, s.topic, s), t)
            return None
        if p.kind == "ambiguity":
            choice = next((o for o in p.options if any(len(x) > 2 and x in w for x in words(o))), None)
            if choice is None or s.topic is None:
                return None
            chosen = Topic(**{**s.topic.__dict__, "subject": choice, "entities": [choice]})
            asked = cls.SINGULAR.sub(lambda _: choice, p.question, count=1)
            res = cls._resolve_text(p.question, chosen, ConversationState(s.turn, chosen, s.previous, s.pending, s.place))
            if isinstance(res, FollowUp):
                res.prompt = asked
                return Resolved(res, t)
            return Resolved(Standalone(asked), t)
        if p.kind == "location":
            place = Places.resolve(t)
            if place is None:
                return None
            topic = s.topic or Topic(p.question)
            return Resolved(FollowUp(p.question, topic, True, topic.subject, prompt=p.question), t, place_from_reply=place.name)
        if p.kind == "choice":
            choice = next((o for o in p.options if any(x in w for x in words(o) if len(x) > 2)), None)
            if choice is None:
                return None
            topic = s.topic or Topic(p.question)
            return Resolved(FollowUp(f"Tell me about {choice}", Topic(**{**topic.__dict__, "subject": choice, "entities": [choice]}), True, choice,
                                     prompt=f"Tell me about {choice}."), t)
        return None
