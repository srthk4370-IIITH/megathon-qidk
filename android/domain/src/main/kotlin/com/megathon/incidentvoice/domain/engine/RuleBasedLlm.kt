package com.megathon.incidentvoice.domain.engine

import com.megathon.incidentvoice.core.FieldDef
import com.megathon.incidentvoice.core.SiteRegistry
import com.megathon.incidentvoice.core.Taxonomy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic stand-in for the LLM: keyword rules that produce the same tool calls (`fill_incident_form`,
 * `update_incident_fields`) the real model is asked for. It is what the app runs on the board until the Llama
 * bundle is pushed, and the safety net if the NPU model is unavailable during a demo.
 *
 * It only ever states what the words support (same rule as the real prompt: omit what is not said), in English and
 * Roman Hinglish. Accuracy on the ML branch's eval set is tracked by `MockLlmEvalTest`.
 */
class RuleBasedLlm(private val tx: Taxonomy, private val registry: SiteRegistry? = null) {

    // ------------------------------------------------------------------------------------------ helpers

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private fun has(t: String, p: String) = rx(p).containsMatchIn(t)

    private val numWords = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
        "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12", "fifteen" to "15", "twenty" to "20", "thirty" to "30",
    )
    private val fillers = rx("\\b(umm+|uh+|you know|basically|like|so|toh|matlab|acha|woh)\\b")

    /** lower-case, fillers removed, immediate word repeats collapsed ("fire fire" → "fire"). */
    private fun clean(s: String): String {
        var t = " " + s.lowercase().replace(Regex("[^\\p{L}\\p{N}:.'\\- ]"), " ") + " "
        t = fillers.replace(t, " ").replace(Regex("\\s+"), " ")
        t = Regex("\\b(\\w+)( \\1\\b)+").replace(t) { it.groupValues[1] }
        return t.trim()
    }

    private val NEG_INJ = "(no ?one|nobody|none|no ?body|no person|not anyone|kisi ko|koi)\\s+(is |was |were |has been |got |ko )?(badly |seriously )?(hurt|heart|injured|harmed|stung|ghayal|chot|nuksaan|dank)" +
        "|no (one )?(injur\\w*|casualt\\w*)|not (hurt|injured)|without (any )?injur\\w*|(chot|dank|nuksaan) (\\w+ )?nahi\\w*|koi ghayal nahi\\w*|ghayal nahi\\w*|(is|are|theek) (fine|okay|ok|theek)|theek hai" +
        "|nobody is inside|no one (is |was )?inside|it was empty"
    private val POS_INJ = "\\b(injur\\w+|hurt|heart his|heart her|bleeding|bled|fractur\\w+|burn(t|ed|s) (his|her|on)|cut (his|her|on)|sprain\\w*|unconscious|collapsed|fainted|not breathing|electric shock|got a shock|" +
        "chot (lagi|lag gayi|hai|aayi)|ghayal|behosh|khoon|jal gaya|current laga|gir gaya|gir gayi|inhaled|coughing badly|slipped|(?<!panel )(?<!ceiling )(?<!rack )(?<!glass )(?<!door )(?<!bottle )(?<!drum )fell (down|from|off)|twisted)\\b"

    // ------------------------------------------------------------------------------------------ classification

    private class Cue(val code: String, val weight: Int, pattern: String) { val re = Regex(pattern, RegexOption.IGNORE_CASE) }

    private val cues = listOf(
        // FIRE
        Cue("FIRE", 6, "caught fire|on fire|fire (broke|started|is still|is under|spread)|aag lag\\w*|aag (abhi|phail|fail)\\w*|flames?|burning smell|explosion|blast|dhamaka"),
        Cue("FIRE", 3, "\\bfire\\b(?! (alarm|drill|extinguisher|brigade|exit|door|team))|\\baag\\b(?! nahi)|\\bsmoke\\b|\\bdhuan\\b|\\bjal rah\\w*"),
        // MEDICAL
        Cue("MEDICAL", 5, "collapsed|fainted|unconscious|not breathing|behosh|chest pain|heat stroke|seizure|fracture\\w*|bleeding|khoon"),
        Cue("MEDICAL", 3, "slipped|(?<!panel )(?<!ceiling )(?<!rack )(?<!glass )(?<!door )(?<!bottle )(?<!drum )fell (down|from|off)|twisted|sprain\\w*|injur\\w+|hurt (his|her)|heart (his|her)|cut (his|her)|burn(t|ed) (his|her)|inhaled|chot (lagi|lag gayi|hai)|gir gay\\w|ghayal(?! nahi)|first aid|ambulance"),
        // WATER
        Cue("WATER", 6, "water leak\\w*|leak\\w* water|water (is )?(leaking|dripping|overflowing)|flood\\w*|pipe (burst|leak\\w*|broke)|burst pipe|paani (leak|tapak|bhar|beh)\\w*|water tank .{0,30}overflow\\w*|se paani"),
        Cue("WATER", 2, "\\bwater\\b|\\bpaani\\b|overflowing|dripping|tapak\\w*|\\bleak\\w*"),
        // ELECTRICAL
        Cue("ELECTRICAL", 6, "spark\\w*|short circuit|electric shock|got a shock|current laga|exposed wir\\w*|khul[ae] taar|power (cut|outage|failure)|light chali|bijli (chali|gayi)|overheating"),
        Cue("ELECTRICAL", 3, "\\bsocket\\b|distribution board|electrical panel|\\bmcb\\b|\\bwiring\\b|\\btaar\\b|\\bcurrent\\b|\\bbijli\\b|\\btripped the (mcb|breaker)"),
        // HAZMAT
        Cue("HAZMAT", 6, "gas leak\\w*|gas ki (smell|badbu)|chemical (spill|leak)\\w*|ammonia|chlorine|\\bacid\\b|caustic|solvent|fumes|toxic|lpg|cylinder (is )?leak\\w*|spill kit"),
        Cue("HAZMAT", 3, "\\bchemical\\w*|\\bgas\\b|\\bspill\\w*|hazardous"),
        // EQUIPMENT
        Cue("EQUIPMENT", 5, "jammed|breakdown|broke down|not (working|restarting|starting)|malfunction\\w*|spindle|faulty parts|grinding noise|vibrat\\w+|kharab|band ho gay\\w|seal se leak"),
        Cue("EQUIPMENT", 3, "\\b(machine|conveyor|compressor|motor|pump|mixer|press|lathe|cnc|boiler|generator|hvac|chiller|crane|hoist)\\b|\\btripped\\b|\\bmashin\\b"),
        // SECURITY
        Cue("SECURITY", 6, "stolen|theft|chori|break[- ]?in|intruder|trespass\\w*|jumped over|without (an )?access|tailgat\\w*|physical fight|\\bfight\\b|assault\\w*|threaten\\w*|vandal\\w*|suspicious|unauthori[sz]ed|ghuspaith|ladai"),
        Cue("SECURITY", 2, "\\bsecurity\\b|\\bguard\\b|\\bbadge\\b|unknown (man|person)"),
        // STRUCTURAL
        Cue("STRUCTURAL", 6, "ceiling (panel )?(fell|collapsed|came down)|false ceiling|\\bcrack\\w*|glass (door )?(broke|shattered|broken)|glass door|shattered|broken glass|sheesh\\w* .{0,20}toot\\w*|kaanch|lift .{0,12}(stuck|atak)|stuck between|wall (collapsed|fell)|chhat|darar|rack (collapsed|fell)|railing|handrail|pothole"),
        Cue("STRUCTURAL", 2, "\\bceiling\\b|\\blift\\b|\\belevator\\b|wet floor|\\bstairs?\\b"),
        // ENVIRONMENTAL
        Cue("ENVIRONMENTAL", 6, "storm drain|into the drain|\\bdrain\\b|\\bnaal[ae]\\b|effluent|(into|onto|on) the soil|\\bsoil\\b|on(to)? the (open )?ground|waste drums|oil spill\\w*|diesel (spill\\w*|leak\\w*)|black smoke from the (boiler|chimney|stack)|chimney|emission|pollut\\w*|waste (dump\\w*|water)|mitti"),
        // VEHICLE
        Cue("VEHICLE", 6, "forklift|\\btruck\\b|\\btanker\\b|\\bcar\\b|motorbike|\\bbike\\b|\\bvehicle\\w*|collided|collision|revers\\w+|takkar|takra\\w*|gaadi|\\bvan\\b|\\bbus\\b"),
        // OTHER
        Cue("OTHER", 9, "false alarm|\\bsnake\\b|beehive|\\bbees\\b|madhumakhi|\\bmonkey\\w*|\\bdog\\b|stray (dog|animal)|\\bsaanp\\b"),
    )

    private data class Classified(val primary: String, val secondary: List<String>, val injuries: String?)

    private fun classify(t0: String): Classified {
        val negInj = has(t0, NEG_INJ)
        val t = rx(NEG_INJ).replace(t0, " ")           // "nobody was hurt" must not vote for MEDICAL
        val posInj = has(t, POS_INJ)
        val score = HashMap<String, Int>()
        for (c in cues) { val n = c.re.findAll(t).count(); if (n > 0) score[c.code] = (score[c.code] ?: 0) + c.weight * minOf(n, 2) }
        fun s(c: String) = score[c] ?: 0

        // disambiguation (mirrors the tie-break rules in the extraction prompt)
        if (s("OTHER") > 0 && has(t, "false alarm|no fire|there was no")) return Classified("OTHER", emptyList(), if (negInj) "NO" else null)
        if (s("FIRE") >= 6) { score["EQUIPMENT"] = s("EQUIPMENT") / 3; score["ELECTRICAL"] = s("ELECTRICAL") / 2 }       // "motor caught fire", FIRE beats ELECTRICAL
        if (has(t, "aag nahi|no fire|sirf emission|only smoke from the (boiler|chimney)")) score["FIRE"] = 0
        if (s("ENVIRONMENTAL") >= 6) score["HAZMAT"] = minOf(s("HAZMAT"), 3)                                                 // release into drain/soil
        if (s("VEHICLE") >= 6) { score["STRUCTURAL"] = minOf(s("STRUCTURAL"), 5); score["EQUIPMENT"] = minOf(s("EQUIPMENT"), 3) } // forklift hit a rack
        if (s("WATER") >= 6) score["EQUIPMENT"] = minOf(s("EQUIPMENT"), 5)
        if (s("EQUIPMENT") >= 5 && s("WATER") <= 4 && !has(t, "water leak|flood|pipe")) score["WATER"] = 0                    // "pump seal leak", "water tank area" as a place
        if (s("SECURITY") >= 6) score["MEDICAL"] = minOf(s("MEDICAL"), 5)                                                     // a fight is SECURITY first
        val waterSource = has(t, "water leak|flood|pipe (burst|leak)|water is leaking|rain ?water|from the roof|roof (is )?leak")
        if (s("ELECTRICAL") >= 6 && !waterSource) score["WATER"] = minOf(s("WATER"), 2)
        if (waterSource && s("ELECTRICAL") > 0) score["WATER"] = s("WATER") + s("ELECTRICAL")              // water reaching a panel is a WATER incident with an electrical consequence
        if (!posInj) score["MEDICAL"] = minOf(s("MEDICAL"), 2)

        val ranked = score.filter { it.value > 0 && it.key != "OTHER" }.entries.sortedByDescending { it.value }
        var primary = ranked.firstOrNull()?.key ?: if (s("OTHER") > 0) "OTHER" else if (posInj) "MEDICAL" else "OTHER"
        // person injured → MEDICAL primary unless an ongoing fire / gas / electrical hazard caused it
        val hazard = listOf("FIRE", "HAZMAT", "ELECTRICAL").filter { s(it) >= 6 }.maxByOrNull { s(it) }
        if (posInj && hazard != null) primary = hazard                                                                    // the hazard is primary, MEDICAL secondary
        else if (posInj && primary != "MEDICAL" && primary != "SECURITY" && s("MEDICAL") >= 3) primary = "MEDICAL"
        val secondary = ArrayList<String>()
        if (posInj && primary != "MEDICAL") secondary += "MEDICAL"
        for ((k, v) in ranked) if (k != primary && k !in secondary && v >= 6 && secondary.size < 2) secondary += k
        return Classified(primary, secondary.take(2), if (posInj) "YES" else if (negInj) "NO" else null)
    }

    // ------------------------------------------------------------------------------------------ slots

    private val TIME = listOf(
        "(just now|right now|abhi abhi)",
        "((about |around |almost )?(half an hour|an hour|a few minutes|a while|\\d+ (minutes?|mins?|hours?|hrs?)|(one|two|three|four|five|six|ten|fifteen|twenty|thirty) (minutes?|hours?)) (ago|back))",
        "((aadha ghanta|thodi der|\\d+ (minute|ghante|ghanta)|(ek|do|teen|char|paanch|das|pandrah) (minute|ghante|ghanta)) pehle)",
        "((last night|yesterday|today|kal|aaj)( (morning|afternoon|evening|night|subah|shaam|raat))?( (at|around|about) \\d{1,2}([:.]\\d{2})?( ?(am|pm|a m|p m))?)?)",
        "((at |around |about |at around |lagbhag |kareeb )?\\d{1,2}([:.]\\d{2})? ?(am|pm|a m|p m|baje)( in the (morning|afternoon|evening))?)",
        "((at |around |about |at around )\\d{1,2}([:.]\\d{2})?( in the (morning|afternoon|evening)| at night))",
        "((dopahar|subah|shaam|raat)( ko)?( lagbhag| kareeb)? ?(saadhe |sawa |paune )?(\\d{1,2}|ek|do|teen|char|paanch|chhe|saat|aath|nau|das|gyarah|barah)( baje)?)",
        "(this (morning|afternoon|evening)|aaj (subah|shaam|dopahar)|since (this )?morning)",
    ).map { rx("\\b$it\\b") }

    private fun timePhrase(t: String): String? {
        for (re in TIME) re.find(t)?.let { m ->
            var s = m.value.trim().replace(Regex("\\b(a m)\\b"), "am").replace(Regex("\\b(p m)\\b"), "pm")
            for ((w, d) in numWords) s = s.replace(Regex("\\b$w\\b"), d)
            return s
        }
        return null
    }

    private val qualifiers = listOf(
        "ke paas" to "near", "ke peeche" to "behind", "ke bahar" to "outside", "ke saamne" to "in front of", "ke andar" to "inside",
    )

    /** Prefer a known site location (name or alias) found in the text; else the phrase after a place preposition. */
    private fun location(t: String): String? {
        var norm = com.megathon.incidentvoice.ask.SpokenPlaces.normalise(t, registry)
        var best: Pair<String, IntRange>? = null
        for (loc in registry?.locations.orEmpty()) {
            for (name in listOf(loc.name) + loc.aliases) {
                val n = name.lowercase()
                if (n.length < 4) continue
                val i = norm.indexOf(" $n")
                if (i >= 0 && (best == null || n.length > best!!.first.length)) best = loc.name to (i + 1..i + n.length)
            }
        }
        best?.let { (name, range) ->
            val before = norm.substring(0, range.first).trimEnd()
            val after = norm.substring(minOf(norm.length, range.last + 1)).trimStart()
            val q = when {
                before.endsWith(" near") || before.endsWith(" next to") || before.endsWith(" beside") -> "near "
                before.endsWith(" near the") -> "near "
                before.endsWith(" behind") || before.endsWith(" behind the") -> "behind "
                before.endsWith(" outside") || before.endsWith(" outside the") -> "outside "
                else -> qualifiers.firstOrNull { after.startsWith(it.first) }?.let { it.second + " " } ?: ""
            }
            return q + name
        }
        rx("\\b(near|behind|outside|inside|in front of|next to|beside|at|in)\\s+(the\\s+)?([a-z0-9][a-z0-9 \\-]{2,28}?)(?=\\s+(around|about|at|since|and|yesterday|today|last|this|just|\\d|a few|half|an hour|caught|is|was|has|started|got)\\b|[.,]|$)")
            .find(norm.trim())?.let { m ->
                val q = m.groupValues[1].lowercase(); val place = m.groupValues[3].trim()
                if (place.split(' ').size <= 5) return (if (q in setOf("at", "in", "inside")) "" else "$q ") + place
            }
        return null
    }

    private fun sentence(s: String): String = s.trim().trimEnd('.', ',', ' ').replaceFirstChar { it.uppercase() }

    // ------------------------------------------------------------------------------------------ enum rules (shared by extract + patch)

    /** field key → ordered (value, pattern). First match wins. */
    private val enumRules: Map<String, List<Pair<String, String>>> = mapOf(
        "fire_status" to listOf(
            "EXTINGUISHED" to "put (it |the fire )?out|extinguished|fire is out|bujh\\w*|doused",
            "CONTAINED" to "under control|contained|control mein",
            "SMOKE_ONLY" to "only smoke|just smoke|sirf dhuan|no flames",
            "ACTIVE" to "still (burning|on|there)|is spreading|fire is still|abhi bhi (jal|lagi)|not under control|burning",
        ),
        "evacuation_status" to listOf(
            "NOT_REQUIRED" to "no need to evacuate|evacuation (is )?not (needed|required)",
            "IN_PROGRESS" to "evacuating|being evacuated|evacuation (is )?(going on|in progress)|khaali kar rahe",
            "NOT_STARTED" to "not (been )?evacuated|no evacuation|haven'?t evacuated|khaali nahi",
            "COMPLETED" to "(have |has been |been |was |were |is )?evacuated|everyone is out|khaali (karwa|kar) diya",
        ),
        "fire_brigade_called" to listOf("NO" to "fire brigade (was |has )?not|not called (the )?fire brigade|didn'?t call", "YES" to "fire brigade (has been |was |is )?(called|informed|on the way|coming)|called the fire brigade|fire brigade ko (call|bula)"),
        "patient_condition" to listOf(
            "NOT_BREATHING" to "not breathing|saans nahi",
            "UNCONSCIOUS" to "unconscious|not conscious|behosh|not responding|unresponsive|passed out",
            "CONSCIOUS_DISTRESSED" to "in (a lot of |severe |great )?pain|cannot (stand|walk|move)|can'?t (stand|walk|move)|khada nahi|bleeding (heavily|a lot)|struggling|distress|coughing badly|dizzy|bahut dard",
            "CONSCIOUS_STABLE" to "conscious|awake|talking|responsive|stable|hosh mein|baat kar|is (fine|okay|ok)|theek hai|breathing",
        ),
        "first_aid_given" to listOf("NO" to "no first aid|first aid (was |has )?not|first aid nahi", "YES" to "first aid (was |has been |is )?(given|done|provided)|gave (him |her |them )?(first aid|water|ors)|first aid de di\\w*|bandage\\w*|applied (ice|pressure)"),
        "ambulance_called" to listOf(
            "NOT_NEEDED" to "no ambulance (is |was )?(needed|required)|ambulance (is |was )?not (needed|required)|doesn'?t need an ambulance",
            "NO" to "ambulance (was |has )?not (been )?called|no ambulance|ambulance nahi",
            "YES" to "ambulance (has been |was |is )?(called|caught|on the way|coming)|called (an |the )?ambulance|ambulance (bula|aa rahi)\\w*",
        ),
        "leak_status" to listOf(
            "STOPPED" to "has stopped|it stopped|stopped now|is stopped|no longer leaking|ruk gay\\w|band ho gay\\w|fixed|leak stopped|it'?s stopped",
            "CONTROLLED" to "under control|controlled|slowed|reduced|bucket",
            "ONGOING" to "still (leaking|dripping|overflowing|flowing|coming)|is (leaking|dripping|overflowing)|abhi bhi|ongoing|continu\\w+|tapak raha",
        ),
        "electrical_hazard" to listOf(
            "SHOCK" to "electric shock|got a shock|shock laga|current laga|electrocut\\w*",
            "SPARKING" to "spark\\w*",
            "SHORT_CIRCUIT" to "short circuit",
            "EXPOSED_WIRING" to "exposed wir\\w*|open wir\\w*|khul[ae] taar|naked wire",
            "OVERHEATING" to "overheat\\w*|very hot|hot to touch|burning smell from",
            "POWER_OUTAGE" to "power (cut|outage|failure)|no power|light chali|bijli (chali|gayi)|blackout",
        ),
        "power_isolated" to listOf("NO" to "power is still on|not (been )?(switched|turned) off|still (live|on)|band nahi", "YES" to "(switched|turned|shut) off|isolated the power|power (is |has been )?(off|isolated|cut off)|mcb (off|band)|band kar di\\w*"),
        "release_status" to listOf(
            "CONTAINED" to "contain\\w*|spill kit|absorbent|under control",
            "STOPPED" to "has stopped|stopped|closed the (valve|drum|cylinder)|drum has been closed|valve (is |was )?closed|ruk gay\\w",
            "ONGOING" to "still (leaking|spilling|coming|strong)|is leaking|smell is (very )?strong|ongoing|abhi bhi|there is an? \\w+ leak",
        ),
        "area_evacuated" to listOf("PARTIAL" to "partly|partially|some people", "NO" to "not (been )?evacuated|no one (has )?left", "YES" to "evacuated|moved (people |everyone )?away|khaali karwa|cleared the area|workers have moved"),
        "operational_status" to listOf(
            "STOPPED" to "stopped completely|has stopped|is stopped|not restarting|not starting|not working|shut ?down|band kar diya|band ho gay\\w|tripped|jammed and stopped|we stopped",
            "DEGRADED" to "running slow\\w*|slow\\w*|faulty parts|reduced|partially|vibrat\\w+|noise|degraded",
            "RUNNING" to "still running|is running|working normally|chal rah\\w",
        ),
        "lockout_tagout" to listOf("NO" to "lockout (tagout )?(was |has )?not", "YES" to "lockout tagout (has been |was )?done|loto (done|applied)|locked out"),
        "security_event" to listOf(
            "THEFT" to "stolen|theft|chori|missing from",
            "ASSAULT" to "fight|assault\\w*|hit (him|her)|punched|ladai|maar ?peet",
            "INTRUSION" to "jumped over|break[- ]?in|broke in|intruder|trespass\\w*|ghus\\w*",
            "UNAUTHORIZED_ACCESS" to "without (an )?access|tailgat\\w*|following an employee|unauthori[sz]ed|no badge",
            "VANDALISM" to "vandal\\w*|graffiti|damaged on purpose|tod ?phod",
            "THREAT" to "threat\\w*|dhamki",
            "SUSPICIOUS_ACTIVITY" to "suspicious|loitering|unknown (man|person|bag)|unattended bag",
        ),
        "security_informed" to listOf("NO" to "(have |has )?not (yet )?(told|informed) security|security (was |has )?not|security ko nahi", "YES" to "security (has been |was |is |team )?(informed|told|called|separated|alerted)|informed security|told security|security ko bata"),
        "police_informed" to listOf("NO" to "police (was |were |has |have )?not|police ko (call )?nahi", "YES" to "police (has been |have been |was |were )?(informed|called)|called the police"),
        "area_cordoned" to listOf("NO" to "not (been )?(cordoned|blocked|barricaded)|nobody has blocked", "YES" to "cordon\\w*|barricad\\w*|blocked (off )?the area|area (is |has been )?(blocked|closed|sealed)|block kar|closed off|taped off|wet[- ]floor sign"),
        "people_trapped" to listOf("NO" to "nobody is inside|no one (is |was )?(inside|trapped)|it was empty|nobody (is |was )?trapped", "YES" to "trapped|stuck inside|phas\\w* (gay|hu)\\w*"),
        "release_medium" to listOf("DRAIN" to "drain|naal[ae]|sewer", "WATER_BODY" to "river|lake|pond|nadi", "SOIL" to "soil|ground|mitti|land", "AIR" to "\\bair\\b|chimney|smoke|emission|hawa|stack"),
        "containment_status" to listOf(
            "CONTAINED" to "contain\\w*|under control|control mein|absorbent|booms?|blocked the drain",
            "STOPPED" to "has stopped|stopped|ruk gay\\w",
            "ONGOING" to "still (flowing|spilling|leaking|overflowing|coming)|is (flowing|overflowing|spreading)|abhi bhi|ongoing",
        ),
        "collision_type" to listOf(
            "VEHICLE_VEHICLE" to "(car|truck|bike|motorbike|forklift|van|bus) and (a |the )?(car|truck|bike|motorbike|forklift|van|bus)|another (vehicle|car|truck|forklift)|collided with (a |the )?(car|truck|bike|van|bus)|two (vehicles|cars|trucks)",
            "ROLLOVER" to "rolled over|overturn\\w*|toppled|palat\\w*",
            "VEHICLE_PERSON" to "hit (a |the )?(person|worker|man|woman|pedestrian|him|her)|ran over|ko takkar|knocked (him|her|down)",
            "VEHICLE_OBJECT" to "hit (a |the )?(storage )?(rack|pillar|wall|gate|pole|door|barrier|shelf|column|dock)|into (a |the )?(rack|pillar|wall|gate|pole)|pillar|rack|wall|se takra",
        ),
        "hazard_ongoing" to listOf("NO" to "no longer|has stopped|is out|resolved|under control", "YES" to "still (leaking|burning|on|flowing|there)|ongoing|abhi bhi"),
        "electrical_risk" to listOf("YES" to "(computer|wires?|socket|panel|electrical|plug)s? (are |is )?(below|under|near|neeche)|near (the )?(socket|panel|wires?)|neeche computer", "NO" to "no electrical"),
        "water_supply_isolated" to listOf("YES" to "(closed|shut|isolated) the (main )?(valve|water supply)|valve (is |was )?(closed|shut)"),
        "traffic_blocked" to listOf("YES" to "(road|way|rasta|gate|traffic) (is )?block\\w*", "NO" to "not blocked|traffic is (moving|normal)"),
    )

    private fun enumByRules(key: String, def: FieldDef, text: String): String? {
        val allowed = def.enum ?: return null
        enumRules[key]?.forEach { (v, p) -> if (v in allowed && has(text, "\\b($p)")) return v }
        return null
    }

    private val SEV = listOf(
        "CRITICAL" to "critical|life[- ]threatening|in danger|jaan ko khatra|very dangerous|out of control",
        "LOW" to "minor|not serious|nothing serious|small (thing|issue|problem)|chhot[ia]|serious nahi|koi serious baat nahi|not a big|low",
        "MEDIUM" to "moderate|medium|manageable|thoda serious",
        "HIGH" to "serious|severe|major|bad\\b|badi|kaafi|high|dangerous",
    )
    private fun severity(text: String): String? = SEV.firstOrNull { has(text, "\\b(${it.second})") }?.first

    private fun yesNo(text: String): String? {
        val no = has(text, "\\b(no|nope|nahi|nahin|not|never|none|nobody|no one|hasn'?t|haven'?t|didn'?t|wasn'?t)\\b")
        val yes = has(text, "\\b(yes|yeah|yep|haan|han|ji|done|already|called|informed|given|sure|correct)\\b")
        return when { yes && !no -> "YES"; no && !yes -> "NO"; yes -> if (has(text, "^\\s*(yes|yeah|haan|ji)\\b")) "YES" else "NO"; else -> null }
    }

    /** A bare "yes"/"no" to a non-YES/NO enum question means the affirmative/negative reading of that question. */
    private val yesMeans = mapOf(
        "patient_condition" to ("CONSCIOUS_STABLE" to "UNCONSCIOUS"), "fire_status" to ("ACTIVE" to "EXTINGUISHED"),
        "evacuation_status" to ("COMPLETED" to "NOT_STARTED"), "leak_status" to ("ONGOING" to "STOPPED"),
        "release_status" to ("ONGOING" to "STOPPED"), "containment_status" to ("ONGOING" to "CONTAINED"),
        "operational_status" to ("STOPPED" to "RUNNING"),
    )

    private val UNKNOWN = "don'?t know|do not know|dont know|not sure|no idea|pata nahi|maloom nahi|can'?t say|unsure"
    private val SKIP = "\\b(skip|baad mein|chhodo|later|next question)\\b"

    // ------------------------------------------------------------------------------------------ tool calls

    fun extract(transcript: String): JsonObject {
        val t = clean(transcript)
        val c = classify(t)
        val p = LinkedHashMap<String, JsonElement>()
        fun put(k: String, v: String?) { if (!v.isNullOrBlank()) p[k] = JsonPrimitive(v) }
        put("lang", if (has(t, "\\b(hai|tha|thi|nahi|mein|ke|ko|aur|ne|kya|raha|gaya|hua|par|se)\\b")) "hinglish" else "en")
        put("incident_type", c.primary)
        if (c.secondary.isNotEmpty()) p["secondary_types"] = JsonArray(c.secondary.map { JsonPrimitive(it) })
        val first = transcript.trim().split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty()
        put("title", sentence(first).take(70))
        put("description", sentence(transcript).take(280) + ".")
        put("location", location(t))
        put("time", timePhrase(t))
        put("injuries", c.injuries)
        if (has(t, "\\b(${SEV.joinToString("|") { it.second }})") && has(t, "serious|minor|critical|severe|moderate|khatra|chhot|life")) put("severity", severity(t))
        if (has(t, "almost (hit|fell)|nearly|near miss|bal bal|just missed")) p["near_miss"] = JsonPrimitive(true)

        val details = LinkedHashMap<String, JsonElement>()
        for (def in tx.detailFields(listOf(c.primary) + c.secondary)) {
            if (def.type == "enum") enumByRules(def.key, def, t)?.let { details[def.key] = JsonPrimitive(it) }
        }
        if (c.primary == "VEHICLE" || "VEHICLE" in c.secondary) {
            val v = rx("\\b(forklift|delivery truck|truck|tanker|car|motorbike|bike|van|bus)\\b").findAll(t).map { it.value }.distinct().toList()
            if (v.isNotEmpty()) details["vehicles_involved"] = JsonArray(v.map { JsonPrimitive(it) })
        }
        if (c.primary == "HAZMAT") rx("\\b(ammonia|chlorine|sulphuric acid|hydrochloric acid|acid|caustic soda|lpg|solvent|diesel|petrol)\\b").find(t)?.let { details["substance"] = JsonPrimitive(it.value) }
        if (c.primary == "ENVIRONMENTAL") rx("\\b(diesel|oil|effluent|black smoke|chemical waste|sewage|paint)\\b").find(t)?.let { details["pollutant"] = JsonPrimitive(it.value) }
        // the real prompt keeps hazard_ongoing at top level
        details.remove("hazard_ongoing")
        if (details.isNotEmpty()) p["details"] = JsonObject(details)
        return JsonObject(mapOf("name" to JsonPrimitive("fill_incident_form"), "parameters" to JsonObject(p)))
    }

    /** @param asked taxonomy keys (or `time`) from the "Asked fields:" line of the patch prompt. */
    fun patch(asked: List<String>, answer: String): JsonObject {
        val t = clean(answer)
        val set = LinkedHashMap<String, JsonElement>()
        val unknown = ArrayList<String>()
        val skipped = has(t, SKIP)
        // split into clauses so "It's minor. I don't know if anyone is hurt." marks only injuries unknown
        val clauses = answer.split(Regex("(?<=[.!?;])\\s+|,\\s+(?=(but|and|i|pata|not sure)\\b)")).map { clean(it) }.filter { it.isNotBlank() }.ifEmpty { listOf(t) }
        var textUsed = false
        for (key in asked) {
            val def = tx.field(if (key == "time") "occurred_at" else key) ?: continue
            val topic = when (def.key) {
                "injuries" -> "hurt|injur|chot|ghayal|anyone"
                "severity" -> "serious|minor|moderate|critical|severe|danger|khatra"
                else -> def.key.split('_').first()
            }
            val mine = clauses.filter { has(it, topic) }.ifEmpty { clauses }
            val unknownHere = mine.any { has(it, UNKNOWN) } && (asked.size == 1 || clauses.filter { has(it, UNKNOWN) }.all { has(it, topic) } || clauses.size == 1)
            if (skipped) continue
            val text = mine.filterNot { has(it, UNKNOWN) }.joinToString(" ").ifBlank { if (unknownHere) "" else t }
            val value: JsonElement? = when {
                def.key == "severity" -> severity(text)?.let { JsonPrimitive(it) }
                def.key == "injuries" -> (if (has(text, NEG_INJ)) "NO" else if (has(text, POS_INJ)) "YES" else yesNo(text))?.let { JsonPrimitive(it) }
                def.type == "enum" -> {
                    val allowed = def.enum.orEmpty()
                    val v = enumByRules(def.key, def, text)
                        ?: allowed.firstOrNull { it != "UNKNOWN" && text.contains(it.lowercase().replace('_', ' ')) }
                        ?: yesNo(text)?.let { yn -> if (allowed.containsAll(listOf("YES", "NO"))) yn else yesMeans[def.key]?.let { m -> if (yn == "YES") m.first else m.second }?.takeIf { it in allowed } }
                    v?.let { JsonPrimitive(it) }
                }
                def.type == "bool" -> yesNo(text)?.let { JsonPrimitive(it == "YES") }
                def.type == "int" -> Regex("\\d+").find(text)?.let { JsonPrimitive(it.value.toInt()) }
                def.type == "string_list" -> if (!textUsed && text.isNotBlank()) { textUsed = true; JsonArray(text.split(Regex(",| and ")).map { it.trim() }.filter { it.length > 1 }.map { JsonPrimitive(it) }) } else null
                def.key == "occurred_at" -> timePhrase(text)?.let { JsonPrimitive(it) }
                else -> if (!textUsed && text.isNotBlank() && !has(text, "^(yes|no|haan|nahi)$")) { textUsed = true; JsonPrimitive(sentence(mine.filterNot { has(it, UNKNOWN) }.joinToString(" ").ifBlank { answer })) } else null
            }
            if (value != null) set[key] = value else if (unknownHere) unknown += key
        }
        val params = LinkedHashMap<String, JsonElement>()
        params["lang"] = JsonPrimitive(if (has(t, "\\b(hai|nahi|pata|haan|chhodo|baad)\\b")) "hinglish" else "en")
        params["set"] = JsonObject(set)
        params["unknown"] = JsonArray(unknown.map { JsonPrimitive(it) })
        params["skipped"] = JsonPrimitive(skipped)
        return JsonObject(mapOf("name" to JsonPrimitive("update_incident_fields"), "parameters" to JsonObject(params)))
    }
}
