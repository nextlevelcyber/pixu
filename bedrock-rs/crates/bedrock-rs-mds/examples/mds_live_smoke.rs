use bedrock_rs_common::{InstrumentId, VenueId};
use bedrock_rs_md::ReconstructionOutput;
use bedrock_rs_md_live::{
    binance_futures_session_once, binance_spot_session_once, bitget_books_smoke_once,
    BinanceDepthSpeed, BinanceLiveConfig, BinanceLiveSessionLimits, BinanceMarket,
    BitgetLiveConfig,
};
use bedrock_rs_mds::{MdsOutput, MdsRouter};

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

    let pipeline_outputs = match venue {
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

    let mut router = MdsRouter::new();
    let mds_outputs = router.apply_pipeline_outputs(pipeline_outputs);

    println!("outputs={}", mds_outputs.len());
    for (index, output) in mds_outputs.iter().enumerate() {
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
        "usage: cargo run -p bedrock-rs-mds --example mds_live_smoke -- <binance-spot|binance-futures|bitget-books> <SYMBOL> [bootstrap_or_depth_messages] [post_bootstrap_messages]"
    );
}

fn print_output(index: usize, output: &MdsOutput) {
    match output {
        MdsOutput::Reconstruction(ReconstructionOutput::Bbo(bbo)) => {
            println!(
                "#{index} bbo sequence={} bid={}x{} ask={}x{}",
                bbo.sequence.raw(),
                bbo.bid_price.raw(),
                bbo.bid_quantity.raw(),
                bbo.ask_price.raw(),
                bbo.ask_quantity.raw()
            );
        }
        MdsOutput::Reconstruction(ReconstructionOutput::Gap(gap)) => {
            println!(
                "#{index} book_gap reason={:?} expected={:?} actual={}",
                gap.reason,
                gap.expected_sequence.map(|sequence| sequence.raw()),
                gap.actual_sequence.raw()
            );
        }
        MdsOutput::Reconstruction(ReconstructionOutput::Reject(reject)) => {
            println!(
                "#{index} reject reason={:?} venue={} instrument={} sequence={}",
                reject.reason,
                reject.actual_venue_id.raw(),
                reject.actual_instrument_id.raw(),
                reject.sequence.raw()
            );
        }
        MdsOutput::IgnoredStale(stale) => {
            println!(
                "#{index} ignored_stale venue={} instrument={} event_sequence={:?}",
                stale.venue_id.raw(),
                stale.instrument_id.raw(),
                stale.event_sequence
            );
        }
        MdsOutput::VenueGap(gap) => {
            println!(
                "#{index} venue_gap reason={:?} event_sequence={:?}",
                gap.reason, gap.event_sequence
            );
        }
    }
}
