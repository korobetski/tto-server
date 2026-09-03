package com.tripletriad.server

/**
 * The two messages this server sends, in the four languages the client ships.
 *
 * ### Why the wording lives here and not in the client's bundles
 *
 * Because the client is not what sends them. `app-*.json` is loaded by a Compose resource reader
 * into a running app; a mail is composed by this process, for somebody who may not have the app
 * open. Duplicating four short strings is the cheaper of the two mistakes — the alternative is
 * teaching the server to read the client's resource bundles, which is a build-time coupling
 * between two repositories for eight sentences.
 *
 * ### How the language is chosen
 *
 * From `Accept-Language`, which the client sets on every request. Not from a field on the request
 * body: that would have been a protocol change to carry something HTTP already has a header for,
 * and the header is also what a future web page would send without being asked.
 *
 * An unknown or absent language falls back to English rather than refusing. A confirmation mail in
 * the wrong language is a mild annoyance; a registration refused because a header was missing is a
 * player who cannot sign up.
 *
 * ### Why each message is written twice
 *
 * Every [MailMessage] carries a plain-text *and* an HTML rendering of the same [Wording], and both
 * go out in the same send. Two reasons, neither decorative:
 *
 * - An HTML part with no text alternative is a shape spam filters score against, and these are the
 *   two mails a player cannot afford to lose — a reset code in a junk folder is a locked-out
 *   account.
 * - Without an HTML part the provider supplies its own, which is how this looked before: Brevo's
 *   default wrapper, their typeface and their logo above our four lines.
 *
 * Both renderings are built from one [Wording] rather than written side by side, so they cannot
 * drift into saying different things.
 */
object MailTemplates {

    fun verification(language: String?, code: String): MailMessage {
        val chosen = pick(language)
        return compose(
            chosen,
            code,
            when (chosen) {
                Language.FR -> Wording(
                    subject = "Votre code de confirmation Triple Triad : $code",
                    lead = "Votre code de confirmation :",
                    instruction = "Saisissez-le dans le jeu pour confirmer votre adresse. " +
                        "Il expire dans $EXPIRY_MINUTES minutes.",
                    disclaimer = "Si vous n'avez pas créé de compte, ignorez ce message : " +
                        "il ne vous engage à rien.",
                    footer = "Ce message vous a été envoyé parce qu'une inscription a été " +
                        "demandée avec cette adresse.",
                )

                Language.DE -> Wording(
                    subject = "Ihr Triple-Triad-Bestätigungscode: $code",
                    lead = "Ihr Bestätigungscode:",
                    instruction = "Geben Sie ihn im Spiel ein, um Ihre Adresse zu bestätigen. " +
                        "Er läuft in $EXPIRY_MINUTES Minuten ab.",
                    disclaimer = "Falls Sie kein Konto erstellt haben, ignorieren Sie diese " +
                        "Nachricht: Sie verpflichtet Sie zu nichts.",
                    footer = "Sie erhalten diese Nachricht, weil mit dieser Adresse ein Konto " +
                        "registriert wurde.",
                )

                Language.JA -> Wording(
                    subject = "トリプルトライアド 確認コード: $code",
                    lead = "確認コード：",
                    instruction = "ゲーム内でこのコードを入力してメールアドレスを確認してください。" +
                        "有効期限は $EXPIRY_MINUTES 分です。",
                    disclaimer = "心当たりのない場合は、このメールを無視してください。",
                    footer = "このアドレスで登録が行われたため、このメールが送信されました。",
                )

                Language.EN -> Wording(
                    subject = "Your Triple Triad confirmation code: $code",
                    lead = "Your confirmation code:",
                    instruction = "Enter it in the game to confirm your address. It expires in " +
                        "$EXPIRY_MINUTES minutes.",
                    disclaimer = "If you did not create an account, ignore this message: " +
                        "it commits you to nothing.",
                    footer = "You received this message because someone registered with this " +
                        "address.",
                )
            },
        )
    }

