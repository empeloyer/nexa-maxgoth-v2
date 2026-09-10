package com.btcsignal.app.live

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.compose.ui.geometry.Offset
import com.btcsignal.app.MainActivity
import com.btcsignal.app.R
import com.btcsignal.app.data.binance.BinanceRestClient
import com.btcsignal.app.data.binance.BinanceStreamListener
import com.btcsignal.app.data.binance.BinanceWebSocketClient
import com.btcsignal.app.data.binance.ConnectionState
import com.btcsignal.app.data.local.AppDatabase
import com.btcsignal.app.data.model.*
import com.btcsignal.app.data.repository.SettingsRepository
import com.btcsignal.app.data.repository.SignalRepository
import com.btcsignal.app.engine.*
import com.btcsignal.app.notifications.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * The Live Engine (spec sections 25-27). Runs as a foreground service so monitoring can
 * continue while the app is backgrounded, subject to normal Android restrictions.
 * Wires: BinanceWebSocketClient -> MarketDataStore -> CandleAggregator ->
 * CoreSignalEngine -> SignalRepository (Room) -> NotificationHelper, all funneling
 * through LiveEngineState for the UI. This is the ONLY place the live path is wired;
 * BacktestEngine wires the same CoreSignalEngine independently for historical replay.
 */
class LiveMonitoringService : Service(), BinanceStreamListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var database: StrategyDatabase
    private val marketDataStore = MarketDataStore()
    private val aggregator = CandleAggregator()
    private lateinit var wsClient: BinanceWebSocketClient
    private val restClient = BinanceRestClient()
    private lateinit var signalRepo: SignalRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var prefs: SharedPreferences

    @Volatile private var resyncing = true
    @Volatile private var lastProcessedOpenTime = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("live_service_prefs", MODE_PRIVATE)
        prefs.edit { putBoolean("was_running", true) }

        database = StrategyRegistry.load(applicationContext)
        signalRepo = SignalRepository(AppDatabase.get(applicationContext).signalDao())
        settingsRepo = SettingsRepository(applicationContext)
        notificationHelper = NotificationHelper(applicationContext)
        wsClient = BinanceWebSocketClient(this)

        startForeground(FOREGROUND_ID, buildForegroundNotification("Connecting to Binance\u2026"))

        // The persistent notification previously only got refreshed from a couple of
        // scattered call sites (a connection-state change, a freshly locked signal), so
        // its text quickly went stale: once the engine moved on to a later phase
        // (ANALYZING, WAITING, CANDLE_CLOSED...) with no call site nearby, the
        // notification just kept showing whatever text was last set - commonly "Status:
        // SYNCING" if a background resync happened to run and set that appState value
        // around the same moment the notification text was last composed. Instead, the
        // notification now continuously mirrors the actual current state by observing
        // it directly, so it can never drift from what the app is really doing.
        serviceScope.launch {
            combine(LiveEngineState.appState, LiveEngineState.currentSignal) { state, signal ->
                notificationTextFor(state, signal)
            }.collect { text -> updateForegroundNotification(text) }
        }

        LiveEngineState.appState.value = AppState.SYNCING
        serviceScope.launch {
            warmUp()
            resyncing = false
            wsClient.connect()
        }
    }

    private fun notificationTextFor(state: AppState, signal: Signal?): String = when (state) {
        AppState.SIGNAL_LOCKED -> signal?.let { "Signal locked: ${it.direction} \u2022 ${it.activeStrategyId}" }
            ?: "Status: $state"
        else -> "Status: $state"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        prefs.edit { putBoolean("was_running", false) }
        wsClient.disconnect()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /** Backfills enough 1-minute history via REST for every indicator's longest lookback
     *  before trusting live data (spec section 26: "resynchronize candle state before
     *  allowing a new signal"). 4h ADX needs 2*period+1=29 closed 4h candles => up to
     *  ~4.8 days; we pull 8 days for headroom. */
    private suspend fun warmUp() {
        val end = System.currentTimeMillis()
        val start = end - WARMUP_DAYS * 24L * 60 * 60 * 1000
        try {
            val candles = restClient.getKlines("BTCUSDT", "1m", start, end)
            for (c in candles) {
                marketDataStore.addClosed1m(c)
                val events = aggregator.onClosed1mCandle(c)
                processAggregatorEvents(events)
                lastProcessedOpenTime = c.openTimeMillis
            }
        } catch (e: Exception) {
            LiveEngineState.appState.value = AppState.ERROR
        }
    }

    override fun onKlineUpdate(candle: Candle) {
        LiveEngineState.livePrice.value = candle.close

        if (!candle.isClosed && aggregator.isNewBucket(candle)) {
            // The next 5-minute candle has already started forming on Binance's side
            // (its first 1-minute sub-candle just opened but hasn't closed yet). The
            // aggregator itself only commits this transition once that sub-candle
            // closes via onClosed1mCandle -- a full minute from now. Without this,
            // the UI would keep showing the previous candle's signal/timer/open price
            // through all of minute 1 of the new candle, even though no signal has
            // been issued for it yet. Reflect the new candle immediately here instead.
            // Freeze the just-finished candle's full path (and record which candle it
            // belongs to) BEFORE candleOpenTimeMillis below gets overwritten with the
            // new candle's -- and before wiping candlePriceLog for the new candle (see
            // LiveEngineState.lastClosedCandlePriceLog KDoc for why this can't wait
            // until handleCandleClosed reads it later; by then it's too late, this same
            // clear would already have overwritten it with the new candle's ticks).
            // onKlineUpdate is always invoked serially on the main thread (see
            // BinanceWebSocketClient), so this freeze is guaranteed to run before any
            // later tick can start refilling the log for the new candle.
            synchronized(LiveEngineState.candlePriceLog) {
                LiveEngineState.lastClosedCandlePriceLog = ArrayList(LiveEngineState.candlePriceLog)
                LiveEngineState.lastClosedCandleOpenTime =
                    LiveEngineState.candleOpenTimeMillis.value.takeIf { it > 0 }
                LiveEngineState.candlePriceLog.clear()
                LiveEngineState.candlePriceLog.add(Offset(0f, candle.open.toFloat()))
            }
            LiveEngineState.candleOpen.value = candle.open
            LiveEngineState.candleOpenTimeMillis.value = aggregator.bucketStartFor(candle)
            LiveEngineState.currentMovePct.value =
                if (candle.open != 0.0) (candle.close - candle.open) / candle.open * 100.0 else 0.0
            LiveEngineState.candlePhase.value = CandlePhase.MINUTE_1
            LiveEngineState.appState.value = AppState.ANALYZING
            LiveEngineState.currentSignal.value = null
        } else {
            aggregator.currentCandleOpen()?.let { open ->
                LiveEngineState.candleOpen.value = open
                LiveEngineState.currentMovePct.value = if (open != 0.0) (candle.close - open) / open * 100.0 else 0.0
            }
            aggregator.currentCandleOpenTimeMillis()?.let { LiveEngineState.candleOpenTimeMillis.value = it }
        }

        // Log this tick into the current candle's chart-snapshot path, independent of
        // whether the Live screen is on-screen (see candlePriceLog KDoc).
        val openTime = LiveEngineState.candleOpenTimeMillis.value
        if (openTime > 0) {
            val elapsed = (System.currentTimeMillis() - openTime).coerceAtLeast(0L)
            synchronized(LiveEngineState.candlePriceLog) {
                // Deliberately no size cap / trim-from-front here. This array is frozen
                // verbatim as the permanent History snapshot the instant the candle
                // closes (see handleCandleClosed + lastClosedCandlePriceLog), and it is
                // fully clear()'d every 5 minutes by the isNewBucket branch above, so its
                // lifetime -- and therefore its size -- is inherently bounded to one
                // candle's worth of ticks regardless of tick rate (a few thousand Offsets
                // at most, negligible on a mobile device). A prior version of this code
                // capped it at 600 and dropped the OLDEST point once exceeded -- but the
                // oldest point is always the one nearest candle open / the Target line,
                // so on a busy stream (Binance can push several kline updates per second)
                // that cap silently deleted the beginning of the path well before the
                // candle closed, leaving History snapshots that start mid-candle, far
                // from x=0 and the Target line, showing only a short, often flat tail.
                // Do not reintroduce a cap on this specific array.
                LiveEngineState.candlePriceLog.add(Offset(elapsed.toFloat(), candle.close.toFloat()))
            }
        }

        if (!candle.isClosed) return
        if (candle.openTimeMillis <= lastProcessedOpenTime) return // duplicate guard (section 27)
        lastProcessedOpenTime = candle.openTimeMillis

        marketDataStore.addClosed1m(candle)
        val events = aggregator.onClosed1mCandle(candle)

        serviceScope.launch {
            processAggregatorEvents(events)
        }
    }

    /**
     * Applies aggregator events wherever they're produced. Previously `warmUp()` and
     * `resyncGap()` called `aggregator.onClosed1mCandle(c)` purely to keep the
     * aggregator's internal bucket state in sync, but threw away the returned events.
     * Any `FiveMinuteCandleClosed` event generated while catching up on REST history
     * (an app restart, a Doze-mode pause, a dropped WebSocket reconnecting after a gap)
     * was silently lost — so a signal that had already been locked for that candle
     * before the gap never had its outcome (WON/LOST) recorded and stayed ACTIVE
     * forever, even though the candle it depended on had long since closed. Routing
     * every call site through this same function means a gap-filled candle close is
     * resolved exactly like a live one. `handleCheckpoint` still separately guards on
     * `resyncing` so no NEW signal is ever issued from stale catch-up data - only
     * existing ones get their result recorded.
     */
    private suspend fun processAggregatorEvents(events: List<CandleEvent>) {
        for (event in events) {
            when (event) {
                is CandleEvent.PhaseChanged -> {
                    LiveEngineState.candlePhase.value = event.phase
                    LiveEngineState.appState.value = when (event.phase) {
                        CandlePhase.MINUTE_1, CandlePhase.MINUTE_2 -> AppState.ANALYZING
                        CandlePhase.PREDICTION_WINDOW_CLOSED -> AppState.PREDICTION_WINDOW_CLOSED
                        CandlePhase.CANDLE_CLOSED -> AppState.CANDLE_CLOSED
                    }
                    if (event.phase == CandlePhase.MINUTE_1) LiveEngineState.reset()
                }
                is CandleEvent.CheckpointReached -> handleCheckpoint(event)
                is CandleEvent.FiveMinuteCandleClosed -> handleCandleClosed(event.candle)
            }
        }
    }

    private suspend fun handleCheckpoint(event: CandleEvent.CheckpointReached) {
        if (resyncing) return // never signal off incomplete post-reconnect state (section 26)
        val candleId = java.time.Instant.ofEpochMilli(event.candleOpenTimeMillis).toString()
        if (signalRepo.isCandleLocked(candleId, isBacktest = false)) return // signal lock (section 9)

        // Precompute rolling stats for every strategy up front (suspend), so the engine
        // itself stays synchronous and identical between Live and Backtest call sites.
        val statsCache = HashMap<String, RecentWindowStats>()
        for (s in database.strategies) statsCache[s.id] = signalRepo.recentWindowStatsFor(s.id)
        val settings = settingsRepo.settingsFlow.first()

        val result = CoreSignalEngine.evaluateCheckpoint(
            database = database,
            store = marketDataStore,
            candleOpenTimeMillis = event.candleOpenTimeMillis,
            candleOpen = event.candleOpen,
            checkpoint = event.checkpoint,
            referencePrice = event.referencePrice,
            minute1Candle = event.minute1,
            minute2Candle = event.minute2,
            timestampMillis = event.timestampMillis,
            statsProvider = { id -> statsCache[id] ?: RecentWindowStats(0, 0.0) },
            blockedStrategyIds = settings.blockedStrategyIds
        )
        LiveEngineState.pushTrace(result.trace)
        result.trace.regime?.let { LiveEngineState.marketRegime.value = it }

        val signal = result.signal ?: return
        signalRepo.saveSignal(signal, isBacktest = false)
        LiveEngineState.currentSignal.value = signal
        LiveEngineState.appState.value = AppState.SIGNAL_LOCKED

        if (settings.notificationsEnabled && signalRepo.markNotifiedIfNeeded(signal.signalId)) {
            notificationHelper.notifySignal(signal, settings.soundEnabled, settings.vibrationEnabled)
        }
    }

    private suspend fun handleCandleClosed(candle: Candle) {
        val candleId = java.time.Instant.ofEpochMilli(candle.openTimeMillis).toString()
        val existing = signalRepo.getMostRecentActiveLiveSignal()
        if (existing != null && existing.candleId == candleId) {
            val signalDirection = Direction.valueOf(existing.direction)
            val (status, pnl) = CoreSignalEngine.evaluateResult(
                database,
                Signal(
                    signalId = existing.signalId, candleId = existing.candleId,
                    candleOpenTimeMillis = existing.candleOpenTimeMillis,
                    signalTimestampMillis = existing.signalTimestampMillis,
                    candleOpen = existing.candleOpen, signalPrice = existing.signalPrice,
                    direction = signalDirection, activeStrategyId = existing.activeStrategyId,
                    activeStrategyName = existing.activeStrategyName,
                    // Result evaluation only needs candleOpen/direction/financial model (see
                    // CoreSignalEngine.evaluateResult); regime is not re-derived here.
                    marketRegime = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK),
                    strategyScore = existing.strategyScore, confidencePct = existing.confidencePct,
                    entryMovePct = existing.entryMovePct, checkpoint = Checkpoint.valueOf(existing.checkpoint)
                ),
                candle.close
            )
            signalRepo.markResult(existing.signalId, status, candle.close, pnl)
            // Persist this candle's chart-snapshot path onto the signal row (see
            // LiveEngineState.lastClosedCandlePriceLog KDoc / PricePathSnapshot.kt) so
            // History can redraw it later. Prefer the frozen copy captured for exactly
            // this candle; only fall back to whatever's currently live if it wasn't
            // captured for this candle (e.g. a gap-filled/resynced close where no live
            // ticks were ever observed for it) rather than risk mis-attributing another
            // candle's path.
            val pathSnapshot = synchronized(LiveEngineState.candlePriceLog) {
                if (LiveEngineState.lastClosedCandleOpenTime == existing.candleOpenTimeMillis) {
                    ArrayList(LiveEngineState.lastClosedCandlePriceLog)
                } else {
                    ArrayList(LiveEngineState.candlePriceLog)
                }
            }
            signalRepo.savePriceSnapshot(existing.signalId, encodePricePath(pathSnapshot))
        }
        LiveEngineState.candlePhase.value = CandlePhase.CANDLE_CLOSED
        LiveEngineState.appState.value = AppState.WAITING
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        LiveEngineState.appState.value = when (state) {
            ConnectionState.CONNECTING -> AppState.CONNECTING
            ConnectionState.CONNECTED -> {
                if (resyncing.not() && lastProcessedOpenTime > 0) {
                    // Reconnected mid-session: resync any gap before trusting live data again.
                    serviceScope.launch { resyncGap() }
                }
                AppState.CONNECTED
            }
            ConnectionState.DISCONNECTED -> AppState.DISCONNECTED
            ConnectionState.ERROR -> AppState.ERROR
        }
    }

    private suspend fun resyncGap() {
        resyncing = true
        LiveEngineState.appState.value = AppState.SYNCING
        try {
            val start = lastProcessedOpenTime + 60_000L
            val end = System.currentTimeMillis()
            if (end > start) {
                val gapCandles = restClient.getKlines("BTCUSDT", "1m", start, end)
                for (c in gapCandles) {
                    if (c.openTimeMillis <= lastProcessedOpenTime) continue
                    marketDataStore.addClosed1m(c)
                    val events = aggregator.onClosed1mCandle(c)
                    processAggregatorEvents(events)
                    lastProcessedOpenTime = c.openTimeMillis
                }
            }
        } catch (e: Exception) {
            LiveEngineState.appState.value = AppState.ERROR
        } finally {
            resyncing = false
        }
    }

    override fun onError(message: String) {
        LiveEngineState.appState.value = AppState.ERROR
    }

    private fun buildForegroundNotification(text: String): Notification {
        notificationHelper.ensureChannels(soundEnabled = true, vibrationEnabled = true)
        val pendingIntent = androidx.core.app.TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@LiveMonitoringService, MainActivity::class.java))
            getPendingIntent(0, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BTCUSDT Signal monitoring")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(FOREGROUND_ID, buildForegroundNotification(text))
    }

    companion object {
        private const val FOREGROUND_ID = 42
        private const val WARMUP_DAYS = 8L
    }
}
