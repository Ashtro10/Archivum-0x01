package ai.clu.core

import ai.clu.core.agent.GoalEngine
import ai.clu.core.agent.GoalStep
import ai.clu.core.agent.GoalStepStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import org.junit.jupiter.api.Test

/**
 * Calibration of the grounding, provenance and relevance checks on the production code ([GoalEngine.admissible],
 * [GoalEngine.about]), independent of any model. Each legitimate item is a correct translation of a natural instruction
 * into tool arguments (paraphrase, inference of the obvious task, context, lookups, calculations); each negative adds
 * something nobody said or found. The false-rejection and false-acceptance rates are printed and must both be zero on
 * this corpus; an item added later that fails shows exactly which check is miscalibrated.
 */
class GroundingCalibrationTest {
    private val engine = newCore().goalEngine
    private fun fact(id: String, phrase: String, value: String, evidence: String) =
        GoalStep(id, lookup = phrase, status = GoalStepStatus.DONE, value = value, evidence = evidence)
    private val diwali = fact("s1", "date of Diwali 2025", "20 October 2025", "Diwali 2025 falls on Monday, 20 October 2025.")
    private val exam = fact("s1", "date of my next exam", "28 October 2025", "My next examination is Data Structures on 28 October 2025.")

    data class Item(val id: String, val owner: String, val tool: String, val args: String, val admit: Boolean, val steps: List<GoalStep> = emptyList(),
                    val anchored: List<String> = emptyList())

