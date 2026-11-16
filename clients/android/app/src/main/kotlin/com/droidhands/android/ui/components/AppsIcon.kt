package com.droidhands.android.ui.components

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A 3 × 3 grid of squares, the Material "Apps" symbol (not in the core icon set this app ships with), for the
 * Apps tab. Built once.
 */
val AppsIcon: ImageVector by lazy {
    materialIcon(name = "DroidHands.Apps") {
        materialPath {
            for (y in listOf(4f, 10f, 16f)) {
                for (x in listOf(4f, 10f, 16f)) {
                    moveTo(x, y)
                    horizontalLineToRelative(4f)
                    verticalLineToRelative(4f)
                    horizontalLineToRelative(-4f)
                    close()
                }
            }
        }
    }
}
