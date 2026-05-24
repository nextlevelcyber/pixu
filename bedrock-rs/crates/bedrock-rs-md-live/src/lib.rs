//! Live market-data network boundary for Rust Bedrock.
//!
//! This crate owns public exchange HTTP/WebSocket endpoint construction and
//! one-shot smoke helpers. Venue-specific sequence and normalization rules stay
//! in `bedrock-rs-md-venue`; venue-neutral reconstruction stays in
//! `bedrock-rs-md`.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use bedrock_rs_common::{InstrumentId, TimestampNs, ValueError, VenueId};
use bedrock_rs_md_venue::{
    BinanceFuturesDepthPipeline, BinanceSpotDepthPipeline, BitgetBooksDepthPipeline,
    NormalizedDepthEvent, VenuePipelineError, VenuePipelineOutput,
};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use tokio::time::timeout;
use tokio_tungstenite::{connect_async, tungstenite::Message};

const IO_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BinanceMarket {
    Spot,
    UsdMFutures,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BinanceDepthSpeed {
    Default,
    Millis100,
    Millis500,
}

impl BinanceDepthSpeed {
    fn stream_suffix(self) -> &'static str {
        match self {
            Self::Default => "@depth",
            Self::Millis100 => "@depth@100ms",
            Self::Millis500 => "@depth@500ms",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BinanceLiveConfig {
    market: BinanceMarket,
    venue_id: VenueId,
    instrument_id: InstrumentId,
    symbol: String,
    depth_limit: u16,
    speed: BinanceDepthSpeed,
}

impl BinanceLiveConfig {
    pub fn new(
        market: BinanceMarket,
        venue_id: VenueId,
        instrument_id: InstrumentId,
        symbol: impl AsRef<str>,
        depth_limit: u16,
        speed: BinanceDepthSpeed,
    ) -> Self {
        Self {
            market,
            venue_id,
            instrument_id,
            symbol: symbol.as_ref().to_ascii_uppercase(),
            depth_limit,
            speed,
        }
    }

    pub fn market(&self) -> BinanceMarket {
        self.market
    }

    pub fn venue_id(&self) -> VenueId {
        self.venue_id
    }

    pub fn instrument_id(&self) -> InstrumentId {
        self.instrument_id
    }

    pub fn rest_depth_url(&self) -> String {
        match self.market {
            BinanceMarket::Spot => format!(
                "https://api.binance.com/api/v3/depth?symbol={}&limit={}",
                self.symbol, self.depth_limit
            ),
            BinanceMarket::UsdMFutures => format!(
                "https://fapi.binance.com/fapi/v1/depth?symbol={}&limit={}",
                self.symbol, self.depth_limit
            ),
        }
    }

    pub fn ws_depth_url(&self) -> String {
        let stream_name = format!(
            "{}{}",
            self.symbol.to_ascii_lowercase(),
            self.speed.stream_suffix()
        );
        match self.market {
            BinanceMarket::Spot => {
                format!("wss://stream.binance.com:9443/ws/{stream_name}")
            }
            BinanceMarket::UsdMFutures => {
                format!("wss://fstream.binance.com/ws/{stream_name}")
            }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BinanceLiveSessionLimits {
    bootstrap_buffer_messages: usize,
    post_bootstrap_messages: usize,
}

impl BinanceLiveSessionLimits {
    pub const fn new(bootstrap_buffer_messages: usize, post_bootstrap_messages: usize) -> Self {
        Self {
            bootstrap_buffer_messages,
            post_bootstrap_messages,
        }
    }

    pub const fn bootstrap_buffer_messages(&self) -> usize {
        self.bootstrap_buffer_messages
    }

    pub const fn post_bootstrap_messages(&self) -> usize {
        self.post_bootstrap_messages
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BitgetLiveConfig {
    venue_id: VenueId,
    instrument_id: InstrumentId,
    inst_type: String,
    topic: String,
    symbol: String,
}

impl BitgetLiveConfig {
    pub fn usdt_futures_books(
        venue_id: VenueId,
        instrument_id: InstrumentId,
        symbol: impl AsRef<str>,
    ) -> Self {
        Self {
            venue_id,
            instrument_id,
            inst_type: "usdt-futures".to_owned(),
            topic: "books".to_owned(),
            symbol: symbol.as_ref().to_ascii_uppercase(),
        }
    }

    pub fn venue_id(&self) -> VenueId {
        self.venue_id
    }

    pub fn instrument_id(&self) -> InstrumentId {
        self.instrument_id
    }

    pub fn ws_public_url(&self) -> &'static str {
        "wss://ws.bitget.com/v3/ws/public"
    }

    pub fn subscribe_json(&self) -> String {
        json!({
            "op": "subscribe",
            "args": [{
                "instType": self.inst_type.as_str(),
                "topic": self.topic.as_str(),
                "symbol": self.symbol.as_str()
            }]
        })
        .to_string()
    }
}

#[derive(Debug)]
pub enum LiveMarketDataError {
    Http(reqwest::Error),
    WebSocket(tokio_tungstenite::tungstenite::Error),
    Pipeline(VenuePipelineError),
    Timeout,
    WebSocketClosed,
    WrongMarket {
        expected: BinanceMarket,
        actual: BinanceMarket,
    },
    Time(std::time::SystemTimeError),
    Timestamp(ValueError),
    TimestampOverflow,
}

impl std::fmt::Display for LiveMarketDataError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Http(error) => write!(formatter, "http error: {error}"),
            Self::WebSocket(error) => write!(formatter, "websocket error: {error}"),
            Self::Pipeline(error) => write!(formatter, "venue pipeline error: {error:?}"),
            Self::Timeout => write!(formatter, "timed out waiting for live market data"),
            Self::WebSocketClosed => write!(formatter, "websocket closed"),
            Self::WrongMarket { expected, actual } => {
                write!(
                    formatter,
                    "wrong market: expected {expected:?}, got {actual:?}"
                )
            }
            Self::Time(error) => write!(formatter, "system time error: {error}"),
            Self::Timestamp(error) => write!(formatter, "invalid timestamp: {error:?}"),
            Self::TimestampOverflow => write!(formatter, "timestamp overflow"),
        }
    }
}

impl std::error::Error for LiveMarketDataError {}

impl From<reqwest::Error> for LiveMarketDataError {
    fn from(value: reqwest::Error) -> Self {
        Self::Http(value)
    }
}

impl From<tokio_tungstenite::tungstenite::Error> for LiveMarketDataError {
    fn from(value: tokio_tungstenite::tungstenite::Error) -> Self {
        Self::WebSocket(value)
    }
}

impl From<VenuePipelineError> for LiveMarketDataError {
    fn from(value: VenuePipelineError) -> Self {
        Self::Pipeline(value)
    }
}

impl From<std::time::SystemTimeError> for LiveMarketDataError {
    fn from(value: std::time::SystemTimeError) -> Self {
        Self::Time(value)
    }
}

pub fn unix_now_timestamp_ns() -> Result<TimestampNs, LiveMarketDataError> {
    let duration = SystemTime::now().duration_since(UNIX_EPOCH)?;
    let raw_ns = duration
        .as_secs()
        .checked_mul(1_000_000_000)
        .and_then(|value| value.checked_add(u64::from(duration.subsec_nanos())))
        .ok_or(LiveMarketDataError::TimestampOverflow)?;
    TimestampNs::new(raw_ns).map_err(LiveMarketDataError::Timestamp)
}

pub fn is_bitget_event_frame(raw: &str) -> bool {
    match serde_json::from_str::<Value>(raw) {
        Ok(value) => value.get("event").is_some(),
        Err(_) => false,
    }
}

pub async fn binance_spot_smoke_once(
    config: &BinanceLiveConfig,
    max_buffered_messages: usize,
) -> Result<Vec<VenuePipelineOutput>, LiveMarketDataError> {
    if config.market() != BinanceMarket::Spot {
        return Err(LiveMarketDataError::WrongMarket {
            expected: BinanceMarket::Spot,
            actual: config.market(),
        });
    }

    let (mut socket, _) = connect_async(config.ws_depth_url()).await?;
    let buffered_messages =
        read_text_messages_from_socket(&mut socket, max_buffered_messages).await?;
    let snapshot = fetch_text(&config.rest_depth_url()).await?;
    let mut pipeline = BinanceSpotDepthPipeline::new(config.venue_id(), config.instrument_id());
    let mut outputs = vec![pipeline.apply_snapshot_json(&snapshot, unix_now_timestamp_ns()?)?];
    let mut has_actionable_update = false;

    for message in buffered_messages {
        let output = pipeline.process_update_json(&message)?;
        has_actionable_update |= is_actionable_after_snapshot(&output);
        outputs.push(output);
    }

    let mut post_snapshot_reads = 0;
    while !has_actionable_update && post_snapshot_reads < max_buffered_messages.max(1) {
        let message = next_text_message(&mut socket).await?;
        let output = pipeline.process_update_json(&message)?;
        has_actionable_update |= is_actionable_after_snapshot(&output);
        outputs.push(output);
        post_snapshot_reads += 1;
    }

    Ok(outputs)
}

pub async fn binance_spot_session_once(
    config: &BinanceLiveConfig,
    limits: BinanceLiveSessionLimits,
) -> Result<Vec<VenuePipelineOutput>, LiveMarketDataError> {
    if config.market() != BinanceMarket::Spot {
        return Err(LiveMarketDataError::WrongMarket {
            expected: BinanceMarket::Spot,
            actual: config.market(),
        });
    }

    let (mut socket, _) = connect_async(config.ws_depth_url()).await?;
    let buffered_messages =
        read_text_messages_from_socket(&mut socket, limits.bootstrap_buffer_messages()).await?;
    let snapshot = fetch_text(&config.rest_depth_url()).await?;
    let mut pipeline = BinanceSpotDepthPipeline::new(config.venue_id(), config.instrument_id());
    let mut outputs = vec![pipeline.apply_snapshot_json(&snapshot, unix_now_timestamp_ns()?)?];

    for message in buffered_messages {
        outputs.push(pipeline.process_update_json(&message)?);
    }

    for _ in 0..limits.post_bootstrap_messages() {
        let message = next_text_message(&mut socket).await?;
        outputs.push(pipeline.process_update_json(&message)?);
    }

    Ok(outputs)
}

pub async fn binance_futures_smoke_once(
    config: &BinanceLiveConfig,
    max_buffered_messages: usize,
) -> Result<Vec<VenuePipelineOutput>, LiveMarketDataError> {
    if config.market() != BinanceMarket::UsdMFutures {
        return Err(LiveMarketDataError::WrongMarket {
            expected: BinanceMarket::UsdMFutures,
            actual: config.market(),
        });
    }

    let (mut socket, _) = connect_async(config.ws_depth_url()).await?;
    let buffered_messages =
        read_text_messages_from_socket(&mut socket, max_buffered_messages).await?;
    let snapshot = fetch_text(&config.rest_depth_url()).await?;
    let mut pipeline = BinanceFuturesDepthPipeline::new(config.venue_id(), config.instrument_id());
    let mut outputs = vec![pipeline.apply_snapshot_json(&snapshot)?];
    let mut has_actionable_update = false;

    for message in buffered_messages {
        let output = pipeline.process_update_json(&message)?;
        has_actionable_update |= is_actionable_after_snapshot(&output);
        outputs.push(output);
    }

    let mut post_snapshot_reads = 0;
    while !has_actionable_update && post_snapshot_reads < max_buffered_messages.max(1) {
        let message = next_text_message(&mut socket).await?;
        let output = pipeline.process_update_json(&message)?;
        has_actionable_update |= is_actionable_after_snapshot(&output);
        outputs.push(output);
        post_snapshot_reads += 1;
    }

    Ok(outputs)
}

pub async fn binance_futures_session_once(
    config: &BinanceLiveConfig,
    limits: BinanceLiveSessionLimits,
) -> Result<Vec<VenuePipelineOutput>, LiveMarketDataError> {
    if config.market() != BinanceMarket::UsdMFutures {
        return Err(LiveMarketDataError::WrongMarket {
            expected: BinanceMarket::UsdMFutures,
            actual: config.market(),
        });
    }

    let (mut socket, _) = connect_async(config.ws_depth_url()).await?;
    let buffered_messages =
        read_text_messages_from_socket(&mut socket, limits.bootstrap_buffer_messages()).await?;
    let snapshot = fetch_text(&config.rest_depth_url()).await?;
    let mut pipeline = BinanceFuturesDepthPipeline::new(config.venue_id(), config.instrument_id());
    let mut outputs = vec![pipeline.apply_snapshot_json(&snapshot)?];

    for message in buffered_messages {
        outputs.push(pipeline.process_update_json(&message)?);
    }

    for _ in 0..limits.post_bootstrap_messages() {
        let message = next_text_message(&mut socket).await?;
        outputs.push(pipeline.process_update_json(&message)?);
    }

    Ok(outputs)
}

pub async fn bitget_books_smoke_once(
    config: &BitgetLiveConfig,
    max_depth_messages: usize,
) -> Result<Vec<VenuePipelineOutput>, LiveMarketDataError> {
    let (mut socket, _) = connect_async(config.ws_public_url()).await?;
    socket
        .send(Message::Text(config.subscribe_json().into()))
        .await?;

    let mut pipeline = BitgetBooksDepthPipeline::new(config.venue_id(), config.instrument_id());
    let mut outputs = Vec::with_capacity(max_depth_messages);
    while outputs.len() < max_depth_messages {
        let message = next_text_message(&mut socket).await?;
        if is_bitget_event_frame(&message) {
            continue;
        }
        outputs.push(pipeline.process_json(&message)?);
    }

    Ok(outputs)
}

async fn fetch_text(url: &str) -> Result<String, LiveMarketDataError> {
    Ok(reqwest::get(url).await?.error_for_status()?.text().await?)
}

fn is_actionable_after_snapshot(output: &VenuePipelineOutput) -> bool {
    matches!(
        output,
        VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(_))
            | VenuePipelineOutput::Gap(_)
    )
}

async fn read_text_messages_from_socket<S>(
    socket: &mut S,
    max_messages: usize,
) -> Result<Vec<String>, LiveMarketDataError>
where
    S: futures_util::Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    let mut messages = Vec::with_capacity(max_messages);
    while messages.len() < max_messages {
        messages.push(next_text_message(socket).await?);
    }
    Ok(messages)
}

async fn next_text_message<S>(socket: &mut S) -> Result<String, LiveMarketDataError>
where
    S: futures_util::Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    loop {
        let next = timeout(IO_TIMEOUT, socket.next())
            .await
            .map_err(|_| LiveMarketDataError::Timeout)?
            .ok_or(LiveMarketDataError::WebSocketClosed)??;
        if let Message::Text(text) = next {
            return Ok(text.to_string());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bedrock_rs_common::{InstrumentId, VenueId};
    use serde_json::json;

    fn venue(raw: u16) -> VenueId {
        VenueId::new(raw).unwrap()
    }

    fn instrument(raw: u32) -> InstrumentId {
        InstrumentId::new(raw).unwrap()
    }

    #[test]
    fn binance_live_session_limits_store_bootstrap_and_post_bootstrap_reads() {
        let limits = BinanceLiveSessionLimits::new(8, 5);

        assert_eq!(limits.bootstrap_buffer_messages(), 8);
        assert_eq!(limits.post_bootstrap_messages(), 5);
    }

    #[test]
    fn binance_spot_urls_use_uppercase_rest_and_lowercase_ws_stream() {
        let config = BinanceLiveConfig::new(
            BinanceMarket::Spot,
            venue(1),
            instrument(1),
            "BNBBTC",
            1000,
            BinanceDepthSpeed::Millis100,
        );

        assert_eq!(
            config.rest_depth_url(),
            "https://api.binance.com/api/v3/depth?symbol=BNBBTC&limit=1000"
        );
        assert_eq!(
            config.ws_depth_url(),
            "wss://stream.binance.com:9443/ws/bnbbtc@depth@100ms"
        );
    }

    #[test]
    fn binance_futures_urls_use_fapi_and_fstream() {
        let config = BinanceLiveConfig::new(
            BinanceMarket::UsdMFutures,
            venue(2),
            instrument(7),
            "BTCUSDT",
            1000,
            BinanceDepthSpeed::Millis100,
        );

        assert_eq!(
            config.rest_depth_url(),
            "https://fapi.binance.com/fapi/v1/depth?symbol=BTCUSDT&limit=1000"
        );
        assert_eq!(
            config.ws_depth_url(),
            "wss://fstream.binance.com/ws/btcusdt@depth@100ms"
        );
    }

    #[test]
    fn bitget_uta_subscribe_json_uses_topic_and_symbol() {
        let config = BitgetLiveConfig::usdt_futures_books(venue(3), instrument(9), "BTCUSDT");

        assert_eq!(config.ws_public_url(), "wss://ws.bitget.com/v3/ws/public");
        assert_eq!(
            config.subscribe_json(),
            json!({
                "op": "subscribe",
                "args": [{
                    "instType": "usdt-futures",
                    "topic": "books",
                    "symbol": "BTCUSDT"
                }]
            })
            .to_string()
        );
    }

    #[test]
    fn bitget_event_frames_are_not_depth_data() {
        assert!(is_bitget_event_frame(
            r#"{"event":"subscribe","arg":{"instType":"UTA","topic":"books","symbol":"BTCUSDT"}}"#
        ));
        assert!(!is_bitget_event_frame(
            r#"{"action":"snapshot","arg":{"topic":"books","symbol":"BTCUSDT"},"data":[]}"#
        ));
    }
}