    private val items = listOf(
        // Legitimate: must be admitted.
        Item("plain", "remind me to call mom at 7", "reminder.set", """{"what":"call mom","when":"at 7"}""", true),
        Item("nudge", "nudge me about the dentist tomorrow morning", "reminder.set", """{"what":"dentist","when":"tomorrow morning"}""", true),
        Item("rent", "don't let me forget to pay rent on the 1st", "reminder.set", """{"what":"pay rent","when":"on the 1st"}""", true),
        Item("insurance-derived", "My car insurance runs out on 5 November and I've let it lapse before", "reminder.set", """{"what":"renew my car insurance","when":"5 November"}""", true),
        Item("birthday", "my sister's birthday is on 2 November and I always forget to call her", "reminder.set", """{"what":"call my sister","when":"2 November"}""", true),
        Item("submission", "I'm going to be busy next Thursday. Make sure I don't forget my assignment submission.", "reminder.set", """{"what":"submit my assignment","when":"Wednesday at 6 pm"}""", true),
        Item("exam-lookup", "Find out when my next exam is and make sure I have enough time to prepare", "reminder.set",
            """{"what":"start preparing for the Data Structures exam","when":"a week before 28 October 2025"}""", true, listOf(exam)),
        Item("coffee", "we're out of coffee, stick it on the shopping list", "task.add", """{"title":"coffee","list":"shopping"}""", true),
        Item("context-those", "We need milk, bread and coffee for the weekend.\nPut those on the shopping list.", "task.add", """{"title":"milk, bread and coffee","list":"shopping"}""", true),
        Item("water", "ping me at 6:30 this evening to water the plants", "reminder.set", """{"what":"water the plants","when":"today at 6:30 pm"}""", true),
        Item("vitamins", "Can you nudge me to take my vitamins at 9 tonight?", "reminder.set", """{"what":"take my vitamins","when":"tonight at 9"}""", true),
        Item("rahul", "remind me an hour before my meeting with Rahul tomorrow at 3", "reminder.set", """{"what":"meeting with Rahul","when":"an hour before tomorrow at 3 pm"}""", true),
        Item("diwali-lookup", "remind me to buy diyas three days before Diwali", "reminder.set", """{"what":"buy diyas","when":"3 days before 20 October 2025"}""", true, listOf(diwali)),
        Item("diwali-anchored", "Diwali's coming up, look up the date and remind me to buy diyas three days before it", "reminder.set",
            """{"what":"buy diyas","when":"three days before 20 October 2025"}""", true, listOf(diwali), listOf("when")),
        Item("default-list", "Add eggs to the list and remind me to buy them tomorrow at 6", "task.add", """{"title":"eggs","list":"shopping"}""", true),
        Item("eggs-reminder", "Add eggs to the list and remind me to buy them tomorrow at 6", "reminder.set", """{"what":"buy eggs","when":"tomorrow at 6 pm"}""", true),
        Item("todo-list", "put 'call the bank' on my to-do list", "task.add", """{"title":"call the bank","list":"tasks"}""", true),
        Item("day-after", "remind me the day after tomorrow at noon to send the report", "reminder.set", """{"what":"send the report","when":"the day after tomorrow at noon"}""", true),
        Item("keys-generic-verb", "I keep forgetting my keys, remind me to check for them when I leave at 8", "reminder.set", """{"what":"check for my keys","when":"at 8"}""", true),
        Item("passport", "Could you make a note to renew my passport?", "task.add", """{"title":"renew my passport","list":"tasks"}""", true),
        Item("doctor", "Remind me to call Dr. Mehta's clinic on Monday", "reminder.set", """{"what":"call Dr. Mehta's clinic","when":"Monday"}""", true),
        Item("hinglish", "kal subah 7 baje yaad dilana ki dawai leni hai", "reminder.set", """{"what":"dawai leni hai","when":"tomorrow at 7 am"}""", true),
        Item("flight", "my flight to Delhi leaves Friday 6 am; remind me the night before to pack", "reminder.set", """{"what":"pack for my flight to Delhi","when":"the night before Friday"}""", true),
        Item("washer", "Remind me in two hours to take the clothes out of the washer", "reminder.set", """{"what":"take the clothes out of the washer","when":"in two hours"}""", true),
        Item("bill-15th", "when the electricity bill comes on the 15th remind me to pay it", "reminder.set", """{"what":"pay the electricity bill","when":"on the 15th"}""", true),
        Item("sms", "text Priya that I'm running late", "sms.compose", """{"to":"Priya","text":"I'm running late"}""", true),
        Item("email-composed", "email the landlord saying the heater is broken", "email.compose", """{"to":"landlord","subject":"heater is broken","body":"Hello, the heater in the flat is broken. Could you arrange a repair?"}""", true),
        Item("alarm", "set an alarm for 6:45", "alarm.set", """{"time":"6:45"}""", true),
        Item("timer-calc", "start a 10 minute timer for the pasta", "timer.set", """{"seconds":600,"label":"pasta"}""", true),
        Item("document", "write up what you found about Diwali and save it", "document.write", """{"title":"Diwali","content":"# Diwali\n\nDiwali 2025 falls on 20 October (web)."}""", true, listOf(diwali)),
        Item("context-dentist", "My dentist appointment is on 30 October at 11 am.\nRemind me the day before.", "reminder.set", """{"what":"dentist appointment","when":"the day before 30 October"}""", true),
        // Invented: must be refused.
        Item("x-name", "remind me to call mom", "reminder.set", """{"what":"call Ravi","when":"tomorrow at 9"}""", false),
        Item("x-date", "remind me to buy diyas before Diwali", "reminder.set", """{"what":"buy diyas","when":"17 October 2025"}""", false),
        Item("x-item", "add eggs to the list", "task.add", """{"title":"eggs and caviar","list":"shopping"}""", false),
        Item("x-recipient", "text Priya that I'm late", "sms.compose", """{"to":"Rahul","text":"I'm late"}""", false),
        Item("x-place", "remind me about the meeting tomorrow", "reminder.set", """{"what":"meeting in Mumbai","when":"tomorrow"}""", false),
        Item("x-month", "set an alarm for 6", "alarm.set", """{"time":"6 am on 3 March"}""", false),
        Item("x-derived-in-message", "tell Priya I'm late", "sms.compose", """{"to":"Priya","text":"pay my bills"}""", false),
        Item("x-list", "add eggs", "task.add", """{"title":"eggs","list":"secret plans"}""", false),
        Item("x-fact-not-found", "remind me three days before the marathon", "reminder.set", """{"what":"marathon","when":"3 days before 12 March 2026"}""", false),
    )

