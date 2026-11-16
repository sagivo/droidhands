package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.Risk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Every rule of docs/PLUGINS.md §3-§9, through [PluginLoader] (parser, validator and examples). */
class PluginValidationTest {
    private val header = """
        id: demo
        name: Demo
        description: A demo app.
        category: other
        android:
          packages: [com.demo.app, com.demo.lite]
    """.trimIndent()

    private val search = """
        - id: search
          title: Search
          description: Opens search results.
          risk: action
          params:
            query: {description: Query, required: true}
          android:
            url: "https://demo.com/s?q={{query}}"
    """.trimIndent()

    private fun doc(actions: String = search, header: String = this.header) = header + "\nactions:\n" + actions.trimIndent().prependIndent("  ") + "\n"

    /** An action with [body] lines (fields at the action's indentation), e.g. "risk: action\nandroid: …". */
    private fun action(body: String, id: String = "act") =
        "- id: $id\n  title: Do it\n  description: Does it.\n" + body.trimIndent().prependIndent("  ")

    private fun load(yaml: String, folder: String? = "demo") = PluginLoader.load(yaml, folder = folder, label = "plugins/demo/plugin.yaml")

    private fun assertValid(yaml: String, folder: String? = "demo"): AppPlugin {
        val result = load(yaml, folder)
        return result.plugin ?: fail("expected valid, got:\n${result.errors.joinToString("\n")}\n---\n$yaml")
    }

    private fun assertProblem(yaml: String, expected: String, folder: String? = "demo") {
        val result = load(yaml, folder)
        assertNull(result.plugin, "expected '$expected' for:\n$yaml")
        assertTrue(result.errors.any { expected in it }, "expected '$expected' in:\n${result.errors.joinToString("\n")}\n---\n$yaml")
    }

    @Test
    fun theBaseDocumentIsValid() {
        val plugin = assertValid(doc())
        assertEquals("demo", plugin.id)
        assertEquals(PluginStatus.EXPERIMENTAL, plugin.status)
        assertEquals(listOf("com.demo.app", "com.demo.lite"), plugin.androidPackages)
        val search = plugin.actions.single()
        assertEquals(Risk.ACTION, search.risk)
        assertEquals(UrlRecipe("https://demo.com/s?q={{query}}"), search.android)
        assertEquals(false, search.completesTask)
        assertEquals(PluginSource.BUNDLED, plugin.source)
    }

    @Test
    fun errorsNameThePluginAndThePath() {
        val result = load(doc(action("risk: high\nandroid: {url: \"https://x\"}")))
        assertEquals(listOf("demo: actions[0].risk: 'high' is not a risk; use read, action or sensitive"), result.errors)
        // Before the id is known, the file label is used.
        assertEquals(listOf("plugins/demo/plugin.yaml: a plugin file must be a map of fields (id, name, description, category, android, actions)"), load("- a\n").errors)
        assertTrue(load("a: [1\n").errors.single().startsWith("plugins/demo/plugin.yaml: not valid YAML"))
    }

    @Test
    fun independentProblemsAreReportedTogether() {
        val yaml = doc(action("risk: action\nandroid: {url: \"https://x/{{nope}}\"}", id = "send_it")).replace("description: A demo app.", "descripton: typo\ndescription: A demo app.")
        val errors = load(yaml, folder = "other").errors
        assertTrue(errors.any { "descripton: unknown field (did you mean 'description'?)" in it }, errors.toString())
        assertTrue(errors.any { "'demo' must equal the folder name 'other'" in it }, errors.toString())
        assertTrue(errors.any { "{{nope}} is not declared in params" in it }, errors.toString())
        assertTrue(errors.any { "the id says 'send'" in it }, errors.toString())
    }

    @Test
    fun pluginFields() {
        assertProblem(doc().replace("id: demo", "id: Demo"), "id: 'Demo' is not a valid plugin id")
        assertProblem(doc().replace("id: demo", "id: d"), "is not a valid plugin id")
        assertProblem(doc(), "'demo' must equal the folder name 'other'", folder = "other")
        assertValid(doc(), folder = "_template")
        assertValid(doc(), folder = null)
        assertProblem(doc().replace("name: Demo", "name: ${"x".repeat(61)}"), "name: is 61 characters long; the limit is 60")
        assertProblem(doc().replace("description: A demo app.", "description: ${"x".repeat(301)}"), "description: is 301 characters long; the limit is 300")
        assertProblem(doc().replace("category: other", "category: games"), "category: 'games' is not a category")
        assertProblem(doc().replace("id: demo", "id: demo\nhomepage: http://demo.com"), "homepage: must be an https:// URL")
        assertValid(doc().replace("id: demo", "id: demo\nhomepage: https://demo.com"))
        assertProblem(doc().replace("id: demo", "id: demo\nstatus: alpha"), "status: 'alpha' is not a status")
        assertProblem(doc().replace("id: demo", "id: demo\nmaintainers: [-bad-]"), "maintainers[0]: '-bad-' is not a GitHub handle")
        assertValid(doc().replace("id: demo", "id: demo\nmaintainers: [octocat, \"@someone\"]"))
        assertProblem(doc().replace("id: demo", "id: demo\nmaintainers: octocat"), "maintainers: must be a list")
        assertProblem(doc().replace("id: demo", "id: demo\ntested: [x]"), "tested[0]: must be a map")
        assertProblem(doc().replace("id: demo", "id: demo\ntested: [{who: me}]"), "tested[0].who: unknown field")
        assertProblem(doc().replace("id: demo", "id: demo\nnmae: x"), "nmae: unknown field (did you mean 'name'?)")
        assertProblem(doc().replace("id: demo", "id: demo\nscreenshots: x.png"), "screenshots: unknown field (allowed: id, name,")
        assertProblem(doc().replace("id: demo", "id: demo\nios: {bundle_ids: [com.demo.ios], anything: [1]}"), "ios.anything: unknown field (allowed: bundle_ids)")
        assertProblem(doc().replace("category: other", "category: 7"), "category: must be text (quote it), found 7 (a number)")
        assertProblem(doc().replace("name: Demo", "name: \"  \""), "name: must not be empty")
        assertProblem(doc().replace("name: Demo\n", ""), "name: required")
    }

