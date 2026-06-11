
fun main() {
    val testCases = listOf(
        TestCase(
            name = "Basic match and remove",
            title = "重要",
            text = "会議は @# 10時からです",
            globalKeywords = setOf("@#"),
            appKeywords = emptySet(),
            expectedMatch = true,
            expectedSpeech = "LINEから通知です。 重要。 会議は  10時からです"
        ),
        TestCase(
            name = "Sequence mismatch (XXX@XXX#)",
            title = "テスト",
            text = "あ@い#う",
            globalKeywords = setOf("@#"),
            appKeywords = emptySet(),
            expectedMatch = false,
            expectedSpeech = ""
        ),
        TestCase(
            name = "Both global and app keywords",
            title = "URGENT",
            text = "[ALARM] Wake up!",
            globalKeywords = setOf("URGENT"),
            appKeywords = setOf("[ALARM]"),
            expectedMatch = true,
            expectedSpeech = "LINEから通知です。 。  Wake up!"
        )
    )

    for (tc in testCases) {
        val result = simulateNotification(tc)
        println("Test: ${tc.name}")
        println("  Match: ${result.matched} (Expected: ${tc.expectedMatch})")
        if (result.matched) {
            println("  Speech: \"${result.speech}\"")
        }
        val pass = result.matched == tc.expectedMatch && (!tc.expectedMatch || result.speech == tc.expectedSpeech)
        println("  Result: ${if (pass) "PASS" else "FAIL"}")
        println()
    }
}

data class TestCase(
    val name: String,
    val title: String,
    val text: String,
    val globalKeywords: Set<String>,
    val appKeywords: Set<String>,
    val expectedMatch: Boolean,
    val expectedSpeech: String
)

data class SimResult(val matched: Boolean, val speech: String)

fun simulateNotification(tc: TestCase): SimResult {
    val allKeywords = tc.globalKeywords + tc.appKeywords
    val content = "${tc.title} ${tc.text}"
    
    val found = if (allKeywords.isNotEmpty()) {
        allKeywords.any { content.contains(it, ignoreCase = true) }
    } else {
        true
    }

    if (!found) return SimResult(false, "")

    var cleanTitle = tc.title
    var cleanText = tc.text
    allKeywords.forEach { keyword ->
        cleanTitle = cleanTitle.replace(keyword, "", ignoreCase = true)
        cleanText = cleanText.replace(keyword, "", ignoreCase = true)
    }

    val appName = "LINE"
    val speechText = "${appName}から通知です。 ${cleanTitle}。 ${cleanText}"
    
    return SimResult(true, speechText)
}
