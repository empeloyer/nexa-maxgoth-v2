package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Candle
import com.btcsignal.app.data.model.CandlePhase
import com.btcsignal.app.data.model.Checkpoint
import org.junit.Assert.*
import org.junit.Test

class CandleAggregatorTest {

    private fun oneMinCandle(openTimeMillis: Long, open: Double, close: Double) =
        Candle(openTimeMillis, open, maxOf(open, close) + 1, minOf(open, close) - 1, close, 5.0, openTimeMillis + 59_999, true)

    @Test
    fun `checkpoint A fires immediately after minute 1 closes, checkpoint B after minute 2`() {
        val aggregator = CandleAggregator()
        val base = 1_700_000_000_000L / 300_000L * 300_000L // aligned to a 5m boundary

        val eventsMinute1 = aggregator.onClosed1mCandle(oneMinCandle(base, 100.0, 100.02))
        assertTrue(eventsMinute1.any { it is CandleEvent.PhaseChanged && it.phase == CandlePhase.MINUTE_1 })
        assertTrue(eventsMinute1.any { it is CandleEvent.CheckpointReached && it.checkpoint == Checkpoint.A })

        val eventsMinute2 = aggregator.onClosed1mCandle(oneMinCandle(base + 60_000, 100.02, 100.01))
        assertTrue(eventsMinute2.any { it is CandleEvent.PhaseChanged && it.phase == CandlePhase.MINUTE_2 })
        assertTrue(eventsMinute2.any { it is CandleEvent.CheckpointReached && it.checkpoint == Checkpoint.B })

        val eventsMinute3 = aggregator.onClosed1mCandle(oneMinCandle(base + 120_000, 100.01, 100.03))
        assertTrue(eventsMinute3.any { it is CandleEvent.PhaseChanged && it.phase == CandlePhase.PREDICTION_WINDOW_CLOSED })
        assertTrue(eventsMinute3.none { it is CandleEvent.CheckpointReached })
    }

    @Test
    fun `a 5-minute candle only closes once all 5 sub-candles are present`() {
        val aggregator = CandleAggregator()
        val base = 1_700_000_000_000L / 300_000L * 300_000L

        for (i in 0 until 5) {
            aggregator.onClosed1mCandle(oneMinCandle(base + i * 60_000, 100.0 + i, 100.0 + i + 0.5))
        }
        // The 6th candle (first of the NEXT 5m period) is what triggers the close event for the previous one.
        val closingEvents = aggregator.onClosed1mCandle(oneMinCandle(base + 5 * 60_000, 105.0, 105.2))
        val closed = closingEvents.filterIsInstance<CandleEvent.FiveMinuteCandleClosed>()
        assertEquals(1, closed.size)
        assertEquals(base, closed[0].candle.openTimeMillis)
        assertEquals(100.0, closed[0].candle.open, 0.0001)
    }

    @Test
    fun `missing 1-minute candles prevent a 5-minute close event (data integrity)`() {
        val aggregator = CandleAggregator()
        val base = 1_700_000_000_000L / 300_000L * 300_000L

        // Only 3 of the 5 sub-candles observed, then jump straight to the next bucket.
        aggregator.onClosed1mCandle(oneMinCandle(base, 100.0, 100.1))
        aggregator.onClosed1mCandle(oneMinCandle(base + 60_000, 100.1, 100.2))
        aggregator.onClosed1mCandle(oneMinCandle(base + 120_000, 100.2, 100.3))

        val events = aggregator.onClosed1mCandle(oneMinCandle(base + 300_000, 105.0, 105.1))
        assertTrue(events.none { it is CandleEvent.FiveMinuteCandleClosed })
    }
}