    @Test
    fun androidPackages() {
        val noAndroid = doc().replace("android:\n  packages: [com.demo.app, com.demo.lite]\n", "")
        assertProblem(noAndroid, "android.packages: required: the package names of the app")
        assertProblem(doc().replace("packages: [com.demo.app, com.demo.lite]", "packages: []"), "android.packages: list at least one package name")
        assertProblem(doc().replace("[com.demo.app, com.demo.lite]", "[Not A Package]"), "android.packages[0]: 'Not A Package' is not an Android package name")
        assertProblem(doc().replace("[com.demo.app, com.demo.lite]", "[com.demo.app, com.demo.app]"), "'com.demo.app' is listed twice")
        assertProblem(doc().replace("[com.demo.app, com.demo.lite]", "[com.demo.app, dev.phonebridge.android]"), "android.packages[1]: plugins can't target PhoneBridge itself")
        assertProblem(doc().replace("[com.demo.app, com.demo.lite]", "[dev.phonebridge.android.debug]"), "can't target PhoneBridge itself")
        assertProblem(doc().replace("[com.demo.app, com.demo.lite]", "[dev.phonebridge]"), "can't target PhoneBridge itself")
        // The emulator-only E2E fixture apps are not PhoneBridge: test plugins may target them.
        assertValid(doc().replace("[com.demo.app, com.demo.lite]", "[dev.phonebridge.fixture.mail]"))
        assertProblem(doc().replace("android:\n  packages: [com.demo.app, com.demo.lite]", "android:\n  package: com.demo.app"), "android.package: unknown field (did you mean 'packages'?)")
    }

    @Test
    fun actionsList() {
        assertProblem(header + "\n", "actions: required")
        assertProblem(header + "\nactions: []\n", "actions: must have 1 to 30 actions (found 0)")
        val many = (1..31).joinToString("\n") { action("risk: action\nandroid: {url: \"https://x\"}", id = "a$it") }
        assertProblem(doc(many), "actions: must have 1 to 30 actions (found 31)")
        assertProblem(doc("$search\n$search"), "actions[1].id: duplicate action id 'search'")
        assertProblem(doc("- just text"), "actions[0]: each action must be a map")
    }

    @Test
    fun actionFields() {
        val ok = "risk: action\nandroid: {url: \"https://x\"}"
        assertProblem(doc(action(ok, id = "Search")), "actions[0].id: 'Search' is not a valid action id")
        assertValid(doc(action(ok, id = "a")))
        assertProblem(doc(action(ok).replace("title: Do it", "title: ${"x".repeat(61)}")), "actions[0].title: is 61 characters long; the limit is 60")
        assertProblem(doc(action(ok).replace("description: Does it.", "description: ${"x".repeat(601)}")), "actions[0].description: is 601 characters long; the limit is 600")
        assertProblem(doc(action("$ok\nkeywords: [${(1..21).joinToString(", ") { "k$it" }}]")), "actions[0].keywords: at most 20 keywords")
        assertProblem(doc(action("$ok\nkeywords: [${"k".repeat(41)}]")), "actions[0].keywords[0]: keywords must be at most 40 characters")
        assertProblem(doc(action("android: {url: \"https://x\"}")), "actions[0].risk: required: read, action or sensitive")
        assertProblem(doc(action("$ok\nscope: screen.control")), "actions[0].scope: 'screen.control' is not allowed")
        assertValid(doc(action("risk: action\nscope: location\nandroid: {url: \"https://x\"}")))
        assertProblem(doc(action("$ok\nconfirm: ${"x".repeat(301)}")), "actions[0].confirm: is 301 characters long; the limit is 300")
        assertProblem(doc(action("$ok\ncompletes: yes")), "actions[0].completes: must be true or false, found text \"yes\"")
        assertProblem(doc(action("risk: action")), "actions[0]: has no recipe: add an 'android' section")
        assertProblem(doc("$search\n" + action("risk: action\nios: {shortcut: x}")), "actions[1].ios.shortcut: unknown field (allowed: url, steps, tool)")
        assertProblem(doc(action("$ok\nconfrim: x")), "actions[0].confrim: unknown field (did you mean 'confirm'?)")
        assertProblem(doc(action("$ok\nconfirm: \"Open {{nope}}\"")), "actions[0].confirm: {{nope}} is not declared in params (the action has no params)")
        assertProblem(doc(action("risk: action\nandroid: {url: \"https://x\", intent: {action: a.B}}")), "needs exactly one recipe: intent, url, steps or tool (found intent, url)")
        assertProblem(doc(action("risk: action\nandroid: {}")), "(found none)")
        assertProblem(doc(action("risk: action\nandroid: {uri: \"https://x\"}")), "actions[0].android.uri: unknown field (did you mean 'url'?)")
    }

