package dev.phonebridge.android.tools

import android.content.Context
import dev.phonebridge.core.tools.Tool

/** Every Android tool, in the order agents see them (docs/TOOLS.md). */
object AndroidToolCatalog {
    fun build(context: Context): List<Tool> {
        val app = context.applicationContext
        return contactTools(app) +
            messagingTools(app) +
            whatsAppTools(app) +
            emailTools(app) +
            notificationTools(app) +
            appTools(app) +
            screenTools(app) +
            deviceTools(app) +
            calendarTools(app) +
            locationTools(app) +
            intentTools(app)
    }
}
