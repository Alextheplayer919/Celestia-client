package com.proxy.mcbedrock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.proxy.mcbedrock.net.ServerTarget
import com.proxy.mcbedrock.net.TargetRules
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the user chose, kept between launches: which app to relay, which
 * server to point the relay at, and a short list of recently used servers.
 *
 * SharedPreferences rather than DataStore: the values are tiny, written only when
 * the user taps Save, and this keeps the app free of another dependency. The rules
 * themselves ([TargetRules]) are unit tested outside Android.
 */
class RelayConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Package whose traffic the VPN captures. */
    var packageName: String
        get() = prefs.getString(KEY_PACKAGE, null) ?: DEFAULT_PACKAGE
        set(value) = prefs.edit().putString(KEY_PACKAGE, value).apply()

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, ServerTarget.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** Route only the target server into the tunnel (see switch_scope). */
    var scopeToServer: Boolean
        get() = prefs.getBoolean(KEY_SCOPE, true)
        set(value) = prefs.edit().putBoolean(KEY_SCOPE, value).apply()

    /** Server currently being relayed; a blank host means "no target chosen yet". */
    fun target(): ServerTarget = ServerTarget(host = host, port = port)

    fun hasTarget(): Boolean = TargetRules.validateHost(host) == null && host.isNotBlank()

    /** Saves the address and moves it to the front of the recent list. */
    fun saveTarget(host: String, port: Int) {
        val normalised = TargetRules.normaliseHost(host)
        this.host = normalised
        this.port = port
        remember(ServerTarget(normalised, port))
    }

    private fun remember(target: ServerTarget) {
        val updated = TargetRules.remember(recents(), target)
        val json = JSONArray()
        updated.forEach { entry ->
            json.put(
                JSONObject()
                    .put("host", entry.host)
                    .put("port", entry.port)
                    .put("motd", entry.motd ?: JSONObject.NULL)
                    .put("protocol", entry.protocolVersion)
                    .put("mc", entry.mcVersion ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY_RECENTS, json.toString()).apply()
    }

    fun recents(): List<ServerTarget> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val host = obj.optString("host").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ServerTarget(
                    host = host,
                    port = obj.optInt("port", ServerTarget.DEFAULT_PORT),
                    motd = obj.optString("motd").takeIf { it.isNotBlank() && it != "null" },
                    mcVersion = obj.optString("mc").takeIf { it.isNotBlank() && it != "null" },
                    protocolVersion = obj.optInt("protocol", -1)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS = "celestia.relay"
        private const val KEY_PACKAGE = "package"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_SCOPE = "scope_to_server"
        private const val KEY_RECENTS = "recents"

        /** The Bedrock package on Android; the beta program replaces this same app. */
        const val DEFAULT_PACKAGE = "com.mojang.minecraftpe"
    }
}

/** One selectable app, ready for the picker. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val icon: Drawable?,
    val isMinecraftLike: Boolean
)

/**
 * Reads the installed apps we are allowed to see.
 *
 * Two Android restrictions shape this:
 *  - since Android 11 an app cannot enumerate everything without the
 *    `QUERY_ALL_PACKAGES` permission, which Play restricts to specific use cases.
 *    Instead the manifest declares a `<queries>` block for launcher activities, so
 *    `queryIntentActivities` can legitimately list launchable apps — which includes
 *    Minecraft — without shipping a restricted permission.
 *  - the Minecraft-ish shortlist is built from that same list, plus the currently
 *    selected package even if it is not launchable.
 */
object InstalledApps {

    private val MINECRAFT_HINTS = listOf("minecraft", "mojang", "bedrock", "minecraftedu")

    fun label(context: Context, packageName: String): String? = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun versionName(context: Context, packageName: String): String? = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    } catch (_: Exception) {
        null
    }

    fun icon(context: Context, packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
        null
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Every launchable app the manifest's `<queries>` block makes visible. */
    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        return resolved
            .map { it.activityInfo.applicationInfo.packageName }
            .distinct()
            .mapNotNull { entry(context, it) }
            .sortedBy { it.label.lowercase() }
    }

    /** Launchable apps that look like a Minecraft client. */
    fun minecraftCandidates(context: Context): List<AppEntry> {
        val selected = RelayConfigStore(context).packageName
        val shortlist = launchable(context).filter { it.isMinecraftLike }
        if (shortlist.any { it.packageName == selected }) return shortlist
        val extras = mutableListOf<AppEntry>()
        // The saved choice or the Bedrock default may not be launchable (some
        // installs hide their launcher entry), so include them explicitly.
        for (packageName in listOf(selected, RelayConfigStore.DEFAULT_PACKAGE)) {
            if (shortlist.any { it.packageName == packageName } || extras.any { it.packageName == packageName }) continue
            entry(context, packageName)?.let { extras += it }
        }
        return shortlist + extras
    }

    private fun entry(context: Context, packageName: String): AppEntry? {
        val label = label(context, packageName) ?: return null
        return AppEntry(
            packageName = packageName,
            label = label,
            versionName = versionName(context, packageName),
            icon = icon(context, packageName),
            isMinecraftLike = MINECRAFT_HINTS.any { packageName.lowercase().contains(it) }
        )
    }
}
