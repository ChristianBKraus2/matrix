package com.shadowrun.matrix.integration

import com.shadowrun.matrix.decker.LoadUtilityResult
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryManagementTest : IntegrationTestBase() {

    private fun storeUtility(icon: ScriptedDeckerIcon, utility: Utility) {
        val d = icon.currentDecker()
        icon.context.updateDecker(d, d.copy(cyberdeck = d.cyberdeck.copy(
            storedUtilities = d.cyberdeck.storedUtilities + utility
        )))
    }

    // ── loadUtility / upload countdown ────────────────────────────────────────

    @Test
    fun `loadUtility places utility in pendingUploads when IO speed is slower than program size`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        // ANALYZE rating 6: mpSize = 6*6*3 = 108 Mp. HIGH_END IO = 300 Mp/turn → fits in 1 turn.
        // Use a smaller deck IO to force multi-turn upload: inject a low-IO deck.
        val d = icon.currentDecker()
        val slowDeck = d.cyberdeck.copy(ioSpeedMpPerTurn = 1)
        icon.context.updateDecker(d, d.copy(cyberdeck = slowDeck))

        val analyze = Utility(UtilityType.ANALYZE, rating = 6)
        storeUtility(icon, analyze)

        val result = icon.currentDecker().loadUtility(analyze)
        assertIs<LoadUtilityResult.Success>(result, "loadUtility should succeed when memory is available")

        val deck = result.decker.cyberdeck
        assertTrue(deck.activeUtilities.none { it.type == UtilityType.ANALYZE },
            "Utility should not be immediately active before upload completes")
        assertTrue(deck.pendingUploads.any { it.utility.type == UtilityType.ANALYZE },
            "Utility should be in pendingUploads while uploading")
        icon.context.updateDecker(icon.currentDecker(), result.decker)
    }

    @Test
    fun `advanceCombatTurn promotes completed upload to active memory`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        // High IO: ANALYZE rating 3, mpSize = 3*3*3 = 27 Mp. IO = 300 → turnsRequired = 1.
        val analyze = Utility(UtilityType.ANALYZE, rating = 3)
        storeUtility(icon, analyze)

        val loadResult = icon.currentDecker().loadUtility(analyze)
        assertIs<LoadUtilityResult.Success>(loadResult)
        icon.context.updateDecker(icon.currentDecker(), loadResult.decker)

        val turnsRequired = icon.currentDecker().cyberdeck.pendingUploads
            .firstOrNull { it.utility.type == UtilityType.ANALYZE }?.turnsRemaining
        assertNotNull(turnsRequired, "Upload should be pending after loadUtility")

        // Advance enough turns to complete the upload
        var d = icon.currentDecker()
        repeat(turnsRequired) { d = d.advanceCombatTurn() }
        icon.context.updateDecker(icon.currentDecker(), d)

        assertTrue(icon.currentDecker().cyberdeck.pendingUploads.none { it.utility.type == UtilityType.ANALYZE },
            "Upload should be complete — no longer pending")
        assertTrue(icon.currentDecker().cyberdeck.activeUtilities.any { it.type == UtilityType.ANALYZE },
            "Utility should now be in active memory after upload completes")
    }

    // ── unloadUtility ─────────────────────────────────────────────────────────

    @Test
    fun `unloadUtility removes utility from active memory`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val armor = Utility(UtilityType.ARMOR, rating = 4)
        icon.equipUtility(armor)
        assertEquals(1, icon.currentDecker().cyberdeck.activeUtilities.size, "Armor should be active before unload")

        val updated = icon.currentDecker().unloadUtility(armor)
        icon.context.updateDecker(icon.currentDecker(), updated)

        assertEquals(0, icon.currentDecker().cyberdeck.activeUtilities.size, "Armor should be unloaded")
    }

    // ── swapUtility ───────────────────────────────────────────────────────────

    @Test
    fun `swapUtility replaces one active utility with another`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val armor = Utility(UtilityType.ARMOR, rating = 4)
        val cloak = Utility(UtilityType.CLOAK, rating = 3)
        icon.equipUtility(armor)
        storeUtility(icon, cloak)
        assertEquals(1, icon.currentDecker().cyberdeck.activeUtilities.size)

        val result = icon.currentDecker().swapUtility(toUnload = armor, toLoad = cloak)
        assertIs<LoadUtilityResult.Success>(result, "swapUtility should succeed")
        icon.context.updateDecker(icon.currentDecker(), result.decker)

        assertTrue(result.decker.cyberdeck.activeUtilities.none { it.type == UtilityType.ARMOR } ||
                   result.decker.cyberdeck.pendingUploads.none { it.utility.type == UtilityType.ARMOR },
            "Armor should be unloaded after swap")
    }

    // ── loadUtility: insufficient memory ─────────────────────────────────────

    @Test
    fun `loadUtility returns InsufficientMemory when active memory is full`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        // Set active memory to 1 Mp so nothing fits
        val d = icon.currentDecker()
        icon.context.updateDecker(d, d.copy(cyberdeck = d.cyberdeck.copy(activeMemoryMp = 1)))

        val analyze = Utility(UtilityType.ANALYZE, rating = 6)
        storeUtility(icon, analyze)

        val result = icon.currentDecker().loadUtility(analyze)

        assertIs<LoadUtilityResult.InsufficientMemory>(result,
            "loadUtility should return InsufficientMemory when deck has no room")
        assertTrue(result.requiredMp > result.availableMp,
            "Required MP should exceed available MP")
    }
}
