package com.shadowrun.matrix.integration

import com.shadowrun.matrix.ic.Scramble
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.operations.OperationResult
import com.shadowrun.matrix.decker.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UploadDataAndScrambleTest : IntegrationTestBase() {

    private fun host(icon: ScriptedDeckerIcon) =
        (icon.currentDecker().currentLocation as MatrixLocation.OnHost).host

    // ── uploadData ────────────────────────────────────────────────────────────

    @Test
    fun `uploadData succeeds with winRoller`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)

        val (result, _) = icon.currentDecker().uploadData(host, dataSizeMp = 100, winRoller())

        assertIs<OperationResult.Success>(result, "uploadData should succeed with winRoller")
    }

    @Test
    fun `uploadData fails when host wins the system test`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)

        val (result, _) = icon.currentDecker().uploadData(host, dataSizeMp = 100, failRoller())

        assertIs<OperationResult.Failure>(result, "uploadData should fail with failRoller")
    }

    @Test
    fun `uploadData failure increments security tally`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val tallyBefore = host.securityTally

        // failRoller: host wins → host successes > 0 → tally increases
        val (result, _) = icon.currentDecker().uploadData(host, dataSizeMp = 100, failRoller())

        val tallyAfter = (result.decker.currentLocation as MatrixLocation.OnHost).host.securityTally
        assertTrue(tallyAfter > tallyBefore, "Failed uploadData should increment the security tally")
    }

    // ── Scramble IC test ──────────────────────────────────────────────────────

    @Test
    fun `resolveScrambleDestructTest scrambles file when IC has more successes`() {
        // STANDARD decker: computerSkill=5 → TN = max(2, 5) = 5
        // hitRoller face=5 ≥ 5 → each die succeeds → IC rolls 6 successes ≥ 1 → fileScrambled = true
        val icon = scenario(deckerTier = DeckerMock.STANDARD) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()
        val scramble = Scramble(rating = 6)
        val file = DataFile(name = "Classified Dossier", isScrambleProtected = true)

        val result = decker.resolveScrambleDestructTest(scramble, file, hitRoller())

        assertTrue(result.fileScrambled, "Scramble IC should scramble the file when it rolls successes")
    }

    @Test
    fun `resolveScrambleDestructTest does not scramble file when IC rolls no successes`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()
        val scramble = Scramble(rating = 6)
        val file = DataFile(name = "Classified Dossier", isScrambleProtected = true)

        // failRoller: face=3, TN = max(2, computerSkill=8) = 8 → all dice fail → successes = 0 → fileScrambled = false
        val result = decker.resolveScrambleDestructTest(scramble, file, failRoller())

        assertFalse(result.fileScrambled, "Scramble IC should not scramble the file when it rolls no successes")
    }

    @Test
    fun `resolveScrambleDestructTest reports correct IC rating`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()
        val scramble = Scramble(rating = 5)
        val file = DataFile(name = "Test File", isScrambleProtected = true)

        val result = decker.resolveScrambleDestructTest(scramble, file, winRoller())

        assertTrue(result.icRating == 5, "ScrambleDestructResult should record IC rating = 5")
    }
}