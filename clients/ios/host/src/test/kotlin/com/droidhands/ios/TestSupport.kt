package com.droidhands.ios

import com.droidhands.core.auth.AppAccessMode
import com.droidhands.core.plugins.DeviceOs
import com.droidhands.core.screen.ScreenSnapshot
import com.droidhands.core.screen.Screenshot
import com.droidhands.core.screen.Size
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.CallContext
import com.droidhands.core.tools.Scopes
import com.droidhands.ios.inventory.AppInventory
import com.droidhands.ios.inventory.CommandResult
import com.droidhands.ios.inventory.CommandRunner
import com.droidhands.ios.inventory.InventoryLimits
import com.droidhands.ios.inventory.IosApp
import com.droidhands.ios.runner.IosDevice
import com.droidhands.ios.runner.RunnerStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object Fixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(Fixtures::class.java.classLoader.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)) as JsonObject
}

/** A [CommandRunner] answering from [handler] and recording every command. */
class FakeRunner(private val handler: (List<String>, ByteArray?) -> CommandResult) : CommandRunner {
    val calls = mutableListOf<List<String>>()

    override fun run(command: List<String>, stdin: ByteArray?, timeoutMs: Long): CommandResult {
        synchronized(calls) { calls += command }
        return handler(command, stdin)
    }

    companion object {
        fun ok(text: String) = CommandResult(0, text.toByteArray(), "")

        fun ok(bytes: ByteArray) = CommandResult(0, bytes, "")

        fun fail(message: String, code: Int = 1) = CommandResult(code, ByteArray(0), message)
    }
}

class FakeInventory(
    var list: List<IosApp>,
    override val limits: InventoryLimits = InventoryLimits(),
) : AppInventory {
    override val source: String get() = "fake"

    override fun apps(): List<IosApp> = list
}

/** An iPhone that records what it was asked and reports [front] as the app in front (after [onOpen] runs). */
class FakeDevice(var front: String? = "com.apple.springboard") : IosDevice {
    val calls = mutableListOf<String>()
    var onOpen: (String) -> Unit = {}
    var status = RunnerStatus("1.0", "18.0", "iPhone15,2", "Test iPhone", Size(393, 852), 3.0, false)

    override val os: DeviceOs get() = DeviceOs.IOS
    override val globalActions: Set<String> = setOf("home", "lock_screen")

    override suspend fun status(): RunnerStatus = status

    override suspend fun foreground(prefer: List<String>): String? = front

    override suspend fun snapshot(): ScreenSnapshot = ScreenSnapshot(front, Size(393, 852), null)

    override suspend fun screenshot(): Screenshot = Screenshot(ByteArray(0), "image/png", 0, 0)

    override suspend fun tap(x: Double, y: Double, durationMs: Long) {
        calls += "tap"
    }

    override suspend fun swipe(fromX: Double, fromY: Double, toX: Double, toY: Double, durationMs: Long) {
        calls += "swipe"
    }

    override suspend fun typeText(text: String) {
        calls += "type"
    }

    override suspend fun clearText() {
        calls += "clear"
    }

    override suspend fun pressKey(key: String) {
        calls += "key:$key"
    }

    override suspend fun globalAction(action: String) {
        calls += "global:$action"
        if (action == "home") front = "com.apple.springboard"
    }

    override suspend fun launchApp(appId: String) {
        calls += "launch:$appId"
        front = appId
    }

    override suspend fun openUrl(url: String) {
        calls += "open:$url"
        onOpen(url)
    }
}

fun app(
    id: String,
    name: String,
    schemes: List<String> = emptyList(),
    domains: List<String> = emptyList(),
    system: Boolean = false,
): IosApp = IosApp(
    bundleId = id,
    name = name,
    version = "1.0",
    build = "1",
    system = system,
    infoPlist = Json.parseToJsonElement(
        """{"CFBundleURLTypes":[{"CFBundleURLSchemes":[${schemes.joinToString(",") { "\"$it\"" }}]}]}""",
    ) as JsonObject,
    entitlements = Json.parseToJsonElement(
        """{"com.apple.developer.associated-domains":[${domains.joinToString(",") { "\"applinks:$it\"" }}]}""",
    ) as JsonObject,
)

fun access(blocked: Set<String> = emptySet()): AppAccess = AppAccess(AppAccessMode.BLOCKLIST, blocked, emptySet(), "Claude")

fun ctx(appAccess: AppAccess = access()): CallContext =
    CallContext("t1", "Claude", Scopes.ALL, "local", "r1", appAccess = appAccess) { }
