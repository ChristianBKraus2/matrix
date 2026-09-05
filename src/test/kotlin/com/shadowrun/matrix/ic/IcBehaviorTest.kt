package com.shadowrun.matrix.ic

import com.shadowrun.matrix.combat.AttackResult
import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.common.UtilityCategory
import com.shadowrun.matrix.common.boxes
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.decker.Persona
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class IcBehaviorTest {

    private fun stubRandom(vararg values: Int): Random = object : Random() {
        private val iter = values.iterator()
        override fun nextBits(bitCount: Int): Int = iter.nextInt()
        override fun nextInt(from: Int, until: Int): Int = iter.nextInt()
    }

    private fun allFaces(face: Int, count: Int = 40) =
        DiceRoller(stubRandom(*IntArray(count) { face }))

    private fun programs(rating: Int = 6) = listOf(
        PersonaProgram(PersonaAttributeType.BOD, rating),
        PersonaProgram(PersonaAttributeType.EVASION, rating),
        PersonaProgram(PersonaAttributeType.MASKING, rating),
        PersonaProgram(PersonaAttributeType.SENSORS, rating)
    )

    private fun deck(
        mcpRating: Int = 8,
        activeUtilities: List<Utility> = emptyList()
    ) = Cyberdeck(
        name = "TestDeck",
        mcpRating = mcpRating,
        hardening = 0,
        activeMemoryMp = 2000,
        storageMemoryMp = 5000,
        ioSpeedMpPerTurn = 500,
        responseIncrease = 0,
        costNuyen = 0,
        personaPrograms = programs(mcpRating * 3 / 4),
        activeUtilities = activeUtilities,
        storedUtilities = activeUtilities
    )

    private fun decker(activeUtilities: List<Utility> = emptyList()) = Decker(
        name = "TestDecker",
        intelligence = 6,
        body = 4,
        willpower = 5,
        reaction = 5,
        computerSkill = 6,
        cyberdeck = deck(activeUtilities = activeUtilities),
        physicalConditionMonitor = ConditionMonitor(),
        mentalConditionMonitor = ConditionMonitor(),
        persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6, status = PersonaStatus.INTRUDING)
    )

    private fun host() = Host(
        name = "TestHost",
        securityRating = SecurityRating(SecurityCode.ORANGE, 5),
        subsystemRatings = SubsystemRatings(5, 5, 5, 5, 5),
        intrusionDifficulty = IntrusionDifficulty.AVERAGE,
        topologyType = TopologyType.TIERED
    )

    private fun context(decker: Decker, vararg ic: IC): GameContext {
        val ctx = GameContext(
            host = host(),
            securityCode = SecurityCode.ORANGE,
            deckers = mutableListOf(decker),
            activeIc = ic.toMutableList()
        )
        return ctx
    }

    // ── TarBaby ───────────────────────────────────────────────────────────────────

    @Test
    fun `TarBaby removes itself from context when IC wins contest`() = runBlocking {
        val utility = Utility(UtilityType.BROWSE, rating = 4)
        val d = decker(activeUtilities = listOf(utility))
        val tarBaby = TarBaby(rating = 6, targetCategory = UtilityCategory.OPERATIONAL)
        val ctx = context(d, tarBaby)

        assertTrue(tarBaby in ctx.activeIc)
        // allFaces(5): IC rolls rating=6 dice TN=utility.currentRating=4 → face 5≥4 → 6 successes
        //              utility rolls currentRating=4 dice TN=icRating=6 → face 5<6 → 0 successes → IC wins
        tarBaby.action(ctx, allFaces(5))
        assertFalse(tarBaby in ctx.activeIc, "TarBaby should be removed from context when it wins the contest")
    }

    @Test
    fun `TarBaby stays in context when decker wins contest`() = runBlocking {
        val utility = Utility(UtilityType.BROWSE, rating = 4)
        val d = decker(activeUtilities = listOf(utility))
        val tarBaby = TarBaby(rating = 6, targetCategory = UtilityCategory.OPERATIONAL)
        val ctx = context(d, tarBaby)

        // IC dice: face=1 → 0 successes (1 < TN=4)
        // Utility dice: face=6 then 1 → total=7 per die → 4 successes (7 >= TN=6=icRating) → utility wins
        // Noticed roll: face=1 → 0 successes
        val roller = DiceRoller(stubRandom(
            1, 1, 1, 1, 1, 1,       // 6 IC dice fail
            6, 1, 6, 1, 6, 1, 6, 1, // 4 utility dice roll 6+1=7, each succeeds
            1, 1, 1, 1, 1, 1        // sensor noticed-check dice (all fail)
        ))
        tarBaby.action(ctx, roller)
        assertTrue(tarBaby in ctx.activeIc, "TarBaby should remain in context when decker wins")
    }

    // ── TarPit ────────────────────────────────────────────────────────────────────

    @Test
    fun `TarPit removes itself from context when IC wins contest`() = runBlocking {
        val utility = Utility(UtilityType.BROWSE, rating = 4)
        val d = decker(activeUtilities = listOf(utility))
        val tarPit = TarPit(rating = 6, targetCategory = UtilityCategory.OPERATIONAL)
        val ctx = context(d, tarPit)

        assertTrue(tarPit in ctx.activeIc)
        // IC wins: face=5, icRating=6 vs utility.currentRating=4 TN, utility TN=icRating=6
        tarPit.action(ctx, allFaces(5))
        assertFalse(tarPit in ctx.activeIc, "TarPit should be removed from context when it wins the contest")
    }

    // ── Blaster ───────────────────────────────────────────────────────────────────

    @Test
    fun `Blaster does not reduce MPCP when hit does not trigger dump shock`() {
        val d = decker()
        val blaster = Blaster(rating = 5)
        val originalMcp = d.cyberdeck.mcpRating

        val hit = AttackResult.Hit(
            attackerSuccesses = 1,
            rawDamageLevel = DamageLevel.LIGHT,
            stagedDamageLevel = DamageLevel.LIGHT,
            rawWeaponPower = 5,
            effectivePower = 5
        )
        // allFaces(5) → willpower test (TN=2 for LIGHT): 5>=2 → passes → dumpShockTriggered=false
        val result = CombatResolver.applyIcDamage(d, hit, blaster, allFaces(5))

        assertFalse(result.dumpShockTriggered, "Dump shock should not trigger on LIGHT hit with fresh persona CM")
        assertEquals(originalMcp, result.updatedDecker.cyberdeck.mcpRating, "MPCP must not be reduced when dump shock is not triggered")
    }

    // ── Sparky ────────────────────────────────────────────────────────────────────

    @Test
    fun `Sparky applies persona CM damage on hit without reducing MPCP when decker is not crashed`() {
        val d = decker()
        val sparky = Sparky(rating = 5)
        val originalMcp = d.cyberdeck.mcpRating

        val hit = AttackResult.Hit(
            attackerSuccesses = 1,
            rawDamageLevel = DamageLevel.LIGHT,
            stagedDamageLevel = DamageLevel.LIGHT,
            rawWeaponPower = 5,
            effectivePower = 5
        )
        // allFaces(5) → willpower test (TN=2 for LIGHT): 5>=2 → passes → dumpShockTriggered=false
        val result = CombatResolver.applyIcDamage(d, hit, sparky, allFaces(5))

        assertFalse(result.dumpShockTriggered, "Dump shock should not trigger on LIGHT hit with fresh persona CM")
        val personaCm = requireNotNull(result.updatedDecker.persona).conditionMonitor
        assertEquals(DamageLevel.LIGHT.boxes, personaCm.damage, "Persona CM should have LIGHT damage applied")
        assertEquals(originalMcp, result.updatedDecker.cyberdeck.mcpRating, "MPCP must not be reduced when dump shock is not triggered")
    }

    // ── Crippler ──────────────────────────────────────────────────────────────────

    @Test
    fun `Crippler strictly reduces target attribute when IC net is positive`() = runBlocking {
        val d = decker()
        val crippler = Crippler(rating = 6, targetAttribute = PersonaAttributeType.BOD)
        val ctx = context(d, crippler)
        val originalBod = requireNotNull(d.persona).bod

        // IC: host securityValue=5, 5 dice vs TN=max(2, DF=3)=3. Exploding [6+1=7]: all 5 succeed → 5 successes.
        // Decker defense: persona.bod=6 dice vs TN=max(2, ic.rating=6)=6. face=1: 0 successes.
        // net=5, reduction=5/2=2, newBod=max(1,6-2)=4 < 6.
        val roller = DiceRoller(stubRandom(
            6, 1, 6, 1, 6, 1, 6, 1, 6, 1,  // 5 IC dice: each explodes to 7 (≥ TN=3)
            1, 1, 1, 1, 1, 1                 // 6 decker defense dice: face=1 (< TN=6)
        ))
        crippler.action(ctx, roller)

        val newBod = requireNotNull(ctx.deckers.first().persona).bod
        assertTrue(newBod < originalBod, "Crippler must strictly reduce BOD when IC net > 0 (got $newBod, was $originalBod)")
    }

    // ── Ripper ────────────────────────────────────────────────────────────────────

    @Test
    fun `Ripper strictly reduces target attribute when IC net is positive`() = runBlocking {
        val d = decker()
        val ripper = Ripper(rating = 6, targetAttribute = PersonaAttributeType.EVASION)
        val ctx = context(d, ripper)
        val originalEvasion = requireNotNull(d.persona).evasion

        // IC: host securityValue=5, 5 dice vs TN=max(2, DF=3)=3. Exploding [6+1=7]: all 5 succeed → 5 successes.
        // Decker defense: persona.evasion=6 dice vs TN=max(2, ic.rating=6)=6. face=1: 0 successes.
        // net=5, reduction=5/2=2, newEvasion=max(0,6-2)=4 < 6.
        val roller = DiceRoller(stubRandom(
            6, 1, 6, 1, 6, 1, 6, 1, 6, 1,  // 5 IC dice: each explodes to 7 (≥ TN=3)
            1, 1, 1, 1, 1, 1                 // 6 decker defense dice: face=1 (< TN=6)
        ))
        ripper.action(ctx, roller)

        val newEvasion = requireNotNull(ctx.deckers.first().persona).evasion
        assertTrue(newEvasion < originalEvasion, "Ripper must strictly reduce EVASION when IC net > 0 (got $newEvasion, was $originalEvasion)")
    }
}

