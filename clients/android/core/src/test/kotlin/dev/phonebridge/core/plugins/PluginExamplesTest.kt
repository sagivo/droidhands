package dev.phonebridge.core.plugins

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The examples runner (docs/PLUGINS.md §7), through [PluginLoader]. */
class PluginExamplesTest {
    private fun plugin(action: String) = """
        id: demo
        name: Demo
        description: A demo app.
        category: other
        android:
          packages: [com.demo.app, com.demo.lite]
        actions:
    """.trimIndent() + "\n" + action.trimIndent().prependIndent("  ") + "\n"

    private fun errors(yaml: String) = PluginLoader.load(yaml, folder = "demo").errors

    private fun assertPasses(yaml: String) = assertEquals(emptyList(), errors(yaml))

    private fun assertFails(yaml: String, expected: String) {
        val errors = errors(yaml)
        assertTrue(errors.any { expected in it }, "expected '$expected' in:\n${errors.joinToString("\n")}")
    }

    private val intentAction = """
        - id: share_text
          title: Share text
          description: Opens the share screen.
          risk: action
          params:
            text: {description: Text, required: true}
            count: {type: integer, description: Count}
          android:
            intent:
              action: android.intent.action.SEND
              type: text/plain
              class: .Share
              categories: [android.intent.category.DEFAULT]
              extras:
                android.intent.extra.TEXT: "{{text}}"
                count: "{{count}}"
                show_ui: true
          examples:
    """.trimIndent()

    private fun intentExample(params: String, expect: String) =
        plugin(intentAction + "\n" + "- params: $params\n  expect:\n${expect.trimIndent().prependIndent("    ")}".prependIndent("    "))

    @Test
    fun intentExamplesCompareListedKeysOnly() {
        assertPasses(intentExample("{text: hi}", "intent: {action: android.intent.action.SEND}"))
        assertPasses(
            intentExample(
                "{text: hi, count: 3}",
                """
                intent:
                  action: android.intent.action.SEND
                  package: com.demo.app
                  type: text/plain
                  class: .Share
                  categories: [android.intent.category.DEFAULT]
                  extras: {android.intent.extra.TEXT: hi, count: 3, show_ui: true}
                """,
            ),
        )
        // The full class name works too, and a null extra means "absent".
        assertPasses(intentExample("{text: hi}", "intent: {class: com.demo.app.Share, extras: {count: null, show_ui: true}}"))
        assertFails(intentExample("{text: hi}", "intent: {package: com.demo.lite}"), "package: expected com.demo.lite, got com.demo.app")
        assertFails(intentExample("{text: hi}", "intent: {data: \"https://x\"}"), "data: expected https://x, got none")
        assertFails(intentExample("{text: hi}", "intent: {categories: []}"), "categories: expected [], got [android.intent.category.DEFAULT]")
        assertFails(intentExample("{text: hi, count: 3}", "intent: {extras: {count: \"3\"}}"), "extras.count: expected \"3\" (text), got 3 (int)")
        assertFails(intentExample("{text: hi}", "intent: {extras: {count: 3}}"), "extras.count: expected 3 (number), but the intent has no such extra")
        assertFails(intentExample("{text: hi}", "intent: {extras: {show_ui: null}}"), "extras.show_ui: expected no extra, got true (boolean)")
        assertFails(intentExample("{text: hi}", "intent: {action: 5}"), "action: write the expected value as text")
    }

    @Test
    fun failuresNameTheExample() {
        val errors = errors(intentExample("{text: hi}", "intent: {package: com.demo.lite}"))
        assertEquals(listOf("demo: actions[0].examples[0]: package: expected com.demo.lite, got com.demo.app"), errors)
    }

    @Test
    fun errorExamples() {
        assertPasses(intentExample("{}", "error: TEXT"))
        assertPasses(intentExample("{text: hi, color: red}", "error: Unknown parameter 'color'"))
        assertFails(intentExample("{}", "error: count"), "expected an error containing 'count', got: Missing required parameter 'text'")
        assertFails(intentExample("{text: hi}", "error: text"), "expected an error containing 'text', but the action rendered fine")
        assertFails(intentExample("{}", "intent: {action: x}"), "failed: Missing required parameter 'text'")
        assertFails(intentExample("{text: hi}", "url: \"https://x\""), "expects url, but the action's recipe is intent")
    }

    @Test
    fun urlExamplesCompareTheWholeUrl() {
        val yaml = plugin(
            """
            - id: directions
              title: Directions
              description: Opens directions.
              risk: action
              params:
                destination: {description: Where, required: true}
                mode: {description: How, enum: [driving, walking], default: driving}
              android:
                url: "https://www.google.com/maps/dir/?api=1&destination={{destination}}&travelmode={{mode}}"
              examples:
                - params: {destination: Eiffel Tower}
                  expect: {url: "https://www.google.com/maps/dir/?api=1&destination=Eiffel%20Tower&travelmode=driving"}
                - params: {}
                  expect: {error: destination}
            """,
        )
        assertPasses(yaml)
        assertFails(yaml.replace("Eiffel%20Tower", "Eiffel Tower"), "url: expected https://www.google.com/maps/dir/?api=1&destination=Eiffel Tower")
    }

