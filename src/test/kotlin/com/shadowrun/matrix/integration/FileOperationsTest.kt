package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.operations.InterrogationState
import com.shadowrun.matrix.operations.LocateResult
import com.shadowrun.matrix.operations.OperationResult
import com.shadowrun.matrix.operations.QueryPrecision
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.decker.*
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileOperationsTest : IntegrationTestBase() {

    private fun host(icon: ScriptedDeckerIcon) =
        (icon.currentDecker().currentLocation as MatrixLocation.OnHost).host

    // ── locateFile ────────────────────────────────────────────────────────────

    @Test
    fun `locateFile accumulates successes and locates Personnel Records`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        assertTrue(host.dataFiles.any { it.name.contains("Personnel", ignoreCase = true) },
            "Mitsuhama Pagoda must have a Personnel Records file for this test")

        // BROWSE-4 + SLEAZE-6: DF = ceil((masking=6 + sleaze=6) / 2) = 6.
        // Host: 6 dice vs TN=6, hitRoller face=5 → 0 successes. Decker: 8 dice vs TN=2 → 8 net → ≥ 5 → Located.
        val browse = com.shadowrun.matrix.programs.Utility(com.shadowrun.matrix.programs.UtilityType.BROWSE, rating = 4)
        val sleaze = com.shadowrun.matrix.programs.Utility(com.shadowrun.matrix.programs.UtilityType.SLEAZE, rating = 6)
        icon.equipUtility(browse)
        icon.equipUtility(sleaze)

        val state = InterrogationState(SystemOperation.LOCATE_FILE, "Personnel")
        val decker = icon.currentDecker()
        val result = decker.locateFile(host, "Personnel", QueryPrecision.VERY_SPECIFIC, hitRoller())
        icon.context.updateDecker(decker, result.first.decker)

        assertIs<LocateResult.Located>(result.second, "Should accumulate 5+ successes and locate the file")
    }

    @Test
    fun `locateFile returns Ongoing when decker has fewer than 5 accumulated successes`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        // Give partial successes by using a roller that wins narrowly (face 5 = 1 success per die most of the time)
        val state = InterrogationState(SystemOperation.LOCATE_FILE, "Project", accumulatedSuccesses = 1)
        val seedDecker = icon.currentDecker().copy(interrogationStates = mapOf(
            "LOCATE_FILE@HOST" to InterrogationState(SystemOperation.LOCATE_FILE, "", 1)
        ))
        val result = seedDecker.locateFile(host, "", QueryPrecision.VAGUE, failRoller())

        // failRoller → host wins → decker gets 0 net successes — stays Ongoing or NotFound but not Located
        assertIs<OperationResult.Failure>(result.first, "failRoller should make the decker lose")
        assertTrue(result.second !is LocateResult.Located, "Should not locate file when host wins")
    }

    // ── downloadData ──────────────────────────────────────────────────────────

    @Test
    fun `downloadData succeeds and returns a DownloadHandle`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val file = host.dataFiles.first()

        val (opResult, handle) = icon.currentDecker().downloadData(file, host, winRoller())

        assertIs<OperationResult.Success>(opResult, "downloadData should succeed with winRoller")
        assertNotNull(handle, "Should receive a DownloadHandle on success")
        assertTrue(handle.turnsRemaining >= 1, "Download should require at least one turn")
        assertTrue(handle.active, "Handle should be active immediately after creation")
    }

    @Test
    fun `downloadData fails when host wins the system test`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val file = host.dataFiles.first()

        val (opResult, handle) = icon.currentDecker().downloadData(file, host, failRoller())

        assertIs<OperationResult.Failure>(opResult, "downloadData should fail with failRoller")
        assertTrue(handle == null, "No DownloadHandle should be returned on failure")
    }

    @Test
    fun `downloadData completes and adds file to runDownloadedFiles after required combat turns`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val file = host.dataFiles.first()

        val (opResult, handle) = icon.currentDecker().downloadData(file, host, winRoller())
        assertIs<OperationResult.Success>(opResult, "downloadData should succeed with winRoller")
        assertNotNull(handle, "Should receive a DownloadHandle on success")

        var d = opResult.decker.copy(activeDownloads = listOf(handle))

        repeat(handle.turnsRemaining - 1) {
            d = d.advanceCombatTurn()
            assertTrue(d.activeDownloads.isNotEmpty(), "Download should still be in-progress before final turn")
            assertTrue(d.runDownloadedFiles.none { it.name == file.name },
                "File should not appear in runDownloadedFiles before transfer completes")
        }

        d = d.advanceCombatTurn()
        assertTrue(d.runDownloadedFiles.any { it.name == file.name },
            "File should appear in runDownloadedFiles after all turns complete")
        assertTrue(d.activeDownloads.isEmpty(), "activeDownloads should be empty after completion")
    }

    // ── editFile ──────────────────────────────────────────────────────────────

    @Test
    fun `editFile succeeds with winRoller`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val file = host.dataFiles.first()

        val result = icon.currentDecker().editFile(file, host, newContent = null, diceRoller = winRoller())

        assertTrue(result.outcome.deckerWins, "editFile should succeed with winRoller")
    }

    @Test
    fun `editFile fails when host wins the system test`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val file = host.dataFiles.first()

        val result = icon.currentDecker().editFile(file, host, newContent = null, diceRoller = failRoller())

        assertTrue(!result.outcome.deckerWins, "editFile should fail with failRoller")
    }
}
