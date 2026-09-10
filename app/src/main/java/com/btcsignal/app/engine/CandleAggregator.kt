package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Candle
import com.btcsignal.app.data.model.CandlePhase
import com.btcsignal.app.data.model.Checkpoint

sealed class CandleEvent {
    /** A 5-minute candle has fully closed (all 5 of its 1m sub-candles were present). */
    data class FiveMinuteCandleClosed(val candle: Candle) : CandleEvent()

    /** Checkpoint A (after minute 1) or Checkpoint B (after minute 2) has just been reached. */
    data class CheckpointReached(
        val checkpoint: Checkpoint,
        val candleOpenTimeMillis: Long,
        val candleOpen: Double,
        val referencePrice: Double,
        val minute1: Candle?,
        val minute2: Candle?,
        val timestampMillis: Long
    ) : CandleEvent()

    data class PhaseChanged(val phase: CandlePhase) : CandleEvent()
}

/**
 * Builds the currently-forming 5-minute BTCUSDT candle from a sequential stream of
 * CLOSED 1-minute candles (spec section 5), and emits Checkpoint A / Checkpoint B events
 * exactly at the moments the Strategy Database defines them (immediately after the 1st
 * and 2nd 1-minute sub-candles close — meta.hard_constraints.checkpoints). This same
 * class drives both LiveMonitoringService (fed by the Binance WebSocket) and
 * BacktestEngine / HistoricalReplay (fed by historical REST klines in chronological
 * order), which is how spec section 39's Live/Backtest consistency requirement is met
 * for candle-timing logic specifically.
 *
 * A 5-minute candle is only ever reported as closed once all 5 of its constituent
 * 1-minute candles have actually been observed — if data is missing, no
 * FiveMinuteCandleClosed event fires for that period (spec section 27, data integrity).
 */
class CandleAggregator {

    var currentPhase: CandlePhase = CandlePhase.CANDLE_CLOSED
        private set

    private var bucketStart: Long = -1L
    private var open = 0.0
    private var high = Double.NEGATIVE_INFINITY
    private var low = Double.POSITIVE_INFINITY
    private var close = 0.0
    private var volume = 0.0
    private var minuteIndex = 0
    private var minute1: Candle? = null
    private var minute2: Candle? = null

    fun onClosed1mCandle(candle: Candle): List<CandleEvent> {
        val events = ArrayList<CandleEvent>()
        val newBucketStart = (candle.openTimeMillis / FIVE_MIN_MILLIS) * FIVE_MIN_MILLIS

        when {
            bucketStart == -1L -> resetBucket(newBucketStart, candle)
            newBucketStart != bucketStart -> {
                if (minuteIndex == 5) {
                    events.add(
                        CandleEvent.FiveMinuteCandleClosed(
                            Candle(bucketStart, open, high, low, close, volume, bucketStart + FIVE_MIN_MILLIS - 1, true)
                        )
                    )
                }
                resetBucket(newBucketStart, candle)
            }
            else -> {
                high = maxOf(high, candle.high)
                low = minOf(low, candle.low)
                close = candle.close
                volume += candle.volume
            }
        }

        minuteIndex += 1
        when (minuteIndex) {
            1 -> {
                minute1 = candle
                currentPhase = CandlePhase.MINUTE_1
                events.add(CandleEvent.PhaseChanged(currentPhase))
                events.add(
                    CandleEvent.CheckpointReached(
                        Checkpoint.A, bucketStart, open, candle.close, minute1, null, candle.closeTimeMillis
                    )
                )
            }
            2 -> {
                minute2 = candle
                currentPhase = CandlePhase.MINUTE_2
                events.add(CandleEvent.PhaseChanged(currentPhase))
                events.add(
                    CandleEvent.CheckpointReached(
                        Checkpoint.B, bucketStart, open, candle.close, minute1, minute2, candle.closeTimeMillis
                    )
                )
            }
            3 -> {
                currentPhase = CandlePhase.PREDICTION_WINDOW_CLOSED
                events.add(CandleEvent.PhaseChanged(currentPhase))
            }
        }
        return events
    }

    fun currentCandleOpenTimeMillis(): Long? = if (bucketStart == -1L) null else bucketStart
    fun currentCandleOpen(): Double? = if (bucketStart == -1L) null else open
    fun currentMinute1(): Candle? = minute1
    fun currentMinute2(): Candle? = minute2

    /** The 5-minute bucket start that [candle] belongs to, regardless of whether it has
     *  closed yet. Lets callers detect a new 5-minute candle the instant it starts
     *  forming (e.g. from live/unclosed kline ticks), instead of only finding out once
     *  its first 1-minute sub-candle has fully closed via [onClosed1mCandle]. */
    fun bucketStartFor(candle: Candle): Long = (candle.openTimeMillis / FIVE_MIN_MILLIS) * FIVE_MIN_MILLIS

    /** True once [candle] (closed or still forming) belongs to a later 5-minute bucket
     *  than the one this aggregator currently has committed via [onClosed1mCandle]. */
    fun isNewBucket(candle: Candle): Boolean =
        bucketStart != -1L && bucketStartFor(candle) != bucketStart

    private fun resetBucket(newStart: Long, candle: Candle) {
        bucketStart = newStart
        open = candle.open
        high = candle.high
        low = candle.low
        close = candle.close
        volume = candle.volume
        minuteIndex = 0
        minute1 = null
        minute2 = null
    }

    companion object {
        private const val FIVE_MIN_MILLIS = 5 * 60_000L
    }
}
