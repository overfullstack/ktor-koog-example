package org.jetbrains.demo.agent.chat.strategy

import ai.koog.agents.core.tools.Tool
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

@Suppress("FunctionName")
inline fun <reified A : Any> SubgraphResultProvider(
    name: String,
    description: String
): Tool<A, A> =
    SubgraphResultProvider(name, description, serializer<A>())

fun <A : Any> SubgraphResultProvider(
    name: String,
    description: String,
    serializer: KSerializer<A>
): Tool<A, A> =
    DefaultSubgraphFinishTool(name, description, serializer)

private class DefaultSubgraphFinishTool<A : Any>(
    toolName: String,
    description: String,
    argsSerializer: KSerializer<A>
) : Tool<A, A>(
    argsSerializer = argsSerializer,
    resultSerializer = argsSerializer,
    name = toolName,
    description = description
) {
    override suspend fun execute(args: A): A = args
}
