package com.droidhands.android.access

/** Shorthand builders for captured trees in JVM tests. */
fun rect(left: Int, top: Int, right: Int, bottom: Int) = Bounds(left, top, right, bottom)

val SCREEN = Bounds(0, 0, 1080, 2400)

fun appWindow(root: UiNode, pkg: String = "com.example", primary: Boolean = true, title: String? = null, bounds: Bounds = SCREEN) =
    WindowSnapshot(title, WindowKind.APPLICATION, pkg, bounds, primary, root)

fun button(text: String? = null, desc: String? = null, id: String? = null, bounds: Bounds = rect(0, 0, 100, 100), pkg: String? = null) =
    UiNode(text = text, description = desc, viewId = id, className = "android.widget.Button", packageName = pkg, bounds = bounds, clickable = true)

fun label(text: String, bounds: Bounds = rect(0, 0, 100, 50), pkg: String? = null, id: String? = null) =
    UiNode(text = text, viewId = id, className = "android.widget.TextView", packageName = pkg, bounds = bounds)

fun group(vararg children: UiNode, clickable: Boolean = false, bounds: Bounds = SCREEN, pkg: String? = null, scrollable: Boolean = false) =
    UiNode(className = "android.widget.LinearLayout", packageName = pkg, bounds = bounds, clickable = clickable, scrollable = scrollable, children = children.toList())
