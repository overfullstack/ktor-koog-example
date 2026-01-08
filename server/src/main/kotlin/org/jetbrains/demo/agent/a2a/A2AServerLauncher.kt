package org.jetbrains.demo.agent.a2a

import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.demo.agent.tools.TavilySearchTool
import org.jetbrains.demo.agent.tools.Tools
import org.jetbrains.demo.agent.tools.WeatherTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2AServerLauncher")

fun main() = runBlocking {
    logger.info("Starting A2A Mesh Servers...")

    val config = A2AConfig(
        baseUrl = System.getenv("A2A_BASE_URL") ?: "http://localhost",
        routePlannerPort = System.getenv("ROUTE_PLANNER_PORT")?.toIntOrNull() ?: 9101,
        poiResearcherPort = System.getenv("POI_RESEARCHER_PORT")?.toIntOrNull() ?: 9102,
        planComposerPort = System.getenv("PLAN_COMPOSER_PORT")?.toIntOrNull() ?: 9103
    )

    val promptExecutor = createPromptExecutor()
    val tools = createTools()

    val meshServer = A2AMeshServer(config, promptExecutor, tools)
    meshServer.start()

    logger.info("A2A Mesh servers started successfully!")
    logger.info("Endpoints:")
    logger.info("  Route Planner:  ${config.baseUrl}:${config.routePlannerPort}$ROUTE_PLANNER_PATH")
    logger.info("  POI Researcher: ${config.baseUrl}:${config.poiResearcherPort}$POI_RESEARCHER_PATH")
    logger.info("  Plan Composer:  ${config.baseUrl}:${config.planComposerPort}$PLAN_COMPOSER_PATH")

    // Keep the main thread alive
    while (true) {
        delay(Long.MAX_VALUE)
    }
}

private fun createPromptExecutor(): MultiLLMPromptExecutor {
    val openAIKey = System.getenv("LLM_GATEWAY_KEY") ?: System.getenv("OPENAI_API_KEY")
    val anthropicKey = System.getenv("ANTHROPIC_AUTH_TOKEN") ?: System.getenv("ANTHROPIC_API_KEY")
    val googleKey = System.getenv("GOOGLE_API_KEY")

    val clients = buildList<Pair<LLMProvider, ai.koog.prompt.executor.clients.LLMClient>> {
        if (!openAIKey.isNullOrBlank()) {
            val gatewayBaseUrl = System.getenv("LLM_GATEWAY_BASE_URL")
            val settings = if (gatewayBaseUrl != null) {
                OpenAIClientSettings(baseUrl = gatewayBaseUrl)
            } else {
                OpenAIClientSettings()
            }
            add(LLMProvider.OpenAI to OpenAILLMClient(apiKey = openAIKey, settings = settings))
        }
        if (!anthropicKey.isNullOrBlank()) {
            val bedrockBaseUrl = System.getenv("ANTHROPIC_BEDROCK_BASE_URL")
            val settings = if (bedrockBaseUrl != null) {
                AnthropicClientSettings(baseUrl = bedrockBaseUrl)
            } else {
                AnthropicClientSettings()
            }
            add(LLMProvider.Anthropic to AnthropicLLMClient(apiKey = anthropicKey, settings = settings))
        }
        if (!googleKey.isNullOrBlank()) {
            add(LLMProvider.Google to GoogleLLMClient(googleKey))
        }
    }
    
    require(clients.isNotEmpty()) { "No LLM API keys configured" }
    return MultiLLMPromptExecutor(clients.toMap())
}

private suspend fun createTools(): Tools {
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                isLenient = true
                encodeDefaults = true
                ignoreUnknownKeys = true
                allowSpecialFloatingPointValues = true
            })
        }
    }

    val weatherApiUrl = System.getenv("WEATHER_API_URL") ?: "https://api.weatherapi.com/v1"
    val tavilyApiKey = System.getenv("TAVILY_API_KEY") ?: ""
    val mcpServerUrl = System.getenv("MCP_SERVER_URL") ?: "http://localhost:9011"

    val googleMaps = try {
        McpToolRegistryProvider.fromTransport(McpToolRegistryProvider.defaultSseTransport(mcpServerUrl))
    } catch (e: Exception) {
        logger.warn("Could not connect to MCP server at $mcpServerUrl: ${e.message}")
        ToolRegistry.EMPTY
    }

    val weatherTool = WeatherTool(httpClient, weatherApiUrl)
    val searchTool = TavilySearchTool(httpClient, tavilyApiKey)

    return Tools(
        searchTool = searchTool,
        weatherTool = weatherTool,
        googleMaps = googleMaps
    )
}
