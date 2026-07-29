package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombatTest : IntegrationTestBase() {

    /**
     * Two failed analyzeSubsystem calls push the tally to 12, crossing threshold 10 and deploying
     * the Killer IC (guarded_node=FILES). The decker is then placed in the FILES node so that
     * Killer attacks rather than moves. The combat roller (thenValue=5) ensures the Killer hits
     * with a Deadly result, confirming White IC icon damage was applied.
     */
    @Test
    fun `Killer IC attacks the decker after crossing threshold 10 in FILES node`() {
        val setupRoller = winThenRoller(zeroCalls = 26, thenValue = 3)

        val icon = scenario(diceRoller = setupRoller) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            navigateToNode(SubsystemType.FILES)
        }

        assertTrue(icon.context.activeIc.any { it is Killer }, "Killer IC should be active after tally crosses threshold 10")

        val deckerBeforeCombat = icon.currentDecker()
        assertEquals(0, deckerBeforeCombat.persona!!.conditionMonitor.damage, "No icon damage before combat")

        val combatRoller = winThenRoller(zeroCalls = 0, thenValue = 5)
        assertTrue(
            icon.runCombatTurn(combatRoller) > 0,
            "Killer IC (White IC) should have dealt icon damage to the decker"
        )
    }
}
