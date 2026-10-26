package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.IntentRouter
import ai.clu.core.agent.TurnRequest
import ai.clu.core.security.Actor
import ai.clu.core.tools.HttpResponse
import ai.clu.core.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Maps / routing tool against recorded-format responses (FakeHttp: Nominatim and OSRM JSON in the documented formats).
 * The live services were not reachable from the build container (network policy), so live behaviour is a device test
 * (DEVICE_ACCEPTANCE section W).
 */
class MapsToolTest {
    private val manhattan = """[{"lat":"40.7831","lon":"-73.9712","display_name":"Manhattan, New York County, New York, United States","importance":0.85,"type":"administrative"}]"""
    private val queens = """[{"lat":"40.6526","lon":"-73.7949","display_name":"Queens, Queens County, New York, United States","importance":0.80,"type":"administrative"},
        {"lat":"40.7282","lon":"-73.7949","display_name":"Queens Village, Queens, New York, United States","importance":0.40,"type":"suburb"}]"""
    private val springfield = """[{"lat":"39.7990","lon":"-89.6440","display_name":"Springfield, Sangamon County, Illinois, United States","importance":0.70},
        {"lat":"42.1015","lon":"-72.5898","display_name":"Springfield, Hampden County, Massachusetts, United States","importance":0.68}]"""
    private val osrm = """{"code":"Ok","routes":[
        {"legs":[{"summary":"Ed Koch Queensboro Bridge, Northern Boulevard","distance":16900,"duration":1980}],"distance":16900,"duration":1980,"weight":1980},
        {"legs":[{"summary":"Queens–Midtown Tunnel, Long Island Expressway","distance":18200,"duration":1740}],"distance":18200,"duration":1740,"weight":1740}],
        "waypoints":[]}"""

    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val ctx = ToolContext(Actor.ownerInApp(), core.personas.active(), null, 0)

    init {
        platform.fakeHttp.routes["q=Manhattan"] = HttpResponse(200, manhattan)
        platform.fakeHttp.routes["q=Queens"] = HttpResponse(200, queens)
        platform.fakeHttp.routes["q=Springfield"] = HttpResponse(200, springfield)
        platform.fakeHttp.routes["routed-car"] = HttpResponse(200, osrm)
        platform.fakeHttp.routes["routed-foot"] = HttpResponse(200, osrm.replace("1980", "12000").replace("1740", "13000"))
    }

    private fun route(args: Map<String, String>) = runBlocking {
        core.tools.execute("maps.distance", buildJsonObject { args.forEach { (k, v) -> put(k, v) } }, ctx)
    }

    @Test fun routeReportsDistanceTimeAlternativesAndNoTraffic() {
        val r = route(mapOf("from" to "Manhattan", "to" to "Queens"))
        assertTrue(r.success && r.verified, r.summary)
        val s = r.spoken!!
        assertTrue(s.contains("18 kilometres by car, about 29 minutes via Queens–Midtown Tunnel"), s) // fastest first
        assertTrue(s.contains("alternative via Ed Koch Queensboro Bridge"), s)
        assertTrue(s.contains("don't have live traffic"), s)
        assertTrue(r.summary.contains("Source: OSRM"), "sources are named")
        assertTrue(platform.fakeHttp.requests.any { it.contains("alternatives=true") })
    }

    @Test fun modesAndTransit() {
        val walk = route(mapOf("from" to "Manhattan", "to" to "Queens", "mode" to "walking"))
        assertTrue(walk.spoken!!.contains("on foot"), walk.spoken)
        assertTrue(platform.fakeHttp.requests.any { it.contains("routed-foot") })
        val transit = route(mapOf("from" to "Manhattan", "to" to "Queens", "mode" to "train"))
        assertTrue(transit.spoken!!.contains("Public transport routes aren't available"), transit.spoken)
    }

    @Test fun ambiguousPlaceIsAQuestionNotAGuess() {
        val r = route(mapOf("from" to "Springfield", "to" to "Queens"))
        assertTrue(r.spoken!!.contains("Which one do you mean?"), r.spoken)
        assertTrue(r.spoken!!.contains("Illinois") && r.spoken!!.contains("Massachusetts"))
        assertFalse(platform.fakeHttp.requests.any { it.contains("routed-") }, "no route is computed for a guess")
    }

    @Test fun routingFailureFallsBackHonestly() {
        platform.fakeHttp.routes.remove("routed-car")
        val r = route(mapOf("from" to "Manhattan", "to" to "Queens"))
        assertFalse(r.verified)
        assertTrue(r.spoken!!.contains("Straight-line distance") && r.spoken!!.contains("actual route is longer"), r.spoken)
    }

    @Test fun offlineIsReportedNotHung() {
        platform.online = false
        val r = route(mapOf("from" to "Manhattan", "to" to "Queens"))
        assertFalse(r.success)
        assertTrue(r.summary.contains("No internet connection"), r.summary)
    }

    @Test fun compoundRouteQuestionIsUnderstood() {
        val q = IntentRouter.route("What is the distance between Manhattan and Queens, how long will the trip take, and which route should I take?")!!
        assertEquals("maps.distance", q.tool)
        assertEquals("Manhattan", q.args["from"].toString().trim('"'))
        assertEquals("Queens", q.args["to"].toString().trim('"'))
        assertEquals("cycling", IntentRouter.route("how far is Queens from Manhattan by bike?")!!.args["mode"].toString().trim('"'))
        assertEquals("walking", IntentRouter.route("How long does it take to walk from Manhattan to Queens")!!.args["mode"].toString().trim('"'))
        // End to end through the agent: deterministic, no model needed, answer spoken from the tool result.
        val conv = core.conversations.create().id
        val done = runBlocking {
            var d: AgentEvent.Done? = null
            core.agent.handle(TurnRequest(conv, "What is the distance between Manhattan and Queens, and which route should I take?", Actor.ownerInApp())) {
                if (it is AgentEvent.Done) d = it }
            d!!
        }
        assertTrue(done.text.contains("Queens–Midtown Tunnel"), done.text)
    }
}
