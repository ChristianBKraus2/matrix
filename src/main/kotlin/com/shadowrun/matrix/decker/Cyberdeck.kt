package com.shadowrun.matrix.decker

import com.shadowrun.matrix.accessories.Accessory
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import kotlin.math.ceil

/** Passive observer jacked in via hitcher jack. Cannot affect persona; immune to IC biofeedback. */
data class HitcherObserver(val name: String)

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
    // Utilities currently loaded into active memory (fully uploaded)
    val activeUtilities: List<Utility> = emptyList(),
    // All utilities held in storage memory (must include every active utility)
    val storedUtilities: List<Utility> = emptyList(),
    val accessories: List<Accessory> = emptyList(),
    // Utilities accepted into active memory but not yet fully uploaded
    val pendingUploads: List<PendingUpload> = emptyList(),
    // Passive observers attached via hitcher jacks; they cannot control the persona (ACC-03)
    val hitchers: List<HitcherObserver> = emptyList(),
    // True when this deck is a cyberterminal (CT-01 through CT-04); enables CT-03 rating reduction and CT-04 dump-shock immunity
    val isCyberterminal: Boolean = false
) {
    val maxResponseIncrease: Int get() = minOf(3, mcpRating / 4)

    val usedActiveMemoryMp: Int
        get() = activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize }

    val freeActiveMemoryMp: Int
        get() = activeMemoryMp - usedActiveMemoryMp

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

        // No utility rating may exceed MPCP
        activeUtilities.forEach { u ->
            require(u.rating <= mcpRating) {
                "Active utility ${u.type} rating ${u.rating} exceeds MPCP $mcpRating"
            }
        }
        storedUtilities.forEach { u ->
            require(u.rating <= mcpRating) {
                "Stored utility ${u.type} rating ${u.rating} exceeds MPCP $mcpRating"
            }
        }

        // Active memory capacity
        val activeMp = activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize }
        require(activeMp <= activeMemoryMp) {
            "Active utilities + pending uploads (${activeMp}Mp) exceed active memory (${activeMemoryMp}Mp)"
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

