# MatchReview icon suggestions

## Implemented: Concept A — Pitch Replay

The app now uses an adaptive launcher icon combining:

- a dark emerald football pitch;
- white pitch markings;
- a warm amber replay/play symbol;
- a monochrome Android themed-icon layer.

This is the strongest fit because it communicates both parts of the product immediately:
**football** and **video review**. Source files are in `app/src/main/res`, and a scalable
preview is available at [`docs/icons/concept-a-pitch-replay.svg`](docs/icons/concept-a-pitch-replay.svg).

## Alternatives

### Concept B — Tactical Board

A navy tactical board with player markers and a movement arrow. This feels more
coach-oriented and analytical, but communicates video less clearly.

Preview: [`docs/icons/concept-b-tactical-board.svg`](docs/icons/concept-b-tactical-board.svg)

### Concept C — MR Ball

A football combined with an `MR` monogram. This is more brand-like and distinctive,
but the small lettering may be less readable on compact launcher displays.

Preview: [`docs/icons/concept-c-mr-ball.svg`](docs/icons/concept-c-mr-ball.svg)

## Recommendation

Keep **Concept A** for the first release. It remains legible at small sizes, works with
round and squircle launcher masks, and supports Android 13+ themed icons.