    fun passwordReset(language: String?, code: String): MailMessage {
        val chosen = pick(language)
        return compose(
            chosen,
            code,
            when (chosen) {
                Language.FR -> Wording(
                    subject = "Votre code de réinitialisation Triple Triad : $code",
                    lead = "Votre code de réinitialisation :",
                    instruction = "Saisissez-le dans le jeu avec votre nouveau mot de passe. " +
                        "Il expire dans $EXPIRY_MINUTES minutes.",
                    disclaimer = "Si vous n'avez pas demandé cette réinitialisation, ignorez ce " +
                        "message : votre mot de passe reste inchangé.",
                    footer = "Ce message vous a été envoyé parce qu'une réinitialisation a été " +
                        "demandée pour cette adresse.",
                )

                Language.DE -> Wording(
                    subject = "Ihr Triple-Triad-Code zum Zurücksetzen: $code",
                    lead = "Ihr Code zum Zurücksetzen:",
                    instruction = "Geben Sie ihn im Spiel zusammen mit Ihrem neuen Passwort ein. " +
                        "Er läuft in $EXPIRY_MINUTES Minuten ab.",
                    disclaimer = "Falls Sie das nicht angefordert haben, ignorieren Sie diese " +
                        "Nachricht: Ihr Passwort bleibt unverändert.",
                    footer = "Sie erhalten diese Nachricht, weil für diese Adresse ein " +
                        "Zurücksetzen des Passworts angefordert wurde.",
                )

                Language.JA -> Wording(
                    subject = "トリプルトライアド 再設定コード: $code",
                    lead = "再設定コード：",
                    instruction = "ゲーム内で新しいパスワードとともに入力してください。" +
                        "有効期限は $EXPIRY_MINUTES 分です。",
                    disclaimer = "心当たりのない場合は無視してください。パスワードは変更されません。",
                    footer = "このアドレスでパスワードの再設定が要求されたため、" +
                        "このメールが送信されました。",
                )

                Language.EN -> Wording(
                    subject = "Your Triple Triad password reset code: $code",
                    lead = "Your password reset code:",
                    instruction = "Enter it in the game along with your new password. " +
                        "It expires in $EXPIRY_MINUTES minutes.",
                    disclaimer = "If you did not ask for this, ignore this message: " +
                        "your password is unchanged.",
                    footer = "You received this message because a password reset was requested " +
                        "for this address.",
                )
            },
        )
    }

    /**
     * The five sentences a message is made of, before either rendering exists.
     *
     * Split this way because the text and HTML versions differ only in what surrounds these. The
     * alternative was writing eight messages instead of four and letting them disagree.
     */
    private data class Wording(
        val subject: String,
        val lead: String,
        val instruction: String,
        val disclaimer: String,
        /**
         * Why this mail arrived, said out loud in the footer.
         *
         * Not boilerplate. The people most likely to press "junk" are those whose address somebody
         * else typed by mistake, and those presses are what costs a sending domain its reputation.
         * A line naming the reason gives them somewhere to put the message other than that button.
         */
        val footer: String,
    )

    private fun compose(language: Language, code: String, wording: Wording) = MailMessage(
        subject = wording.subject,
        body = listOf(
            wording.lead,
            code,
            wording.instruction,
            wording.disclaimer,
            wording.footer,
        ).joinToString("\n\n"),
        html = html(language, code, wording),
    )

    /**
     * The document around the card: doctype, `lang`, and the line the inbox shows as a preview.
     *
     * Light only, and said twice because clients read different metas: the dark modes of Apple Mail
     * and Gmail on Android repaint colours they judge light, and a card whose palette is already
     * decided fares better forcing their hand than being repainted by them.
     *
     * ### On escaping
     *
     * Nothing player-supplied reaches here. [code] is six digits from `Codes.issue`, and every
     * other string is a literal above. That invariant is what keeps this safe with no escaper — a
     * template that ever interpolates a display name or an address needs one first.
     */
    private fun html(language: Language, code: String, wording: Wording): String = head(
        language,
        wording,
    ) + "\n" + card(code, wording) + "\n</body>\n</html>\n"

