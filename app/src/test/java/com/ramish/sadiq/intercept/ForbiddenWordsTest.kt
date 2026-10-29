package com.ramish.sadiq.intercept

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The voice rule from the name addendum: Sadiq never shames, never accuses, never asks for a vow.
 *
 * This is a plain JVM test on purpose — it reads the resource XML as text, so it needs no Android
 * runtime and runs in milliseconds. Every user-facing string lives in `res/values/strings*.xml`,
 * which is exactly why those strings must never be hard-coded in Kotlin.
 */
class ForbiddenWordsTest {

    /** Words the product may never say to the user. Matched case-insensitively, whole-word. */
    private val forbidden = listOf(
        "lie", "lied", "liar", "lying", "dishonest",
        "promise", "promises", "promised",
        "cheat", "cheating", "cheated",
        "sin", "sinful", "haram", "hypocrite", "munafiq",
        "addict", "addiction", "failure"
    )

    /** "lock" is allowed only in the OEM wizard, where it is the OS's own menu label. */
    private val lockWords = listOf("lock", "locks", "locked", "locking")
    private val lockWhitelist = setOf("strings_onboarding_oem.xml")

    private val resDir = File("src/main/res")
    private val stringTag = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun stringsFiles(): List<File> =
        (resDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values") }
            .flatMap { dir -> (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith("strings") && it.extension == "xml" } }

    private fun entries(file: File): List<Pair<String, String>> =
        stringTag.findAll(file.readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    private fun matches(value: String, word: String): Boolean =
        Regex("""(?<![\w-])${Regex.escape(word)}(?![\w-])""", RegexOption.IGNORE_CASE).containsMatchIn(value)

    @Test
    fun theStringFilesExistAtAll() {
        val files = stringsFiles()
        assertTrue(files.isNotEmpty(), "no strings*.xml found under ${resDir.absolutePath}")
        val total = files.sumOf { entries(it).size }
        assertTrue(total > 100, "expected the app's copy, found only $total strings")
    }

    @Test
    fun noUserFacingStringShamesOrPromises() {
        val hits = mutableListOf<String>()
        for (file in stringsFiles()) {
            for ((name, value) in entries(file)) {
                for (word in forbidden) {
                    if (matches(value, word)) hits += "${file.name}:$name contains '$word' -> $value"
                }
            }
        }
        assertTrue(hits.isEmpty(), "forbidden words in user-facing copy:\n" + hits.joinToString("\n"))
    }

    @Test
    fun lockOnlyAppearsInTheOemWizard() {
        val hits = mutableListOf<String>()
        for (file in stringsFiles()) {
            if (file.name in lockWhitelist) continue
            for ((name, value) in entries(file)) {
                for (word in lockWords) {
                    if (matches(value, word)) hits += "${file.name}:$name contains '$word' -> $value"
                }
            }
        }
        assertTrue(hits.isEmpty(), "lock words outside the OEM wizard:\n" + hits.joinToString("\n"))
    }

    @Test
    fun noStringStillSaysSaduqInsteadOfSadiq() {
        val stale = mutableListOf<String>()
        for (file in stringsFiles()) {
            for ((name, value) in entries(file)) {
                if (value.contains("Saduq", ignoreCase = true)) stale += "${file.name}:$name -> $value"
            }
        }
        assertTrue(stale.isEmpty(), "the old name is still shipping: " + stale.joinToString("; "))
    }
}
