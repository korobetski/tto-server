-- Moves every enrolled bot's auction prices to the ranges `BotPersonality.TEMPERAMENTS` now draws.
--
-- ### Why the ranges moved
--
-- A bot's lot is bought by another bot only when the buyer's ceiling (worth × appetite, a quarter
-- more for a collector) reaches the seller's ask (worth × markup). The first ranges put nearly
-- every ask at or over worth and nearly every non-collector ceiling under it, so bots almost never
-- bought from one another: drawn uniformly, a competitor could afford about one bot lot in nine, a
-- duelist one in thirty, a merchant none. The asks moved down and the appetites up to meet
-- — never over worth, which is the line that keeps a bot from being a way to pump MGP out of the
-- roster. `BotPersonality`'s KDoc has the numbers and the rejected alternative.
--
-- ### Why a migration, when the ranges are Kotlin
--
-- A personality is stored as it was drawn (`V20__bot_personality.sql`), so a new range reaches only
-- the bots enrolled after it. Without this, a server with a roster already running would see the
-- change the day it next enrolled a bot, and not before.
--
-- **Rejected: clearing the column and letting the director redraw.** That redraws the archetype and
-- every other trait too, turning a merchant into a duelist to fix a price.
--
-- ### Each bot keeps its place inside its range
--
-- A trait is mapped linearly from the old range onto the new one: a duelist drawn at the middle of
-- 0.90–1.00 lands at the middle of 0.80–0.90. So the roster keeps its spread — the cheapest seller
-- is still the cheapest — and only the band it spreads over moves. Only the rows that changed are
-- listed: a collector's and a merchant's appetite, for one, are where they were.
--
-- A value an operator set outside its range by hand is mapped by the same line and stays outside
-- the new one by the same proportion. It was a deliberate choice, and this is not the place to
-- overrule it.
--
-- Rounded to six places: the draw carries sixteen, and a trait multiplies a worth of at most
-- 3 750 MGP into a price rounded to the MGP (a ceiling) or to ten (an ask), so the rest is noise in
-- a document an operator reads on the console.
--
-- ### What it does not touch
--
-- A bot with no personality yet (NULL) is drawn one from the new ranges when the director next acts
-- for it. A document whose trait is missing or not a number is left alone rather than failing the
-- boot on a cast: `BotStore` already redraws a personality it cannot read.
--
-- Data only, and safe under the old build: a rollback reads the same keys with different numbers.

UPDATE bots AS b
SET personality = jsonb_set(
        b.personality,
        '{appetite}',
        to_jsonb(round(
            r.low + ((b.personality ->> 'appetite')::numeric - r.was_low)
                / (r.was_high - r.was_low) * (r.high - r.low),
            6
        ))
    )
FROM (
    VALUES
        ('COMPETITOR', 0.80, 1.00, 0.90, 1.00),
        ('DUELIST',    0.75, 0.95, 0.85, 1.00)
) AS r (archetype, was_low, was_high, low, high)
WHERE b.personality ->> 'archetype' = r.archetype
  AND jsonb_typeof(b.personality -> 'appetite') = 'number';

UPDATE bots AS b
SET personality = jsonb_set(
        b.personality,
        '{markup}',
        to_jsonb(round(
            r.low + ((b.personality ->> 'markup')::numeric - r.was_low)
                / (r.was_high - r.was_low) * (r.high - r.low),
            6
        ))
    )
FROM (
    VALUES
        ('COLLECTOR',  0.95, 1.10, 0.90, 1.00),
        ('COMPETITOR', 0.90, 1.05, 0.85, 0.95),
        ('DUELIST',    0.90, 1.00, 0.80, 0.90),
        ('MERCHANT',   1.05, 1.20, 1.00, 1.10)
) AS r (archetype, was_low, was_high, low, high)
WHERE b.personality ->> 'archetype' = r.archetype
  AND jsonb_typeof(b.personality -> 'markup') = 'number';
