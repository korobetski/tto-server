-- A refereed PvE match is one match, and `stats.match_events` counted it twice.
--
-- ### The double count
--
-- `V18__stats_views.sql` built its stream of matches from three tables on the premise, written at
-- the top of that file, that each holds a different kind: `matches` credited transcripts,
-- `pve_matches` refereed sessions, `pvp_matches` player against player. The premise was already
-- half wrong the day it was written. `AccountStore.creditRefereedMatch` settles a refereed session
-- by inserting a `matches` row too — that row is what feeds the profile's record, recent matches
-- and the NPC tallies — with the session's id standing in for the transcript digest. So every
-- refereed match that finished appeared in the stream twice: once as `PVE` when it was dealt, once
-- as `CREDITED` when it was paid.
--
-- On a server whose clients all play PvE refereed, that made `matches_credited` a second count of
-- `matches_pve` (minus the abandoned sessions) rather than a figure of its own, and made
-- `matches_today` — the one figure defined as the sum of the three kinds — roughly double the number
-- of PvE games anybody played. Found by an audit of every figure the console shows, prompted by
-- two of them ("last seen", "active today") being visibly wrong.
--
-- ### The fix: `CREDITED` means *submitted*
--
-- A `matches` row whose `transcript_hash` is the id of one of the same account's `pve_matches` is
-- the settlement of that session, and is left out of `CREDITED`; the session itself is already in
-- the stream as `PVE`. What remains under `CREDITED` is what the name was meant to say: a
-- transcript a client played on its own and the server replayed and paid through
-- `POST /matches/submit`. Both columns of `stats.matches` keep their names and now count disjoint
-- things, and `today` counts each game once.
--
-- **Rejected: dropping the `PVE` branch instead**, and letting `matches` stand for every finished
-- PvE game. It would count each refereed game once as well, but `pve_matches` is the only table
-- that knows about the sessions nobody finished — an `ABANDONED` session never reaches `matches` —
-- and a stream that loses them understates what the lobby dealt. It would also move refereed games
-- from "when dealt" to "when paid", which is a change of meaning `today` has no reason to take.
--
-- **Why the id and not a shape test.** A transcript digest is hexadecimal and a match id is not,
-- so `transcript_hash !~ '^[0-9a-f]+$'` would sort the rows too — by a coincidence of two formats
-- chosen for other reasons, which a later change to either would break without a word. The join
-- is on the fact itself: this row settles that session. `pve_matches.id` is the primary key, so
-- the anti-join is an index probe per row and costs nothing V18's sequential scan does not already
-- pay. The account is part of the predicate because `matches_transcript_idx` is unique per account
-- and not globally, and a digest colliding with somebody else's session id should not hide a
-- match from the count.
--
-- ### The same columns, so dependants need not move
--
-- `CREATE OR REPLACE VIEW` accepts a new body with an identical column list, which leaves
-- `stats.matches` and `stats.overview` — built on this view — in place, and leaves `tto_stats`'s
-- grants in place with them: a replaced view keeps its privileges. Nothing downstream is
-- recreated, so nothing downstream needs repeating here.
--
-- The console's player page had the same double count in its match history, where a refereed game
-- was listed once as `PVE` and once as `CREDITED`. That query is Kotlin (`AdminStore.readMatches`)
-- and not a view, so it is fixed there, with the same predicate and a pointer back to this file.
--
-- The other two branches are copied from V18 unchanged, bot predicates included; V18 carries the
-- reasoning for each.
CREATE OR REPLACE VIEW stats.match_events AS
SELECT 'CREDITED'::text AS kind, m.id::text AS id, m.played_at AS at
FROM public.matches m
WHERE NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = m.account_id)
  AND NOT EXISTS (
      SELECT 1 FROM public.pve_matches p
      WHERE p.id = m.transcript_hash AND p.account_id = m.account_id
  )
UNION ALL
SELECT 'PVE'::text, p.id, p.created_at
FROM public.pve_matches p
WHERE NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = p.account_id)
UNION ALL
SELECT 'PVP'::text, v.id, v.created_at
FROM public.pvp_matches v
WHERE NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = v.blue_account)
   OR NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = v.red_account);
