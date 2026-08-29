package com.shadowrun.matrix.operations

/**
 * Any entity reachable by the decker for message delivery: hitcher jack, radiolink, or datascreen.
 * PRD: operations.md Buffered Messages section, rules p. 224.
 */
data class LinkedObserver(val name: String)

/**
 * A message buffered by the decker as a Free Action (up to 100 words).
 * Always delivered to [recipient] at the end of the Combat Turn.
 * PRD: operations.md Buffered Messages section.
 */
data class BufferedMessage(
    val text: String,
    val recipient: LinkedObserver
)
