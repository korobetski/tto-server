# What this service stores about a player

An inventory of the personal data this system actually holds, derived from the schema and the
client's own storage rather than from intent. It exists to be **checked against the code**, which is
the part a privacy notice cannot do for itself.

> **This is not a privacy policy.** It is the factual basis for writing one. A policy makes promises
> about retention, lawful basis and who to contact, and those are the operator's to make — not
> something to be inferred from a table definition. What follows is the inventory such a policy
> would have to be true about, and it is written so that a claim contradicting it is visible.

Last derived from the schema on 2026-08-14, at migration `V9`.

## On the server

| What | Where | Why it exists | Notes |
|---|---|---|---|
| Username | `accounts.username` | It is the account's name, and other players see it in the lobby and on an invitation | Chosen by the player. Nothing requires it to be a real name |
| Password **digest** | `accounts.password_hash` | Signing in | bcrypt, cost 12, salt included. Nothing in this database can be turned back into what the player typed |
| Session tokens, **fingerprinted** | `sessions.token_hash` | Staying signed in for 30 days | The token itself is never stored. A database dump does not let anybody impersonate a player |
| Game profile | `characters.save` | It is the game: cards, decks, purse, achievements, statistics | Server-owned since the anti-cheat work — see `GameSave.withServerOwnedFrom` |
| Match records | `matches` | Crediting a match once, and the player's own history | Opponent, format, seed, score, payout. No free text |
| Applied operations | `applied_operations` | Making a retried purchase happen once | Holds the response that was sent, so it can be replayed |
| Seed tickets | `match_tickets` | Stopping a client choosing its own deal | Random integers |
| Lobby tables, invitations, matches | `pvp_*` | Playing another person | Carries both players' account ids |

**IP addresses** are not stored in any table. They appear in the reverse proxy's access log
(`Caddyfile`, `format json`) and in the rate limiter's in-memory buckets, which are keyed by address
and never written down. Retention of the proxy log is a deployment decision, not a code one.

**What is deliberately absent**: no email, no telephone number, no device identifier, no advertising
id, no analytics, no third-party SDK of any kind. Nothing in this service sends anything to anybody
except the player's own client. That is a property worth keeping, and it is the reason crash
reporting is a local file rather than a service — see `CrashLog`.

## On the player's device

| What | Collection | Notes |
|---|---|---|
| Local profiles | `saves` | The offline mode. The player's own files, on their own device |
| Session token | `session` | The credential, in the clear — see `SessionStore` for that decision and its bounds |
| Unspent seeds | `tickets` | Random integers, worth nothing to anybody else |
| Unsubmitted matches | `pending` | Transcripts waiting for a network |
| Chosen server | `servers` | An address |
| Recent warnings | `diagnostics` | The last 20 serious log lines. Never leaves the device unless the player sends it |
| Match history | `history` | Local record of finished matches |

## What a policy could claim from this, and what it must still say itself

Supported by the code as it stands:

- Nothing is shared with third parties, because nothing is sent to any.
- The password cannot be recovered from what is stored, only verified against.
- A session can be revoked (`DELETE /sessions`), and expires by itself after 30 days.
- A player can delete their own account: `DELETE /accounts/me`, which requires the **password** and
  not merely a session token — a stolen token must not be able to destroy an account. Everything
  belonging to it goes with it, because every table referencing `accounts` is `ON DELETE CASCADE`.
  `AccountDeletionTest.everythingBelongingToTheAccountGoesWithIt` checks that in the database rather
  than trusting the response, because a deletion that leaves rows behind is worse than none: it is a
  promise that reads as kept.
- The username becomes free again afterwards, which is what "deleted" has to mean to be worth
  anything.

**Two things a policy should still say plainly.**

*Deletion is not reversible and there is no grace period.* The row is gone when the request returns.

*Deleting an account also removes shared PvP rows*, which names both players — so a finished match
disappears from the **opponent's** history too. That is a real consequence, and the honest way round:
the alternative is keeping a record of somebody who asked to be forgotten in order to preserve
somebody else's statistics.

**Still missing**: the endpoint exists but **no screen calls it**. A player cannot yet delete their
own account from inside the game, so a request still reaches the operator, who can now honour it with
one call instead of by hand. The remaining work is a confirmation flow, and it is deliberately not
rushed: an irreversible action behind a mis-tap is worse than one behind an email.
