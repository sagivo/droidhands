package com.droidhands.core.tools

/** Thread-safe, insertion-ordered tool registry. */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    @Synchronized
    fun register(tool: Tool) {
        require(tool.name.matches(Regex("^[a-z][a-z0-9_]{0,63}$"))) { "Invalid tool name '${tool.name}'" }
        require(tool.name !in tools) { "Duplicate tool '${tool.name}'" }
        tools[tool.name] = tool
    }

    fun registerAll(tools: Iterable<Tool>) = tools.forEach(::register)

    @Synchronized
    fun get(name: String): Tool? = tools[name]

    @Synchronized
    fun all(): List<Tool> = tools.values.toList()
}
