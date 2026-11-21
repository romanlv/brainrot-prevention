package dev.brainrotprevention.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UrlBarIdKind { VIEW_ID, COMPOSE_TAG }

@Serializable
enum class UrlBarSource { TEXT, CONTENT_DESC }

/** One URL-bar locator of `browsers.json` (§7.2). */
@Serializable
data class UrlBarId(
    val id: String,
    val kind: UrlBarIdKind = UrlBarIdKind.VIEW_ID,
    val source: UrlBarSource = UrlBarSource.TEXT,
    val reliable: Boolean = true,
)

/** One entry of `browsers.json` (§7.2). */
@Serializable
data class BrowserEntry(
    @SerialName("package") val packageName: String,
    val urlBarIds: List<UrlBarId>,
    val canEditUrl: Boolean = true,
)

/** `strict_guards.json` (§9.3). */
@Serializable
data class StrictGuards(
    val settings: List<String> = listOf("com.android.settings", "com.miui.securitycenter", "com.samsung.android.lool"),
    val installer: List<String> = listOf("com.google.android.packageinstaller", "com.android.packageinstaller"),
)

/** Built-in package sets the engine always unions with the platform-resolved ones (§6.3). */
object PackageDefaults {
    val ALWAYS_EXEMPT: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** Matched by component (`pkg/.Cls` or `pkg/full.Cls`), not by package. */
    val ALWAYS_EXEMPT_COMPONENTS: Set<String> = setOf("com.android.settings/.ConfirmDeviceCredentialActivity")

    const val DEVICE_ADMIN_ADD_COMPONENT = "com.android.settings/.DeviceAdminAdd"

    val ALLOWLIST_ESSENTIALS: Set<String> = setOf(
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.settings",
    )
}
