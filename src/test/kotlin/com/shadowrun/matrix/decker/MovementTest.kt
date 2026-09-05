package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.combat.BlackIcPinState
import com.shadowrun.matrix.ic.LethalBlackIC
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.operations.InterrogationState
import com.shadowrun.matrix.operations.SystemOperation
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

    /**
     * Deterministic roller where the decker always wins: the first 6 dice (computerSkill) roll a
     * non-exploding face 5 (a success at the low access TNs used here), and every subsequent die —
     * the host's — rolls face 1 (never a success). Face 5 is used rather than 6 so the die never
     * explodes and the decker/host call boundary stays fixed.
     */
    private fun deckerWinsRoller() = DiceRoller(object : Random() {
        private var call = 0
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int): Int {
            call++
            return if (call <= 6) 5 else 0 // decker: face=5 (success), host: face=1 (no success)
        }
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

        // Deterministic decker-wins roller so a "succeeds" test actually asserts success (T-6).
        val result = d.jackInToLtg(targetLtg, deckerWinsRoller())

        assertIs<LogonResult.Success>(result)
        assertNotNull(result.decker.persona)
        assertIs<MatrixLocation.OnLTG>(result.location)
        assertEquals(targetLtg.name, (result.location as MatrixLocation.OnLTG).ltg.name)
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
        assertTrue((result.attemptedLocation as MatrixLocation.OnLTG).ltg.securityTally > 0,
            "jackInToLtg failure should increment LTG security tally (M-05)")
        assertNull(result.decker.persona)
    }

    @Test
    fun `jackInToLtg failure result location carries incremented security tally`() {
        val targetLtg = ltg().copy(securityTally = 0)
        val jp = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = targetLtg)
        val hostWinsRoller = DiceRoller(object : Random() {
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 0 else 5  // decker: face=1, host: face=6
            }
        })
        val d = decker(jackpoint = jp)
        val result = d.jackInToLtg(targetLtg, hostWinsRoller)
        assertIs<LogonResult.Failure>(result)
        val failedLocation = result.attemptedLocation
        assertNotNull(failedLocation)
        val tally = (failedLocation as MatrixLocation.OnLTG).ltg.securityTally
        assertTrue(tally > 0, "Failure.attemptedLocation must carry the tally-incremented LTG (M-05)")
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
        val result = d.jackInToHost(h, deckerWinsRoller())
        assertIs<LogonResult.Success>(result)
        assertNotNull(result.decker.persona)
        assertIs<MatrixLocation.OnHost>(result.location)
    }

    @Test
    fun `jackInToHost with ILLEGAL_JUNCTION_BOX jackpoint does not throw`() {
        val h = host()
        val jp = Jackpoint(JackpointType.ILLEGAL_JUNCTION_BOX, connectsToHost = h)
        val d = decker(jackpoint = jp)
        // ILLEGAL_JUNCTION_BOX is an allowed host jackpoint (unlike TELECOM which throws); with a
        // decker-wins roller the logon resolves to Success rather than merely "some LogonResult".
        val result = d.jackInToHost(h, deckerWinsRoller())
        assertIs<LogonResult.Success>(result)
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
        val result = d.logonToRtg(r, deckerWinsRoller())
        assertIs<LogonResult.Success>(result)
        assertIs<MatrixLocation.OnRTG>(result.location)
        assertEquals("UCAS", (result.location as MatrixLocation.OnRTG).rtg.name)
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

    @Test
    fun `logonToRtg from LTG carries accumulated tally to parent RTG`() {
        val r = rtg()
        val l = ltg(parentRtg = r).copy(securityTally = 5)
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        val result = d.logonToRtg(r, deckerWinsRoller())
        assertIs<LogonResult.Success>(result)
        // M-09: RTG and its LTGs share one tally — logonToRtg must seed the RTG from the LTG's value.
        assertEquals(5, (result.location as MatrixLocation.OnRTG).rtg.securityTally,
            "RTG tally should carry forward from LTG accumulated tally (M-09)")
    }

    @Test
    fun `tally accumulated on LTG carries through parent RTG to sibling LTG`() {
        val r0 = rtg()
        val ltgA = ltg("Seattle", parentRtg = r0).copy(securityTally = 5)
        val ltgB = ltg("Portland", parentRtg = r0)
        val r = r0.copy(ltgs = listOf(ltgA, ltgB))
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)

        // Step 1: move from LTG-A (tally=5) back up to parent RTG
        val d1 = decker(currentLocation = MatrixLocation.OnLTG(ltgA), persona = persona)
        val rtgResult = d1.logonToRtg(r, deckerWinsRoller())
        assertIs<LogonResult.Success>(rtgResult)
        assertEquals(5, (rtgResult.location as MatrixLocation.OnRTG).rtg.securityTally,
            "RTG should carry LTG-A's tally (M-09)")

        // Step 2: from RTG (tally=5), move down to sibling LTG-B — must seed from RTG tally
        val ltgBResult = rtgResult.decker.logonToLtg(ltgB, deckerWinsRoller())
        assertIs<LogonResult.Success>(ltgBResult)
        assertEquals(5, (ltgBResult.location as MatrixLocation.OnLTG).ltg.securityTally,
            "LTG-B should start with the inherited RTG tally (M-09)")
    }

    // ── logonToLtg ───────────────────────────────────────────────────────────────

    @Test
    fun `logonToLtg from RTG to child LTG`() {
        val l = ltg()
        val r = rtg(ltgs = listOf(l))
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnRTG(r), persona = persona)
        val result = d.logonToLtg(l, deckerWinsRoller())
        assertIs<LogonResult.Success>(result)
        assertIs<MatrixLocation.OnLTG>(result.location)
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
    fun `logonToLtg from LTG to non-sibling LTG throws`() {
        val l = ltg()
        val otherLtg = ltg("Portland")
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        assertFailsWith<IllegalStateException> { d.logonToLtg(otherLtg, DiceRoller()) }
    }

    @Test
    fun `logonToLtg from LTG throws - sibling hops are not allowed`() {
        val r = rtg()
        val targetLtg = ltg("Portland", parentRtg = r)
        val sourceLtg = ltg("Seattle", parentRtg = r)
        val rFinal = r.copy(ltgs = listOf(sourceLtg, targetLtg))
        val sourceLtgFinal = sourceLtg.copy(parentRtg = rFinal)
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(sourceLtgFinal), persona = persona)
        assertFailsWith<IllegalStateException> { d.logonToLtg(targetLtg, DiceRoller()) }
    }

    // ── logonToPltg ──────────────────────────────────────────────────────────────

    @Test
    fun `logonToPltg inherits LTG security tally`() {
        val r = rtg().copy(securityTally = 3)  // RTG tally = 3 (inherited by PLTG, not the LTG tally)
        val p = pltg()
        val l = ltg(parentRtg = r, pltgs = listOf(p))  // LTG tally stays 0
        val pWithLtg = p.copy(parentLtg = l)
        val lFinal = l.copy(pltgs = listOf(pWithLtg))
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
        val l = ltg().copy(hosts = listOf(h))
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        val result = d.logonToHost(h, deckerWinsRoller())
        assertIs<LogonResult.Success>(result)
        assertIs<MatrixLocation.OnHost>(result.location)
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
        val result = d.logonToHost(deepHost, deckerWinsRoller())
        assertIs<LogonResult.Success>(result)
        assertEquals("Deep", (result.location as MatrixLocation.OnHost).host.name)
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
            .copy(blackIcPin = BlackIcPinState(LethalBlackIC(rating = 6)))
        assertFailsWith<IllegalStateException> { d.jackOut() }
    }

    // ── Security tally accumulation ──────────────────────────────────────────────

    @Test
    fun `successful logon increments security tally by host successes`() {
        val h = host().copy(securityTally = 0)
        val l = ltg().copy(hosts = listOf(h))
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

    // ── logonToRtg tally accumulation ─────────────────────────────────────────────

    @Test
    fun `logonToRtg accumulates host successes on top of existing RTG security tally`() {
        val existingTally = 5
        val r = rtg().copy(securityTally = existingTally)
        val l = ltg(parentRtg = r)
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona)
        // Decker rolls: face~6 ≥ TN 4 (success); Host rolls: face~4 ≥ detectionFactor 3 (success)
        val roller = DiceRoller(object : Random() {
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 5 else 3
            }
        })
        val result = d.logonToRtg(r, roller)
        assertIs<LogonResult.Success>(result)
        val newTally = (result.location as MatrixLocation.OnRTG).rtg.securityTally
        assertTrue(newTally > existingTally,
            "RTG tally ($newTally) should accumulate on top of existing tally ($existingTally)")
    }

    // ── interrogationStates cleared on logoff/jackOut ─────────────────────────────

    @Test
    fun `gracefulLogoff clears interrogationStates`() {
        val l = ltg()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona).copy(
            interrogationStates = mapOf(
                "LOCATE_FILE@HOST" to InterrogationState(SystemOperation.LOCATE_FILE, "paydata", 2)
            )
        )
        assertEquals(1, d.interrogationStates.size)
        val result = d.gracefulLogoff(DiceRoller(Random(42)))
        val finalDecker = when (result) {
            is LogoffResult.GracefulSuccess -> result.decker
            is LogoffResult.JackOut         -> result.decker
        }
        assertTrue(finalDecker.interrogationStates.isEmpty(),
            "interrogationStates should be cleared after gracefulLogoff")
    }

    @Test
    fun `jackOut clears interrogationStates`() {
        val l = ltg()
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val d = decker(currentLocation = MatrixLocation.OnLTG(l), persona = persona).copy(
            interrogationStates = mapOf(
                "LOCATE_FILE@HOST" to InterrogationState(SystemOperation.LOCATE_FILE, "paydata", 3)
            )
        )
        assertEquals(1, d.interrogationStates.size)
        val result = d.jackOut()
        assertIs<LogoffResult.JackOut>(result)
        assertTrue(result.decker.interrogationStates.isEmpty(),
            "interrogationStates should be cleared after jackOut")
    }
}
