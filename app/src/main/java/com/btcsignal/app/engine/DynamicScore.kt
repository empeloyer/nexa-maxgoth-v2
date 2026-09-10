package com.btcsignal.app.engine

import com.btcsignal.app.data.model.StrategyDef
import kotlin.math.ln
import kotlin.math.max

/**
 * Rolling recent-window statistics for one strategy, used by [DynamicScore]. In the live
 * engine this is computed from this strategy's own signal history in Room over the
 * trailing 30 days (spec section 10: "N_signals_recent_window ... باید روی یک پنجره
 * غلطان ... محاسبه شوند"). Until enough live history has accumulated, the engine falls
 * back to the strategy's Out-of-Sample numbers straight from the Strategy Database —
 * this fallback is a deliberate, documented choice (the formula's own recent-window
 * inputs don't exist yet on day one of a fresh install), not an invented replacement
 * for the formula itself.
 */
data class RecentWindowStats(
    val nSignals: Int,
    val pnlPerSignalUsd: Double
)

/**
 * Implements, verbatim, the scoring formula from BTC_5m_Strategy_Research_Report.md
 * section 10:
 *
 *   Dynamic_Score = 0.5 * max(z_OOS, 0)
 *                 + 10  * Expected_PnL_per_Signal_recent_window
 *                 + 0.3 * log(1 + N_signals_recent_window)
 *                 - Penalty_if_regime_mismatch
 *                 - Penalty_if_high_overlap_with_higher_score_strategy
 *
 * DOCUMENTED AMBIGUITY: the report defines these two penalty terms by name only and
 * does not give a formula or coefficient for either (section 10's "implementation
 * notes" only describe *when* to apply them qualitatively). Per the master-prompt rule
 * to never invent a scoring formula when one isn't fully specified, both penalty terms
 * are kept in the formula's structure but evaluate to 0.0 here:
 *   - Penalty_if_regime_mismatch is structurally always 0 in this engine because
 *     ConflictResolver only ever calls this function for strategies whose regime_gate
 *     already matches the current MarketRegimeState — a mismatched strategy is excluded
 *     upstream, before scoring, rather than scored-down.
 *   - Penalty_if_high_overlap_with_higher_score_strategy is left at 0.0 with the
 *     strategy's max_signal_overlap_with_other_selected_strategies_pct exposed on the
 *     StrategyDef for a future engine revision to wire in once Anthropic/you supply the
 *     missing coefficient — inventing one now would violate rule 0 of the master prompt.
 */
object DynamicScore {

    fun compute(strategy: StrategyDef, recent: RecentWindowStats): Double {
        val zOos = strategy.performance.oosZScoreVsBreakeven ?: 0.0
        val pnlPerSignal = if (recent.nSignals > 0) recent.pnlPerSignalUsd
            else strategy.performance.oosPnlPerSignalUsd ?: 0.0
        val nRecent = if (recent.nSignals > 0) recent.nSignals else strategy.performance.oosSignals

        val term1 = 0.5 * max(zOos, 0.0)
        val term2 = 10.0 * pnlPerSignal
        val term3 = 0.3 * ln(1.0 + nRecent)
        val regimeMismatchPenalty = 0.0   // see class doc: structurally excluded upstream
        val overlapPenalty = 0.0          // see class doc: coefficient not specified by source report

        return term1 + term2 + term3 - regimeMismatchPenalty - overlapPenalty
    }
}
