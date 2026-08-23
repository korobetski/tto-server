-- Move the FFVIII set from card block 2 to block 8.
--
-- ## Why a set moves at all
--
-- A card id is `(block << 8) | number`, so a block holds 255 cards. FFXIV has 454 — it needs two
-- blocks, and `CardSet.blocks` (in `:core`) is now a *list* for exactly that reason. The natural
-- second block for a set that starts at 1 is 2, which is where FFVIII has been sitting since ids
-- went global.
--
-- So FFVIII moves out of the way rather than FFXIV skipping over it. Block 8 leaves 1..7 free,
-- which is 1785 cards of room: FFXIV can keep growing for as long as Square Enix keeps printing
-- without ever colliding with the other set again. The alternative — widening the id so one set
-- could hold everything — would have re-issued *every* card id in every save and every stored
-- match, for a limit exactly one set has ever reached.
--
-- ## Why this is surgical rather than a sweep
--
-- Every field below is named, and that is not fussiness. Once a card id is an integer inside JSONB
-- it is indistinguishable from an MGP balance, a match fee, a shop price or a stack count — and the
-- FFVIII id range, 513..767, is squarely inside the range those take. A recursive "renumber every
-- integer in 513..767" would rewrite player balances and pack prices along with the cards. The
-- authoring pass that produced the catalogue side of this change proved the point by mangling three
-- prices in a KDoc before it was made field-targeted.
--
-- ## What is deliberately not migrated
--
-- - `matches` holds no card id at all — a scoreline, a seed and a transcript hash. The whole match
--   history therefore needs nothing, which is the one piece of luck in this change.
-- - `applied_operations` is **deleted** rather than remapped. Its own comment puts the useful life
--   of a row at "minutes" — it exists so a client retrying a purchase gets the first answer back —
--   and a response body has no fixed shape to walk. A row old enough to survive a deploy is a row
--   nothing will ever ask for again.
-- - `pvp_challenges`, `pvp_tables`: their `stake` is `PvpStake(mgp, trade)`, which carries no ids.
--
-- ## Reversibility
--
-- There is none, and that is accepted: this ran against a database holding no live FFVIII
-- collections. Were it not, the ordering constraint would matter a great deal — FFXIV must not be
-- allowed into block 2 until FFVIII has left it, or ids 513..622 name two different cards at once.
-- Completing FFXIV is therefore a *later, separate* migration-free release, and this one ships
-- alone.

-- The remap, named once. `id + 1536` is `(8 - 2) << 8`, and it fires only on the FFVIII block so
-- running this twice is a no-op on block 8 — the function is total and idempotent.
CREATE FUNCTION pg_temp.ff8_to_block_8(id integer) RETURNS integer AS $$
SELECT CASE WHEN id BETWEEN 513 AND 767 THEN id + 1536 ELSE id END;
$$ LANGUAGE sql IMMUTABLE;

-- ---------------------------------------------------------------------------
-- characters.save
-- ---------------------------------------------------------------------------

-- `CARDS` has two shapes on disk and both are live: the modern object form, `{"513": 2}`, whose
-- **keys** are the ids, and the legacy array form, `[513, 513]`, whose elements are. `:core`'s
-- `CardCopiesSerializer` still reads the second so a save written before cards could be owned twice
-- keeps loading, which means this migration has to remap both or quietly strip one.
UPDATE characters
SET save = jsonb_set(
    save,
    '{CARDS}',
    CASE jsonb_typeof(save -> 'CARDS')
        WHEN 'object' THEN (
            SELECT COALESCE(jsonb_object_agg(pg_temp.ff8_to_block_8(key::integer)::text, value), '{}'::jsonb)
            FROM jsonb_each(save -> 'CARDS')
        )
        WHEN 'array' THEN (
            -- Order is not meaningful in the legacy form — a repeat is a second copy, not a
            -- position — but it is carried anyway so the migrated row reads like the one it
            -- replaced rather than in whatever order the expansion happened to yield.
            SELECT COALESCE(jsonb_agg(
                to_jsonb(pg_temp.ff8_to_block_8(value::text::integer)) ORDER BY pos
            ), '[]'::jsonb)
            FROM jsonb_array_elements(save -> 'CARDS') WITH ORDINALITY AS t(value, pos)
        )
        ELSE save -> 'CARDS'
    END
)
WHERE save ? 'CARDS';

