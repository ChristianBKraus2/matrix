package com.shadowrun.matrix.game

import com.shadowrun.matrix.utility.DiceRoller

interface ActiveIcon {
    suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult
}
