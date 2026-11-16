import XCTest

/// Maps XCUITest element snapshots to the protocol's `Element` JSON.
enum Elements {
    static let textInputTypes: Set<XCUIElement.ElementType> = [.textField, .secureTextField, .searchField, .textView]

    /// `{type, label?, value?, identifier?, placeholder?, frame, enabled, selected, focused, secure?, children}`.
    /// Children deeper than `maxDepth` levels below this element are left out. A secure text field never reports
    /// its value.
    static func json(_ snapshot: XCUIElementSnapshot, maxDepth: Int) -> [String: Any] {
        var element: [String: Any] = [
            "type": typeName(snapshot.elementType),
            "frame": frame(snapshot.frame),
            "enabled": snapshot.isEnabled,
            "selected": snapshot.isSelected,
            "focused": isFocused(snapshot),
        ]
        if !snapshot.label.isEmpty { element["label"] = snapshot.label }
        if !snapshot.identifier.isEmpty { element["identifier"] = snapshot.identifier }
        if let placeholder = snapshot.placeholderValue, !placeholder.isEmpty { element["placeholder"] = placeholder }
        if snapshot.elementType == .secureTextField {
            element["secure"] = true
        } else if let value = valueString(snapshot.value), !value.isEmpty {
            element["value"] = value
        }
        if maxDepth > 0 {
            element["children"] = snapshot.children.map { json($0, maxDepth: maxDepth - 1) }
        } else {
            element["children"] = [Any]()
        }
        return element
    }

    /// Keyboard focus: the public `hasFocus` (tvOS-style focus, usually false on iPhone) or XCTest's
    /// `hasKeyboardFocus` attribute, read only when the snapshot object answers to it.
    static func isFocused(_ snapshot: XCUIElementSnapshot) -> Bool {
        if snapshot.hasFocus { return true }
        return PrivateXCTest.hasKeyboardFocus(snapshot) ?? false
    }

    /// Depth-first search over the whole snapshot (no depth limit).
    static func first(in snapshot: XCUIElementSnapshot, where match: (XCUIElementSnapshot) -> Bool) -> XCUIElementSnapshot? {
        if match(snapshot) { return snapshot }
        for child in snapshot.children {
            if let found = first(in: child, where: match) { return found }
        }
        return nil
    }

    static func valueString(_ value: Any?) -> String? {
        switch value {
        case nil: return nil
        case let string as String: return string
        case let number as NSNumber: return number.stringValue
        case is NSNull: return nil
        case let other?: return String(describing: other)
        }
    }

    static func frame(_ rect: CGRect) -> [Double] {
        [rect.origin.x, rect.origin.y, rect.size.width, rect.size.height].map { component in
            let value = Double(component)
            return value.isFinite ? (value * 100).rounded() / 100 : 0
        }
    }

    /// The `XCUIElement.ElementType` case name: `button`, `textField`, `cell`, …
    static func typeName(_ type: XCUIElement.ElementType) -> String {
        switch type {
        case .any: return "any"
        case .other: return "other"
        case .application: return "application"
        case .group: return "group"
        case .window: return "window"
        case .sheet: return "sheet"
        case .drawer: return "drawer"
        case .alert: return "alert"
        case .dialog: return "dialog"
        case .button: return "button"
        case .radioButton: return "radioButton"
        case .radioGroup: return "radioGroup"
        case .checkBox: return "checkBox"
        case .disclosureTriangle: return "disclosureTriangle"
        case .popUpButton: return "popUpButton"
        case .comboBox: return "comboBox"
        case .menuButton: return "menuButton"
        case .toolbarButton: return "toolbarButton"
        case .popover: return "popover"
        case .keyboard: return "keyboard"
        case .key: return "key"
        case .navigationBar: return "navigationBar"
        case .tabBar: return "tabBar"
        case .tabGroup: return "tabGroup"
        case .toolbar: return "toolbar"
        case .statusBar: return "statusBar"
        case .table: return "table"
        case .tableRow: return "tableRow"
        case .tableColumn: return "tableColumn"
        case .outline: return "outline"
        case .outlineRow: return "outlineRow"
        case .browser: return "browser"
        case .collectionView: return "collectionView"
        case .slider: return "slider"
        case .pageIndicator: return "pageIndicator"
        case .progressIndicator: return "progressIndicator"
        case .activityIndicator: return "activityIndicator"
        case .segmentedControl: return "segmentedControl"
        case .picker: return "picker"
        case .pickerWheel: return "pickerWheel"
        case .switch: return "switch"
        case .toggle: return "toggle"
        case .link: return "link"
        case .image: return "image"
        case .icon: return "icon"
        case .searchField: return "searchField"
        case .scrollView: return "scrollView"
        case .scrollBar: return "scrollBar"
        case .staticText: return "staticText"
        case .textField: return "textField"
        case .secureTextField: return "secureTextField"
        case .datePicker: return "datePicker"
        case .textView: return "textView"
        case .menu: return "menu"
        case .menuItem: return "menuItem"
        case .menuBar: return "menuBar"
        case .menuBarItem: return "menuBarItem"
        case .map: return "map"
        case .webView: return "webView"
        case .incrementArrow: return "incrementArrow"
        case .decrementArrow: return "decrementArrow"
        case .timeline: return "timeline"
        case .ratingIndicator: return "ratingIndicator"
        case .valueIndicator: return "valueIndicator"
        case .splitGroup: return "splitGroup"
        case .splitter: return "splitter"
        case .relevanceIndicator: return "relevanceIndicator"
        case .colorWell: return "colorWell"
        case .helpTag: return "helpTag"
        case .matte: return "matte"
        case .dockItem: return "dockItem"
        case .ruler: return "ruler"
        case .rulerMarker: return "rulerMarker"
        case .grid: return "grid"
        case .levelIndicator: return "levelIndicator"
        case .cell: return "cell"
        case .layoutArea: return "layoutArea"
        case .layoutItem: return "layoutItem"
        case .handle: return "handle"
        case .stepper: return "stepper"
        case .tab: return "tab"
        case .touchBar: return "touchBar"
        case .statusItem: return "statusItem"
        @unknown default: return "other"
        }
    }
}
