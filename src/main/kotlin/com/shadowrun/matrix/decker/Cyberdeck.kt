package com.shadowrun.matrix.decker

import com.shadowrun.matrix.accessories.Accessory
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import kotlin.math.ceil

data class Cyberdeck(
    val name: String,
    val mcpRating: Int,
    val hardening: Int = 0,
    val activeMemoryMp: Int,
    val storageMemoryMp: Int,
    val ioSpeedMpPerTurn: Int,
    val responseIncrease: Int = 0,
    val costNuyen: Int,
    // The four persona programs (Bod, Evasion, Masking, Sensors); may be empty when not yet configured
    val personaPrograms: List<PersonaProgram> = emptyList(),
    // Utilities currently loaded into active memory
    val activeUtilities: List<Utility> = emptyList(),
    // All utilities held in storage memory (must include every active utility)
    val storedUtilities: List<Utility> = emptyList(),
    val accessories: List<Accessory> = emptyList()
) {
    val maxResponseIncrease: Int get() = mcpRating / 4

    init {
        require(responseIncrease <= maxResponseIncrease) {
            "Response Increase $responseIncrease exceeds maximum $maxResponseIncrease for MPCP $mcpRating"
        }
        require(responseIncrease in 0..3) { "Response Increase must be 0-3" }

        // Each persona program rating must not exceed MPCP
        personaPrograms.forEach { pp ->
            require(pp.rating <= mcpRating) {
                "PersonaProgram ${pp.name} rating ${pp.rating} exceeds MPCP $mcpRating"
            }
        }
        // Sum of all persona program ratings must not exceed MPCP × 3
        val totalPersonaRatings = personaPrograms.sumOf { it.rating }
        require(totalPersonaRatings <= mcpRating * 3) {
            "Total persona program ratings $totalPersonaRatings exceed MPCP×3 = ${mcpRating * 3}"
        }

        // Active memory capacity
        val activeMp = activeUtilities.sumOf { it.mpSize }
        require(activeMp <= activeMemoryMp) {
            "Active utilities use $activeMp Mp, exceeding active memory $activeMemoryMp Mp"
        }

        // Storage memory capacity (covers stored utilities; downloads are not modelled here)
        val storageMp = storedUtilities.sumOf { it.mpSize }
        require(storageMp <= storageMemoryMp) {
            "Stored utilities use $storageMp Mp, exceeding storage memory $storageMemoryMp Mp"
        }
    }

    fun detectionFactor(maskingRating: Int, sleazeRating: Int? = null): Int =
        if (sleazeRating != null)
            ceil((maskingRating + sleazeRating) / 2.0).toInt()
        else
            ceil(maskingRating / 2.0).toInt()
}

