package org.jetbrains.demo.agent.chat.strategy

import kotlinx.serialization.Serializable
import org.jetbrains.demo.PointOfInterest

@Serializable
data class ItineraryIdeas(val pointsOfInterest: List<PointOfInterest>)

val ItineraryIdeasProvider = SubgraphResultProvider<ItineraryIdeas>(
    name = "provide_itinerary_ideas",
    description = """
            Finish tool to compile final suggestion for the user's itinerary.
            Call to provide the final conclusion suggestion itinerary ideas result.
        """.trimIndent(),
)