    /** (text, phrase, should match): lookup relevance (a source must be about the fact) and goal relevance. */
    private val relevance = listOf(
        Triple("Find out when my next exam is and make sure I have enough time to prepare", "date of my next examination", true),
        Triple("My next examination is Data Structures on 28 October 2025.", "date of my next exam", true),
        Triple("Australia won the 2023 ICC Men's Cricket World Cup, beating India", "winner of the last men's cricket world cup", true),
        Triple("Diwali 2025 falls on Monday, 20 October 2025.", "date of Diwali 2025", true),
        Triple("My car insurance runs out on 5 November and I've let it lapse before", "reminder to renew insurance", true),
        Triple("Don't let me forget the electricity bill on Monday", "remind about the bill", true),
        Triple("we're out of coffee, stick it on the shopping list", "shopping item", true),
        Triple("My dentist appointment is on 30 October at 11 am. Remind me the day before.", "dentist reminder", true),
        Triple("kal subah 7 baje yaad dilana ki dawai leni hai", "dawai reminder", true),
        Triple("My next examination is Data Structures on 28 October 2025.", "Diwali 2025", false),
        Triple("Diwali's coming up, look up the date", "date of the next Chennai marathon", false),
        Triple("Diwali's coming up and I need diyas", "marathon registration reminder", false),
        Triple("Message number 3", "#{ Romance.getString attacker", false),
    )

    /** Found by the conversation evaluation with the real model (Phase 4): each was a CLU decision, not the model's. */
    @Test fun answerChecksDoNotRejectGoodAnswersForTheirLabel() {
        // The model copied the note's label in front of a correct offline answer; the whole answer was thrown away.
        assertFalse(ai.clu.core.agent.Grounding.echoes("OFFLINE: The Python Global Interpreter Lock (GIL) lets only one thread run Python bytecode at a time."))
        assertTrue(ai.clu.core.agent.Grounding.echoes("OFFLINE:"))
        assertTrue(ai.clu.core.agent.Grounding.echoes("OFFLINE: the web search could not be completed (no internet)."))
        assertTrue(ai.clu.core.agent.Grounding.usableWithoutSearch("The Python Global Interpreter Lock (GIL) lets only one thread run Python bytecode at a time.", subject = "Python GIL"))
    }

    @Test fun researchDecisionsForOrdinaryConversation() {
        val now = 1_760_000_000_000L
        // The owner's own past is for memory, not the web (it was searched because of "yesterday").
        for (q in listOf("What did I have for breakfast yesterday?", "Where did we park last night?", "When did I last call my sister?", "Have I paid the rent this month?"))
            assertNull(ai.clu.core.search.SearchTrigger.detect(q, now), q)
        // …but the world's news stays research even when phrased around the owner.
        assertNotNull(ai.clu.core.search.SearchTrigger.detect("What did I miss in the news today?", now))
        // Asking whether sources agree is asking for sources.
        for (q in listOf("How tall is Mount Everest? Do the sources agree?", "What do different sources say about the age of the universe?"))
            assertNotNull(ai.clu.core.search.SearchTrigger.detect(q, now), q)
        // Unchanged: plain knowledge questions are not forced to research.
        assertNull(ai.clu.core.search.SearchTrigger.detect("Explain how a refrigerator keeps food cold.", now))
    }

    @Test fun groundingIsCalibrated() {
        val falseRejections = mutableListOf<String>(); val falseAcceptances = mutableListOf<String>()
        for (it in items) {
            val (prov, refusal) = engine.admissible(it.tool, Json.parseToJsonElement(it.args) as JsonObject, it.owner, it.steps, it.anchored)
            val admitted = refusal == null
            println("calibration ${if (admitted == it.admit) "ok " else "BAD"} ${it.id}: admitted=$admitted provenance=$prov${refusal?.let { r -> " refusal=$r" } ?: ""}")
            if (it.admit && !admitted) falseRejections += "${it.id}: $refusal"
            if (!it.admit && admitted) falseAcceptances += "${it.id}: $prov"
        }
        for ((text, phrase, expect) in relevance) {
            val got = GoalEngine.about(text, phrase)
            println("relevance ${if (got == expect) "ok " else "BAD"} \"$phrase\" in \"$text\": $got")
            if (expect && !got) falseRejections += "relevance: $phrase"
            if (!expect && got) falseAcceptances += "relevance: $phrase"
        }
        val pos = items.count { it.admit } + relevance.count { it.third }; val neg = items.count { !it.admit } + relevance.count { !it.third }
        println("calibration summary: false rejections ${falseRejections.size}/$pos, false acceptances ${falseAcceptances.size}/$neg")
        assertTrue(falseRejections.isEmpty(), "legitimate translations refused: $falseRejections")
        assertTrue(falseAcceptances.isEmpty(), "invented content admitted: $falseAcceptances")
    }
}