    @Test
    fun parameters() {
        fun withParams(params: String) = doc(action("risk: action\nparams:\n" + params.trimIndent().prependIndent("  ") + "\nandroid: {url: \"https://x\"}"))
        assertValid(withParams("q: {description: Q}"))
        assertProblem(withParams((1..11).joinToString("\n") { "p$it: {description: P}" }), "actions[0].params: at most 10 parameters (found 11)")
        assertProblem(withParams("Query: {description: Q}"), "actions[0].params.Query: 'Query' is not a valid parameter name")
        assertProblem(withParams("q: Query text"), "actions[0].params.q: must be a map")
        assertProblem(withParams("q: {description: Q, requird: true}"), "actions[0].params.q.requird: unknown field (did you mean 'required'?)")
        assertProblem(withParams("q: {required: true}"), "actions[0].params.q.description: required")
        assertProblem(withParams("q: {description: Q, type: text}"), "actions[0].params.q.type: 'text' is not a type")
        assertProblem(withParams("q: {description: Q, type: integer, min_length: 1}"), "params.q.min_length: only applies to string parameters")
        assertProblem(withParams("q: {description: Q, pattern: x, type: boolean}"), "params.q.pattern: only applies to string parameters")
        assertProblem(withParams("q: {description: Q, minimum: 1}"), "params.q.minimum: only applies to integer and number parameters")
        assertProblem(withParams("q: {description: Q, max_length: 10001}"), "params.q.max_length: must be 1 to 10000")
        assertProblem(withParams("q: {description: Q, min_length: 5, max_length: 4}"), "params.q.min_length: is larger than max_length")
        assertProblem(withParams("q: {description: Q, min_length: 2001}"), "params.q.min_length: is larger than max_length")
        assertProblem(withParams("q: {description: Q, type: number, minimum: 5, maximum: 4}"), "params.q.minimum: is larger than maximum")
        assertProblem(withParams("q: {description: Q, type: number, minimum: \"5\"}"), "params.q.minimum: must be a number")
        assertProblem(withParams("q: {description: Q, pattern: \"[a-\"}"), "params.q.pattern: not a valid regular expression")
        assertProblem(withParams("q: {description: Q, enum: []}"), "params.q.enum: list at least one value")
        assertProblem(withParams("q: {description: Q, enum: [a, 2]}"), "params.q.enum[1]: must be a string (quote it), found 2 (a number)")
        assertProblem(withParams("q: {description: Q, type: integer, enum: [1, 2.5]}"), "params.q.enum[1]: must be a integer")
        assertProblem(withParams("q: {description: Q, type: boolean, enum: [true]}"), "params.q.enum: boolean parameters can't have an enum")
        assertProblem(withParams("q: {description: Q, type: integer, default: \"5\"}"), "params.q.default: must be a integer, found text \"5\"")
        assertProblem(withParams("q: {description: Q, required: true, default: x}"), "params.q.default: a required parameter can't have a default")
        assertProblem(withParams("q: {description: Q, enum: [a, b], default: c}"), "params.q.default: the default is not a valid value: it must be one of a, b")
        assertProblem(withParams("q: {description: Q, type: integer, maximum: 5, default: 6}"), "the default is not a valid value: it must be at most 5")
        val plugin = assertValid(withParams("n: {description: N, type: number, enum: [1, 2.5], default: 1}"))
        assertEquals(2, plugin.actions.single().params.single().enum!!.size)
    }

    @Test
    fun intentRecipes() {
        fun intent(body: String, params: String = "", risk: String = "action") =
            doc(action("risk: $risk\n$params\nandroid:\n  intent:\n" + body.trimIndent().prependIndent("    ")))
        val text = "params: {text: {description: T}}"
        assertValid(intent("action: android.intent.action.SEND\ntype: text/plain\nclass: .Share\ncategories: [android.intent.category.DEFAULT]\nextras:\n  android.intent.extra.TEXT: \"{{text}}\"", text))
        assertValid(intent("action: a.B\nclass: com.demo.lite.Main"))
        assertProblem(intent("type: text/plain"), "actions[0].android.intent.action: required")
        assertProblem(intent("action: a.B\nflags: 1"), "actions[0].android.intent.flags: unknown field")
        for (call in listOf("android.intent.action.CALL", "android.intent.action.CALL_PRIVILEGED", "android.intent.action.CALL_EMERGENCY")) {
            assertProblem(intent("action: $call\ndata: \"tel:123\""), "actions[0].android.intent.action: call actions are not allowed")
        }
        assertProblem(intent("action: \"{{text}}\"", text), "android.intent.action: templates are not allowed in this field")
        assertProblem(intent("action: a.B\ntype: \"{{text}}\"", text), "android.intent.type: templates are not allowed")
        assertProblem(intent("action: a.B\ncategories: [\"{{text}}\"]", text), "android.intent.categories[0]: templates are not allowed")
        assertProblem(intent("action: a.B\nclass: \".{{text}}\"", text), "android.intent.class: templates are not allowed")
        assertProblem(intent("action: a.B\nextras: {\"{{text}}\": x}", text), "templates are not allowed in extra names")
        assertProblem(intent("action: a.B\nclass: com.other.Main"), "android.intent.class: 'com.other.Main' is outside the plugin's packages")
        assertProblem(intent("action: a.B\ndata: \"{{text}}:x\"", text), "android.intent.data: the URL scheme must be literal")
        assertProblem(intent("action: a.B\ndata: \"content://x/{{text}}\"", text), "android.intent.data: the 'content:' scheme is not allowed")
        assertProblem(intent("action: a.B\nextras: {n: {type: float, value: 1}}"), "extras.n.type: 'float' is not an extra type")
        assertProblem(intent("action: a.B\nextras: {n: {type: string_array, value: x}}"), "extras.n.value: a string_array needs a list")
        assertProblem(intent("action: a.B\nextras: {n: {type: int, value: [1]}}"), "extras.n.value: must be a single value for type int")
        assertProblem(intent("action: a.B\nextras: {n: {type: int}}"), "extras.n.value: required")
        assertProblem(intent("action: a.B\nextras: {n: {type: int, value: 1, extra: 2}}"), "extras.n.extra: unknown field")
        assertProblem(intent("action: a.B\nextras: {n: null}"), "extras.n: needs a value")
        assertProblem(intent("action: a.B\nextras: {n: \"{{nope}}\"}"), "extras.n: {{nope}} is not declared in params")
        assertProblem(intent("action: a.B\nextras: {n: {type: int, value: \"{{nope}}\"}}"), "extras.n.value: {{nope}} is not declared")
        assertProblem(intent("action: a.B\nextras: {n: [a, [b]]}"), "extras.n[1]: list items must be single values")
        assertProblem(intent("action: a.B\nextras: {n: {a: b}}"), "extras.n.a: unknown field")
    }

