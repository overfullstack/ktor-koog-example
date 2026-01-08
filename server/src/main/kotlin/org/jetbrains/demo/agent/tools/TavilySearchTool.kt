package org.jetbrains.demo.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class TavilySearchTool(
    private val client: HttpClient,
    private val apiKey: String
) : ToolSet {
    private val logger = LoggerFactory.getLogger(TavilySearchTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Tool
    @LLMDescription("Searches the web for information using Tavily AI search engine. Returns relevant web results with titles, URLs, and content snippets.")
    suspend fun search(
        @LLMDescription("The search query to execute.")
        query: String,
        @LLMDescription("Maximum number of results to return (default: 5, max: 10).")
        maxResults: Int = 5
    ): SearchToolResult = try {
        val requestBody = TavilyRequest(
            apiKey = apiKey,
            query = query,
            maxResults = maxResults.coerceIn(1, 10),
            includeAnswer = true,
            includeRawContent = false
        )
        
        val responseText = client.post("https://api.tavily.com/search") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TavilyRequest.serializer(), requestBody))
        }.bodyAsText()
        
        logger.info("Tavily search for: $query")
        logger.debug("Tavily raw response: $responseText")
        
        val response = json.decodeFromString<TavilyResponse>(responseText)

        val results = response.results.map { result ->
            SearchResult(
                title = result.title,
                url = result.url,
                snippet = result.content
            )
        }

        if (results.isNotEmpty()) {
            SearchResults(
                answer = response.answer,
                results = results
            )
        } else {
            ErrorResult("No search results found for query: $query")
        }
    } catch (e: Exception) {
        logger.error("Tavily search failed: ${e.message}", e)
        ErrorResult("Search failed: ${e.message}")
    }

    @Serializable
    sealed interface SearchToolResult

    @LLMDescription("Successful search results from Tavily.")
    @Serializable
    data class SearchResults(
        @LLMDescription("AI-generated answer summarizing the search results (if available).")
        val answer: String?,
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
    private data class TavilyRequest(
        @SerialName("api_key") val apiKey: String,
        val query: String,
        @SerialName("max_results") val maxResults: Int = 5,
        @SerialName("include_answer") val includeAnswer: Boolean = true,
        @SerialName("include_raw_content") val includeRawContent: Boolean = false
    )

    @Serializable
    private data class TavilyResponse(
        val answer: String? = null,
        val results: List<TavilyResult> = emptyList()
    )

    @Serializable
    private data class TavilyResult(
        val title: String = "",
        val url: String = "",
        val content: String = "",
        val score: Double = 0.0
    )
}
