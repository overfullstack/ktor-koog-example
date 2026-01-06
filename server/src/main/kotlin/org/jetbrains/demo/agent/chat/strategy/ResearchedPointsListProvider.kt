package org.jetbrains.demo.agent.chat.strategy

import ai.koog.agents.ext.agent.SubgraphResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ResearchedPointsList(
    val researchedPoints: List<ResearchedPointOfInterest>
) : SubgraphResult {
    override fun toStringDefault(): String =
        Json.encodeToString(serializer(), this)
}

val ResearchedPointsListProvider = SubgraphResultProvider<ResearchedPointsList>(
    name = "provide_all_research_results",
    description = """
            Finish tool to compile final research results for ALL points of interest.
            Call after researching every point of interest to provide all results together.
        """.trimIndent(),
)
