use bedrock_rs_common::{InstrumentId, VenueId};
use bedrock_rs_md_live::{
    binance_futures_session_once, binance_spot_session_once, bitget_books_smoke_once,
    BinanceDepthSpeed, BinanceLiveConfig, BinanceLiveSessionLimits, BinanceMarket,
    BitgetLiveConfig,
};
use bedrock_rs_md_venue::{NormalizedDepthEvent, VenuePipelineOutput};

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("{error}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        print_usage();
        std::process::exit(2);
    }

    let venue = args[1].as_str();
    let symbol = args[2].as_str();
    let max_messages = args
        .get(3)
        .map(|raw| raw.parse::<usize>())
        .transpose()?
        .unwrap_or(8);
    let post_bootstrap_messages = args
        .get(4)
        .map(|raw| raw.parse::<usize>())
        .transpose()?
        .unwrap_or(1);

    let outputs = match venue {
        "binance-spot" => {
            let config = BinanceLiveConfig::new(
                BinanceMarket::Spot,
                venue_id(1),
                instrument_id(1),
                symbol,
                1000,
                BinanceDepthSpeed::Millis100,
            );
            binance_spot_session_once(
                &config,
                BinanceLiveSessionLimits::new(max_messages, post_bootstrap_messages),
            )
            .await?
        }
        "binance-futures" => {
            let config = BinanceLiveConfig::new(
                BinanceMarket::UsdMFutures,
                venue_id(2),
                instrument_id(1),
                symbol,
                1000,
                BinanceDepthSpeed::Millis100,
            );
            binance_futures_session_once(
                &config,
                BinanceLiveSessionLimits::new(max_messages, post_bootstrap_messages),
            )
            .await?
        }
        "bitget-books" => {
            let config =
                BitgetLiveConfig::usdt_futures_books(venue_id(3), instrument_id(1), symbol);
            bitget_books_smoke_once(&config, max_messages).await?
        }
        _ => {
            print_usage();
            std::process::exit(2);
        }
    };

    println!("outputs={}", outputs.len());
    for (index, output) in outputs.iter().enumerate() {
        print_output(index, output);
    }

    Ok(())
}

fn venue_id(raw: u16) -> VenueId {
    VenueId::new(raw).expect("example venue id must be non-zero")
}

fn instrument_id(raw: u32) -> InstrumentId {
    InstrumentId::new(raw).expect("example instrument id must be non-zero")
}

fn print_usage() {
    eprintln!(
        "usage: cargo run -p bedrock-rs-md-live --example live_smoke -- <binance-spot|binance-futures|bitget-books> <SYMBOL> [bootstrap_or_depth_messages] [post_bootstrap_messages]"
    );
}

fn print_output(index: usize, output: &VenuePipelineOutput) {
    match output {
        VenuePipelineOutput::Normalized(NormalizedDepthEvent::Snapshot(snapshot)) => {
            println!(
                "#{index} snapshot sequence={} bids={} asks={}",
                snapshot.sequence.raw(),
                snapshot.bids.len(),
                snapshot.asks.len()
            );
        }
        VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(delta)) => {
            println!(
                "#{index} delta sequence={} updates={}",
                delta.sequence.raw(),
                delta.updates.len()
            );
        }
        VenuePipelineOutput::IgnoredStale(stale) => {
            println!(
                "#{index} ignored_stale venue={} instrument={} event_sequence={:?}",
                stale.venue_id.raw(),
                stale.instrument_id.raw(),
                stale.event_sequence
            );
        }
        VenuePipelineOutput::Gap(gap) => {
            println!(
                "#{index} gap reason={:?} event_sequence={:?}",
                gap.reason, gap.event_sequence
            );
        }
    }
}
