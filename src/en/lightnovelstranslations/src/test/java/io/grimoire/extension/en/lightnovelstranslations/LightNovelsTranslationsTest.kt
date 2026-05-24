package io.grimoire.extension.en.lightnovelstranslations

import io.grimoire.api.model.NovelPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LightNovelsTranslationsTest {

    private val source = LightNovelsTranslations()

    private fun fixture(name: String): Document {
        val html = checkNotNull(javaClass.getResourceAsStream("/$name")) {
            "missing fixture: /$name"
        }.bufferedReader().use { it.readText() }
        return Jsoup.parse(html, "https://lightnovelstranslations.com/")
    }

    private fun pagesFromFixture(name: String): List<NovelPage> {
        val doc = fixture(name)
        return doc.select(source.pageListSelector())
            .mapIndexed { i, el -> source.pageFromElement(el, i) }
    }

    private fun pagesFromHtml(html: String): List<NovelPage> {
        val doc = Jsoup.parse(html, "https://lightnovelstranslations.com/")
        return doc.select(source.pageListSelector())
            .mapIndexed { i, el -> source.pageFromElement(el, i) }
    }

    // --- Scene-break (<hr/>) handling ----------------------------------

    @Test
    fun `hr inside text_story produces exactly one separator page`() {
        val pages = pagesFromFixture("i-will-save-you-part-01.html")
        val separators = pages.filter { it.isSeparator }
        assertEquals(1, separators.size, "expected exactly one <hr/> separator in this chapter")
        val sep = separators.single()
        assertEquals("", sep.text)
        assertNull(sep.imageUrl)
        assertNull(sep.formattedText)
    }

    @Test
    fun `separator sits between the temple scene and the cafe scene`() {
        val pages = pagesFromFixture("i-will-save-you-part-01.html")
        val sepIdx = pages.indexOfFirst { it.isSeparator }
        assertTrue(sepIdx in 1 until pages.size - 1, "separator should not be first or last")
        val before = pages[sepIdx - 1].text
        val after = pages[sepIdx + 1].text
        assertTrue(
            before.contains("Are you free at the moment"),
            "page before separator should be the cafe invitation, was: $before",
        )
        assertTrue(
            after.contains("Can I really order anything"),
            "page after separator should open the cafe scene, was: $after",
        )
    }

    @Test
    fun `part-09 fixture has exactly one separator page`() {
        // Part 09 also has a single <hr/> mid-chapter (line 737 in the
        // captured fixture). The count is deterministic against the
        // snapshot — a regression would either drop it or double-count.
        val separators = pagesFromFixture("life-of-an-adventurer-in-a-harsh-world-part-09.html")
            .filter { it.isSeparator }
        assertEquals(1, separators.size)
    }

    // --- formattedText: italics and bold --------------------------------

    @Test
    fun `inner-monologue em is preserved as italic in formattedText`() {
        val pages = pagesFromFixture("i-will-save-you-part-01.html")
        // Line 608: <p><em>I need more information.</em></p> — Hikaru's
        // internal thought, the canonical italic case on this site.
        val thought = pages.firstOrNull {
            it.formattedText == "<i>I need more information.</i>"
        }
        assertNotNull(
            thought,
            "expected a page whose formattedText is exactly \"<i>I need more information.</i>\"",
        )
        assertEquals("I need more information.", thought!!.text)
    }

    @Test
    fun `strong inside em renders as bold inside italic`() {
        // Part 09 line 663 mixes <em> and <strong> for in-thought emphasis.
        val pages = pagesFromFixture("life-of-an-adventurer-in-a-harsh-world-part-09.html")
        val emWithStrong = pages.firstOrNull {
            it.formattedText?.contains("<b>Group Cloaking</b>") == true
        }
        assertNotNull(emWithStrong, "expected a page that highlights Group Cloaking in bold")
        val html = emWithStrong!!.formattedText!!
        // The bold span lives inside the wider <i> wrapper for the thought.
        assertTrue(
            html.contains("<i>") && html.contains("</i>") &&
                html.indexOf("<i>") < html.indexOf("<b>Group Cloaking</b>"),
            "<b>Group Cloaking</b> should sit inside the surrounding <i>…</i>, got: $html",
        )
    }

    // --- formattedText: br and indentation ------------------------------

    @Test
    fun `soul board paragraph emits br between stat rows`() {
        val pages = pagesFromFixture("life-of-an-adventurer-in-a-harsh-world-part-09.html")
        val vitality = pages.firstOrNull { it.formattedText?.contains("【Vitality】") == true }
        assertNotNull(vitality, "expected a page containing the 【Vitality】 stat block")
        val html = vitality!!.formattedText!!
        assertTrue(
            html.contains("【Vitality】<br>"),
            "【Vitality】 should be followed by a <br>, got: ${html.take(200)}",
        )
        assertTrue(
            html.contains("<br>&nbsp;&nbsp;【Natural Recovery】"),
            "【Natural Recovery】 should sit on its own indented line after a <br>, got: ${html.take(200)}",
        )
    }

    @Test
    fun `soul board preserves margin-left indentation hierarchy`() {
        val pages = pagesFromFixture("life-of-an-adventurer-in-a-harsh-world-part-09.html")
        val html = pages.first { it.formattedText?.contains("【Vitality】") == true }
            .formattedText!!

        // 【Immunity】 is margin-left:15px (2 nbsp). 【Magic Resistance】
        // is margin-left:30px (4 nbsp). The latter should have strictly
        // more leading nbsp after its preceding <br>.
        val immunityCount = nbspBefore(html, "【Immunity】")
        val magicCount = nbspBefore(html, "【Magic Resistance】")
        val vitalityCount = nbspBefore(html, "【Vitality】")

        assertEquals(0, vitalityCount, "【Vitality】 sits at the top of the paragraph")
        assertEquals(2, immunityCount, "【Immunity】 is 15px indented (2 nbsp)")
        assertEquals(4, magicCount, "【Magic Resistance】 is 30px indented (4 nbsp)")
    }

    /**
     * Counts `&nbsp;` entities immediately preceding [marker] in [html], up
     * to the previous `<br>` (or the start of the string).
     */
    private fun nbspBefore(html: String, marker: String): Int {
        val idx = html.indexOf(marker)
        require(idx >= 0) { "marker '$marker' not found in $html" }
        val prefix = html.substring(0, idx)
        val lastBr = prefix.lastIndexOf("<br>")
        val tail = if (lastBr < 0) prefix else prefix.substring(lastBr + 4)
        // Tail should be pure `&nbsp;` runs at this point.
        return Regex("&nbsp;").findAll(tail).count()
    }

    // --- Regression / no-op cases --------------------------------------

    @Test
    fun `plain paragraph populates text and formattedText with the same string`() {
        val pages = pagesFromHtml(
            """<html><body><div class="text_story"><p>Hello world.</p></div></body></html>""",
        )
        assertEquals(1, pages.size)
        val page = pages.single()
        assertEquals("Hello world.", page.text)
        assertEquals("Hello world.", page.formattedText)
        assertFalse(page.isSeparator)
        assertNull(page.imageUrl)
    }

    @Test
    fun `image-wrapped paragraph still maps to imageUrl`() {
        val pages = pagesFromHtml(
            """
            <html><body><div class="text_story">
              <p><img src="https://lightnovelstranslations.com/illustration.jpg"/></p>
            </div></body></html>
            """.trimIndent(),
        )
        val page = pages.single()
        assertEquals("https://lightnovelstranslations.com/illustration.jpg", page.imageUrl)
        assertEquals("", page.text)
        assertNull(page.formattedText)
        assertFalse(page.isSeparator)
    }

    @Test
    fun `hr without a stylesheet still produces a separator`() {
        // Guard against future markup that wraps <hr/> in attributes or self-closes oddly.
        val pages = pagesFromHtml(
            """
            <html><body><div class="text_story">
              <p>before</p>
              <hr class="something" />
              <p>after</p>
            </div></body></html>
            """.trimIndent(),
        )
        assertEquals(3, pages.size)
        assertEquals("before", pages[0].text)
        assertTrue(pages[1].isSeparator)
        assertEquals("after", pages[2].text)
    }
}
