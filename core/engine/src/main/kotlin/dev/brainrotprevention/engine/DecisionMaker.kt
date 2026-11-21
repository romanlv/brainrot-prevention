package dev.brainrotprevention.engine

import dev.brainrotprevention.model.AllowlistWeb
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.PackageDefaults
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebTarget

/** A schedule or the Quick Block virtual schedule, with lookup sets (§5.5, §6.2 step 3). */
class EffectiveSchedule(
    val id: Long,
    val isQuickBlock: Boolean,
    val schedule: Schedule?,
    val mode: Mode,
    val allowlistWeb: AllowlistWeb,
    val apps: Set<String>,
    val webRules: List<WebRule>,
    val blockNotifications: Boolean,
    val blockUnsupportedBrowsers: Boolean,
) {
    val blocksWeb: Boolean =
        (mode == Mode.BLOCKLIST && webRules.isNotEmpty()) || (mode == Mode.ALLOWLIST && allowlistWeb == AllowlistWeb.ONLY_LISTED)

    fun source(): ReasonSource = if (isQuickBlock) ReasonSource.QuickBlock else ReasonSource.Schedule(id)
}

/** Snapshot with derived lookup sets; built once per snapshot instance. */
class PreparedSnapshot(val snapshot: ConfigSnapshot) {
    val effective: List<EffectiveSchedule> = snapshot.schedules.map {
        EffectiveSchedule(
            it.id, false, it, it.mode, it.allowlistWeb, it.apps.toHashSet(), it.webRules,
            it.blockNotifications, it.blockUnsupportedBrowsers,
        )
    } + EffectiveSchedule(
        QUICK_BLOCK_ID, true, null, Mode.BLOCKLIST, AllowlistWeb.OPEN, snapshot.quickBlock.apps.toHashSet(),
        snapshot.quickBlock.webRules, snapshot.quickBlock.blockNotifications, true,
    )
    val exempt: Set<String> = buildSet {
        addAll(PackageDefaults.ALWAYS_EXEMPT)
        addAll(snapshot.exemptPackages)
        if (snapshot.ownPackage.isNotEmpty()) add(snapshot.ownPackage)
    }
    val exemptComponents: Set<String> = (PackageDefaults.ALWAYS_EXEMPT_COMPONENTS + snapshot.exemptComponents).mapTo(HashSet()) {
        canonicalComponent(it)
    }
    val essentials: Set<String> = PackageDefaults.ALLOWLIST_ESSENTIALS + snapshot.allowlistEssentials
    val essentialComponents: Set<String> = PackageDefaults.ALLOWLIST_ESSENTIAL_COMPONENTS.mapTo(HashSet()) { canonicalComponent(it) }
    val settingsGuard: Set<String> = snapshot.strictGuards.settings.toHashSet()
    val installerGuard: Set<String> = snapshot.strictGuards.installer.toHashSet()
    val webApps: Map<String, NormalizedUrl> = snapshot.webApps.mapValues { UrlNormalizer.normalizeDetected(it.value) }
    val redirectPrefix: String = snapshot.redirectUriPrefix.lowercase()

    companion object {
        /** `pkg/.Cls` → `pkg/pkg.Cls`. */
        fun canonicalComponent(c: String): String {
            val pkg = c.substringBefore('/')
            val cls = c.substringAfter('/', "")
            return if (cls.startsWith(".")) "$pkg/$pkg$cls" else c
        }
    }
}

/** Everything [DecisionMaker] needs besides the target. */
class DecisionContext(
    val prepared: PreparedSnapshot,
    val statuses: Map<Long, ScheduleStatus>,
    val strictDisplay: StrictDisplayState,
    /** (scheduleId, item key) → the LAUNCHES gate refused this new launch. */
    val gated: (Long, String) -> Boolean = { _, _ -> false },
    val keyguardLocked: Boolean = false,
    val urlBarMissing: Set<String> = emptySet(),
    val adminActivationPending: Boolean = false,
) {
    val strict: StrictModeRow get() = prepared.snapshot.strict
}

/** §6.2 (apps) and §7.5 (web pages). */
object DecisionMaker {
    private val INTERNAL_PAGES = listOf("chrome://newtab", "chrome-native://newtab", "about:newtab", "about:home")

    fun isExempt(p: PreparedSnapshot, app: ForegroundApp, adminActivationPending: Boolean = false): Boolean {
        if (app.packageName in p.exempt) return true
        val comp = app.component?.let { PreparedSnapshot.canonicalComponent(it) } ?: return false
        if (comp in p.exemptComponents) return true
        return adminActivationPending && comp == PreparedSnapshot.canonicalComponent(PackageDefaults.DEVICE_ADMIN_ADD_COMPONENT)
    }

    fun strictUntil(row: StrictModeRow): Until = Until.UnlockCondition(
        row.method,
        if (row.method ==
            StrictMethod.TIMER
        ) {
            row.endsAt
        } else {
            null
        }
    )