    @Test
    fun urlRecipes() {
        fun url(u: String) = doc(action("risk: action\nparams: {q: {description: Q}}\nandroid: {url: \"$u\"}"))
        assertValid(url("geo:0,0?q={{q}}"))
        assertProblem(url("https://x/{{q}"), "actions[0].android.url: '{{' without a closing '}}'")
        assertProblem(url("https://x/q}}"), "actions[0].android.url: '}}' without an opening '{{'")
        assertProblem(url("{{q}}"), "a URL needs a literal scheme")
        assertProblem(url("{{q}}:x"), "the URL scheme must be literal")
        assertProblem(url("javascript:alert({{q}})"), "the 'javascript:' scheme is not allowed")
        assertProblem(url("Intent:#x"), "the 'intent:' scheme is not allowed")
        assertProblem(url("https://x/{{ Q }}"), "is not a parameter placeholder")
    }

    @Test
    fun toolRecipes() {
        fun tool(body: String, extra: String = "") =
            doc(action("$extra\nparams: {to: {description: T}, text: {description: M, required: true}}\nandroid:\n  tool:\n" + body.trimIndent().prependIndent("    ")))
        val plugin = assertValid(tool("name: sms_send\nargs: {to: \"{{to}}\", message: \"{{text}}\"}"))
        val recipe = plugin.actions.single().android as ToolRecipe
        assertEquals("sms_send", recipe.name)
        assertNull(plugin.actions.single().risk)
        assertEquals(true, plugin.actions.single().completesTask)
        assertProblem(tool("name: sms_send", extra = "risk: sensitive"), "actions[0].risk: remove it: a tool recipe takes the built-in tool's risk")
        assertProblem(tool("name: sms_send", extra = "scope: apps"), "actions[0].scope: remove it: a tool recipe takes the built-in tool's scope")
        assertProblem(tool("name: nope_tool"), "android.tool.name: 'nope_tool' is not a PhoneBridge tool")
        assertProblem(tool("name: intent_start"), "use an intent recipe, which is pinned to the app")
        assertProblem(tool("name: ui_tap"), "use a steps recipe, which is confined to the app")
        assertProblem(tool("name: screen_read"), "use a steps recipe")
        assertProblem(tool("name: app_action_run"), "plugin actions can't list or run other actions")
        assertProblem(tool("name: sms_send\nargs: {to: {a: b}}"), "android.tool.args.to: must be text, a number, a boolean or a list of those")
        assertProblem(tool("name: sms_send\nargs: {Bad-Name: x}"), "'Bad-Name' is not a valid argument name")
        assertProblem(tool("name: sms_send\nargs: {to: \"{{nope}}\"}"), "android.tool.args.to: {{nope}} is not declared in params")
        assertProblem(tool("args: {}"), "android.tool.name: required")
        // The validator can be given another platform's tool list.
        val result = PluginLoader.load(tool("name: phone_call"), folder = "demo", toolNames = setOf("sms_send"))
        assertTrue(result.errors.any { "'phone_call' is not a PhoneBridge tool" in it }, result.errors.toString())
    }

