package com.droidhands.android.tools

import android.content.Context
import com.droidhands.core.plugins.AppActionCatalog
import com.droidhands.core.plugins.appActionTools
import com.droidhands.core.tools.Tool

/**
 * Every Android tool, in the order agents see them (docs/TOOLS.md). [actions] backs `apps_list`'s action ids and
 * the core's `app_actions_list` / `app_action_run` (docs/PLUGINS.md), which follow the other app tools.
 */
object AndroidToolCatalog {
    fun build(context: Context, actions: AppActionCatalog): List<Tool> {
        val app = context.applicationContext
        return contactTools(app) +
            messagingTools(app) +
            callControlTools(app) +
            notificationTools(app) +
            appTools(app, actions) +
            appActionTools(actions) +
            screenTools(app) +
            deviceTools(app) +
            calendarTools(app) +
            locationTools(app) +
            intentTools(app)
    }
}
