package dev.phonebridge.core.mcp

import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolRegistry

/**
 * The `bridge_capabilities` meta tool (scope "", Risk.READ): returns the calling token's name and
 * scopes, plus every tool visible to that token with `available` and `unavailable_reason`, and
 * [extraStatus] (platform status such as accessibility/notification access/locked) merged in.
 *
 * STUB — implemented by the core agent.
 */
fun capabilitiesTool(
    registry: ToolRegistry,
    extraStatus: () -> Map<String, Any?> = { emptyMap() },
): Tool = TODO()