    @Test
    fun stepRecipes() {
        fun steps(body: String, risk: String = "action", params: String = "params: {q: {description: Q}}") =
            doc(action("risk: $risk\n$params\nandroid:\n  steps:\n" + body.trimIndent().prependIndent("    ")))
        val plugin = assertValid(
            steps(
                """
                - launch: true
                - launch: {fresh: true}
                - open_url: "demo://search/{{q}}"
                - intent: {action: a.VIEW, class: .Main}
                - wait_for: {text_contains: "{{q}}", timeout_ms: 8000}
                - tap: Search
                - long_press: {id: row, index: 1}
                - type: {into: {id: search_src_text}, text: "{{q}}", submit: true}
                - scroll: {direction: down, in: {class: RecyclerView}, until: {text: End}, max: 10}
                - if_present: {text: "Not now"}
                  then:
                    - tap: {text: "Not now"}
                  optional: true
                - back: true
                - sleep: 500
                - expect: {id: "com.demo.app:id/now_playing"}
                  fail: "It did not start."
                - expect: {id: "android:id/button1", desc_contains: ok, clickable: true}
                """,
            ),
        )
        val recipe = plugin.actions.single().android as StepsRecipe
        assertEquals(14, recipe.steps.size)
        assertEquals(Step(StepAction.Launch(fresh = true)), recipe.steps[1])
        assertEquals(Step(StepAction.Tap(Selector(textOrDesc = "Search"))), recipe.steps[5])
        // As in docs/PLUGINS.md §6.4, step modifiers may sit inside a selector step's map.
        assertEquals(Step(StepAction.WaitFor(Selector(textContains = "{{q}}")), timeoutMs = 8000), recipe.steps[4])
        assertEquals(true, recipe.steps[9].optional)
        assertEquals(true, plugin.actions.single().completesTask)
        assertEquals(Step(StepAction.Expect(Selector(id = "com.demo.app:id/now_playing"), "It did not start.")), recipe.steps[12])

        assertProblem(steps("launch: true"), "android.steps: must be a list of steps")
        assertProblem(steps("- {launch: true, back: true}"), "steps[0]: needs exactly one of launch, open_url")
        assertProblem(steps("- {optional: true}"), "(found none)")
        assertProblem(steps("- launch: false"), "steps[0].launch: use 'launch: true'")
        assertProblem(steps("- back: false"), "steps[0].back: use 'back: true'")
        assertProblem(steps("- sleep: 5000"), "steps[0].sleep: must be 0 to 3000 ms")
        assertProblem(steps("- {tap: x, timeout_ms: 20000}"), "steps[0].timeout_ms: must be 1 to 15000")
        assertProblem(steps("- {wait_for: {text: x, timeout_ms: 20000}}"), "steps[0].timeout_ms: must be 1 to 15000")
        assertProblem(steps("- {wait_for: {text: x, timeout_ms: 1}, timeout_ms: 2}"), "steps[0].wait_for.timeout_ms: is also set on the step")
        assertProblem(steps("- {wait_for: {timeout_ms: 1}}"), "steps[0].wait_for: say what to match")
        assertProblem(steps("- {tap: x, fail: oops}"), "steps[0].fail: only goes with expect")
        assertProblem(steps("- {tap: x, then: []}"), "steps[0].then: only goes with if_present")
        assertProblem(steps("- if_present: x"), "steps[0].then: if_present needs 'then'")
        assertProblem(steps("- if_present: x\n  then:\n    - if_present: y\n      then: [{back: true}]"), "steps[0].then[0].if_present: if_present can't be nested")
        assertProblem(steps("- tap: {}"), "steps[0].tap: say what to match")
        assertProblem(steps("- tap: \"\""), "steps[0].tap: a selector can't be empty")
        assertProblem(steps("- tap: {txt: x}"), "steps[0].tap.txt: unknown field (did you mean 'text'?)")
        assertProblem(steps("- tap: {text: x, index: 99}"), "steps[0].tap.index: must be 0 to 50")
        assertProblem(steps("- tap: {id: \"com.other:id/x\"}"), "steps[0].tap.id: 'com.other:id/x' belongs to another app")
        assertProblem(steps("- tap: {id: \"a:b\"}"), "steps[0].tap.id: 'a:b' is not a view id")
        assertProblem(steps("- tap: {id: \"{{q}}\"}"), "steps[0].tap.id: templates are not allowed")
        assertProblem(steps("- tap: {text: \"{{nope}}\"}"), "steps[0].tap.text: {{nope}} is not declared")
        assertProblem(steps("- scroll: {direction: sideways}"), "steps[0].scroll.direction: 'sideways' is not a direction")
        assertProblem(steps("- scroll: {direction: up, max: 50}"), "steps[0].scroll.max: must be 1 to 20")
        assertProblem(steps("- type: {submit: true}"), "steps[0].type.text: required")
        assertProblem(steps("- open_url: \"{{q}}\""), "steps[0].open_url: a URL needs a literal scheme")
        assertProblem(steps("- intent: {action: android.intent.action.CALL}"), "steps[0].intent.action: call actions are not allowed")
        assertProblem(steps((1..41).joinToString("\n") { "- back: true" }), "android.steps: at most 40 steps in total, nested ones included (found 41)")
        // Nested steps count too: 20 × (if_present + 1) = 40 is the limit.
        assertValid(steps((1..20).joinToString("\n") { "- if_present: x\n  then: [{back: true}]" }))
        assertProblem(steps((1..21).joinToString("\n") { "- if_present: x\n  then: [{back: true}]" }), "(found 42)")
    }

