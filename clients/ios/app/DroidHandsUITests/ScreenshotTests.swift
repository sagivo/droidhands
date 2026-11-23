import XCTest

/// Walks every screen and saves a screenshot of each to $DH_SCREENSHOT_DIR (default /tmp/droidhands-screens):
/// a visual check of the UI, and the App Store screenshots (scripts/ios-app.sh screenshots).
final class ScreenshotTests: XCTestCase {
    private var dir: URL {
        URL(fileURLWithPath: ProcessInfo.processInfo.environment["DH_SCREENSHOT_DIR"] ?? "/tmp/droidhands-screens")
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? app.screenshot().pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    /// Rows combine their title and subtitle into one label, so match on how it starts.
    private func button(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
    }

    func testScreens() {
        let app = XCUIApplication()
        app.launchEnvironment["DH_SKIP_ONBOARDING"] = "1"
        if let relay = ProcessInfo.processInfo.environment["DH_RELAY_URL"] { app.launchEnvironment["DH_RELAY_URL"] = relay }
        app.launch()
        XCTAssertTrue(app.staticTexts["Connected"].waitForExistence(timeout: 15), "the app should connect to the relay")
        shot(app, "1-home")

        button(app, "Connect an AI agent").tap()
        XCTAssertTrue(app.navigationBars["Connect an AI agent"].waitForExistence(timeout: 5))
        shot(app, "2-connect")
        app.buttons["Done"].tap()

        app.tabBars.buttons["Agents"].tap()
        shot(app, "3-agents")
        let firstAgent = app.cells.element(boundBy: 0)
        if firstAgent.waitForExistence(timeout: 3) {
            firstAgent.tap()
            shot(app, "4-agent")
            let access = button(app, "Access")
            for _ in 0..<4 where !access.isHittable { app.swipeUp() }
            shot(app, "4b-agent-settings")
            access.tap()
            app.buttons["Only what I choose"].tap()
            shot(app, "5-access")
        }

        app.tabBars.buttons["Activity"].tap()
        shot(app, "6-activity")
        let entry = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "status")).firstMatch
        if entry.waitForExistence(timeout: 2) {
            entry.tap()
            if app.navigationBars["Request"].waitForExistence(timeout: 3) {
                shot(app, "6b-request")
                app.navigationBars["Request"].buttons.firstMatch.tap()
            }
        }
        app.tabBars.buttons["Settings"].tap()
        shot(app, "7-settings")
        button(app, "Permissions").tap()
        shot(app, "8-permissions")
    }

    func testOnboarding() {
        let app = XCUIApplication()
        app.launchEnvironment["DH_RESET_ONBOARDING"] = "1"
        app.launch()
        XCTAssertTrue(app.staticTexts["Let your AI agents use your iPhone"].waitForExistence(timeout: 10))
        shot(app, "0-welcome")

        app.buttons["Get started"].tap()
        XCTAssertTrue(app.staticTexts["Allow permissions"].waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 0.4)
        shot(app, "0-permissions")

        app.buttons["Continue"].tap()
        XCTAssertTrue(app.staticTexts["Connect this iPhone"].waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 0.4)
        shot(app, "0-connect")

        app.buttons["Do it yourself"].tap()
        XCTAssertTrue(app.navigationBars["More ways to connect"].waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 0.4)
        shot(app, "0-more-connect")

        app.navigationBars["More ways to connect"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Connect this iPhone"].waitForExistence(timeout: 5))
        app.buttons["Skip for now"].tap()
        XCTAssertTrue(app.staticTexts["Connect your first agent"].waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 0.4)
        shot(app, "0-agent")
    }
}
