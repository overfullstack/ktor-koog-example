package org.jetbrains.demo.agent.a2a.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import org.jetbrains.demo.Day
import org.jetbrains.demo.InternetResource
import org.jetbrains.demo.PointOfInterest

@Serializable
data class ItineraryIdeasResult(
    @property:LLMDescription("List of points of interest to visit during the journey")
    val pointsOfInterest: List<PointOfInterest>
)

@Serializable
data class POIResearchRequest(
    val pointOfInterest: PointOfInterest,
    val travelers: String,
    val startDate: String,
    val endDate: String
)

@Serializable
data class POIResearchResult(
    val pointOfInterest: PointOfInterest,
    @property:LLMDescription("Detailed research findings about this point of interest")
    val research: String,
    @property:LLMDescription("Links to relevant web pages with more information")
    val links: List<InternetResource>,
    @property:LLMDescription("Links to images. Links must be direct image URLs, not just pages containing images.")
    val imageLinks: List<InternetResource>
)

@Serializable
data class TravelPlanRequest(
    val journeyDetails: String,
    val researchedPoints: List<POIResearchResult>
)

@Serializable
data class TravelPlanResult(
    @property:LLMDescription("Catchy title appropriate to the travelers and travel brief")
    val title: String,
    @property:LLMDescription("Detailed travel plan in markdown format")
    val plan: String,
    @property:LLMDescription("List of days in the travel plan")
    val days: List<Day>,
    @property:LLMDescription("Links to images")
    val imageLinks: List<InternetResource>,
    @property:LLMDescription("Links to pages with more information about the travel plan")
    val pageLinks: List<InternetResource>,
    @property:LLMDescription("List of country names that the travelers will visit")
    val countriesVisited: List<String>
)