    @Test
    fun riskFloors() {
        val url = "android: {url: \"https://x\"}"
        assertProblem(doc(action("risk: action\nscope: messages.send\n$url")), "actions[0].risk: must be sensitive for scope messages.send")
        assertProblem(doc(action("risk: read\nscope: calls\n$url")), "actions[0].risk: must be sensitive for scope calls")
        assertValid(doc(action("risk: sensitive\nscope: messages.send\n$url")))
        assertProblem(doc(action("risk: action\n$url", id = "send_message")), "actions[0].risk: must be sensitive: the id says 'send'")
        assertProblem(doc(action("risk: action\n$url").replace("title: Do it", "title: Order food")), "the title says 'order'")
        assertProblem(doc(action("risk: action\nconfirm: Pay now\n$url")), "the confirm says 'pay'")
        assertValid(doc(action("risk: sensitive\n$url", id = "book_ride")))
        assertValid(doc(action("risk: action\n$url").replace("title: Do it", "title: Show recent calls")))
        val tap = "android:\n  steps:\n    - launch: true\n    - tap: Go"
        assertProblem(doc(action("risk: read\n$tap")), "actions[0].risk: can't be read: the steps tap, type, scroll, start an intent or open a link")
        assertProblem(doc(action("risk: read\nandroid:\n  steps:\n    - if_present: x\n      then: [{scroll: {direction: down}}]")), "can't be read")
        assertValid(doc(action("risk: read\nandroid:\n  steps:\n    - launch: true\n    - wait_for: Inbox")))
        assertProblem(doc(action("risk: read\nandroid:\n  steps:\n    - open_url: \"https://example.com/inbox\"")), "can't be read: the steps tap")
        assertProblem(doc(action("risk: read\nandroid:\n  steps:\n    - intent: {action: a.VIEW}")), "can't be read: the steps tap")
        assertProblem(doc(action("risk: read\nandroid:\n  steps:\n    - if_present: x\n      then: [{open_url: \"https://example.com\"}]")), "can't be read")
        assertProblem(doc(action("risk: read\n$url")), "actions[0].risk: can't be read: url recipes open the app, which is at least an action")
        assertProblem(doc(action("risk: read\nandroid: {intent: {action: a.VIEW}}")), "can't be read: intent recipes open the app")
    }

    // iOS (§3, §4, §6.6, §7)

    private val iosHeader = header + "\nios:\n  bundle_ids: [com.demo.ios]"

    /** An action with an iOS recipe ([ios], at the recipe's indentation) and optionally an Android one. */
    private fun iosAction(ios: String, android: String? = null, risk: String = "risk: action", params: String = "params: {q: {description: Q}}") =
        action("$risk\n$params\n" + (android?.let { "android:\n" + it.trimIndent().prependIndent("  ") + "\n" } ?: "") + "ios:\n" + ios.trimIndent().prependIndent("  "))

    @Test
    fun iosBundleIds() {
        val plugin = assertValid(doc(iosAction("url: \"demo://s?q={{q}}\""), iosHeader))
        assertEquals(listOf("com.demo.ios"), plugin.iosBundleIds)
        assertEquals(listOf("com.demo.ios"), plugin.appIds(DeviceOs.IOS))
        assertEquals(listOf("com.demo.app", "com.demo.lite"), plugin.appIds(DeviceOs.ANDROID))
        // An iOS-only plugin needs no android section, and an Android-only one no ios section.
        assertValid(doc(iosAction("url: \"demo://x\""), iosHeader.replace("android:\n  packages: [com.demo.app, com.demo.lite]\n", "")))
        assertProblem(doc(iosAction("url: \"demo://x\"")), "ios.bundle_ids: required: the bundle ids of the iOS app")
        assertProblem(doc(header = iosHeader), "ios.bundle_ids: no action has an 'ios' recipe: add one, or remove the 'ios' section")
        val ios = doc(iosAction("url: \"demo://x\""), iosHeader)
        assertValid(ios.replace("[com.demo.ios]", "[com.demo.ios, com.demo-co.App2]"))
        assertProblem(ios.replace("[com.demo.ios]", "[]"), "ios.bundle_ids: list at least one bundle id")
        assertProblem(ios.replace("[com.demo.ios]", "[demo]"), "ios.bundle_ids[0]: 'demo' is not an iOS bundle id")
        assertProblem(ios.replace("[com.demo.ios]", "[com.demo_ios.app]"), "is not an iOS bundle id")
        assertProblem(ios.replace("[com.demo.ios]", "[com.demo.ios, com.demo.ios]"), "ios.bundle_ids: 'com.demo.ios' is listed twice")
        assertProblem(ios.replace("[com.demo.ios]", "[dev.phonebridge.runner]"), "ios.bundle_ids[0]: plugins can't target PhoneBridge itself")
        assertProblem(ios.replace("bundle_ids: [com.demo.ios]", "bundle_id: com.demo.ios"), "ios.bundle_id: unknown field (did you mean 'bundle_ids'?)")
        assertProblem(ios.replace("ios:\n  bundle_ids: [com.demo.ios]", "ios: com.demo.ios"), "ios: must be a map with 'bundle_ids'")
        // tested records may name the iOS version.
        val tested = assertValid(ios.replace("id: demo", "id: demo\ntested: [{app_version: 3.1, ios: 18.1, date: 2026-11-01, by: octocat}]"))
        assertEquals(TestedRecord("3.1", null, "2026-11-01", "octocat", ios = "18.1"), tested.tested.single())
    }

