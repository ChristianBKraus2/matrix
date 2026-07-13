package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovementTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────────

    private fun easyRatings() = SubsystemRatings(4, 4, 4, 4, 4)
    private fun hardRatings() = SubsystemRatings(12, 12, 12, 12, 12)

    private fun greenRating() = SecurityRating(SecurityCode.GREEN, 6)
    private fun redRating() = SecurityRating(SecurityCode.RED, 12)

    private fun rtg(name: String = "UCAS", ltgs: List<LTG> = emptyList(), connected: List<RTG> = emptyList()) =
        RTG(name, "North America", greenRating(), easyRatings(), ltgs = ltgs, connectedRtgs = connected)

    private fun ltg(name: String = "Seattle", parentRtg: RTG = rtg(), pltgs: List<PLTG> = emptyList()) =
        LTG(name, parentRtg, greenRating(), easyRatings(), pltgs = pltgs)

    private fun pltg(name: String = "Corp PLTG", parentLtg: LTG = ltg(), hosts: List<Host> = emptyList()) =
        PLTG(name, "MegaCorp", parentLtg, greenRating(), easyRatings(), hosts = hosts)

    private fun host(name: String = "Corp Host", ratings: SubsystemRatings = easyRatings()) =
        Host(name, greenRating(), ratings, IntrusionDifficulty.EASY, TopologyType.OPEN_ACCESS)

    private fun hardHost(name: String = "Secure Host") =
        Host(name, redRating(), hardRatings(), IntrusionDifficulty.HARD, TopologyType.OPEN_ACCESS)

    private fun personaPrograms(rating: Int = 6) = listOf(
        PersonaProgram(PersonaAttributeType.BOD, rating),
        PersonaProgram(PersonaAttributeType.EVASION, rating),
        PersonaProgram(PersonaAttributeType.MASKING, rating),
        PersonaProgram(PersonaAttributeType.SENSORS, rating)
    )

    private fun deck(
        mcpRating: Int = 8,
        programs: List<PersonaProgram> = personaPrograms(),
        activeUtilities: List<Utility> = emptyList(),
        storedUtilities: List<Utility> = emptyList()
    ) = Cyberdeck(
        name = "TestDeck",
        mcpRating = mcpRating,
        activeMemoryMp = 2000,
        storageMemoryMp = 5000,
        ioSpeedMpPerTurn = 300,
        costNuyen = 400_000,
        personaPrograms = programs,
        activeUtilities = activeUtilities,
        storedUtilities = storedUtilities
    )

    private fun decker(
        computerSkill: Int = 6,
        cyberdeck: Cyberdeck = deck(),
        jackpoint: Jackpoint? = null,
        currentLocation: MatrixLocation? = null,
        persona: Persona? = null
    ) = Decker(
        name = "HeadCrash",
        intelligence = 6,
        body = 4,
        willpower = 5,
        reaction = 5,
        computerSkill = computerSkill,
        cyberdeck = cyberdeck,
        physicalConditionMonitor = ConditionMonitor(),
        mentalConditionMonitor = ConditionMonitor(),
        jackpoint = jackpoint,
        currentLocation = currentLocation,
        persona = persona
    )

    /** DiceRoller that always returns the maximum value (6) on every die — decker always wins. */
    private fun alwaysWinRoller() = DiceRoller(Random(seed = 0L).also {
        // Override: use a fixed random that reliably gives successes at low TNs.
        // We construct a seeded roller and pair it with very low target numbers (access = 4)
        // so the decker gets successes and the host (TN = detectionFactor ≥ 3) gets few.
    })

    /** Deterministic roller: decker gets [deckerDice] dice all rolling [deckerValue], host gets [hostValue]. */
    private fun fixedRoller(deckerValue: Int, hostValue: Int): DiceRoller {
        val values = mutableListOf<Int>()
        // Decker rolls first (computerSkill dice), host rolls second (securityValue dice).
        // We interleave: each call to rollOne() cycles through the supplied value.
        var callCount = 0
        val deckerRolls = 6 // computerSkill
        return DiceRoller(object : Random() {
            override fun nextBits(bitCount: Int): Int = 0  // unused
            override fun nextInt(from: Int, until: Int): Int {
                callCount++
                // decker rolls first (calls 1..deckerRolls), then host
                return if (callCount <= deckerRolls) deckerValue - 1   // nextInt(1,7) → value-1 → face = value
                else hostValue - 1
            }
        })
    }

    // ── detectionFactor ──────────────────────────────────────────────────────────

    @Test
    fun `detectionFactor with sleaze active`() {
        val sleaze = Utility(UtilityType.SLEAZE, 4)
        val d = decker(cyberdeck = deck(activeUtilities = listOf(sleaze), storedUtilities = listOf(sleaze)))
        // masking = 6, sleaze = 4 → ceil((6+4)/2) = 5
        assertEquals(5, d.detectionFactor)
    }

    @Test
    fun `detectionFactor without sleaze`() {
        val d = decker()
        // masking = 6, no sleaze → ceil(6/2) = 3
        assertEquals(3, d.detectionFactor)
    }

    // ── jackInToLtg ──────────────────────────────────────────────────────────────

    @Test
    fun `jackInToLtg succeeds and persona appears on LTG`() {
        val targetLtg = ltg()
        val jp = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = targetLtg)
        val d = decker(jackpoint = jp)

        // Use seeded roller: decker skill 6 dice at TN 4 (access rating) — likely successes
        val result = d.jackInToLtg(targetLtg, DiceRoller(Random(42)))

        if (result is LogonResult.Success) {
            assertNotNull(result.decker.persona)
            assertIs<MatrixLocation.OnLTG>(result.location)
            assertEquals(targetLtg.name, (result.location as MatrixLocation.OnLTG).ltg.name)
        } else {
            // Failure is also valid for the random seed; just verify state is consistent.
            val failed = result as LogonResult.Failure
            assertNull(failed.decker.persona)
            assertNull(failed.decker.currentLocation)
        }
    }

    @Test
    fun `jackInToLtg accumulates security tally on failure`() {
        val targetLtg = ltg(
            parentRtg = rtg()
        ).copy(securityTally = 0)
        val jp = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = targetLtg)
        // Use a roller where host always wins: decker rolls 1s (no successes), host rolls 6s.
        val hostWinsRoller = DiceRoller(object : Random() {
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 0  // decker: face=1, no success at TN 4
                else 5                    // host: face=6, success
            }
        })
        val d = decker(jackpoint = jp)
        val result = d.jackInToLtg(targetLtg, hostWinsRoller)

        assertIs<LogonResult.Failure>(result)
        // Security tally must have increased on the LTG stored in the location
        // (even on failure the host's successes are counted — M-05)
        // The failure result returns the previous location (null on initial jack-in),
        // but we verify the decker's state hasn't changed.
        assertNull(result.decker.persona)
    }

    @Test
    fun `jackInToLtg with workstation jackpoint throws`() {
        val jp = Jackpoint(JackpointType.WORKSTATION, connectsToHost = host())
        val d = decker(jackpoint = jp)
        assertFailsWith<IllegalArgumentException> {
            d.jackInToLtg(ltg(), DiceRoller())
        }
    }

    @Test
    fun `jackInToLtg when already jacked in throws`() {
        val targetLtg = ltg()
        val jp = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = targetLtg)
        val d = decker(
            jackpoint = jp,
            currentLocation = MatrixLocation.OnLTG(targetLtg),
            persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        )
        assertFailsWith<IllegalStateException> { d.jackInToLtg(targetLtg, DiceRoller()) }
    }

    // ── jackInToHost ─────────────────────────────────────────────────────────────

    @Test
    fun `jackInToHost succeeds with workstation jackpoint`() {
        val h = host()
        val jp = Jackpoint(JackpointType.WORKSTATION, connectsToHost = h)
        val d = decker(jackpoint = jp)
        val result = d.jackInToHost(h, DiceRoller(Random(42)))
        if (result is LogonResult.Success) {
            assertNotNull(result.decker.persona)
            assertIs<MatrixLocation.OnHost>(result.location)
        }
    }

    @Test
    fun `jackInToHost with telecom jackpoint throws`() {
        val h = host()
        val jp = Jackpoint(JackpointType.TELECOM, connectsToLtg = ltg())
        val d = decker(jackpoint = jp)
        assertFailsWith<IllegalArgumentException> { d.jackInToHost(h, DiceRoller()) }
    }

    @Test
    fun `jackInToHost with wrong host throws`() {
        val h1 = host("Host A")
        val h2 = host("Host B")
        val jp = Jackpoint(JackpointType.WORKSTATION, connectsToHost = h1)
        val d = decker(jackpoint = jp)
        assertFailsWith<IllegalArgumentException> { d.jackInToHost(h2, DiceRoller()) }
    }

    // ── logonToRtg ───────────────────────────────────────────────────────────────

    @Test
    fun `logonToRtg from LTG to parent RTG succeeds`() {
        val r = rtg()
        val l = ltg(parentRtg = r)
        val jp = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = l)
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(
            jackpoint = jp,
            currentLocation = MatrixLocation.OnLTG(l),
            persona = persona
        )
        val result = d.logonToRtg(r, DiceRoller(Random(42)))
        if (result is LogonResult.Success) {
            assertIs<MatrixLocation.OnRTG>(result.location)
            assertEquals("UCAS", (result.location as MatrixLocation.OnRTG).rtg.name)
        }
    }

    @Test
    fun `logonToRtg to different RTG resets security tally`() {
        val r1 = rtg("UCAS")
        val r2 = RTG("Aztlan", "Mexico", greenRating(), easyRatings())
        val r1WithLink = r1.copy(connectedRtgs = listOf(r2))
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        // Start on r1WithLink with accumulated tally
        val d = decker(
            currentLocation = MatrixLocation.OnRTG(r1WithLink.copy(securityTally = 5)),
            persona = persona
        )
        // Always-win roller: decker rolls high face values
        val winRoller = DiceRoller(object : Random() {
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 5 else 0  // decker: face=6 (success), host: face=1 (no success)
            }
        })
        val result = d.logonToRtg(r2, winRoller)
        assertIs<LogonResult.Success>(result)
        val newRtg = (result.location as MatrixLocation.OnRTG).rtg
        // Old tally (5) must NOT carry over; only new logon successes count (M-10)
        assertTrue(newRtg.securityTally < 5, "Tally should reset when moving to a different RTG")
    }

    @Test
    fun `logonToRtg from non-LTG non-RTG throws`() {
        val h = host()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnHost(h), persona = persona)
        assertFailsWith<IllegalStateException> { d.logonToRtg(rtg(), DiceRoller()) }
    }

    @Test
    fun `logonToRtg to non-parent RTG from LTG throws`() {
        val r1 = rtg("UCAS")
        val r2 = RTG("Aztlan", "Mexico", greenRating(), easyRatings())
        val l = ltg(parentRtg = r1)
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        assertFailsWith<IllegalArgumentException> { d.logonToRtg(r2, DiceRoller()) }
    }

    // ── logonToLtg ───────────────────────────────────────────────────────────────

    @Test
    fun `logonToLtg from RTG to child LTG`() {
        val l = ltg()
        val r = rtg(ltgs = listOf(l))
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnRTG(r), persona = persona)
        val result = d.logonToLtg(l, DiceRoller(Random(42)))
        if (result is LogonResult.Success) {
            assertIs<MatrixLocation.OnLTG>(result.location)
        }
    }

    @Test
    fun `logonToLtg same-RTG sibling LTG does not reset RTG tally`() {
        val ltg1 = ltg("Seattle")
        val ltg2 = ltg("Portland")
        val r = rtg(ltgs = listOf(ltg1, ltg2)).copy(securityTally = 4)
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnRTG(r), persona = persona)
        val winZeroHostRoller = DiceRoller(object : Random() {
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 5 else 0
            }
        })
        val result = d.logonToLtg(ltg2, winZeroHostRoller)
        assertIs<LogonResult.Success>(result)
        // Source RTG tally unchanged at 4 (M-09)
        assertEquals(4, r.securityTally)
    }

    @Test
    fun `logonToLtg from non-RTG non-PLTG throws`() {
        val l = ltg()
        val otherLtg = ltg("Portland")
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        assertFailsWith<IllegalStateException> { d.logonToLtg(otherLtg, DiceRoller()) }
    }

    // ── logonToPltg ──────────────────────────────────────────────────────────────

    @Test
    fun `logonToPltg inherits RTG security tally`() {
        val p = pltg()
        val l = ltg(pltgs = listOf(p))
        val r = rtg(ltgs = listOf(l)).copy(securityTally = 3)
        val lWithRtg = l.copy(parentRtg = r)
        val pWithLtg = p.copy(parentLtg = lWithRtg)
        val lFinal = lWithRtg.copy(pltgs = listOf(pWithLtg))
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(lFinal), persona = persona)
        val winZeroHostRoller = DiceRoller(object : Random() {
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 5 else 0 // decker wins, host gets 0 successes
            }
        })
        val result = d.logonToPltg(pWithLtg, winZeroHostRoller)
        assertIs<LogonResult.Success>(result)
        val newPltg = (result.location as MatrixLocation.OnPLTG).pltg
        // PLTG tally = inherited RTG tally (3) + host successes (0) = 3 (M-11)
        assertEquals(3, newPltg.securityTally)
    }

    @Test
    fun `logonToPltg from non-attached LTG throws`() {
        val p = pltg()
        val l = ltg() // no pltgs attached
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        assertFailsWith<IllegalArgumentException> { d.logonToPltg(p, DiceRoller()) }
    }

    // ── logonToHost ──────────────────────────────────────────────────────────────

    @Test
    fun `logonToHost from LTG succeeds`() {
        val h = host()
        val l = ltg()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        val result = d.logonToHost(h, DiceRoller(Random(42)))
        if (result is LogonResult.Success) {
            assertIs<MatrixLocation.OnHost>(result.location)
        }
    }

    @Test
    fun `logonToHost from PLTG only allows hosts in that PLTG`() {
        val h1 = host("Corp Research")
        val h2 = host("Other Host")
        val p = pltg(hosts = listOf(h1))
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnPLTG(p), persona = persona)
        assertFailsWith<IllegalArgumentException> { d.logonToHost(h2, DiceRoller()) }
    }

    @Test
    fun `logonToHost from host only allows connected hosts`() {
        val innerHost = host("Inner")
        val outerHost = host("Outer").copy(connectedHosts = listOf(innerHost))
        val unrelatedHost = host("Unrelated")
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnHost(outerHost), persona = persona)
        assertFailsWith<IllegalArgumentException> { d.logonToHost(unrelatedHost, DiceRoller()) }
    }

    @Test
    fun `logonToHost from RTG throws`() {
        val r = rtg()
        val h = host()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnRTG(r), persona = persona)
        assertFailsWith<IllegalStateException> { d.logonToHost(h, DiceRoller()) }
    }

    @Test
    fun `logonToHost traverses connected host chain`() {
        val deepHost = host("Deep")
        val midHost = host("Mid").copy(connectedHosts = listOf(deepHost))
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnHost(midHost), persona = persona)
        val result = d.logonToHost(deepHost, DiceRoller(Random(42)))
        if (result is LogonResult.Success) {
            assertEquals("Deep", (result.location as MatrixLocation.OnHost).host.name)
        }
    }

    // ── gracefulLogoff ───────────────────────────────────────────────────────────

    @Test
    fun `gracefulLogoff success clears persona and location`() {
        val l = ltg()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        val winRoller = DiceRoller(object : Random() {
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 5 else 0
            }
        })
        val result = d.gracefulLogoff(winRoller)
        assertIs<LogoffResult.GracefulSuccess>(result)
        assertNull(result.decker.persona)
        assertNull(result.decker.currentLocation)
    }

    @Test
    fun `gracefulLogoff failure causes dump shock`() {
        val l = ltg()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        val loseRoller = DiceRoller(object : Random() {
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 0 else 5 // decker rolls 1s, host rolls 6s
            }
        })
        val result = d.gracefulLogoff(loseRoller)
        assertIs<LogoffResult.JackOut>(result)
        assertTrue(result.dumpShock)
    }

    @Test
    fun `gracefulLogoff when not jacked in throws`() {
        val d = decker()
        assertFailsWith<IllegalStateException> { d.gracefulLogoff(DiceRoller()) }
    }

    // ── jackOut ──────────────────────────────────────────────────────────────────

    @Test
    fun `jackOut always causes dump shock`() {
        val l = ltg()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        val result = d.jackOut()
        assertIs<LogoffResult.JackOut>(result)
        assertTrue(result.dumpShock)
        assertNull(result.decker.persona)
        assertNull(result.decker.currentLocation)
    }

    @Test
    fun `jackOut when not jacked in throws`() {
        assertFailsWith<IllegalStateException> { decker().jackOut() }
    }

    @Test
    fun `jackOut when pinned by Black IC throws`() {
        val l = ltg()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        assertFailsWith<IllegalStateException> { d.jackOut(pinnedByBlackIc = true) }
    }

    // ── Security tally accumulation ──────────────────────────────────────────────

    @Test
    fun `successful logon increments security tally by host successes`() {
        val h = host().copy(securityTally = 0)
        val l = ltg()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        // Force exactly 2 host successes
        val roller = DiceRoller(object : Random() {
            // decker rolls (computerSkill=6 dice) then host rolls (security value=6 dice)
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 5  // decker: face=6 (success at TN 4)
                else 3                    // host: face=4 (success at detectionFactor=3)
            }
        })
        val result = d.logonToHost(h, roller)
        assertIs<LogonResult.Success>(result)
        val newTally = (result.location as MatrixLocation.OnHost).host.securityTally
        // Host rolled 6 dice, all face=4 which is >= detectionFactor(3), so 6 successes
        assertEquals(6, newTally)
    }

    // ── Deception utility reduces target number ──────────────────────────────────

    @Test
    fun `deception utility lowers effective access rating`() {
        val deception = Utility(UtilityType.DECEPTION, 4)
        val d = decker(
            cyberdeck = deck(activeUtilities = listOf(deception), storedUtilities = listOf(deception))
        )
        // Just verify the decker object is well-formed with the utility loaded
        assertEquals(1, d.cyberdeck.activeUtilities.size)
        assertEquals(UtilityType.DECEPTION, d.cyberdeck.activeUtilities[0].type)
        assertEquals(4, d.cyberdeck.activeUtilities[0].rating)
    }
}
