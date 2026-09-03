package com.tripletriad.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The wording of the two mails this server sends, and the header that picks between four languages.
 *
 * No database and no network: these are pure functions over a string. That is the point of them
 * being pure functions — the language rule is the part of the mail flow most likely to be wrong,
 * and it is the only part that can be measured without a provider.
 */
class MailTemplatesTest {

    /** The code is the entire content. A mail that does not carry it is a mail worth nothing. */
    @Test
    fun everyLanguageCarriesTheCode() {
        for (tag in listOf("en", "fr", "de", "ja", null)) {
            assertTrue(
                CODE in MailTemplates.verification(tag, CODE).body,
                "the confirmation mail for ${tag ?: "no language"} did not carry the code",
            )
            assertTrue(
                CODE in MailTemplates.passwordReset(tag, CODE).body,
                "the reset mail for ${tag ?: "no language"} did not carry the code",
            )
        }
    }

    /** And says how long it is good for, because a code with no stated expiry looks broken. */
    @Test
    fun everyLanguageStatesTheExpiry() {
        for (tag in listOf("en", "fr", "de", "ja")) {
            assertTrue(
                EXPIRY_MINUTES.toString() in MailTemplates.verification(tag, CODE).body,
                "the confirmation mail for $tag did not say when the code expires",
            )
        }
    }

    /**
     * `fr`, `fr-FR` and `fr_FR` are the same language.
     *
     * Which is the whole reason `pick` matches on a prefix: the client sends whichever of the three
     * its platform produces, and none of them is wrong.
     */
    @Test
    fun aRegionalTagPicksItsLanguage() {
        val plain = MailTemplates.verification("fr", CODE)

        assertEquals(plain, MailTemplates.verification("fr-FR", CODE))
        assertEquals(plain, MailTemplates.verification("fr_CA", CODE))
        assertEquals(plain, MailTemplates.verification("FR-fr", CODE))
    }

    /** A weighted list is read for its first tag, which is what this client actually sends. */
    @Test
    fun theFirstTagWins() {
        assertEquals(
            MailTemplates.verification("de", CODE),
            MailTemplates.verification("de-DE,de;q=0.9,en;q=0.8", CODE),
        )
    }

    /**
     * An unknown or missing language is English, not a refusal.
     *
     * The judgement is stated in [MailTemplates]'s own KDoc: a mail in the wrong language is an
     * annoyance, a registration refused over a header is a player who cannot sign up.
     */
    @Test
    fun anUnknownLanguageFallsBackToEnglishRatherThanFailing() {
        val english = MailTemplates.verification("en", CODE)

        assertEquals(english, MailTemplates.verification(null, CODE))
        assertEquals(english, MailTemplates.verification("", CODE))
        assertEquals(english, MailTemplates.verification("kl-GL", CODE))
        assertEquals(english, MailTemplates.verification("not a language tag at all", CODE))
    }

    /**
     * The two mails are not the same mail.
     *
     * Worth an assertion because they are near-identical in shape and were written by copying one
     * into the other: a player asked to confirm an address they never registered, or told their
     * password was being reset when it was not, is being told something false.
     */
    @Test
    fun confirmingAnAddressAndResettingAPasswordAreDifferentMessages() {
        for (tag in listOf("en", "fr", "de", "ja")) {
            assertNotEquals(
                MailTemplates.verification(tag, CODE).subject,
                MailTemplates.passwordReset(tag, CODE).subject,
                "the two mails share a subject in $tag",
            )
        }
    }

    /**
     * The subject carries the code too, and that is a decision rather than an accident.
     *
     * On a phone the subject is what the notification shows, so a player reads the code without
     * unlocking anything or opening anything. The cost is that the code sits in a line that
     * intermediate mail servers log; it expires in ten minutes and unlocks nothing on its own,
     * which is the trade being made.
     */
    @Test
    fun theSubjectCarriesTheCode() {
        for (tag in listOf("en", "fr", "de", "ja")) {
            assertTrue(
                CODE in MailTemplates.verification(tag, CODE).subject,
                "the confirmation subject for $tag did not carry the code",
            )
            assertTrue(
                CODE in MailTemplates.passwordReset(tag, CODE).subject,
                "the reset subject for $tag did not carry the code",
            )
        }
    }

    /**
     * Both renderings carry the same code, in every language and both messages.
     *
     * The failure this guards is the one that made a single [Wording] worth building: two
     * renderings assembled separately, one of them interpolating the wrong value or none, and a
     * player reading an HTML mail that disagrees with the text part their client did not show.
     */
    @Test
    fun theTextAndHtmlRenderingsCarryTheSameCode() {
        for (tag in listOf("en", "fr", "de", "ja", null)) {
            for (message in listOf(
                MailTemplates.verification(tag, CODE),
                MailTemplates.passwordReset(tag, CODE),
            )) {
                assertTrue(CODE in message.body, "no code in the text part for ${tag ?: "none"}")
                assertTrue(CODE in message.html, "no code in the HTML part for ${tag ?: "none"}")
            }
        }
    }

    /** A whole document, and one that declares the language it is written in. */
    @Test
    fun theHtmlIsACompleteDocumentInTheChosenLanguage() {
        for (tag in listOf("en", "fr", "de", "ja")) {
            val html = MailTemplates.verification(tag, CODE).html

            assertTrue(html.startsWith("<!DOCTYPE html>"), "no doctype for $tag")
            assertTrue(html.trimEnd().endsWith("</html>"), "unclosed document for $tag")
            assertTrue("""lang="$tag"""" in html, "the HTML for $tag did not declare its language")
        }
    }

    /**
     * And asks for nothing it will not get.
     *
     * Gmail and Outlook strip web fonts and remote stylesheets and then fall back without saying
     * so, which is the worst of both: the design silently becomes Times and nothing reports it.
     * The families named in the template are the ones every client already has, so this asserts
     * the template never grows a link to one it does not.
     */
    @Test
    fun theHtmlLoadsNothingFromTheNetwork() {
        val html = MailTemplates.verification("fr", CODE).html

        for (forbidden in listOf("<link", "@font-face", "fonts.googleapis", "<script", "<img")) {
            assertFalse(forbidden in html, "the mail template reaches for $forbidden")
        }
    }

    private companion object {
        /** Six digits, the shape `Codes.issue` produces. Not one it ever would. */
        const val CODE = "424242"
    }
}