-- Each deck is `{"name": …, "cards": [id, …]}`, so this rebuilds the list of decks with each one's
-- own card list remapped. The zero an empty deck slot carries is below 513 and passes through.
UPDATE characters
SET save = jsonb_set(
    save,
    '{DECKS}',
    (
        SELECT COALESCE(jsonb_agg(
            -- A deck with no `cards` key passes through whole rather than being filtered out.
            -- `Deck.cards` defaults to empty and the server encodes with `encodeDefaults = false`,
            -- so an empty deck really is written as `{"name": "…"}` — and dropping it here would
            -- renumber every slot after it, silently moving a player's decks under them.
            CASE WHEN deck ? 'cards'
                THEN jsonb_set(
                    deck,
                    '{cards}',
                    (
                        -- Ordered, because a deck's order is load-bearing: under `RULE_ORDER` the
                        -- hand is played left to right as dealt. `jsonb_array_elements` promises no
                        -- order of its own, so the ordinality is carried through explicitly.
                        SELECT COALESCE(jsonb_agg(
                            to_jsonb(pg_temp.ff8_to_block_8(card::text::integer)) ORDER BY pos
                        ), '[]'::jsonb)
                        FROM jsonb_array_elements(deck -> 'cards') WITH ORDINALITY AS c(card, pos)
                    )
                )
                ELSE deck
            END
            ORDER BY ordinality
        ), '[]'::jsonb)
        FROM jsonb_array_elements(save -> 'DECKS') WITH ORDINALITY AS d(deck, ordinality)
    )
)
WHERE save ? 'DECKS'
  AND jsonb_array_length(save -> 'DECKS') > 0;

-- The bag holds several item types and only one of them names a card. A booster names its pack by
-- enum constant and a potion its boon, so both pass through untouched — which is why the `type`
-- discriminator is tested rather than "does this row have a `card` key".
UPDATE characters
SET save = jsonb_set(
    save,
    '{BAG}',
    (
        SELECT COALESCE(jsonb_agg(
            CASE WHEN item ->> 'type' = 'item-type-card'
                THEN jsonb_set(item, '{card}', to_jsonb(pg_temp.ff8_to_block_8((item ->> 'card')::integer)))
                ELSE item
            END
            ORDER BY ordinality
        ), '[]'::jsonb)
        FROM jsonb_array_elements(save -> 'BAG') WITH ORDINALITY AS b(item, ordinality)
    )
)
WHERE save ? 'BAG'
  AND jsonb_array_length(save -> 'BAG') > 0;

-- ---------------------------------------------------------------------------
-- Matches in flight
-- ---------------------------------------------------------------------------

-- A hand is a flat `[id, …]`; a move is a `Placement`, whose only id is `cardId` — its `captures`
-- name board positions rather than cards, and `position` and `handIndex` are indices. Both live
-- tables carry the same two shapes, so the same two expressions serve each.
--
-- These rows are what the server replays to verify a match. Leaving an old id in one would make an
-- honest client's next move fail to reconcile and be rejected as if it were cheating, which is the
-- precise failure `VersionGate` exists to prevent and which a half-migrated row would reintroduce
-- behind its back.
UPDATE pvp_matches
SET blue_hand = (
        SELECT COALESCE(jsonb_agg(to_jsonb(pg_temp.ff8_to_block_8(c::text::integer)) ORDER BY o), '[]'::jsonb)
        FROM jsonb_array_elements(blue_hand) WITH ORDINALITY AS t(c, o)
    ),
    red_hand = (
        SELECT COALESCE(jsonb_agg(to_jsonb(pg_temp.ff8_to_block_8(c::text::integer)) ORDER BY o), '[]'::jsonb)
        FROM jsonb_array_elements(red_hand) WITH ORDINALITY AS t(c, o)
    ),
    moves = (
        SELECT COALESCE(jsonb_agg(
            CASE WHEN m ? 'cardId'
                THEN jsonb_set(m, '{cardId}', to_jsonb(pg_temp.ff8_to_block_8((m ->> 'cardId')::integer)))
                ELSE m
            END
            ORDER BY o
        ), '[]'::jsonb)
        FROM jsonb_array_elements(moves) WITH ORDINALITY AS t(m, o)
    );