    /**
     * Everything above the card.
     *
     * Concatenated with [card] rather than interpolating it, and that is not a style preference:
     * `trimIndent` measures the common indentation of *every* line including the ones an
     * interpolation brings in, so a multi-line value indented at zero makes it trim nothing and
     * ship the whole document indented. Each raw string here interpolates single-line values only.
     */
    private fun head(language: Language, wording: Wording): String = """
        <!DOCTYPE html>
        <html lang="${language.prefix}">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="color-scheme" content="light">
        <meta name="supported-color-schemes" content="light">
        <title>${wording.lead}</title>
        </head>
        <body style="margin:0;padding:0;background-color:#F2F3F6;">
        <div style="display:none;max-height:0;overflow:hidden;font-size:1px;line-height:1px;
        color:#F2F3F6;">${wording.instruction}</div>
    """.trimIndent()

    /**
     * The card, and it looks like it was written in 2004 on purpose.
     *
     * Tables for layout, presentational attributes, every style inline. Mail clients are not
     * browsers — Outlook renders with Word's engine, Gmail strips `<style>` blocks it dislikes, and
     * several drop `<head>` entirely. This is the subset that survives all of them.
     *
     * No web fonts either: Gmail and Outlook refuse them and fall back without saying so, which
     * means a design that assumed one would arrive set in Times. Georgia, Arial and Courier are
     * what every client already has.
     */
    private fun card(code: String, wording: Wording): String = """
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
        style="background-color:#F2F3F6;">
        <tr><td align="center" style="padding:24px 12px;">
        <table role="presentation" width="480" cellpadding="0" cellspacing="0" border="0"
        style="width:480px;max-width:100%;background-color:#FFFFFF;border:1px solid #DFE3EA;">

        <tr><td align="center" style="padding:28px 32px 20px;border-bottom:1px solid #EAEDF2;
        font-family:Arial,Helvetica,sans-serif;font-size:14px;font-weight:bold;
        letter-spacing:3px;color:#1B2130;">TRIPLE TRIAD ONLINE
        <div style="width:34px;height:2px;line-height:2px;font-size:2px;margin:12px auto 0;
        background-color:#B8912F;">&nbsp;</div></td></tr>

        <tr><td style="padding:28px 32px 0;font-family:Georgia,'Times New Roman',serif;
        font-size:16px;line-height:1.6;color:#1B2130;">${wording.lead}</td></tr>

        <tr><td style="padding:18px 32px;">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
        <tr><td align="center" style="padding:18px 8px;background-color:#F8F9FB;
        border:1px dashed #C7CDD8;font-family:'Courier New',Courier,monospace;font-size:28px;
        font-weight:bold;letter-spacing:7px;color:#1B2130;">$code</td></tr>
        </table></td></tr>

        <tr><td style="padding:0 32px;font-family:Georgia,'Times New Roman',serif;font-size:16px;
        line-height:1.6;color:#1B2130;">${wording.instruction}</td></tr>

        <tr><td style="padding:20px 32px 28px;font-family:Georgia,'Times New Roman',serif;
        font-size:14px;line-height:1.6;color:#6A7286;">${wording.disclaimer}</td></tr>

        <tr><td align="center" style="padding:16px 32px;border-top:1px solid #EAEDF2;
        font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:1.5;color:#8B93A5;">
        Triple&nbsp;Triad Online<br>${wording.footer}</td></tr>

        </table></td></tr></table>
    """.trimIndent()

    /**
     * The first tag we recognise, ignoring quality values.
     *
     * Deliberately crude. `Accept-Language` can carry a weighted list and a correct parser is more
     * code than this whole file; what actually arrives from this client is one tag it chose itself,
     * because the player picked a language in the settings. Prefix matching on the first two
     * letters covers `fr`, `fr-FR` and `fr_FR` without caring which the caller sent.
     */
    private fun pick(header: String?): Language {
        val first = header?.split(',')?.firstOrNull()?.trim()?.take(2)?.lowercase()
        return Language.entries.firstOrNull { it.prefix == first } ?: Language.EN
    }

    /** @param prefix matched against `Accept-Language`, and written into the HTML's `lang`. */
    private enum class Language(val prefix: String) {
        EN("en"),
        FR("fr"),
        DE("de"),
        JA("ja"),
    }
}

/**
 * How long a code lives, in minutes, and it is the same number in both messages and in the store.
 *
 * Ten. Long enough for a mail to be delivered, read on a phone and typed back; short enough that a
 * code left in an inbox is not a standing key to the account. The mail says the number, so it is
 * defined once and read by both.
 */
const val EXPIRY_MINUTES: Int = 10
