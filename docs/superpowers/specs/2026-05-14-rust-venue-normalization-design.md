# Rust Venue Normalization Design

日期：2026-05-14

状态：Implemented milestone 11

## Goal

把 raw venue depth parser 输出转换成 `bedrock-rs-md` 的 venue-neutral `BookSnapshot` / `BookDelta`。这一步仍然离线，不接 WebSocket、不做 REST bootstrap，只负责精确 decimal fixed-point 转换和 raw levels -> normalized levels/updates。

## Scope

修改 crate：

```text
bedrock-rs/crates/bedrock-rs-md-venue
```

新增依赖：

- `bedrock-rs-common`
- `bedrock-rs-md`

## Design

新增：

- `VenueNormalizeError`
- `NormalizedDepthEvent`
- `decimal_to_scaled_i64`
- `normalize_binance_spot_delta`
- `normalize_binance_futures_delta`
- `normalize_bitget_depth_message`

转换规则：

- price/quantity raw string 使用精确 decimal parser，scale `1e-8`。
- 不使用 float。
- Binance Spot/Futures diff depth 转 `BookDelta`，sequence 使用 raw `u`。
- Bitget `books` / `books5` `snapshot` 转 `BookSnapshot`，sequence 使用 raw `seq`。
- Bitget `books` `update` 转 `BookDelta`，sequence 使用 raw `seq`。
- raw bid levels 转 `Side::Bid`。
- raw ask levels 转 `Side::Ask`。
- quantity `0` 是合法 delete/update zero size。

## Verification

Tests cover:

- decimal parser exactness。
- decimal parser rejects too many fractional digits。
- Binance Spot raw fixture -> `BookDelta`。
- Binance Futures raw fixture -> `BookDelta`。
- Bitget snapshot fixture -> `BookSnapshot`。
- Bitget update fixture -> `BookDelta`。

Run:

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test -p bedrock-rs-md-venue
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

已验证：

- `cd bedrock-rs && cargo test -p bedrock-rs-md-venue`，32 个 `bedrock-rs-md-venue` 单元测试通过。
- `cd bedrock-rs && cargo fmt --check` 通过。
- `cd bedrock-rs && cargo test`，62 个 workspace 单元测试通过，5 个 doc-test crate 无测试且通过。
- `cd bedrock-rs && cargo clippy --all-targets -- -D warnings` 通过。
- `cd bedrock-rs && cargo doc --no-deps` 通过。

## Non-Goals

- 不执行 sequence policy。
- 不做 bootstrap buffering。
- 不连接 live WebSocket。
- 不获取 REST snapshot。
- 不发布到 transport。
