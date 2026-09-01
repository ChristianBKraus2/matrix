package com.shadowrun.matrix.decker

import com.shadowrun.matrix.operations.DownloadHandle
import com.shadowrun.matrix.operations.UploadHandle
import com.shadowrun.matrix.programs.Utility
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.ceil

private val logger = KotlinLogging.logger {}

/** PRD: CD-07, CD-08, CD-10, CD-12 */
fun Decker.loadUtility(utility: Utility): LoadUtilityResult {
    logger.info { "[$name] loadUtility → ${utility.type} (rating=${utility.rating}, ${utility.mpSize} Mp)" }
    check(persona != null) { "Decker is not jacked in" }
    require(cyberdeck.storedUtilities.any { it.type == utility.type && it.rating == utility.rating }) {
        "Utility ${utility.type} is not in storage"
    }
    require(cyberdeck.activeUtilities.none { it.type == utility.type } &&
            cyberdeck.pendingUploads.none { it.utility.type == utility.type }) {
        "Utility ${utility.type} is already loaded or uploading"
    }
    if (cyberdeck.freeActiveMemoryMp < utility.mpSize) {
        logger.warn { "[$name] loadUtility ${utility.type}: insufficient memory (need=${utility.mpSize}, free=${cyberdeck.freeActiveMemoryMp})" }
        return LoadUtilityResult.InsufficientMemory(this, utility.mpSize, cyberdeck.freeActiveMemoryMp)
    }
    val turnsRequired = if (cyberdeck.ioSpeedMpPerTurn <= 0) {
        logger.warn { "[$name] loadUtility ${utility.type}: ioSpeedMpPerTurn is 0 — treating as instant load" }
        0
    } else {
        ceil(utility.mpSize.toDouble() / cyberdeck.ioSpeedMpPerTurn).toInt()
    }
    val updatedDeck = if (turnsRequired == 0) {
        cyberdeck.copy(activeUtilities = cyberdeck.activeUtilities + utility)
    } else {
        cyberdeck.copy(pendingUploads = cyberdeck.pendingUploads + PendingUpload(utility, turnsRequired))
    }
    logger.info { "[$name] loadUtility ${utility.type}: accepted (uploadTurns=$turnsRequired)" }
    return LoadUtilityResult.Success(copy(cyberdeck = updatedDeck))
}

/** PRD: CD-09 */
fun Decker.unloadUtility(utility: Utility): Decker {
    logger.info { "[$name] unloadUtility → ${utility.type}" }
    check(persona != null) { "Decker is not jacked in" }
    val newActive = cyberdeck.activeUtilities.filterNot { it.type == utility.type }
    val newPending = cyberdeck.pendingUploads.filterNot { it.utility.type == utility.type }
    require(newActive.size < cyberdeck.activeUtilities.size || newPending.size < cyberdeck.pendingUploads.size) {
        "Utility ${utility.type} is not loaded or uploading"
    }
    return copy(cyberdeck = cyberdeck.copy(activeUtilities = newActive, pendingUploads = newPending)).also {
        logger.info { "[$name] unloadUtility ${utility.type}: removed" }
    }
}

/** PRD: CD-13 */
fun Decker.swapUtility(toUnload: Utility, toLoad: Utility): LoadUtilityResult {
    logger.info { "[$name] swapUtility: unload ${toUnload.type} → load ${toLoad.type}" }
    val afterUnload = unloadUtility(toUnload)
    return afterUnload.loadUtility(toLoad)
}

/** PRD: CD-11, CD-22 */
fun Decker.advanceCombatTurn(): Decker {
    logger.info { "[$name] advanceCombatTurn" }
    val decremented = cyberdeck.pendingUploads.map { it.copy(turnsRemaining = it.turnsRemaining - 1) }
    val nowActive = decremented.filter { it.turnsRemaining <= 0 }.map { it.utility }
    val stillPending = decremented.filter { it.turnsRemaining > 0 }

    val allActive = cyberdeck.activeUtilities + nowActive
    val (live, depleted) = allActive.partition { it.currentRating > 0 }
    depleted.forEach { logger.warn { "[$name] advanceCombatTurn: utility ${it.type} depleted and auto-unloaded" } }
    val newStored = cyberdeck.storedUtilities.filterNot { su -> depleted.any { it.type == su.type } }

    val updatedDeck = cyberdeck.copy(
        activeUtilities = live,
        pendingUploads = stillPending,
        storedUtilities = newStored
    )
    nowActive.forEach { logger.info { "[$name] advanceCombatTurn: ${it.type} upload complete, now active" } }
    val newTrackState = trackState?.let {
        val remaining = it.locationCycleTurnsRemaining - 1
        if (remaining <= 0) null else it.copy(locationCycleTurnsRemaining = remaining)
    }

    // PRD SO-10/SO-12: advance in-progress data transfers
    val decrementedDownloads = activeDownloads.map { it.copy(turnsRemaining = it.turnsRemaining - 1) }
    val completedDownloads = decrementedDownloads.filter { it.turnsRemaining <= 0 }
    val ongoingDownloads = decrementedDownloads.filter { it.turnsRemaining > 0 }

    val decrementedUploads = activeUploads.map { it.copy(turnsRemaining = it.turnsRemaining - 1) }
    val completedUploads = decrementedUploads.filter { it.turnsRemaining <= 0 }
    val ongoingUploads = decrementedUploads.filter { it.turnsRemaining > 0 }

    completedDownloads.forEach { logger.info { "[$name] advanceCombatTurn: download of '${it.file.name}' complete" } }
    completedUploads.forEach { logger.info { "[$name] advanceCombatTurn: upload (${it.totalMp} Mp to host) complete" } }

    var result = copy(
        cyberdeck = updatedDeck,
        trackState = newTrackState,
        activeDownloads = ongoingDownloads,
        activeUploads = ongoingUploads
    )
    completedDownloads.forEach { handle -> result = result.recordCompletedDownload(handle.file) }
    return result
}
