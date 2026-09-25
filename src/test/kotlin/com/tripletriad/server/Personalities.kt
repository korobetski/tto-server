package com.tripletriad.server

/**
 * A bot with no temper at all: every trait at the value that leaves the deployment's numbers as
 * they are.
 *
 * Shared because most bot tests are not about personality, and a drawn one would make each of them
 * about it anyway — a reserve three times the policy's, a price a fifth over worth, a bid a quarter
 * over it. Under this one a bot keeps exactly `BotPolicy.reserve`, saves for nothing, bids and asks
 * at worth, has no taste for hard opponents or drops, never enters a tournament, and keeps the
 * policy's cadence and wait. A test about one trait overrides that trait and nothing else.
 *
 * The archetype defaults to a duelist, the one that neither collects nor keeps a second lot per
 * tier, so a test about collecting or merchanting has to say so.
 */
fun plainPersonality(
    archetype: BotArchetype = BotArchetype.DUELIST,
    favourite: FavouriteSet = FavouriteSet.FF14,
) = BotPersonality(
    archetype = archetype,
    favourite = favourite,
    thrift = 1.0,
    horizon = 0.0,
    appetite = 1.0,
    markup = 1.0,
    daring = 0.0,
    greed = 0.0,
    ambition = 0.0,
    pace = 1.0,
    patience = 1.0,
    seed = 0,
)
