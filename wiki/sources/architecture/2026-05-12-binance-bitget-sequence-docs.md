# Binance/Bitget Sequence Docs Source

日期：2026-05-12

来源：

- Binance Spot WebSocket Streams: <https://developers.binance.com/docs/binance-spot-api-docs/web-socket-streams>
- Binance Spot REST order book: <https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints#order-book>
- Binance USDⓈ-M Futures local order book: <https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/How-to-manage-a-local-order-book-correctly>
- Binance USDⓈ-M Futures diff depth stream: <https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Diff-Book-Depth-Streams>
- Bitget UTA Depth Channel: <https://www.bitget.com/api-doc/uta/websocket/public/Order-Book-Channel>
- Bitget UTA Changelog: <https://www.bitget.com/api-doc/uta/changelog>

## Key Findings

Binance Spot diff depth uses update ranges `U..=u`. A local order book must bootstrap with WS buffering plus REST snapshot, discard stale events, ensure the first applied event bridges the snapshot boundary, then detect later gaps when a new range starts beyond the local update id.

Binance USDⓈ-M Futures diff depth adds `pu`, the previous stream event's final update id. After the first bridged event, continuity should be validated by requiring each event's `pu` to equal the previous event's `u`.

Bitget UTA `books` is the reconstructable full-depth channel: first push is `snapshot`, later pushes are `update`. UTA docs expose `seq` and `pseq`; normal update continuity requires previous update `seq` to equal next update `pseq`. `pseq = 0` is a reset/restart path. The 2026-05-12 Bitget changelog records the checksum removal path for affected depth channels and tells clients to use `seq`/`pseq`.

`books1`, `books5`, and `books50` are snapshot-style channels in the current Bitget UTA docs. They should not be treated as incremental L2 delta streams.

## Bedrock Implications

The old Java `SequenceValidator` assumes a scalar `seq == last + 1` model. That is insufficient for Binance `U/u` ranges, Binance futures `pu`, and Bitget `seq/pseq`.

Rust should keep `BookReconstructor` venue-neutral and put raw venue continuity checks into venue adapters. Venue adapters should only emit normalized snapshot/delta batches after bootstrap and continuity have been validated.