UPDATE pve_matches
SET blue_hand = (
        SELECT COALESCE(jsonb_agg(to_jsonb(pg_temp.ff8_to_block_8(c::text::integer)) ORDER BY o), '[]'::jsonb)
        FROM jsonb_array_elements(blue_hand) WITH ORDINALITY AS t(c, o)
    ),
    red_hand = (
        SELECT COALESCE(jsonb_agg(to_jsonb(pg_temp.ff8_to_block_8(c::text::integer)) ORDER BY o), '[]'::jsonb)
        FROM jsonb_array_elements(red_hand) WITH ORDINALITY AS t(c, o)
    ),
    moves = (
        SELECT COALESCE(jsonb_agg(
            CASE WHEN m ? 'cardId'
                THEN jsonb_set(m, '{cardId}', to_jsonb(pg_temp.ff8_to_block_8((m ->> 'cardId')::integer)))
                ELSE m
            END
            ORDER BY o
        ), '[]'::jsonb)
        FROM jsonb_array_elements(moves) WITH ORDINALITY AS t(m, o)
    );

-- A settled PvE match records what it paid, as a `RewardSummary`. Its `items` are the same `Item`
-- shapes the bag holds, so only the card arm moves — a booster names its pack by enum constant.
-- The summary is written at settlement and never recomputed (see `V10`'s note on why it is a
-- column at all), so a stale id here would outlive every other copy of it.
UPDATE pve_matches
SET reward = jsonb_set(
    reward,
    '{items}',
    (
        SELECT COALESCE(jsonb_agg(
            CASE WHEN item ->> 'type' = 'item-type-card'
                THEN jsonb_set(item, '{card}', to_jsonb(pg_temp.ff8_to_block_8((item ->> 'card')::integer)))
                ELSE item
            END
            ORDER BY o
        ), '[]'::jsonb)
        FROM jsonb_array_elements(reward -> 'items') WITH ORDINALITY AS t(item, o)
    )
)
WHERE reward IS NOT NULL
  AND jsonb_typeof(reward -> 'items') = 'array';

-- The cards a PvP winner named under the One and Diff trade rules, as `{"BLUE":[261,…],"RED":[]}`
-- — see `V4__pvp_tables.sql`. A claim is stored as the *input* it is, so that the settlement stays
-- derivable and checkable against the engine; that is precisely why it cannot be left behind here,
-- since re-deriving it later would resolve ids that no longer name the cards that were taken.
UPDATE pvp_matches
SET claimed = (
    SELECT COALESCE(jsonb_object_agg(
        side,
        (
            SELECT COALESCE(jsonb_agg(to_jsonb(pg_temp.ff8_to_block_8(c::text::integer)) ORDER BY o), '[]'::jsonb)
            FROM jsonb_array_elements(taken) WITH ORDINALITY AS t(c, o)
        )
    ), '{}'::jsonb)
    FROM jsonb_each(claimed) AS e(side, taken)
    WHERE jsonb_typeof(taken) = 'array'
)
WHERE claimed IS NOT NULL
  AND claimed <> '{}'::jsonb;

-- ---------------------------------------------------------------------------
-- The idempotency cache
-- ---------------------------------------------------------------------------

-- Emptied rather than walked: see this file's header. A retry arriving after the deploy that
-- carried this migration re-performs its operation against the new ids, which is the correct answer
-- and the one a remapped row could not have given for every response shape.
DELETE FROM applied_operations;
