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
 */
object MailTemplates {

    fun verification(language: String?, code: String): MailMessage = when (pick(language)) {
        Language.FR -> MailMessage(
            subject = "Votre code de confirmation Triple Triad",
            body = """
                Votre code de confirmation est : $code

                Saisissez-le dans le jeu pour confirmer votre adresse. Il expire dans
                $EXPIRY_MINUTES minutes.

                Si vous n'avez pas créé de compte, ignorez ce message.
            """.trimIndent(),
        )

        Language.DE -> MailMessage(
            subject = "Ihr Triple-Triad-Bestätigungscode",
            body = """
                Ihr Bestätigungscode lautet: $code

                Geben Sie ihn im Spiel ein, um Ihre Adresse zu bestätigen. Er läuft in
                $EXPIRY_MINUTES Minuten ab.

                Falls Sie kein Konto erstellt haben, ignorieren Sie diese Nachricht.
            """.trimIndent(),
        )

        Language.JA -> MailMessage(
            subject = "トリプルトライアド 確認コード",
            body = """
                確認コード: $code

                ゲーム内でこのコードを入力してメールアドレスを確認してください。
                有効期限は $EXPIRY_MINUTES 分です。

                心当たりのない場合は、このメールを無視してください。
            """.trimIndent(),
        )

        Language.EN -> MailMessage(
            subject = "Your Triple Triad confirmation code",
            body = """
                Your confirmation code is: $code

                Enter it in the game to confirm your address. It expires in $EXPIRY_MINUTES
                minutes.

                If you did not create an account, ignore this message.
            """.trimIndent(),
        )
    }

    fun passwordReset(language: String?, code: String): MailMessage = when (pick(language)) {
        Language.FR -> MailMessage(
            subject = "Réinitialisation de votre mot de passe Triple Triad",
            body = """
                Votre code de réinitialisation est : $code

                Saisissez-le dans le jeu avec votre nouveau mot de passe. Il expire dans
                $EXPIRY_MINUTES minutes.

                Si vous n'avez pas demandé cette réinitialisation, ignorez ce message : votre
                mot de passe reste inchangé.
            """.trimIndent(),
        )

        Language.DE -> MailMessage(
            subject = "Triple Triad: Passwort zurücksetzen",
            body = """
                Ihr Code zum Zurücksetzen lautet: $code

                Geben Sie ihn im Spiel zusammen mit Ihrem neuen Passwort ein. Er läuft in
                $EXPIRY_MINUTES Minuten ab.

                Falls Sie das nicht angefordert haben, ignorieren Sie diese Nachricht: Ihr
                Passwort bleibt unverändert.
            """.trimIndent(),
        )

        Language.JA -> MailMessage(
            subject = "トリプルトライアド パスワード再設定",
            body = """
                再設定コード: $code

                ゲーム内で新しいパスワードとともに入力してください。
                有効期限は $EXPIRY_MINUTES 分です。

                心当たりのない場合は無視してください。パスワードは変更されません。
            """.trimIndent(),
        )

        Language.EN -> MailMessage(
            subject = "Reset your Triple Triad password",
            body = """
                Your reset code is: $code

                Enter it in the game along with your new password. It expires in
                $EXPIRY_MINUTES minutes.

                If you did not ask for this, ignore this message: your password is unchanged.
            """.trimIndent(),
        )
    }

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
