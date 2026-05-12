# Rust Venue Sequence Rules Design

日期：2026-05-12

状态：Implemented documentation milestone 7

## Goal

在接入 Rust live feed 前，先把 Binance 与 Bitget 的 L2 snapshot/delta/sequence 规则写成 Bedrock 内部合同。后续 parser、reconstructor、replay fixture、gap/rebuild metrics 都必须以此为准，而不是沿用 Java 里的单一 `seq + 1` validator。

## Official Sources Checked

- Binance Spot WebSocket Streams: <https://developers.binance.com/docs/binance-spot-api-docs/web-socket-streams>
- Binance Spot REST order book: <https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints#order-book>
- Binance USDⓈ-M Futures local order book: <https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/How-to-manage-a-local-order-book-correctly>
- Binance USDⓈ-M Futures diff depth stream: <https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Diff-Book-Depth-Streams>
- Bitget UTA Depth Channel: <https://www.bitget.com/api-doc/uta/websocket/public/Order-Book-Channel>
- Bitget UTA Changelog: <https://www.bitget.com/api-doc/uta/changelog>

## Scope

This milestone is documentation only. It does not implement WebSocket clients, REST clients, JSON parsing, checksum/CRC32, SBE, or venue-specific Rust crates.

Initial Rust target:

- Binance Spot diff depth.
- Binance USDⓈ-M Futures diff depth.
- Bitget UTA JSON `books` depth channel.

Compatibility notes are recorded for current Java behavior and older Bitget classic channels, but Java is not the Rust production baseline.

## Binance Spot Contract

Input streams:

- WebSocket diff depth: `<symbol>@depth` or `<symbol>@depth@100ms`.
- REST snapshot: `GET /api/v3/depth?symbol=...&limit=5000`.

Raw fields to preserve:

- `U`: first update id in event.
- `u`: final update id in event.
- `E`: event time.
- `b` / `a`: absolute quantities for bid/ask price levels.

Bootstrap:

1. Open WS diff depth and buffer events.
2. Record the first buffered event's `U`.
3. Fetch REST snapshot.
4. If snapshot `lastUpdateId` is before the buffered stream boundary, refetch.
5. Drop buffered events with `u <= lastUpdateId`.
6. First applied event must bridge the snapshot boundary: after stale events are dropped, require `u > lastUpdateId` and `U <= lastUpdateId + 1`; otherwise refetch.
7. Apply buffered events, then live events.

Ongoing validation:

- If `u < local_update_id`, ignore as stale.
- If `U > local_update_id + 1`, mark gap and rebuild from REST snapshot.
- Otherwise apply the batch and set `local_update_id = u`.
- A quantity of zero removes the price level; non-zero quantity sets the absolute level size.

Design consequence:

- Binance Spot cannot be validated by a single scalar `seq == last + 1` rule. The adapter must preserve event ranges `U..=u`.

## Binance USDⓈ-M Futures Contract

Input streams:

- WebSocket diff depth: `<symbol>@depth`, `<symbol>@depth@500ms`, or `<symbol>@depth@100ms`.
- REST snapshot: `GET /fapi/v1/depth?symbol=...&limit=1000`.

Raw fields to preserve:

- `U`: first update id in event.
- `u`: final update id in event.
- `pu`: previous stream event's final update id.
- `E`: event time.
- `T`: transaction time.
- `b` / `a`: absolute quantities.

Bootstrap:

1. Open WS diff depth and buffer events.
2. For the same price while buffering, latest update wins.
3. Fetch REST snapshot.
4. Drop buffered events with `u < lastUpdateId`.
5. First processed event must satisfy `U <= lastUpdateId` and `u >= lastUpdateId`.
6. Apply buffered events, then live events.

Ongoing validation:

- For every event after the first processed event, require `pu == previous_u`.
- If `pu != previous_u`, mark gap and rebuild from REST snapshot.
- Apply absolute quantities; zero quantity removes the level.
- Removing a price level not present locally is normal and must not be treated as a fatal error.

Design consequence:

- Futures continuity should be checked with `pu`, not only `U/u` arithmetic.
- Binance futures must not reuse the current Java snapshot URL shape blindly; the official USDⓈ-M snapshot path is `/fapi/v1/depth`.

## Bitget UTA Contract

Input stream:

- WebSocket depth `books` is the reconstructable full-depth channel: first push is `snapshot`, later pushes are `update`.
- `books1`, `books5`, and `books50` push snapshots each time; they are useful for BBO/top-N snapshot use cases, but they are not an incremental L2 reconstruction stream.

Raw fields to preserve:

- `action`: `snapshot` or `update`.
- `seq`: current serial number.
- `pseq`: previous push serial number. For UTA docs, it is meaningful for `books`.
- `ts`: engine/data timestamp in milliseconds.
- `maxDepth`: maximum depth for `books`, when present.
- `b` / `a`: bid/ask levels.

Bootstrap:

1. Subscribe to `books`.
2. Apply the initial `snapshot` as the local book and set `local_seq = seq`.
3. Apply the first subsequent `update` only if the snapshot `seq` falls in that update's `[pseq, seq]` continuity range.
4. If the first update cannot bridge the snapshot, resubscribe/rebuild.

Ongoing validation:

- In normal operation, update `seq` is greater than `pseq`.
- Previous update `seq` must equal the next update `pseq`.
- `pseq = 0` indicates a venue-side reset/restart path; reset local state and wait for a fresh snapshot/rebuild path.
- If `pseq != local_seq`, mark gap and rebuild/resubscribe.
- Do not rely on checksum for UTA JSON depth consistency. Bitget's 2026-05-12 changelog records the checksum removal path for affected depth channels and directs clients to use `seq`/`pseq`.

Design consequence:

- Rust Bitget implementation should target `seq/pseq` continuity first.
- Old `books5`-style snapshot channels should be modeled as snapshot refresh feeds, not as delta feeds.

## Rust Adapter Boundary

Future venue adapters should emit a venue-normalization layer before calling `BookRouter`:

- `VenueSnapshotReady`: full book snapshot with venue raw sequence metadata.
- `VenueDeltaBatchReady`: absolute level updates whose venue continuity has already been validated.
- `VenueGap`: a rebuild-required condition with venue, instrument, raw sequence metadata, and reason.

The generic `BookReconstructor` can continue to receive normalized `BookSnapshot` / `BookDelta`, but venue adapters must own raw bootstrap and continuity checks:

- Binance Spot owns `U/u` bridge logic.
- Binance Futures owns `pu == previous_u`.
- Bitget owns `seq/pseq`.

## Required Fixtures For Implementation Milestone

When code is added, create venue-specific fixtures before parser implementation:

- Binance Spot snapshot bridge success.
- Binance Spot stale event ignored.
- Binance Spot range gap triggers rebuild.
- Binance USDⓈ-M futures `pu` continuity success.
- Binance USDⓈ-M futures `pu` mismatch triggers rebuild.
- Bitget `books` snapshot then bridged update success.
- Bitget `pseq` mismatch triggers rebuild.
- Bitget `pseq = 0` reset path.
- Bitget `books5` modeled as snapshot-only, not delta.

## Non-Goals

- No live WebSocket client in this milestone.
- No REST snapshot client in Rust yet.
- No SBE decoder yet.
- No checksum/CRC32 implementation yet.
- No production reconnect policy yet.
