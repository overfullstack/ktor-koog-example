package org.jetbrains.demo.agent.tools

import ai.koog.agents.core.tools.ToolResult
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class DuckDuckGoSearchTool(private val client: HttpClient) : ToolSet {
    private val logger = LoggerFactory.getLogger(DuckDuckGoSearchTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    @Tool
    @LLMDescription("Searches DuckDuckGo for web results based on a query.")
    suspend fun search(
        @LLMDescription("The search query to execute.")
        query: String,
        @LLMDescription("Maximum number of results to return (default: 5).")
        maxResults: Int = 5
    ): SearchToolResult = try {
        val responseText = client.get("https://api.duckduckgo.com/") {
            parameter("q", query)
            parameter("format", "json")
            parameter("no_html", "1")
            parameter("skip_disambig", "1")
        }.bodyAsText()
        logger.info("DuckDuckGo raw response: $responseText")
        val response = json.decodeFromString<DuckDuckGoResponse>(responseText)

        val results = response.relatedTopics
            .take(maxResults)
            .mapNotNull { topic ->
                topic.firstUrl?.let { url ->
                    SearchResult(
                        title = topic.text.substringBefore(" - ").trim(),
                        url = url,
                        snippet = topic.text
                    )
                }
            }

        if (results.isNotEmpty()) SearchResults(results)
        else ErrorResult("No search results found for query: $query")
    } catch (e: Exception) {
        println("An error occurred during search: ${e.message}")
        ErrorResult("Search failed: ${e.message}")
    }

    @Serializable
    sealed interface SearchToolResult : ToolResult {
        override fun toStringDefault(): String = when (this) {
            is SearchResults -> Json.encodeToString(SearchResults.serializer(), this)
            is ErrorResult -> message
        }
    }

    @LLMDescription("Successful search results from DuckDuckGo.")
    @Serializable
    data class SearchResults(
        @LLMDescription("List of search results.")
        val results: List<SearchResult>
    ) : SearchToolResult

    @LLMDescription("A single search result.")
    @Serializable
    data class SearchResult(
        @LLMDescription("The title of the search result.")
        val title: String,
        @LLMDescription("The URL of the search result.")
        val url: String,
        @LLMDescription("A snippet or description of the search result.")
        val snippet: String
    )

    @LLMDescription("Error result when search fails.")
    @Serializable
    data class ErrorResult(
        @LLMDescription("Error message describing what went wrong.")
        val message: String
    ) : SearchToolResult

    @Serializable
    private data class DuckDuckGoResponse(
        @SerialName("RelatedTopics") val relatedTopics: List<RelatedTopic> = emptyList()
    )

    @Serializable
    private data class RelatedTopic(
        @SerialName("Text") val text: String = "",
        @SerialName("FirstURL") val firstUrl: String? = null,
        @SerialName("Icon") val icon: Icon? = null,
        @SerialName("Result") val result: String = ""
    )

    @Serializable
    private data class Icon(
        @SerialName("Height") val height: String = "",
        @SerialName("URL") val url: String = "",
        @SerialName("Width") val width: String = ""
    )
}