    @Test
    fun toolExamplesCompareNameAndAllArgs() {
        val yaml = plugin(
            """
            - id: alarm
              title: Set an alarm
              description: Sets an alarm.
              params:
                hour: {type: integer, description: Hour, required: true, minimum: 0, maximum: 23}
                label: {description: Label}
              android:
                tool:
                  name: alarm_set
                  args: {hour: "{{hour}}", minute: 0, label: "{{label}}"}
              examples:
                - params: {hour: 7}
                  expect: {tool: {name: alarm_set, args: {hour: 7, minute: 0}}}
                - params: {hour: "7", label: Gym}
                  expect: {tool: {args: {hour: 7.0, minute: 0, label: Gym}}}
                - params: {hour: 24}
                  expect: {error: at most 23}
            """,
        )
        assertPasses(yaml)
        assertFails(yaml.replace("args: {hour: 7, minute: 0}}", "args: {hour: \"7\", minute: 0}}"), "args: expected {\"hour\":\"7\",\"minute\":0}, got {\"hour\":7,\"minute\":0}")
        assertFails(yaml.replace("name: alarm_set, args", "name: timer_set, args"), "name: expected timer_set, got alarm_set")
    }

    @Test
    fun stepExamplesCompareEveryRenderedStep() {
        val yaml = plugin(
            """
            - id: search
              title: Search
              description: Searches in the app.
              risk: action
              params:
                query: {description: Query, required: true}
              android:
                steps:
                  - launch: true
                  - tap: {desc: Search}
                  - type: {into: {id: search_src_text}, text: "{{query}}", submit: true}
                  - wait_for: {text_contains: "{{query}}", timeout_ms: 8000}
              examples:
                - params: {query: daft punk}
                  expect:
                    steps:
                      - launch: true
                      - tap: {desc: Search}
                      - type: {into: {id: "com.demo.app:id/search_src_text"}, text: daft punk, submit: true}
                      - wait_for: {text_contains: daft punk, timeout_ms: 8000}
            """,
        )
        assertPasses(yaml)
        assertFails(yaml.replace("text: daft punk, submit", "text: daft, submit"), "steps[2]: expected")
        val lastExpected = yaml.lines().last { "wait_for: {text_contains: daft punk" in it }
        assertFails(yaml.replace(lastExpected + "\n", ""), "steps[3]: expected no more steps")
        val firstExpected = yaml.lines().last { "- launch: true" in it }
        assertFails(yaml.replace(firstExpected, firstExpected.replace("true", "maybe")), "expect.steps")
    }

    @Test
    fun iosExamplesRenderTheIosRecipeForTheFirstBundleId() {
        val yaml = plugin(
            """
            - id: search
              title: Search
              description: Searches in the app.
              risk: action
              params:
                query: {description: Query, required: true}
              android:
                steps:
                  - launch: true
                  - type: {into: {id: search_src_text}, text: "{{query}}", submit: true}
              ios:
                steps:
                  - launch: true
                  - type: {into: {id: SearchField}, text: "{{query}}", submit: true}
              examples:
                - params: {query: daft punk}
                  expect:
                    steps:
                      - launch: true
                      - type: {into: {id: search_src_text}, text: daft punk, submit: true}
                - os: ios
                  params: {query: daft punk}
                  expect:
                    steps:
                      - launch: true
                      - type: {into: {id: SearchField}, text: daft punk, submit: true}
                - os: ios
                  params: {}
                  expect: {error: query}
            """,
        ).replace("actions:", "ios:\n  bundle_ids: [com.demo.ios, com.demo.ipad]\nactions:")
        assertPasses(yaml)
        // The expected steps are read with the iOS rules too.
        val back = yaml.lines().last { "type: {into: {id: SearchField}, text: daft punk" in it }
        assertFails(yaml.replace(back, back.substringBefore("-") + "- back: true"), "expect.steps")
        assertFails(yaml.replace("{id: SearchField}, text: daft punk", "{id: Search}, text: daft punk"), "actions[0].examples[1]: steps[1]: expected")
        val url = plugin(
            """
            - id: open_chat
              title: Open a chat
              description: Opens a chat.
              risk: action
              params:
                phone: {description: Number, required: true}
              ios:
                url: "demo://send?phone={{phone}}"
              examples:
                - os: ios
                  params: {phone: "+1 555"}
                  expect: {url: "demo://send?phone=%2B1%20555"}
            """,
        ).replace("actions:", "ios:\n  bundle_ids: [com.demo.ios]\nactions:")
        assertPasses(url)
        assertFails(url.replace("expect: {url: \"demo://send?phone=%2B1%20555\"}", "expect: {intent: {action: x}}"), "expects intent, but the action's recipe is url")
    }

    @Test
    fun examplesRunOnlyWhenEverythingElseIsValid() {
        // A broken example next to a validation problem: only the validation problem is reported.
        val yaml = intentExample("{text: hi}", "intent: {package: com.demo.lite}").replace("risk: action", "risk: action\n    scope: calls")
        val errors = errors(yaml)
        assertTrue(errors.any { "must be sensitive for scope calls" in it }, errors.toString())
        assertTrue(errors.none { "examples[0]" in it }, errors.toString())
        // Runs can be turned off.
        assertEquals(emptyList(), PluginLoader.load(intentExample("{text: hi}", "intent: {package: x.y}"), folder = "demo", runExamples = false).errors)
    }
}