    @Test
    fun iosRecipes() {
        val both = assertValid(doc(iosAction("url: \"demo://s?q={{q}}\"", android = "url: \"https://demo.com/s?q={{q}}\""), iosHeader)).actions.single()
        assertEquals(UrlRecipe("https://demo.com/s?q={{q}}"), both.android)
        assertEquals(UrlRecipe("demo://s?q={{q}}"), both.ios)
        assertEquals(UrlRecipe("demo://s?q={{q}}"), both.recipeFor(DeviceOs.IOS))
        assertEquals(false, both.completesOn(DeviceOs.IOS))
        val iosOnly = assertValid(doc(iosAction("steps:\n  - launch: true\n  - tap: Search"), iosHeader)).actions.single()
        assertEquals(null, iosOnly.recipeFor(DeviceOs.ANDROID))
        assertEquals(true, iosOnly.completesOn(DeviceOs.IOS))

        fun ios(recipe: String, android: String? = null, risk: String = "risk: action") = doc(iosAction(recipe, android, risk), iosHeader)
        assertProblem(ios("intent: {action: a.VIEW}"), "actions[0].ios.intent: iOS has no intents: use url (a link the app declares) or steps")
        assertProblem(ios("{url: \"demo://x\", steps: [{launch: true}]}"), "actions[0].ios: needs exactly one recipe: url, steps or tool (found url, steps)")
        assertProblem(ios("{}"), "actions[0].ios: needs exactly one recipe: url, steps or tool (found none)")
        assertProblem(ios("{uri: \"demo://x\"}"), "actions[0].ios.uri: unknown field (did you mean 'url'?)")
        // The same template and scheme rules as on Android.
        assertProblem(ios("url: \"{{q}}:x\""), "actions[0].ios.url: the URL scheme must be literal")
        assertProblem(ios("url: \"javascript:{{q}}\""), "actions[0].ios.url: the 'javascript:' scheme is not allowed")
        assertProblem(ios("url: \"demo://{{nope}}\""), "actions[0].ios.url: {{nope}} is not declared in params")
        assertProblem(ios("url: \"demo://x\"", risk = ""), "actions[0].risk: required: read, action or sensitive")
        assertProblem(ios("url: \"demo://x\"", risk = "risk: read"), "actions[0].risk: can't be read: url recipes open the app")
        assertProblem(ios("url: \"demo://x\"", android = "url: \"https://x\"", risk = "risk: read"), "can't be read: url recipes open the app")
        assertProblem(doc(iosAction("url: \"demo://x\"", risk = "risk: action", params = "").replace("id: act", "id: send_it"), iosHeader), "the id says 'send'")
        // Tool recipes: no risk or scope, and both OS sections tool recipes or neither.
        val tool = "tool: {name: url_open, args: {url: \"https://x/{{q}}\"}}"
        val t = assertValid(ios(tool, risk = "")).actions.single()
        assertEquals("url_open", (t.ios as ToolRecipe).name)
        assertProblem(ios(tool), "actions[0].risk: remove it: a tool recipe takes the built-in tool's risk")
        assertProblem(ios("tool: {name: ui_tap}", risk = ""), "actions[0].ios.tool.name: 'ui_tap' can't be used by plugins: use a steps recipe")
        assertProblem(ios(tool, android = "url: \"https://x\""), "actions[0]: the android and ios recipes must both be tool recipes or neither")
        assertValid(ios(tool, android = tool, risk = ""))
    }

    @Test
    fun iosSteps() {
        fun steps(body: String, risk: String = "risk: action") = doc(iosAction("steps:\n" + body.trimIndent().prependIndent("  "), risk = risk), iosHeader)
        val recipe = assertValid(
            steps(
                """
                - launch: {fresh: true}
                - open_url: "demo://search/{{q}}"
                - type: {into: {id: SearchField}, text: "{{q}}", submit: true}
                - tap: {id: "com.other:id/x"}
                - tap: {id: "Player:Play"}
                - wait_for: {class: Button, text_contains: "{{q}}"}
                """,
            ),
        ).actions.single().ios as StepsRecipe
        // Accessibility identifiers are kept as written: no package prefix, any characters.
        assertEquals(Step(StepAction.Tap(Selector(id = "Player:Play"))), recipe.steps[4])
        assertProblem(steps("- intent: {action: a.VIEW}"), "actions[0].ios.steps[0].intent: iOS has no intents: use open_url")
        assertProblem(steps("- back: true"), "actions[0].ios.steps[0].back: iOS has no Back button: tap the app's own back control instead")
        assertProblem(steps("- if_present: x\n  then: [{back: true}]"), "actions[0].ios.steps[0].then[0].back: iOS has no Back button")
        assertProblem(steps("- tap: {id: \"{{q}}\"}"), "actions[0].ios.steps[0].tap.id: templates are not allowed")
        assertProblem(steps("- tap: {text: \"{{nope}}\"}"), "actions[0].ios.steps[0].tap.text: {{nope}} is not declared")
        assertProblem(steps((1..41).joinToString("\n") { "- sleep: 1" }), "actions[0].ios.steps: at most 40 steps in total")
        assertProblem(steps("- tap: Go", risk = "risk: read"), "can't be read: the steps tap")
    }