    /** List match of an app for [s] irrespective of blocking (used for counting and matching). */
    fun appListMatch(
        p: PreparedSnapshot,
        s: EffectiveSchedule,
        pkg: String,
        keyguardLocked: Boolean,
        component: String? = null,
    ): Boolean = when (s.mode) {
        Mode.BLOCKLIST -> pkg in s.apps || p.webApps[pkg]?.let { UrlMatcher.matchesAny(s.webRules, it) } == true
        Mode.ALLOWLIST -> pkg !in s.apps && pkg !in p.essentials && !keyguardLocked && !isEssentialComponent(p, component)
    }

    private fun isEssentialComponent(p: PreparedSnapshot, component: String?): Boolean =
        component != null && PreparedSnapshot.canonicalComponent(component) in p.essentialComponents

    /** Web page match for [s] irrespective of blocking; false for pages always allowed. */
    fun pageMatch(p: PreparedSnapshot, s: EffectiveSchedule, target: WebTarget, url: NormalizedUrl): Boolean {
        if (!s.blocksWeb || alwaysAllowed(p, url)) return false
        return when (s.mode) {
            Mode.BLOCKLIST -> UrlMatcher.matchesAny(s.webRules, url)
            Mode.ALLOWLIST ->
                target.browserPackage in s.apps && target.reliable && !isInternalPage(url) && !UrlMatcher.matchesAny(s.webRules, url)
        }
    }

    fun alwaysAllowed(p: PreparedSnapshot, url: NormalizedUrl): Boolean =
        url.text.isEmpty() || url.text == "about:blank" || (p.redirectPrefix.isNotEmpty() && url.text.startsWith(p.redirectPrefix))

    fun isInternalPage(url: NormalizedUrl): Boolean = INTERNAL_PAGES.any { url.text.startsWith(it) }

    fun decideApp(ctx: DecisionContext, app: ForegroundApp): Decision {
        val p = ctx.prepared
        val pkg = app.packageName
        if (isExempt(p, app, ctx.adminActivationPending)) return Decision.Allow
        val strict = ctx.strict
        if (ctx.strictDisplay == StrictDisplayState.ACTIVE) {
            if (strict.has(StrictOptions.BLOCK_SETTINGS) && pkg in p.settingsGuard) {
                return Decision.of(listOf(Reason(ReasonSource.StrictSettings, null, strictUntil(strict))))
            }
            if (strict.has(StrictOptions.BLOCK_UNINSTALL) && pkg in p.installerGuard) {
                return Decision.of(listOf(Reason(ReasonSource.StrictUninstall, null, strictUntil(strict))))
            }
        }
        val reasons = ArrayList<Reason>()
        val excluded = p.snapshot.excludedBrowsers
        for (s in p.effective) {
            val st = ctx.statuses[s.id] ?: continue
            val (limit, until) = activeLimit(st, ctx.gated(s.id, pkg)) ?: continue
            if (appListMatch(p, s, pkg, ctx.keyguardLocked, app.component)) {
                reasons += Reason(s.source(), limit, until)
                continue
            }
            val unsupported = pkg in p.snapshot.unsupportedBrowsers ||
                pkg in ctx.urlBarMissing ||
                (s.mode == Mode.ALLOWLIST && pkg in p.webApps)
            if (s.blocksWeb && s.blockUnsupportedBrowsers && unsupported && pkg !in excluded) {
                reasons += Reason(ReasonSource.UnsupportedBrowser(if (s.isQuickBlock) null else s.id), limit, until)
            }
        }
        return Decision.of(reasons)
    }

    fun decideWeb(ctx: DecisionContext, target: WebTarget, url: NormalizedUrl = UrlNormalizer.normalizeDetected(target.url)): Decision {
        val p = ctx.prepared
        if (target.browserPackage in p.exempt || alwaysAllowed(p, url)) return Decision.Allow
        val reasons = ArrayList<Reason>()
        for (s in p.effective) {
            if (!s.blocksWeb) continue
            val st = ctx.statuses[s.id] ?: continue
            val (limit, until) = activeLimit(st, ctx.gated(s.id, pageKey(url))) ?: continue
            if (pageMatch(p, s, target, url)) reasons += Reason(s.source(), limit, until)
        }
        return Decision.of(reasons)
    }

    /** (limit, until) when [st] is blocking or its LAUNCHES gate refused the item; null when inactive. */
    private fun activeLimit(st: ScheduleStatus, gated: Boolean): Pair<LimitKind?, Until>? = when {
        st.blocking -> (if (st.timeExhausted) LimitKind.TIME else null) to (st.blockUntil ?: Until.Ongoing)
        gated && st.armed -> LimitKind.LAUNCHES to st.gateUntil
        else -> null
    }

    /** Item key used for launch counting of a page (§5.4). */
    fun pageKey(url: NormalizedUrl): String = if (url.hasHost) url.itemKey else url.text
}
