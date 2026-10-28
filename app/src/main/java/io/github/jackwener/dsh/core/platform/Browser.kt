package io.github.jackwener.dsh.core.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Platform pages (sign-in, 充值, 用量) open in a Chrome Custom Tab: the user's browser session and
 * cookies, the app's colors, and — launched from an activity — on top of the app's own task, so
 * returning to MainActivity (singleTask) closes the tab.
 */
object Browser {
    fun open(context: Context, url: String, dark: Boolean, toolbar: Int) {
        val uri = Uri.parse(url)
        val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(toolbar).build()
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setDefaultColorSchemeParams(colors)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        if (context !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Pin a browser that really implements Custom Tabs. Many OEM default browsers don't; the
        // page then opens as a full browser task whose Back walks to the browser's home page
        // instead of returning to DSH.
        androidx.browser.customtabs.CustomTabsClient.getPackageName(context, null)?.let { tab.intent.setPackage(it) }
        runCatching { tab.launchUrl(context, uri) }.onFailure {
            Log.w("Browser", "custom tab unavailable, falling back to VIEW", it)
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
