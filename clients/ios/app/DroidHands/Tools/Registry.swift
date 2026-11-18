import Foundation

/// `bridge_capabilities`: the calling agent's access in plain words, the phone's status, and every tool it can
/// use with whether it works right now.
struct CapabilitiesTool: Tool {
    let name = "bridge_capabilities"
    let title = "DroidHands capabilities"
    let description = "Describes this DroidHands connection: the access the user gave this agent (what it may and may not do, whether actions ask the user first, where the user can change it), per-tool rules, the iPhone's status (whether the DroidHands app is on screen, which permissions are on), and every tool this agent can use with whether it is available now and why not. Call it when unsure which tools work or after a tool reports it is unavailable."
    let scope = ""
    let risk = Risk.READ
    let inputSchema = Schema.empty
    let tools: () -> [Tool]

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let t = ctx.token
        let onScreen = await Foreground.isActive
        var permissions = JSONObject()
        for p in Permission.allCases {
            let s: String
            switch p.status {
            case .granted: s = "allowed"
            case .notAsked: s = "not asked yet"
            case .limited: s = "limited"
            case .denied: s = "off"
            }
            permissions[p.rawValue] = .string(s)
        }
        let visible = tools().filter { McpServer.isVisible($0, to: t) }
        let list: [JSON] = visible.map { tool in
            let reason = tool.unavailableReason()
            return .object(JSONObject([
                ("name", .string(tool.name)), ("title", .string(tool.title)), ("risk", .string(tool.risk.rawValue)),
                ("scope", .string(tool.scope)), ("rule", t.toolRules[tool.name].map { .string($0.rawValue) }),
                ("available", .bool(reason == nil)), ("unavailable_reason", reason.map { .string($0) }),
            ]))
        }
        return .json(.object(JSONObject([
            ("token", .object(JSONObject([
                ("name", .string(t.name)),
                ("scopes", .strings(t.scopes.sorted())),
                ("access", AccessSummary.json(t)),
                ("tool_rules", .object(JSONObject(t.toolRules.sorted { $0.key < $1.key }.map { ($0.key, .string($0.value.rawValue)) }))),
                ("max_risk", t.maxRisk.map { .string($0.rawValue) } ?? .null),
            ]))),
            ("status", .object(JSONObject([
                ("platform", "ios"),
                ("app_on_screen", .bool(onScreen)),
                ("note", onScreen ? nil : "DroidHands is in the background: tools that open links, show screens, use the flashlight, speak or read the location fail until the user opens it."),
                ("permissions", .object(permissions)),
            ]))),
            ("tools", .array(list)),
        ])))
    }
}

enum ToolCatalog {
    /// Every tool of the iPhone app, in the order agents see them.
    static func all() -> [Tool] {
        var tools: [Tool] = [
            DeviceStatusTool(), FlashlightTool(), ClipboardTool(), SpeakTool(),
            UrlOpenTool(), ShortcutRunTool(),
            ContactsSearchTool(),
            SmsSendTool(), PhoneCallTool(), CallStatusTool(),
            CalendarListTool(), CalendarEventsListTool(), CalendarEventCreateTool(),
            RemindersListTool(), ReminderCreateTool(), ReminderCompleteTool(),
            LocationTool(),
        ]
        var box: [Tool] = []
        tools.insert(CapabilitiesTool(tools: { box }), at: 0)
        box = tools
        return tools
    }
}