    @Test
    fun iosExamples() {
        val action = iosAction("url: \"demo://s?q={{q}}\"", android = "url: \"https://demo.com/s?q={{q}}\"") +
            "\n  examples:\n" + """
                - params: {q: a b}
                  expect: {url: "https://demo.com/s?q=a%20b"}
                - os: ios
                  params: {q: a b}
                  expect: {url: "demo://s?q=a%20b"}
            """.trimIndent().prependIndent("    ")
        val plugin = assertValid(doc(action, iosHeader))
        assertEquals(listOf(DeviceOs.ANDROID, DeviceOs.IOS), plugin.actions.single().examples.map { it.os })
        assertProblem(doc(action.replace("os: ios", "os: windows"), iosHeader), "actions[0].examples[1].os: 'windows' is not an OS; use android or ios")
        assertProblem(doc(action.replace("demo://s?q=a%20b", "demo://s?q=a+b"), iosHeader), "actions[0].examples[1]: url: expected demo://s?q=a+b")
        val iosOnly = iosAction("url: \"demo://x\"") + "\n  examples:\n    - expect: {url: \"demo://x\"}"
        assertProblem(doc(iosOnly, iosHeader), "actions[0].examples[0]: the action has no 'android' recipe to test; add 'os: ios'")
        assertValid(doc(iosOnly.replace("- expect", "- os: ios\n      expect"), iosHeader))
        val androidOnly = doc(search + "\n  examples:\n    - os: ios\n      expect: {error: query}")
        assertProblem(androidOnly, "actions[0].examples[0].os: the action has no 'ios' recipe to test")
    }

    @Test
    fun stablePluginsNeedIosExamplesForIosRecipes() {
        val tested = "id: demo\nstatus: stable\ntested: [{app_version: 1, ios: 18, date: 2026-11-01, by: octocat}]"
        val action = iosAction("url: \"demo://x\"", params = "") + "\n  examples:\n    - os: ios\n      expect: {url: \"demo://x\"}"
        assertValid(doc(action, iosHeader).replace("id: demo", tested))
        val noIosExample = iosAction("url: \"demo://x\"", android = "url: \"https://x\"", params = "") + "\n  examples:\n    - expect: {url: \"https://x\"}"
        assertProblem(doc(noIosExample, iosHeader).replace("id: demo", tested), "actions[0].examples: every ios recipe of a stable plugin needs examples with os: ios")
    }

    @Test
    fun stablePluginsNeedTestsAndExamples() {
        val stable = doc().replace("id: demo", "id: demo\nstatus: stable")
        assertProblem(stable, "tested: a stable plugin needs at least one 'tested' record")
        assertProblem(stable, "actions[0].examples: every action of a stable plugin needs examples")
        val ok = stable.replace("status: stable", "status: stable\ntested: [{app_version: 8.9, android: 14, date: 2026-11-01, by: octocat}]") +
            "    examples:\n      - params: {query: x}\n        expect: {url: \"https://demo.com/s?q=x\"}\n"
        val plugin = assertValid(ok)
        assertEquals(TestedRecord("8.9", "14", "2026-11-01", "octocat"), plugin.tested.single())
    }

    @Test
    fun exampleShapes() {
        fun ex(examples: String) = doc(search + "\n  examples:\n" + examples.trimIndent().prependIndent("    "))
        assertProblem(ex("- x"), "actions[0].examples[0]: each example must be a map")
        assertProblem(ex("- params: {query: x}"), "actions[0].examples[0].expect: required")
        assertProblem(ex("- expect: {url: x, error: y}"), "examples[0].expect: needs exactly one of intent, url, tool, steps, error (found url, error)")
        assertProblem(ex("- expect: {}"), "(found none)")
        assertProblem(ex("- expect: {intent: {action: a, flags: 1}}"), "examples[0].expect.intent.flags: unknown field")
        assertProblem(ex("- expect: {intent: x}"), "examples[0].expect.intent: must be a map")
        assertProblem(ex("- expect: {tool: {name: x, argz: {}}}"), "examples[0].expect.tool.argz: unknown field")
        assertProblem(ex("- expect: {steps: x}"), "examples[0].expect.steps: must be a list of steps")
        assertProblem(ex("- params: {query: [x]}\n  expect: {error: q}"), "examples[0].params.query: example parameters must be single values")
        assertProblem(ex("- params: x\n  expect: {error: q}"), "examples[0].params: must be a map")
        assertProblem(ex("- {params: {}, expect: {error: q}, note: x}"), "examples[0].note: unknown field")
        assertNotNull(assertValid(ex("- params: {}\n  expect: {error: query}")).actions.single().examples.single())
    }

    @Test
    fun theTemplateAndTheSpotifyPluginAreValid() {
        // Sanity check on the format document's own example (docs/PLUGINS.md §2), trimmed to Android fields.
        val spotify = """
            id: spotify
            name: Spotify
            description: Music and podcast streaming.
            category: music
            homepage: https://www.spotify.com
            status: experimental
            android:
              packages: [com.spotify.music]
            actions:
              - id: play
                title: Play music
                description: Plays the best match.
                keywords: [music, song]
                risk: action
                params:
                  query: {description: What to play, required: true, max_length: 200}
                confirm: Play "{{query}}" on Spotify
                completes: true
                android:
                  intent:
                    action: android.media.action.MEDIA_PLAY_FROM_SEARCH
                    extras:
                      query: "{{query}}"
                      android.intent.extra.focus: vnd.android.cursor.item/*
                examples:
                  - params: {query: lofi beats}
                    expect:
                      intent:
                        action: android.media.action.MEDIA_PLAY_FROM_SEARCH
                        package: com.spotify.music
                        extras: {query: lofi beats, android.intent.extra.focus: vnd.android.cursor.item/*}
        """.trimIndent()
        val plugin = assertValid(spotify, folder = "spotify")
        assertEquals("Play \"{{query}}\" on Spotify", plugin.actions.single().confirm)
    }
}
