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

/**
 * `strict_guards.json` (§9.3). Beyond the spec's list, the OEM screens that can turn off an accessibility service
 * are guarded too: Samsung's accessibility settings (`com.samsung.accessibility`) and Huawei's phone manager.
 */
@Serializable
data class StrictGuards(
    val settings: List<String> = listOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.samsung.android.lool",
        "com.samsung.accessibility",
        "com.huawei.systemmanager",
    ),
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

    /**
     * Matched by component (`pkg/.Cls` or `pkg/full.Cls`), not by package. Class names as resolved on
     * API 28–37 (`cmd package query-activities -a android.app.action.CONFIRM_DEVICE_CREDENTIAL`).
     */
    val ALWAYS_EXEMPT_COMPONENTS: Set<String> = setOf(
        "com.android.settings/.password.ConfirmDeviceCredentialActivity",
        "com.android.settings/.password.ConfirmDeviceCredentialActivity\$InternalActivity",
    )

    /** `android.app.action.ADD_DEVICE_ADMIN` resolves here on API 28–37. */
    const val DEVICE_ADMIN_ADD_COMPONENT = "com.android.settings/.applications.specialaccess.deviceadmin.DeviceAdminAdd"

    /**
     * Always allowed in "Allow only selected" schedules (§6.3). Besides Files and Settings, the system screens that
     * moved out of `android` on Android 14+ and that the app picker can't offer (review round 2, adversarial 8;
     * packages and activities checked on the API 37 image with `cmd package resolve-activity` and the APK manifests):
     * the share sheet (`ACTION_CHOOSER` → `com.android.intentresolver/.ChooserActivity`), the photo picker
     * (`MediaStore.ACTION_PICK_IMAGES` → the MediaProvider module, and the newer `photopicker` package), Credential
     * Manager (`CredentialSelectorActivity`, passkeys and passwords), and the captive-portal login.
     */
    val ALLOWLIST_ESSENTIALS: Set<String> = setOf(
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.settings",
        "com.android.intentresolver",
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        "com.google.android.photopicker",
        "com.android.photopicker",
        "com.android.credentialmanager",
        "com.google.android.captiveportallogin",
        "com.android.captiveportallogin",
    )

    /**
     * Allowlist essentials matched by component, for packages that are too broad to allow whole (Play Services):
     * the Google account picker, Google sign-in and the passkey / FIDO and password-manager sheets (activity names
     * from the API 37 image's GmsCore manifest).
     */
    val ALLOWLIST_ESSENTIAL_COMPONENTS: Set<String> = listOf(
        "common.account.AccountPickerActivity",
        "common.account.SimpleDialogAccountPickerActivity",
        "common.account.AccountTypePickerActivity",
        "auth.api.signin.internal.SignInHubActivity",
        "auth.api.signin.ui.SignInActivity",
        "signin.activity.SignInActivity",
        "auth.api.credentials.assistedsignin.ui.AssistedSignInActivity",
        "auth.api.credentials.assistedsignin.ui.GoogleSignInActivity",
        "auth.api.credentials.authorization.ui.AuthorizationActivity",
        "auth.api.credentials.credman.authentication.AuthenticationActivity",
        "auth.api.credentials.credman.create.CreatePasswordActivity",
        "auth.api.credentials.fido.authentication.ui.AuthenticationActivity",
        "auth.api.credentials.fido.registration.ui.RegistrationActivity",
        "fido.fido2.ui.Fido2FullScreenActivity",
        "fido.fido2.ui.hybrid.HybridAuthenticateActivity",
    ).mapTo(LinkedHashSet()) { "com.google.android.gms/com.google.android.gms.$it" }
}
