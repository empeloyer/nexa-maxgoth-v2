package com.btcsignal.app.engine

import org.junit.Assert.*
import org.junit.Test

class StrategyRegistryTest {

    private fun loadRealDatabase(): String =
        StrategyRegistryTest::class.java.classLoader!!
            .getResourceAsStream("strategies_parameters.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `every strategy in the source JSON is parsed - coverage check`() {
        val json = loadRealDatabase()
        val db = StrategyRegistry.parse(json)

        val rawIds = Regex("\"id\"\\s*:\\s*\"(STRAT-[A-Z0-9]+)\"").findAll(json).map { it.groupValues[1] }.toSet()
        val parsedIds = db.strategies.map { it.id }.toSet()

        assertEquals("Strategy coverage mismatch: database vs implementation", rawIds, parsedIds)
        assertTrue("Expected at least one strategy to be parsed", db.strategies.isNotEmpty())
    }

    @Test
    fun `no strategy loses its components during parsing`() {
        val db = StrategyRegistry.parse(loadRealDatabase())
        for (strategy in db.strategies) {
            assertTrue("Strategy ${strategy.id} has no components", strategy.components.isNotEmpty())
        }
    }

    @Test
    fun `hard constraints match the exact fixed entry range from the report`() {
        val db = StrategyRegistry.parse(loadRealDatabase())
        assertEquals(0.0, db.hardConstraints.entryRangeGreenPct.start, 0.0001)
        assertEquals(0.03, db.hardConstraints.entryRangeGreenPct.endInclusive, 0.0001)
        assertEquals(-0.03, db.hardConstraints.entryRangeRedPct.start, 0.0001)
        assertEquals(0.0, db.hardConstraints.entryRangeRedPct.endInclusive, 0.0001)
    }

    @Test
    fun `financial model matches the fixed spec`() {
        val db = StrategyRegistry.parse(loadRealDatabase())
        assertEquals(100.0, db.financialModel.startCapitalUsd, 0.0001)
        assertEquals(1.0, db.financialModel.stakePerSignalUsd, 0.0001)
        assertEquals(0.5, db.financialModel.winUsd, 0.0001)
        assertEquals(-1.0, db.financialModel.lossUsd, 0.0001)
    }
}
