package org.jetbrains.demo.agent.chat.strategy

import kotlinx.serialization.Serializable

@Serializable
data class ResearchedPointsList(
    val researchedPoints: List<ResearchedPointOfInterest>
)

val ResearchedPointsListProvider = SubgraphResultProvider<ResearchedPointsList>(
    name = "provide_all_research_results",
    description = """
            Finish tool to compile final research results for ALL points of interest.
            Call after researching every point of interest to provide all results together.
        """.trimIndent(),
)
