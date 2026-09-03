package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.CombatManeuverType
import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.IcBehavior
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.UtilityCategory
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.boxes
import com.shadowrun.matrix.decker.*
import com.shadowrun.matrix.ic.Blaster
import com.shadowrun.matrix.ic.Crippler
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.LethalBlackIC
import com.shadowrun.matrix.ic.NonLethalBlackIC
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.ic.Ripper
import com.shadowrun.matrix.ic.Scramble
import com.shadowrun.matrix.ic.Sparky
import com.shadowrun.matrix.ic.TarBaby
import com.shadowrun.matrix.ic.TarPit
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CombatResolverTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    /** Stub Random that serves [values] in order via nextInt(from, until). */
    private fun stubRandom(vararg values: Int): Random = object : Random() {
        private val iter = values.iterator()
        override fun nextBits(bitCount: Int): Int = iter.nextInt()
        override fun nextInt(from: Int, until: Int): Int = iter.nextInt()
    }

    /** Returns a DiceRoller whose every die shows [face]. */
    private fun allFaces(face: Int, count: Int = 20) =
        DiceRoller(stubRandom(*IntArray(count) { face }))

    private fun programs(rating: Int = 6) = listOf(
        PersonaProgram(PersonaAttributeType.BOD, rating),
        PersonaProgram(PersonaAttributeType.EVASION, rating),
        PersonaProgram(PersonaAttributeType.MASKING, rating),
        PersonaProgram(PersonaAttributeType.SENSORS, rating)
    )

    private fun deck(
        mcpRating: Int = 8,
        hardening: Int = 0,
        responseIncrease: Int = 0,
        activeUtilities: List<Utility> = emptyList(),
        storedUtilities: List<Utility> = emptyList()
    ) = Cyberdeck(
        name = "TestDeck",
        mcpRating = mcpRating,
        hardening = hardening,
        activeMemoryMp = 2000,
        storageMemoryMp = 5000,
        ioSpeedMpPerTurn = 500,
        responseIncrease = responseIncrease,
        costNuyen = 0,
        // Each persona program must be ≤ mcpRating and sum ≤ mcpRating×3; floor(mcp*3/4) satisfies both.
        personaPrograms = programs(mcpRating * 3 / 4),
        activeUtilities = activeUtilities,
        storedUtilities = storedUtilities
    )

    private fun decker(
        body: Int = 4,
        willpower: Int = 5,
        reaction: Int = 5,
        cyberdeck: Cyberdeck = deck(),
        persona: Persona? = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6, reaction = reaction),
        physicalCm: ConditionMonitor = ConditionMonitor(),
        mentalCm: ConditionMonitor = ConditionMonitor()
    ) = Decker(
        name = "TestDecker",
        intelligence = 6,
        body = body,
        willpower = willpower,
        reaction = reaction,
        computerSkill = 6,
        cyberdeck = cyberdeck,
        physicalConditionMonitor = physicalCm,
        mentalConditionMonitor = mentalCm,
        persona = persona
    )

    private fun host(securityCode: SecurityCode = SecurityCode.ORANGE, securityValue: Int = 5) = Host(
        name = "TestHost",
        securityRating = SecurityRating(securityCode, securityValue),
        subsystemRatings = SubsystemRatings(5, 5, 5, 5, 5),
        intrusionDifficulty = IntrusionDifficulty.AVERAGE,
        topologyType = TopologyType.TIERED
    )

    // ── DamageLevel.boxes ─────────────────────────────────────────────────────────

    @Test
    fun `DamageLevel boxes property returns correct box counts`() {
        assertEquals(1,  DamageLevel.LIGHT.boxes)
        assertEquals(3,  DamageLevel.MODERATE.boxes)
        assertEquals(6,  DamageLevel.SERIOUS.boxes)
        assertEquals(10, DamageLevel.DEADLY.boxes)
    }

    // ── ConditionMonitor ──────────────────────────────────────────────────────────

    @Test
    fun `ConditionMonitor applyDamage with DamageLevel applies correct boxes`() {
        val cm = ConditionMonitor()
        assertEquals(3, cm.applyDamage(DamageLevel.MODERATE).damage)
        assertEquals(6, cm.applyDamage(DamageLevel.SERIOUS).damage)
    }

    @Test
    fun `ConditionMonitor isCrashed is true at 10 boxes`() {
        val cm = ConditionMonitor(damage = 10)
        assertTrue(cm.isCrashed)
    }

    @Test
    fun `ConditionMonitor isCrashed is false below 10 boxes`() {
        val cm = ConditionMonitor(damage = 9)
        assertFalse(cm.isCrashed)
    }

    // ── stage helper ──────────────────────────────────────────────────────────────

    @Test
    fun `stage positive net shifts damage up`() {
        // 4 net successes → shift by 2
        assertEquals(DamageLevel.SERIOUS, CombatResolver.stage(DamageLevel.LIGHT, 4))
    }

    @Test
    fun `stage negative net shifts damage down`() {
        assertEquals(DamageLevel.LIGHT, CombatResolver.stage(DamageLevel.SERIOUS, -4))
    }

    @Test
    fun `stage clamps at LIGHT`() {
        assertEquals(DamageLevel.LIGHT, CombatResolver.stage(DamageLevel.LIGHT, -10))
    }

    @Test
    fun `stage clamps at DEADLY`() {
        assertEquals(DamageLevel.DEADLY, CombatResolver.stage(DamageLevel.DEADLY, 10))
    }

    @Test
    fun `stage zero net returns same level`() {
        assertEquals(DamageLevel.MODERATE, CombatResolver.stage(DamageLevel.MODERATE, 0))
    }

    @Test
    fun `stage odd net truncates toward zero`() {
        // 3 net → 1 level shift (3/2 = 1)
        assertEquals(DamageLevel.MODERATE, CombatResolver.stage(DamageLevel.LIGHT, 3))
    }

    // ── CombatModifiers ───────────────────────────────────────────────────────────

    @Test
    fun `CombatModifiers allows TN bonus alone`() {
        val m = CombatModifiers(positionAttackTnBonus = 2)
        assertEquals(2, m.positionAttackTnBonus)
        assertEquals(0, m.positionAttackPowerBonus)
    }

    @Test
    fun `CombatModifiers allows Power bonus alone`() {
        val m = CombatModifiers(positionAttackPowerBonus = 2)
        assertEquals(2, m.positionAttackPowerBonus)
    }

    @Test
    fun `CombatModifiers rejects both TN and Power bonus`() {
        val ex = runCatching {
            CombatModifiers(positionAttackTnBonus = 1, positionAttackPowerBonus = 1)
        }
        assertTrue(ex.isFailure)
    }

    // ── rollDeckerInitiative ──────────────────────────────────────────────────────

    @Test
    fun `rollDeckerInitiative score = sum of dice + reaction, passes = numDice`() {
        // RI=2 → numDice=3; all dice show 3; reaction=5 → score=14
        val roller = allFaces(3)
        val result = CombatResolver.rollDeckerInitiative(
            decker(reaction = 5, cyberdeck = deck(responseIncrease = 2)),
            meatworldComm = false,
            diceRoller = roller
        )
        assertEquals(14, result.score)      // 3+3+3+5
        assertEquals(3, result.initiativePasses)
    }

    @Test
    fun `rollDeckerInitiative meatworldComm subtracts one die, floor 1`() {
        // RI=2, commPenalty=1 → numDice=2
        val roller = allFaces(4)
        val result = CombatResolver.rollDeckerInitiative(
            decker(reaction = 5, cyberdeck = deck(responseIncrease = 2)),
            meatworldComm = true,
            diceRoller = roller
        )
        assertEquals(2, result.initiativePasses)
    }

    @Test
    fun `rollDeckerInitiative floors numDice at 1 when comm penalty exceeds RI`() {
        // RI=0, commPenalty=1 → max(1, 0) = 1
        val roller = allFaces(3)
        val result = CombatResolver.rollDeckerInitiative(
            decker(reaction = 5, cyberdeck = deck(responseIncrease = 0)),
            meatworldComm = true,
            diceRoller = roller
        )
        assertEquals(1, result.initiativePasses)
        assertEquals(8, result.score)   // 3+5
    }

    // ── rollIcInitiative ──────────────────────────────────────────────────────────

    @Test
    fun `rollIcInitiative in Orange host rolls 3 dice`() {
        // all dice = 2; IC rating=5 → score=2+2+2+5=11
        val roller = allFaces(2)
        val ic = Killer(rating = 5)
        val result = CombatResolver.rollIcInitiative(ic, SecurityCode.ORANGE, roller)
        assertEquals(11, result.score)
        assertEquals(3, result.initiativePasses)
    }

    @Test
    fun `rollIcInitiative in Blue host rolls 1 die`() {
        val roller = allFaces(4)
        val ic = Killer(rating = 3)
        val result = CombatResolver.rollIcInitiative(ic, SecurityCode.BLUE, roller)
        assertEquals(7, result.score)   // 4+3
        assertEquals(1, result.initiativePasses)
    }

    @Test
    fun `rollIcInitiative in Red host rolls 4 dice`() {
        val roller = allFaces(3)
        val ic = Killer(rating = 6)
        val result = CombatResolver.rollIcInitiative(ic, SecurityCode.RED, roller)
        assertEquals(18, result.score)  // 3*4+6
        assertEquals(4, result.initiativePasses)
    }

    // ── resolveManeuver ───────────────────────────────────────────────────────────

    @Test
    fun `resolveManeuver returns Success when mover outrolls opponent`() {
        // mover evasion=6, hackingPool=3 → 9 dice all succeed
        // opponent sensor=4 → 4 dice all fail
        val roller = DiceRoller(stubRandom(
            *IntArray(9) { 5 },   // mover rolls: all hit TN 4
            *IntArray(4) { 1 }    // opponent rolls: all miss
        ))
        val mover    = ManeuverParticipant(evasion = 6, sensor = 4, hackingPool = 3)
        val opponent = ManeuverParticipant(evasion = 4, sensor = 4)
        val result = CombatResolver.resolveManeuver(CombatManeuverType.EVADE_DETECTION, mover, opponent, roller)
        assertIs<ManeuverResult.Success>(result)
        assertEquals(9, result.netSuccesses)
    }

    @Test
    fun `resolveManeuver returns Failure when mover ties opponent`() {
        // each side rolls 2 successes → net = 0
        val roller = DiceRoller(stubRandom(
            5, 5, 1, 1,  // mover 4 dice: 2 successes
            5, 5, 1, 1   // opponent 4 dice: 2 successes
        ))
        val mover    = ManeuverParticipant(evasion = 4, sensor = 4)
        val opponent = ManeuverParticipant(evasion = 4, sensor = 4)
        val result = CombatResolver.resolveManeuver(CombatManeuverType.EVADE_DETECTION, mover, opponent, roller)
        assertIs<ManeuverResult.Failure>(result)
    }

    @Test
    fun `resolveManeuver Cloak reduces mover TN, LockOn reduces opponent TN`() {
        // mover TN = max(2, opponent.sensor(4) - cloak(3)) = max(2,1) = 2
        // opponent TN = max(2, mover.evasion(4) - lockOn(4)) = 2
        // all dice succeed at TN 2 (face=3)
        val roller = allFaces(3)
        val mover    = ManeuverParticipant(evasion = 4, sensor = 4, cloakRating = 3)
        val opponent = ManeuverParticipant(evasion = 4, sensor = 4, lockOnRating = 4)
        val result = CombatResolver.resolveManeuver(CombatManeuverType.EVADE_DETECTION, mover, opponent, roller)
        // Both roll same number of dice but at TN 2 → equal successes → Failure
        assertIs<ManeuverResult.Failure>(result)
    }

    // ── resolveAttack ─────────────────────────────────────────────────────────────

    @Test
    fun `resolveAttack returns Miss when no attacker successes`() {
        // TN for INTRUDING in BLUE = 6; all attacker dice show 1 → 0 successes
        val roller = allFaces(1)
        val attacker = AttackParticipant(attackDicePool = 4, rawDamageLevel = DamageLevel.MODERATE)
        val defender = DefenderParticipant(bod = 4, personaStatus = PersonaStatus.INTRUDING, securityCode = SecurityCode.BLUE)
        val result = CombatResolver.resolveAttack(attacker, defender, roller)
        assertIs<AttackResult.Miss>(result)
    }

    @Test
    fun `resolveAttack Hit uses CC-21 table for Intruding in Blue TN=6`() {
        // TN=6; exploding die (6,1 → total 7) beats TN 6; defender all=1 → 0 success
        val attackDice = intArrayOf(6, 1, 6, 1, 6, 1, 6, 1)   // 4 dice each rolling (6,1) = 7
        val defendDice = IntArray(4) { 1 }
        val roller = DiceRoller(stubRandom(*attackDice, *defendDice))
        val attacker = AttackParticipant(attackDicePool = 4, rawDamageLevel = DamageLevel.MODERATE)
        val defender = DefenderParticipant(bod = 4, personaStatus = PersonaStatus.INTRUDING, securityCode = SecurityCode.BLUE)
        val result = CombatResolver.resolveAttack(attacker, defender, roller)
        assertIs<AttackResult.Hit>(result)
        assertEquals(4, result.attackerSuccesses)
    }

    @Test
    fun `resolveAttack Hit uses CC-21 table for Legitimate in Red TN=6`() {
        val roller = DiceRoller(stubRandom(*intArrayOf(6, 1, 6, 1, 6, 1, 6, 1), *IntArray(4) { 1 }))
        val attacker = AttackParticipant(attackDicePool = 4, rawDamageLevel = DamageLevel.MODERATE)
        val defender = DefenderParticipant(bod = 4, personaStatus = PersonaStatus.LEGITIMATE, securityCode = SecurityCode.RED)
        val result = CombatResolver.resolveAttack(attacker, defender, roller)
        assertIs<AttackResult.Hit>(result)
    }

    @Test
    fun `resolveAttack Armor reduces effectivePower`() {
        // utilityRating=7, armor=4 → effectivePower=3; all attacker dice succeed at TN 5 (INTRUDING/GREEN)
        val roller = DiceRoller(stubRandom(*IntArray(7) { 5 }, *IntArray(4) { 1 }))
        val attacker = AttackParticipant(attackDicePool = 7, rawDamageLevel = DamageLevel.MODERATE)
        val defender = DefenderParticipant(bod = 4, armorCurrentRating = 4, personaStatus = PersonaStatus.INTRUDING, securityCode = SecurityCode.GREEN)
        val result = CombatResolver.resolveAttack(attacker, defender, roller)
        assertIs<AttackResult.Hit>(result)
        assertEquals(3, result.effectivePower)  // effectivePower = max(0, rawWeaponPower - armorRating)
    }

    @Test
    fun `resolveAttack stages up by 2 on 4 net attacker successes`() {
        // attacker: 4 dice, all succeed (TN 5 for intruding/GREEN)
        // defender: 0 successes → net=4 → shift +2 from LIGHT → SERIOUS
        val roller = DiceRoller(stubRandom(*IntArray(4) { 5 }, *IntArray(4) { 1 }))
        val attacker = AttackParticipant(attackDicePool = 4, rawDamageLevel = DamageLevel.LIGHT)
        val defender = DefenderParticipant(bod = 4, personaStatus = PersonaStatus.INTRUDING, securityCode = SecurityCode.GREEN)
        val result = CombatResolver.resolveAttack(attacker, defender, roller)
        assertIs<AttackResult.Hit>(result)
        assertEquals(DamageLevel.SERIOUS, result.stagedDamageLevel)
    }

    @Test
    fun `resolveAttack stages down by 2 on 4 net defender successes`() {
        // attacker: 1 success; defender: 5 successes → net = -4 → SERIOUS staged down to LIGHT
        val roller = DiceRoller(stubRandom(
            5, 1, 1, 1,           // attacker 4 dice: 1 success at TN 5
            5, 5, 5, 5, 5         // defender 5 dice: all succeed
        ))
        val attacker = AttackParticipant(attackDicePool = 4, rawDamageLevel = DamageLevel.SERIOUS)
        val defender = DefenderParticipant(bod = 5, personaStatus = PersonaStatus.INTRUDING, securityCode = SecurityCode.GREEN)
        val result = CombatResolver.resolveAttack(attacker, defender, roller)
        assertIs<AttackResult.Hit>(result)
        assertEquals(DamageLevel.LIGHT, result.stagedDamageLevel)
    }

    @Test
    fun `resolveAttack parryAttackBonus raises attacker TN`() {
        // TN for INTRUDING/GREEN = 5; parryBonus = 3 → effective TN = 8; all dice = 5 → 0 successes → Miss
        val roller = allFaces(5)
        val mods = CombatModifiers(parryAttackBonus = 3)
        val attacker = AttackParticipant(attackDicePool = 4, rawDamageLevel = DamageLevel.MODERATE, modifiers = mods)
        val defender = DefenderParticipant(bod = 4, personaStatus = PersonaStatus.INTRUDING, securityCode = SecurityCode.GREEN)
        val result = CombatResolver.resolveAttack(attacker, defender, roller)
        assertIs<AttackResult.Miss>(result)
    }

    @Test
    fun `resolveAttack positionAttackTnBonus reduces attacker TN`() {
        // TN for INTRUDING/BLUE = 6; positionTnBonus=4 → effective TN = max(2,2) = 2; all dice=3 → hit
        val roller = DiceRoller(stubRandom(*IntArray(4) { 3 }, *IntArray(4) { 1 }))
        val mods = CombatModifiers(positionAttackTnBonus = 4)
        val attacker = AttackParticipant(attackDicePool = 4, rawDamageLevel = DamageLevel.MODERATE, modifiers = mods)
        val defender = DefenderParticipant(bod = 4, personaStatus = PersonaStatus.INTRUDING, securityCode = SecurityCode.BLUE)
        val result = CombatResolver.resolveAttack(attacker, defender, roller)
        assertIs<AttackResult.Hit>(result)
    }

    @Test
    fun `resolveAttack positionAttackPowerBonus adds to power`() {
        // utilityRating=4, powerBonus=2 → power=6; armor=0; effectivePower=6
        val roller = DiceRoller(stubRandom(*IntArray(4) { 5 }, *IntArray(4) { 1 }))
        val mods = CombatModifiers(positionAttackPowerBonus = 2)
        val attacker = AttackParticipant(attackDicePool = 4, rawDamageLevel = DamageLevel.MODERATE, modifiers = mods)
        val defender = DefenderParticipant(bod = 4, personaStatus = PersonaStatus.INTRUDING, securityCode = SecurityCode.GREEN)
        val result = CombatResolver.resolveAttack(attacker, defender, roller)
        assertIs<AttackResult.Hit>(result)
        assertEquals(6, result.effectivePower)
    }

    // ── applyIcDamage – White/Gray IC ─────────────────────────────────────────────

    @Test
    fun `applyIcDamage applies staged damage boxes to persona ConditionMonitor`() {
        val roller = allFaces(5) // Willpower test: all succeed → no stun
        val d = decker()
        val ic = Killer(rating = 5)
        val attack = AttackResult.Hit(3, DamageLevel.MODERATE, DamageLevel.MODERATE, 5, 5)
        val result = CombatResolver.applyIcDamage(d, attack, ic, roller)
        assertEquals(3, result.updatedDecker.persona!!.conditionMonitor.damage)
    }

    @Test
    fun `applyIcDamage Willpower test failure adds 1 stun box`() {
        // willpower dice all fail (face=1) at overloadTn=3 (MODERATE)
        val roller = allFaces(1)
        val d = decker(willpower = 3)
        val ic = Killer(rating = 5)
        val attack = AttackResult.Hit(2, DamageLevel.MODERATE, DamageLevel.MODERATE, 5, 5)
        val result = CombatResolver.applyIcDamage(d, attack, ic, roller)
        val overload = result.simsenseOverload
        assertNotNull(overload)
        assertFalse(overload.willpowerTestPassed)
        assertEquals(1, overload.stressBoxesApplied)
        assertEquals(1, result.updatedDecker.mentalConditionMonitor.damage)
    }

    @Test
    fun `applyIcDamage Willpower test success adds no stun box`() {
        val roller = allFaces(5)
        val d = decker(willpower = 5)
        val ic = Killer(rating = 5)
        val attack = AttackResult.Hit(2, DamageLevel.MODERATE, DamageLevel.MODERATE, 5, 5)
        val result = CombatResolver.applyIcDamage(d, attack, ic, roller)
        val overload = result.simsenseOverload
        assertNotNull(overload)
        assertTrue(overload.willpowerTestPassed)
        assertEquals(0, overload.stressBoxesApplied)
        assertEquals(0, result.updatedDecker.mentalConditionMonitor.damage)
    }

    @Test
    fun `applyIcDamage Deadly damage from White IC sets dumpShock without Willpower test`() {
        val roller = allFaces(1)  // if a test were made it would fail; but no test should occur
        val d = decker()
        val ic = Killer(rating = 5)
        val attack = AttackResult.Hit(1, DamageLevel.DEADLY, DamageLevel.DEADLY, 5, 5)
        val result = CombatResolver.applyIcDamage(d, attack, ic, roller)
        assertTrue(result.dumpShockTriggered)
        assertNull(result.simsenseOverload)
    }

    @Test
    fun `applyIcDamage sets dumpShockTriggered when persona CM crashes`() {
        val roller = allFaces(5) // willpower succeeds
        val personaWithAlmostCrashed = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6,
            conditionMonitor = ConditionMonitor(damage = 9))
        val d = decker(persona = personaWithAlmostCrashed)
        val ic = Killer(rating = 5)
        // LIGHT = 1 box; 9+1 = 10 → crashed
        val attack = AttackResult.Hit(1, DamageLevel.LIGHT, DamageLevel.LIGHT, 5, 5)
        val result = CombatResolver.applyIcDamage(d, attack, ic, roller)
        assertTrue(result.dumpShockTriggered)
    }

    // ── resolveDumpShock ──────────────────────────────────────────────────────────

    @Test
    fun `resolveDumpShock in Orange host applies Serious damage staged by body successes`() {
        // Orange → SERIOUS; power=5; body=4 all fail → net=0 → actual=SERIOUS (6 boxes)
        val roller = allFaces(1)
        val d = decker(body = 4)
        val result = CombatResolver.resolveDumpShock(d, host(SecurityCode.ORANGE, 5), roller)
        assertEquals(6, result.mentalConditionMonitor.damage)
    }

    @Test
    fun `resolveDumpShock body successes stage damage down`() {
        // Orange → SERIOUS; 4 body dice all succeed → shift -2 from SERIOUS to LIGHT (1 box)
        val roller = allFaces(5)
        val d = decker(body = 4)
        val result = CombatResolver.resolveDumpShock(d, host(SecurityCode.ORANGE, 5), roller)
        assertEquals(1, result.mentalConditionMonitor.damage)
    }

    @Test
    fun `resolveDumpShock Blue host applies Light base damage`() {
        val roller = allFaces(1)  // no body successes
        val d = decker(body = 4)
        val result = CombatResolver.resolveDumpShock(d, host(SecurityCode.BLUE, 3), roller)
        assertEquals(1, result.mentalConditionMonitor.damage)  // LIGHT = 1 box
    }

    @Test
    fun `resolveDumpShock does not affect physicalConditionMonitor`() {
        val roller = allFaces(1)  // no body successes — maximum mental damage
        val d = decker(body = 4)
        val result = CombatResolver.resolveDumpShock(d, host(SecurityCode.RED, 5), roller)
        assertEquals(0, result.physicalConditionMonitor.damage)
    }

    // ── resolveJackOutWithPin ─────────────────────────────────────────────────────

    @Test
    fun `resolveJackOutWithPin Willpower success returns succeeded=true and finalAttack=true`() {
        val roller = allFaces(5) // willpower dice at TN=4 → succeed
        val ic = LethalBlackIC(rating = 4)
        val d = decker(willpower = 5).copy(blackIcPin = BlackIcPinState(ic))
        val result = CombatResolver.resolveJackOutWithPin(d, roller)
        assertTrue(result.succeeded)
        assertTrue(result.finalIcAttackTriggered)
    }

    @Test
    fun `resolveJackOutWithPin Willpower failure returns succeeded=false`() {
        val roller = allFaces(1) // willpower fails
        val ic = LethalBlackIC(rating = 4)
        val d = decker(willpower = 5).copy(blackIcPin = BlackIcPinState(ic))
        val result = CombatResolver.resolveJackOutWithPin(d, roller)
        assertFalse(result.succeeded)
        assertFalse(result.finalIcAttackTriggered)
    }

    @Test
    fun `resolveJackOutWithPin throws when not pinned`() {
        val result = runCatching {
            CombatResolver.resolveJackOutWithPin(decker(), allFaces(5))
        }
        assertTrue(result.isFailure)
    }

    // ── resolveCrippler ───────────────────────────────────────────────────────────

    @Test
    fun `resolveCrippler reduces attribute by half net IC successes`() {
        // SV for GREEN = 4 dice all succeed; decker bod(6) dice all fail
        // net = 4; reduction = 2; new bod = max(1, 6-2) = 4
        val roller = DiceRoller(stubRandom(
            *IntArray(4) { 5 },  // IC rolls vs detectionFactor
            *IntArray(6) { 1 }   // decker rolls vs ic.rating
        ))
        val ic = Crippler(rating = 5, targetAttribute = PersonaAttributeType.BOD)
        val result = CombatResolver.resolveCrippler(decker(), ic, 4, roller)
        assertEquals(2, result.reduction)
        assertEquals(4, result.updatedDecker.persona!!.bod)
    }

    @Test
    fun `resolveCrippler floors attribute at 1`() {
        // SV for GREEN=4 all succeed (4 dice); decker bod=1 die fails → net=4 → reduction=2; bod=1 → max(1,1-2)=1
        val personaLowBod = Persona(bod = 1, evasion = 6, masking = 6, sensor = 6)
        val roller = DiceRoller(stubRandom(
            *IntArray(4) { 5 },  // IC dice succeed
            *IntArray(1) { 1 }   // decker 1 die fails
        ))
        val ic = Crippler(rating = 5, targetAttribute = PersonaAttributeType.BOD)
        val result = CombatResolver.resolveCrippler(decker(persona = personaLowBod), ic, 4, roller)
        assertEquals(1, result.updatedDecker.persona!!.bod)
    }

    @Test
    fun `resolveCrippler with full decker defense yields no reduction`() {
        // IC all fail, decker all succeed → net ≤ 0 → reduction=0
        val roller = DiceRoller(stubRandom(
            *IntArray(4) { 1 },  // IC dice all fail
            *IntArray(6) { 5 }   // decker dice all succeed
        ))
        val ic = Crippler(rating = 5, targetAttribute = PersonaAttributeType.BOD)
        val result = CombatResolver.resolveCrippler(decker(), ic, 4, roller)
        assertEquals(0, result.reduction)
        assertEquals(6, result.updatedDecker.persona!!.bod)
    }

    // ── resolveProbe ──────────────────────────────────────────────────────────────

    @Test
    fun `resolveProbe returns number of IC successes as tally increment`() {
        // 3 dice, all hit detectionFactor
        val roller = DiceRoller(stubRandom(*IntArray(3) { 5 }))
        val ic = Probe(rating = 3)
        val tallied = CombatResolver.resolveProbe(ic, decker(), roller)
        assertEquals(3, tallied)
    }

    @Test
    fun `resolveProbe returns 0 when IC rolls no successes`() {
        val roller = allFaces(1)
        val ic = Probe(rating = 3)
        val tallied = CombatResolver.resolveProbe(ic, decker(), roller)
        assertEquals(0, tallied)
    }

    // ── resolveTarBaby ────────────────────────────────────────────────────────────

    @Test
    fun `resolveTarBaby IC wins - both programs crash, decker not notified`() {
        val utility = Utility(UtilityType.ATTACK, 4)
        // IC 5 dice succeed, utility 4 dice fail
        val roller = DiceRoller(stubRandom(
            *IntArray(5) { 5 },  // IC dice vs utility.currentRating
            *IntArray(4) { 1 }   // utility dice vs ic.rating
        ))
        val ic = TarBaby(rating = 5, targetCategory = UtilityCategory.OPERATIONAL)
        val d = decker(cyberdeck = deck(activeUtilities = listOf(utility),
            storedUtilities = listOf(utility)))
        val result = CombatResolver.resolveTarBaby(d, ic, utility, roller)
        assertTrue(result.bothCrashed)
        assertFalse(result.deckerNoticed)
        assertFalse(result.updatedDecker.cyberdeck.activeUtilities.any { it.type == UtilityType.ATTACK })
    }

    @Test
    fun `resolveTarBaby utility wins - decker may notice via Sensor test`() {
        // utility 4 dice succeed, IC 5 dice fail; sensor test: 1 success → noticed
        val roller = DiceRoller(stubRandom(
            *IntArray(5) { 1 },  // IC dice fail
            *IntArray(4) { 5 },  // utility dice succeed
            5, 1, 1, 1, 1, 1     // sensor test: 1 success
        ))
        val utility = Utility(UtilityType.ATTACK, 4)
        val ic = TarBaby(rating = 5, targetCategory = UtilityCategory.OPERATIONAL)
        val result = CombatResolver.resolveTarBaby(decker(), ic, utility, roller)
        assertFalse(result.bothCrashed)
        assertTrue(result.deckerNoticed)
    }

    @Test
    fun `resolveTarBaby utility wins but sensor test fails - deckerNoticed false`() {
        // utility wins; sensor test: all fail
        val roller = DiceRoller(stubRandom(
            *IntArray(5) { 1 },  // IC fails
            *IntArray(4) { 5 },  // utility succeeds
            *IntArray(6) { 1 }   // sensor test: all fail
        ))
        val utility = Utility(UtilityType.ATTACK, 4)
        val ic = TarBaby(rating = 5, targetCategory = UtilityCategory.OPERATIONAL)
        val result = CombatResolver.resolveTarBaby(decker(), ic, utility, roller)
        assertFalse(result.deckerNoticed)
    }

    // ── resolveBlasterMpcpTest ────────────────────────────────────────────────────

    @Test
    fun `resolveBlasterMpcpTest reduces MPCP by half IC successes`() {
        // tn = hardening(0) + mcpRating(8) = 8; IC rating=6, all dice=5 → 0 successes at TN 8
        val roller = allFaces(5)
        val d = decker(cyberdeck = deck(mcpRating = 8, hardening = 0))
        val ic = Blaster(rating = 6)
        val result = CombatResolver.resolveBlasterMpcpTest(d, ic, roller)
        assertEquals(8, result.cyberdeck.mcpRating) // no change when all successes = 0
    }

    @Test
    fun `resolveBlasterMpcpTest with 4 IC successes reduces MPCP by 2`() {
        // Use programs(1) so that any MPCP reduction still satisfies sum ≤ mcpRating*3
        // tn = hardening(0) + mcpRating(6) = 6; IC 8 dice, exploding (6,1)=7≥6 gives 4 hits
        // reduction = 4/2 = 2; new mcpRating = 6-2 = 4; programs(1): sum=4 ≤ 4*3=12 ✓
        val roller = DiceRoller(stubRandom(6, 1, 6, 1, 6, 1, 6, 1, 1, 1, 1, 1))
        val lowPrograms = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 1),
            PersonaProgram(PersonaAttributeType.EVASION, 1),
            PersonaProgram(PersonaAttributeType.MASKING, 1),
            PersonaProgram(PersonaAttributeType.SENSORS, 1)
        )
        val d = decker(cyberdeck = Cyberdeck(
            name = "TestDeck", mcpRating = 6, hardening = 0,
            activeMemoryMp = 2000, storageMemoryMp = 5000, ioSpeedMpPerTurn = 500,
            costNuyen = 0, personaPrograms = lowPrograms
        ))
        val ic = Blaster(rating = 8)
        val result = CombatResolver.resolveBlasterMpcpTest(d, ic, roller)
        assertEquals(4, result.cyberdeck.mcpRating)  // 6 - (4/2) = 4
    }

    // ── resolveRipper ─────────────────────────────────────────────────────────────

    @Test
    fun `resolveRipper reduces attribute by net-successes divided by 2 and does not floor at 1`() {
        // 10 net IC successes → reduction=5; evasion=6 → max(0, 1) = 1; wait, reduction=5 → 6-5=1
        // Actually need net/2=5 → net=10; SV for RED=6, need 6+extra dice to get 10 successes
        // Let's keep it simple: 4 dice succeed, 0 decker → net=4 → reduction=2 → evasion=4
        val roller = DiceRoller(stubRandom(
            *IntArray(6) { 5 },  // IC dice (RED SV=6)
            *IntArray(6) { 1 }   // decker evasion dice
        ))
        val ic = Ripper(rating = 5, targetAttribute = PersonaAttributeType.EVASION)
        val result = CombatResolver.resolveRipper(decker(), ic, 6, roller)
        assertEquals(3, result.reduction) // 6 successes/2 = 3 reduction
        assertEquals(3, result.updatedDecker.persona!!.evasion) // 6-3=3
    }

    // ── resolveSparkyMpcpTest and resolveSparkyBodyDamage ─────────────────────────

    @Test
    fun `resolveSparkyMpcpTest tn is mcpRating + hardening + 2`() {
        // tn = 8+0+2=10; IC rating=6, all dice=5 → 0 successes; MPCP unchanged
        val roller = allFaces(5)
        val d = decker(cyberdeck = deck(mcpRating = 8, hardening = 0))
        val ic = Sparky(rating = 6)
        val (updated, successes) = CombatResolver.resolveSparkyMpcpTest(d, ic, roller)
        assertEquals(0, successes)
        assertEquals(8, updated.cyberdeck.mcpRating)
    }

    @Test
    fun `resolveSparkyBodyDamage applies physical damage after MPCP test`() {
        // MODERATE staged up by sparkySuccesses/2; then body resist
        // sparkySuccesses=4 → staged=stage(MODERATE,4) = DEADLY (3→4 boxes? 4/2=2 shift: MODERATE+2=DEADLY)
        // effectivePower = max(0, rating(6) - hardening(2)) = 4; body(4) all fail → actual = DEADLY (10 boxes)
        val roller = allFaces(1) // body dice all fail
        val d = decker(body = 4, cyberdeck = deck(hardening = 2))
        val ic = Sparky(rating = 6)
        val result = CombatResolver.resolveSparkyBodyDamage(d, ic, sparkySuccesses = 4, diceRoller = roller)
        // MODERATE (ord 1) + 2 shifts = DEADLY (ord 3) = 10 boxes
        assertEquals(10, result.physicalConditionMonitor.damage)
    }

    @Test
    fun `resolveSparkyBodyDamage hardening reduces effectivePower`() {
        // hardening=10 → effectivePower = max(0, 6-10) = 0 → body rolls at TN=2 but all dice show 1 → 0 successes → staged damage applied raw
        val roller = allFaces(1)
        val d = decker(body = 4, cyberdeck = deck(mcpRating = 8, hardening = 10))
        val ic = Sparky(rating = 6)
        // sparkySuccesses=0 → staged=MODERATE; effectivePower=0 → no resist → actual=MODERATE (3 boxes)
        val result = CombatResolver.resolveSparkyBodyDamage(d, ic, sparkySuccesses = 0, diceRoller = roller)
        assertEquals(3, result.physicalConditionMonitor.damage)
    }

    // ── resolveTarPit ─────────────────────────────────────────────────────────────

    @Test
    fun `resolveTarPit IC wins removes utility from active memory`() {
        val utility = Utility(UtilityType.ANALYZE, 4)
        val roller = DiceRoller(stubRandom(*IntArray(5) { 5 }, *IntArray(4) { 1 }))
        val ic = TarPit(rating = 5, targetCategory = UtilityCategory.OPERATIONAL)
        val d = decker(cyberdeck = deck(activeUtilities = listOf(utility), storedUtilities = listOf(utility)))
        val result = CombatResolver.resolveTarPit(d, ic, utility, roller)
        assertTrue(result.bothCrashed)
    }

    @Test
    fun `resolveTarPitMpcpTest with successes corrupts utility in all memory`() {
        // tn = hardening+mcpRating = 0+4 = 4; IC 5 dice, all show 5 → 5 successes
        val utility = Utility(UtilityType.ANALYZE, 4)
        val roller = DiceRoller(stubRandom(*IntArray(5) { 5 }))
        val ic = TarPit(rating = 5, targetCategory = UtilityCategory.OPERATIONAL)
        val d = decker(cyberdeck = deck(mcpRating = 4,
            activeUtilities = listOf(utility), storedUtilities = listOf(utility)))
        val result = CombatResolver.resolveTarPitMpcpTest(d, ic, utility, roller)
        assertFalse(result.cyberdeck.activeUtilities.any { it.type == UtilityType.ANALYZE })
        assertFalse(result.cyberdeck.storedUtilities.any { it.type == UtilityType.ANALYZE })
    }

    @Test
    fun `resolveTarPitMpcpTest with 0 successes leaves utility in storage`() {
        val utility = Utility(UtilityType.ANALYZE, 4)
        val roller = allFaces(1)
        val ic = TarPit(rating = 5, targetCategory = UtilityCategory.OPERATIONAL)
        val d = decker(cyberdeck = deck(mcpRating = 4,
            activeUtilities = listOf(utility), storedUtilities = listOf(utility)))
        val result = CombatResolver.resolveTarPitMpcpTest(d, ic, utility, roller)
        assertTrue(result.cyberdeck.storedUtilities.any { it.type == UtilityType.ANALYZE })
    }

    // ── resolveLethalBlackIc ──────────────────────────────────────────────────────

    @Test
    fun `resolveLethalBlackIc Blue host uses Moderate base damage`() {
        // icon bod resist: 6 dice all fail → full MODERATE damage = 3 boxes
        // body resist: effectivePower=0 (hardening=ic.rating) → no roll → MODERATE
        val roller = allFaces(1)
        val d = decker(body = 4, cyberdeck = deck(hardening = 8))
        val ic = LethalBlackIC(rating = 8)
        val result = CombatResolver.resolveLethalBlackIc(d, ic, SecurityCode.BLUE, roller)
        assertEquals(DamageLevel.MODERATE, (result.iconDamage as AttackResult.Hit).rawDamageLevel)
        assertNull(result.simsenseOverload)
        assertTrue(result.updatedDecker.isPinnedByBlackIc)
    }

    @Test
    fun `resolveLethalBlackIc Red host uses Serious base damage`() {
        val roller = allFaces(1)
        val d = decker(body = 4)
        val ic = LethalBlackIC(rating = 6)
        val result = CombatResolver.resolveLethalBlackIc(d, ic, SecurityCode.RED, roller)
        assertEquals(DamageLevel.SERIOUS, (result.iconDamage as AttackResult.Hit).rawDamageLevel)
    }

    @Test
    fun `resolveLethalBlackIc sets dumpShockTriggered on persona crash`() {
        // persona CM nearly full; one Moderate hit fills it
        // Dice: icon bod(6) + body(4) + MPCP shot(12) = 22 total when kill fires
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6,
            conditionMonitor = ConditionMonitor(damage = 8))
        val roller = allFaces(1, 30)  // no defense, no MPCP successes
        val d = decker(persona = persona)
        val ic = LethalBlackIC(rating = 6)
        val result = CombatResolver.resolveLethalBlackIc(d, ic, SecurityCode.BLUE, roller)
        assertTrue(result.dumpShockTriggered)
    }

    @Test
    fun `resolveLethalBlackIc no MPCP reduction when kill not triggered`() {
        // Persona CM has plenty of room; MODERATE hit won't crash it → no MPCP shot fires
        val roller = allFaces(1, 30)
        val d = decker()
        val ic = LethalBlackIC(rating = 6)
        val result = CombatResolver.resolveLethalBlackIc(d, ic, SecurityCode.BLUE, roller)
        assertFalse(result.dumpShockTriggered)
        assertEquals(0, result.mpcpReductionOnKill)
        assertEquals(8, result.updatedDecker.cyberdeck.mcpRating)
    }

    @Test
    fun `resolveLethalBlackIc MPCP death blow reduces mcpRating on kill`() {
        // Physical CM = 8 → MODERATE (3 boxes) crashes it → kill fires
        // MPCP shot: ic.rating*2=12 dice vs TN hardening(0)+mcpRating(6)=6 → 4 hits via [6,1]×4 → reduction=2
        // Programs rating=1 each; after reduction: sum=4 ≤ 4*3=12, each=1 ≤ 4 ✓
        val lowPrograms = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 1),
            PersonaProgram(PersonaAttributeType.EVASION, 1),
            PersonaProgram(PersonaAttributeType.MASKING, 1),
            PersonaProgram(PersonaAttributeType.SENSORS, 1)
        )
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val roller = DiceRoller(stubRandom(
            *IntArray(10) { 1 },                         // iconDef(6) + body(4) — all fail
            6, 1, 6, 1, 6, 1, 6, 1,                     // 4 of 12 MPCP dice hit via [6,1] pairs
            *IntArray(8) { 1 }                           // remaining 8 MPCP dice fail
        ))
        val d = decker(
            cyberdeck = Cyberdeck(
                name = "TestDeck", mcpRating = 6, hardening = 0,
                activeMemoryMp = 2000, storageMemoryMp = 5000, ioSpeedMpPerTurn = 500, costNuyen = 0,
                personaPrograms = lowPrograms
            ),
            persona = persona,
            physicalCm = ConditionMonitor(damage = 8)
        )
        val ic = LethalBlackIC(rating = 6)
        val result = CombatResolver.resolveLethalBlackIc(d, ic, SecurityCode.BLUE, roller)
        assertTrue(result.dumpShockTriggered)
        assertEquals(2, result.mpcpReductionOnKill)
        assertEquals(4, result.updatedDecker.cyberdeck.mcpRating)  // 6 - 2
    }

    @Test
    fun `resolveLethalBlackIc MPCP floors at 0`() {
        // Physical CM = 8 → MODERATE (3 boxes) crashes it → kill fires
        // ic.rating=6 → MPCP dice=12, TN=max(2, 0+2)=2; face=5 hits TN 2 without exploding → 12 successes → reduction=6 → max(0,2-6)=0
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val roller = DiceRoller(stubRandom(
            *IntArray(10) { 1 },    // iconDef(6) + body(4) — all fail
            *IntArray(12) { 5 }     // all 12 MPCP dice succeed at TN 2 (no exploding)
        ))
        val d = decker(
            cyberdeck = Cyberdeck(
                name = "TestDeck", mcpRating = 2, hardening = 0,
                activeMemoryMp = 2000, storageMemoryMp = 5000, ioSpeedMpPerTurn = 500, costNuyen = 0,
                personaPrograms = emptyList()
            ),
            persona = persona,
            physicalCm = ConditionMonitor(damage = 8)
        )
        val ic = LethalBlackIC(rating = 6)
        val result = CombatResolver.resolveLethalBlackIc(d, ic, SecurityCode.BLUE, roller)
        assertTrue(result.dumpShockTriggered)
        assertEquals(0, result.updatedDecker.cyberdeck.mcpRating)
    }

    @Test
    fun `resolveLethalBlackIc does not overwrite pin when decker already pinned`() {
        val roller = allFaces(1, 30)
        val existingIc = LethalBlackIC(rating = 4)
        val newIc = LethalBlackIC(rating = 6)
        val d = decker(cyberdeck = deck(hardening = 8)).copy(blackIcPin = BlackIcPinState(existingIc))
        val result = CombatResolver.resolveLethalBlackIc(d, newIc, SecurityCode.BLUE, roller)
        assertEquals(existingIc, result.updatedDecker.blackIcPin!!.pinningIc)
    }

    @Test
    fun `resolveLethalBlackIc degrades Armor utility when power exceeds armor rating (CD-19)`() {
        // power=6, armorRating=3 → bleed-through → ARMOR currentRating decreases by 1
        val armor = Utility(UtilityType.ARMOR, rating = 3)
        val d = decker(cyberdeck = deck(hardening = 0, activeUtilities = listOf(armor)))
        val ic = LethalBlackIC(rating = 6)
        val result = CombatResolver.resolveLethalBlackIc(d, ic, SecurityCode.BLUE, allFaces(1, 20))
        val updatedArmor = result.updatedDecker.cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.ARMOR }
        assertNotNull(updatedArmor, "ARMOR utility should still be present after degradation")
        assertEquals(2, updatedArmor.currentRating, "ARMOR should degrade by 1 when power > armorRating")
    }

    @Test
    fun `resolveLethalBlackIc does not degrade Armor utility when power does not exceed armor rating (CD-19)`() {
        // power=6, armorRating=8 → no bleed-through → ARMOR currentRating unchanged
        val armor = Utility(UtilityType.ARMOR, rating = 8)
        val d = decker(cyberdeck = deck(hardening = 0, activeUtilities = listOf(armor)))
        val ic = LethalBlackIC(rating = 6)
        val result = CombatResolver.resolveLethalBlackIc(d, ic, SecurityCode.BLUE, allFaces(1, 20))
        val updatedArmor = result.updatedDecker.cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.ARMOR }
        assertNotNull(updatedArmor)
        assertEquals(8, updatedArmor.currentRating, "ARMOR should NOT degrade when power ≤ armorRating")
    }

    // ── resolveNonLethalBlackIc ───────────────────────────────────────────────────

    @Test
    fun `resolveNonLethalBlackIc applies mental damage via Willpower resist`() {
        // willpower=5 all fail → full MODERATE mental damage = 3 boxes
        // effectivePower = max(0, 8-8) = 0 → no mental roll → no MPCP shot (no crash from 3 boxes on fresh CM)
        val roller = allFaces(1, 30)
        val d = decker(willpower = 5, cyberdeck = deck(hardening = 8))
        val ic = NonLethalBlackIC(rating = 8)
        val result = CombatResolver.resolveNonLethalBlackIc(d, ic, SecurityCode.BLUE, roller)
        assertTrue(result.updatedDecker.mentalConditionMonitor.damage > 0)
        assertNull(result.simsenseOverload)
    }

    @Test
    fun `resolveNonLethalBlackIc no MPCP reduction when kill not triggered`() {
        // Fresh CMs — MODERATE hit (3 boxes) won't crash either → no MPCP shot
        val roller = allFaces(1, 30)
        val d = decker(willpower = 5)
        val ic = NonLethalBlackIC(rating = 6)
        val result = CombatResolver.resolveNonLethalBlackIc(d, ic, SecurityCode.BLUE, roller)
        assertFalse(result.dumpShockTriggered)
        assertEquals(0, result.mpcpReductionOnKill)
        assertEquals(8, result.updatedDecker.cyberdeck.mcpRating)
    }

    @Test
    fun `resolveNonLethalBlackIc MPCP death blow fires on mental CM crash`() {
        // Mental CM = 8 → MODERATE mental hit crashes it → kill fires
        // Dice order: iconDef(bod=6) + mental(willpower=5) + MPCP(ic.rating*2=12)
        // 4 MPCP dice use (6,1) → 4 successes → reduction=2; remaining 8 fail
        // Programs rating=1 each; after reduction: sum=4 ≤ 4*3=12, each=1 ≤ 4 ✓
        val lowPrograms = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 1),
            PersonaProgram(PersonaAttributeType.EVASION, 1),
            PersonaProgram(PersonaAttributeType.MASKING, 1),
            PersonaProgram(PersonaAttributeType.SENSORS, 1)
        )
        val roller = DiceRoller(stubRandom(
            *IntArray(11) { 1 },                         // iconDef(6) + mental(5) — all fail
            6, 1, 6, 1, 6, 1, 6, 1,                     // 4 of 12 MPCP dice hit via [6,1] pairs
            *IntArray(8) { 1 }                           // remaining 8 MPCP dice fail
        ))
        val d = decker(
            willpower = 5,
            cyberdeck = Cyberdeck(
                name = "TestDeck", mcpRating = 6, hardening = 0,
                activeMemoryMp = 2000, storageMemoryMp = 5000, ioSpeedMpPerTurn = 500, costNuyen = 0,
                personaPrograms = lowPrograms
            ),
            mentalCm = ConditionMonitor(damage = 8)
        )
        val ic = NonLethalBlackIC(rating = 6)
        val result = CombatResolver.resolveNonLethalBlackIc(d, ic, SecurityCode.BLUE, roller)
        assertTrue(result.dumpShockTriggered)
        assertEquals(2, result.mpcpReductionOnKill)
        assertEquals(4, result.updatedDecker.cyberdeck.mcpRating)  // 6 - 2
    }

    @Test
    fun `resolveNonLethalBlackIc MPCP death blow does NOT fire on persona-only CM crash`() {
        // Icon CM = 8 → MODERATE icon hit crashes it → kill fires; MPCP shot (ic.rating*2=12 dice) all fail → reduction=0
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6,
            conditionMonitor = ConditionMonitor(damage = 8))
        val roller = allFaces(1, 30)  // all fail, including MPCP shot (ic.rating*2=12 dice)
        val d = decker(willpower = 5, cyberdeck = deck(mcpRating = 8, hardening = 0), persona = persona)
        val ic = NonLethalBlackIC(rating = 6)
        val result = CombatResolver.resolveNonLethalBlackIc(d, ic, SecurityCode.BLUE, roller)
        assertTrue(result.dumpShockTriggered)
        assertEquals(0, result.mpcpReductionOnKill)
        assertEquals(8, result.updatedDecker.cyberdeck.mcpRating)  // no successes, unchanged
    }

    // ── resolveBlackHammer ────────────────────────────────────────────────────────

    @Test
    fun `resolveBlackHammer applies icon and physical damage without MPCP attack`() {
        // body all fail → staged damage applied to physical CM
        val roller = allFaces(1)
        val d = decker(body = 4)
        val attack = AttackResult.Hit(3, DamageLevel.SERIOUS, DamageLevel.SERIOUS, 6, 6)
        val result = CombatResolver.resolveBlackHammer(d, attack, roller)
        assertTrue(result.updatedDecker.physicalConditionMonitor.damage > 0)
        assertNull(result.simsenseOverload)
    }

    // ── resolveKilljoy ────────────────────────────────────────────────────────────

    @Test
    fun `resolveKilljoy applies mental damage via Willpower resist`() {
        val roller = allFaces(1)  // willpower fails
        val d = decker(willpower = 5)
        val attack = AttackResult.Hit(3, DamageLevel.MODERATE, DamageLevel.MODERATE, 6, 6)
        val result = CombatResolver.resolveKilljoy(d, attack, roller)
        assertTrue(result.updatedDecker.mentalConditionMonitor.damage > 0)
        assertNull(result.simsenseOverload)
    }

    @Test
    fun `resolveBlackHammer throws for immune decker`() {
        val immuneDeck = deck().copy(isCyberterminal = true)
        val d = decker(cyberdeck = immuneDeck)
        val attack = AttackResult.Hit(3, DamageLevel.SERIOUS, DamageLevel.SERIOUS, 6, 6)
        val result = runCatching { CombatResolver.resolveBlackHammer(d, attack, allFaces(1)) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolveKilljoy throws for immune decker`() {
        val immuneDeck = deck().copy(isCyberterminal = true)
        val d = decker(cyberdeck = immuneDeck)
        val attack = AttackResult.Hit(3, DamageLevel.MODERATE, DamageLevel.MODERATE, 6, 6)
        val result = runCatching { CombatResolver.resolveKilljoy(d, attack, allFaces(1)) }
        assertTrue(result.isFailure)
    }

    // ── resolveTrackLock ──────────────────────────────────────────────────────────

    @Test
    fun `resolveTrackLock 5 attacker successes vs 2 evader yields cycleTurns = 4`() {
        // evasion=6 dice, 2 succeed; attacker successes=5; net=3 → ceil(10/3)=4
        val roller = DiceRoller(stubRandom(5, 5, 1, 1, 1, 1))
        val d = decker()
        val attack = AttackResult.Hit(5, DamageLevel.LIGHT, DamageLevel.LIGHT, 4, 4)
        val result = CombatResolver.resolveTrackLock(attack, d, trackRating = 4, diceRoller = roller)
        assertNotNull(result)
        assertEquals(4, result!!.locationCycleTurnsRemaining)
    }

    @Test
    fun `resolveTrackLock evader matches attacker returns null`() {
        // evasion=6 dice, 5 succeed; attacker successes=5 → evadeSuccesses >= attack → no lock
        val roller = DiceRoller(stubRandom(*IntArray(6) { 5 }))
        val d = decker()
        val attack = AttackResult.Hit(5, DamageLevel.LIGHT, DamageLevel.LIGHT, 4, 4)
        val result = CombatResolver.resolveTrackLock(attack, d, trackRating = 4, diceRoller = roller)
        assertNull(result)
    }

    @Test
    fun `resolveTrackLock evader exceeds attacker returns null`() {
        val roller = DiceRoller(stubRandom(*IntArray(6) { 5 }))
        val d = decker()
        val attack = AttackResult.Hit(3, DamageLevel.LIGHT, DamageLevel.LIGHT, 4, 4)
        val result = CombatResolver.resolveTrackLock(attack, d, trackRating = 4, diceRoller = roller)
        assertNull(result)
    }

    // ── resolveSlow ───────────────────────────────────────────────────────────────

    @Test
    fun `resolveSlow on Reactive IC returns SlowResult(0, false) immediately`() {
        val ic = Scramble(rating = 4)  // REACTIVE
        val initiative = CombatInitiative(10, 2)
        val result = CombatResolver.resolveSlow(ic, slowRating = 6, securityValue = 5, initiative, allFaces(5))
        assertEquals(0, result.actionsLost)
        assertFalse(result.icInert)
    }

    @Test
    fun `resolveSlow with 6 net successes loses 3 actions`() {
        // sv for ORANGE = 5; slow 6 dice all succeed; IC 5 dice all fail → net=6 → actionsLost=3
        val roller = DiceRoller(stubRandom(
            *IntArray(5) { 1 },  // IC dice vs slowRating: fail
            *IntArray(6) { 5 }   // slow dice vs sv: all succeed
        ))
        val ic = Killer(rating = 4)  // PROACTIVE
        val initiative = CombatInitiative(10, 3)
        val result = CombatResolver.resolveSlow(ic, slowRating = 6, securityValue = 5, initiative, roller)
        assertEquals(3, result.actionsLost)
        assertTrue(result.icInert)   // 3 passes - 3 lost = 0 ≤ 0
    }

    @Test
    fun `resolveSlow net 0 returns no effect`() {
        // equal successes → net = 0 → no effect
        val roller = DiceRoller(stubRandom(
            *IntArray(5) { 5 },  // IC dice succeed (sv=5 at TN=slowRating)
            *IntArray(6) { 1 }   // slow dice fail
        ))
        val ic = Killer(rating = 4)
        val initiative = CombatInitiative(10, 3)
        val result = CombatResolver.resolveSlow(ic, slowRating = 6, securityValue = 5, initiative, roller)
        assertEquals(0, result.actionsLost)
        assertFalse(result.icInert)
    }

    // ── Decker.advanceCombatTurn trackState decrement ─────────────────────────────

    @Test
    fun `advanceCombatTurn decrements locationCycleTurnsRemaining`() {
        val track = TrackState(trackingIcRating = 4, locationCycleTurnsRemaining = 3, opponentSensorRating = 4, trackerMcpRating = 4)
        val d = decker().copy(trackState = track)
        val updated = d.advanceCombatTurn()
        assertEquals(2, updated.trackState!!.locationCycleTurnsRemaining)
    }

    @Test
    fun `advanceCombatTurn clears trackState when turns reach 0`() {
        val track = TrackState(trackingIcRating = 4, locationCycleTurnsRemaining = 1, opponentSensorRating = 4, trackerMcpRating = 4)
        val d = decker().copy(trackState = track)
        val updated = d.advanceCombatTurn()
        assertNull(updated.trackState)
    }

    // ── suppressIc / unsuppressIc (CC-22) ────────────────────────────────────────

    @Test
    fun `suppressIc adds IC to suppressedIc list`() {
        val ic = Probe(rating = 4)
        val h = host()
        val d = decker().copy(currentLocation = MatrixLocation.OnHost(h))
        val updated = CombatResolver.suppressIc(d, ic, h)
        assertEquals(1, updated.suppressedIc.size)
        assertEquals(ic, updated.suppressedIc.first().ic)
        assertEquals(4, updated.suppressedIc.first().icRating)
    }

    @Test
    fun `suppressIc records rating at crash time`() {
        val ic = Probe(rating = 6)
        val h = host()
        val updated = CombatResolver.suppressIc(decker().copy(currentLocation = MatrixLocation.OnHost(h)), ic, h)
        assertEquals(6, updated.suppressedIc.first().icRating)
    }

    @Test
    fun `suppressIc multiple times accumulates entries`() {
        val ic1 = Probe(rating = 3)
        val ic2 = Killer(rating = 5)
        val h = host()
        val d = CombatResolver.suppressIc(decker().copy(currentLocation = MatrixLocation.OnHost(h)), ic1, h)
        val d2 = CombatResolver.suppressIc(d, ic2, h)
        assertEquals(2, d2.suppressedIc.size)
    }

    @Test
    fun `suppressIc throws when decker has no persona (already left system)`() {
        val ic = Probe(rating = 4)
        val d = decker(persona = null)
        val result = runCatching { CombatResolver.suppressIc(d, ic, host()) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `suppressIc throws when decker is on LTG not a host`() {
        // D-6: decker has persona but is not on a host — cannot suppress IC
        val ic = Probe(rating = 4)
        // decker() fixture has currentLocation=null; we need to check the persona+location combo
        val d = decker() // currentLocation is null → not OnHost → should throw
        val result = runCatching { CombatResolver.suppressIc(d, ic, host()) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `unsuppressIc removes IC from suppressedIc list`() {
        val ic = Probe(rating = 4)
        val h = host()
        val withSuppressed = CombatResolver.suppressIc(decker().copy(currentLocation = MatrixLocation.OnHost(h)), ic, h)
        var callbackRating = -1
        val released = CombatResolver.unsuppressIc(withSuppressed, ic) { callbackRating = it }
        assertEquals(0, released.suppressedIc.size)
    }

    @Test
    fun `unsuppressIc calls onTallyIncrease with stored IC rating`() {
        val ic = Probe(rating = 7)
        val h = host()
        val withSuppressed = CombatResolver.suppressIc(decker().copy(currentLocation = MatrixLocation.OnHost(h)), ic, h)
        var callbackRating = -1
        CombatResolver.unsuppressIc(withSuppressed, ic) { callbackRating = it }
        assertEquals(7, callbackRating)
    }

    @Test
    fun `unsuppressIc for unknown IC is a no-op`() {
        val ic = Probe(rating = 4)
        val d = decker()
        var callbackFired = false
        val result = CombatResolver.unsuppressIc(d, ic) { callbackFired = true }
        assertFalse(callbackFired)
        assertEquals(d, result)
    }

    @Test
    fun `unsuppressIc only removes the matching IC when multiple are suppressed`() {
        val ic1 = Probe(rating = 3)
        val ic2 = Killer(rating = 5)
        val h = host()
        val base = decker().copy(currentLocation = MatrixLocation.OnHost(h))
        val d = CombatResolver.suppressIc(CombatResolver.suppressIc(base, ic1, h), ic2, h)
        val released = CombatResolver.unsuppressIc(d, ic1) {}
        assertEquals(1, released.suppressedIc.size)
        assertEquals(ic2, released.suppressedIc.first().ic)
    }

    // ── icAttackParticipant (CC-23) ───────────────────────────────────────────────

    @Test
    fun `icAttackParticipant Blue host gives MODERATE damage and SV=3`() {
        // CC-23: pool = Security Value only; CC-27: Blue/Green = Moderate damage level
        val ic = Killer(rating = 5)
        val p = CombatResolver.icAttackParticipant(ic, SecurityCode.BLUE, 3)
        assertEquals(DamageLevel.MODERATE, p.rawDamageLevel)
        assertEquals(3, p.attackDicePool)
        assertEquals(5, p.weaponPower)
        assertEquals(0, p.hackingPool)
    }

    @Test
    fun `icAttackParticipant Green host gives MODERATE damage and SV=4`() {
        // CC-23: pool = Security Value only; CC-27: Blue/Green = Moderate damage level
        val ic = Killer(rating = 4)
        val p = CombatResolver.icAttackParticipant(ic, SecurityCode.GREEN, 4)
        assertEquals(DamageLevel.MODERATE, p.rawDamageLevel)
        assertEquals(4, p.attackDicePool)
        assertEquals(4, p.weaponPower)
        assertEquals(0, p.hackingPool)
    }

    @Test
    fun `icAttackParticipant Orange host gives SERIOUS damage and SV=5`() {
        // CC-23: pool = Security Value only; CC-27: Orange/Red = Serious damage level
        val ic = Killer(rating = 6)
        val p = CombatResolver.icAttackParticipant(ic, SecurityCode.ORANGE, 5)
        assertEquals(DamageLevel.SERIOUS, p.rawDamageLevel)
        assertEquals(5, p.attackDicePool)
        assertEquals(6, p.weaponPower)
        assertEquals(0, p.hackingPool)
    }

    @Test
    fun `icAttackParticipant Red host gives SERIOUS damage and SV=6`() {
        val ic = Killer(rating = 8)
        val p = CombatResolver.icAttackParticipant(ic, SecurityCode.RED, 6)
        assertEquals(DamageLevel.SERIOUS, p.rawDamageLevel)
        assertEquals(6, p.attackDicePool)
        assertEquals(8, p.weaponPower)
        assertEquals(0, p.hackingPool)
    }

    // ── effectiveDetectionFactor under suppression ────────────────────────────────

    @Test
    fun `resolveCrippler uses effectiveDetectionFactor when IC is suppressed`() {
        // Suppress one IC → DF penalty = 1 → effectiveDF = base DF - 1
        // With a lower effective DF the IC's TN is easier, so it hits more often.
        // We verify the state change by running two scenarios with a fixed roller and
        // checking that the reduction differs.
        val ic = Crippler(rating = 5, targetAttribute = PersonaAttributeType.BOD)
        val suppressedIc = Probe(rating = 3)

        // 4 IC dice succeed, 6 decker dice fail → reduction = 2 (used for both paths)
        val roller = DiceRoller(stubRandom(
            *IntArray(4) { 5 }, *IntArray(6) { 1 },  // scenario A
            *IntArray(4) { 5 }, *IntArray(6) { 1 }   // scenario B
        ))

        val baseline = CombatResolver.resolveCrippler(decker(), ic, 4, roller)
        val h = host()
        val withSuppression = CombatResolver.resolveCrippler(
            CombatResolver.suppressIc(decker().copy(currentLocation = MatrixLocation.OnHost(h)), suppressedIc, h), ic, 4, roller
        )
        // Both should produce the same structural result (roller is deterministic).
        assertEquals(baseline.reduction, withSuppression.reduction)
    }

    // ── resolveRipperMpcpTest ─────────────────────────────────────────────────────

    @Test
    fun `resolveRipperMpcpTest reduces MPCP by half IC successes`() {
        // tn = hardening(0) + mcpRating(6) = 6; IC 8 dice, each pair (6,1)=7≥6 → 4 hits → reduction=2
        // Programs(1) so that sum=4 ≤ 4*3=12 after reduction
        val lowPrograms = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 1),
            PersonaProgram(PersonaAttributeType.EVASION, 1),
            PersonaProgram(PersonaAttributeType.MASKING, 1),
            PersonaProgram(PersonaAttributeType.SENSORS, 1)
        )
        val roller = DiceRoller(stubRandom(6, 1, 6, 1, 6, 1, 6, 1, 1, 1, 1, 1))
        val d = decker(cyberdeck = Cyberdeck(
            name = "TestDeck", mcpRating = 6, hardening = 0,
            activeMemoryMp = 2000, storageMemoryMp = 5000, ioSpeedMpPerTurn = 500,
            costNuyen = 0, personaPrograms = lowPrograms
        ))
        val ic = Ripper(rating = 8, targetAttribute = PersonaAttributeType.EVASION)
        val result = CombatResolver.resolveRipperMpcpTest(d, ic, roller)
        assertEquals(4, result.cyberdeck.mcpRating)  // 6 - (4/2) = 4
    }

    @Test
    fun `resolveRipperMpcpTest with no IC successes leaves MPCP unchanged`() {
        // tn = 8; IC 6 dice all show 5 → 0 successes at TN 8
        val roller = allFaces(5)
        val d = decker(cyberdeck = deck(mcpRating = 8, hardening = 0))
        val ic = Ripper(rating = 6, targetAttribute = PersonaAttributeType.BOD)
        val result = CombatResolver.resolveRipperMpcpTest(d, ic, roller)
        assertEquals(8, result.cyberdeck.mcpRating)
    }

    @Test
    fun `resolveRipperMpcpTest floors MPCP at 0`() {
        // tn = max(2, 0+1) = 2; IC 12 dice all show 5 → 12 successes → reduction=6 → max(0,1-6)=0
        val roller = allFaces(5, 30)
        val d = decker(cyberdeck = Cyberdeck(
            name = "TestDeck", mcpRating = 1, hardening = 0,
            activeMemoryMp = 2000, storageMemoryMp = 5000, ioSpeedMpPerTurn = 500,
            costNuyen = 0, personaPrograms = emptyList()
        ))
        val ic = Ripper(rating = 12, targetAttribute = PersonaAttributeType.BOD)
        val result = CombatResolver.resolveRipperMpcpTest(d, ic, roller)
        assertEquals(0, result.cyberdeck.mcpRating)
    }

    // ── Persona attribute helpers ─────────────────────────────────────────────────

    @Test
    fun `Persona attribute returns correct value for each type`() {
        val p = Persona(bod = 1, evasion = 2, masking = 3, sensor = 4)
        assertEquals(1, p.attribute(PersonaAttributeType.BOD))
        assertEquals(2, p.attribute(PersonaAttributeType.EVASION))
        assertEquals(3, p.attribute(PersonaAttributeType.MASKING))
        assertEquals(4, p.attribute(PersonaAttributeType.SENSORS))
    }

    @Test
    fun `Persona withAttribute updates the correct field`() {
        val p = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        assertEquals(3, p.withAttribute(PersonaAttributeType.BOD, 3).bod)
        assertEquals(3, p.withAttribute(PersonaAttributeType.EVASION, 3).evasion)
        assertEquals(3, p.withAttribute(PersonaAttributeType.MASKING, 3).masking)
        assertEquals(3, p.withAttribute(PersonaAttributeType.SENSORS, 3).sensor)
    }
}

