package com.tripletriad.server

import kotlin.test.Test
import kotlin.test.assertEquals
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

    private companion object {
        /** Six digits, the shape `Codes.issue` produces. Not one it ever would. */
        const val CODE = "424242"
    }
}
