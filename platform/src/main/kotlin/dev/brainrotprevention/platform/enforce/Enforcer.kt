package dev.brainrotprevention.platform.enforce

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.engine.Decision
import dev.brainrotprevention.engine.EvaluationResult
import dev.brainrotprevention.engine.PageDecision
import dev.brainrotprevention.engine.QUICK_BLOCK_ID
import dev.brainrotprevention.engine.Reason
import dev.brainrotprevention.engine.ReasonSource
import dev.brainrotprevention.engine.Until
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.platform.BlockerAccessibilityService
import dev.brainrotprevention.platform.Events
import dev.brainrotprevention.platform.NotificationBlockerService
import dev.brainrotprevention.platform.PlatformGraph
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.time.SystemTimeAndroid
import dev.brainrotprevention.platform.ui.BlockActions
import dev.brainrotprevention.platform.ui.BlockModel
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Enforcement (§10.2 apps, §7.6 pages, §9.3 Settings guard) with the §6.4 debounce and loop protection.
 * [apply] runs on the engine dispatcher; window and global actions are posted to the main thread.
 */
class Enforcer(private val service: BlockerAccessibilityService, private val g: PlatformGraph) : BlockActions {
    private val main = Handler(Looper.getMainLooper())
    private val lastEnforcedRt = HashMap<String, Long>()
    private val history = HashMap<String, ArrayDeque<Long>>()
    private val mediaCallbacks = HashMap<MediaController, MediaController.Callback>()

    /** Main-thread state of the overlay. */
    private var overlayTarget: String? = null
    private var overlayWeb = false
    private var redirectWorked = false
    private var continuousUntilGone: String? = null
    private var token = 0L

    val overlay = BlockOverlay(service, service, g.debug, this)

    private val blankUri: Uri get() = Uri.parse("${g.packages.redirectUriPrefix}blank/blank.html")

    suspend fun apply(r: EvaluationResult, foreground: List<ForegroundApp>, snapshot: ConfigSnapshot) {
        val now = SystemTimeAndroid.elapsedRealtime()
        val fgPkgs = foreground.map { it.packageName }.toSet()
        val blockedPkgs = HashSet<String>()
        for ((pkg, d) in r.apps) {
            if (d !is Decision.Block) continue
            blockedPkgs += pkg
            val last = lastEnforcedRt[pkg]
            if (last != null && now - last < DEBOUNCE_MS) continue
            note(pkg, now)
            enforceApp(pkg, d, snapshot, r)
        }
        for (p in r.pages) {
            val d = p.decision as? Decision.Block ?: continue
            val pkg = p.target.browserPackage
            blockedPkgs += pkg
            if (pkg in r.apps.filterValues { it.isBlock }.keys) continue
            val last = lastEnforcedRt[pkg]
            if (last != null && now - last < DEBOUNCE_MS) continue
            note(pkg, now)
            enforceWeb(p, d, snapshot, r)
        }
        pauseMediaCallbacksCleanup(blockedPkgs)
        main.post { housekeeping(fgPkgs, blockedPkgs) }
    }

    private fun note(pkg: String, now: Long) {
        lastEnforcedRt[pkg] = now
        val h = history.getOrPut(pkg) { ArrayDeque() }
        h.addLast(now)
        while (h.isNotEmpty() && now - h.first() > 10_000) h.removeFirst()
        if (h.size > 5) continuousUntilGone = pkg
    }

    // ---- apps (§10.2) ------------------------------------------------------------------------

    private suspend fun enforceApp(pkg: String, d: Decision.Block, snapshot: ConfigSnapshot, r: EvaluationResult) {
        Events.decision(pkg, null, d)
        pauseMedia(pkg)
        val settingsGuard = d.reasons.any { it.source == ReasonSource.StrictSettings }
        val model = model(pkg, label(pkg), icon(pkg), d, snapshot, r, web = false)
        main.post {
            if (settingsGuard) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                main.postDelayed({ goHome() }, 400)
            } else {
                goHome()
            }
            Brp.event("enforced", "how" to "HOME", "target" to pkg)
            showOverlay(pkg, model, web = false, redirect = false, reason = "app")
            main.postDelayed({ handlePip(pkg) }, 300)
            main.postDelayed({ checkStuck(pkg) }, 1_000)
        }
    }

    private fun goHome() {
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            try {
                service.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun pipWindows(pkg: String): List<AccessibilityWindowInfo> = service.foreground.windowsSafe().filter { w ->
        w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            w.isInPictureInPictureMode &&
            runCatching { w.root?.packageName?.toString() }.getOrNull() == pkg
    }

    private fun handlePip(pkg: String) {
        val pips = pipWindows(pkg)
        if (pips.isEmpty()) return
        for (w in pips) {
            val root = runCatching { w.root }.getOrNull()
            root?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id)
        }
        main.postDelayed({
            for (w in pipWindows(pkg)) {
                val b = Rect()
                w.getBoundsInScreen(b)
                overlay.cover("$pkg#${w.id}", b)
                Brp.event("enforced", "how" to "OVERLAY", "target" to pkg, "pip" to true)
            }
        }, 300)
    }

    private fun checkStuck(pkg: String) {
        if (overlayTarget != pkg) return
        val still = service.foreground.current().any { it.packageName == pkg && !it.secondary }
        val lockTask = service.getSystemService(ActivityManager::class.java)?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        if (still || lockTask) {
            overlay.current?.let { overlay.update(it.copy(stuck = true)) }
            Brp.event("overlay", "state" to "stuck", "reason" to if (lockTask) "lock-task" else "still-foreground", "target" to pkg)
        }
    }

    // ---- web (§7.6) --------------------------------------------------------------------------

    private suspend fun enforceWeb(p: PageDecision, d: Decision.Block, snapshot: ConfigSnapshot, r: EvaluationResult) {
        val pkg = p.target.browserPackage
        Events.decision(pkg, p.target.url, d)
        val host = p.url.hostUnicode.ifEmpty { p.target.url }
        val model = model(pkg, host, icon(pkg), d, snapshot, r, web = true)
        service.urlReader.forget(pkg)
        g.scope.launch {
            val uri = blankUri
            runCatching { service.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val entry = snapshot.browsers.firstOrNull { it.packageName == pkg }
            var how = if (Build.VERSION.SDK_INT >= 30 && entry?.canEditUrl != false && entry != null) {
                if (rewrite(pkg, entry, uri.toString())) "URL_REWRITE" else null
            } else {
                null
            }
            if (how == null && viewIntent(pkg, uri)) how = "VIEW_INTENT"
            if (how == null) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                how = "BACK"
            }
            service.urlReader.forget(pkg)
            Brp.event("enforced", "how" to how, "target" to pkg, "url" to p.target.url)
            val worked = how != "BACK"
            main.post { showOverlay(pkg, model, web = true, redirect = worked, reason = "web") }
        }
    }

    @RequiresApi(30)
    private suspend fun rewrite(pkg: String, entry: dev.brainrotprevention.model.BrowserEntry, text: String): Boolean {
        val root = service.foreground.windowsSafe()
            .firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    runCatching { it.root?.packageName?.toString() }.getOrNull() == pkg
            }
            ?.root ?: return false
        val node = service.urlReader.find(root, pkg, entry)?.first ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val deadline = SystemTimeAndroid.elapsedRealtime() + 300
        while (SystemTimeAndroid.elapsedRealtime() < deadline) {
            node.refresh()
            if (node.isFocused) break
            delay(30)
        }
        val fresh = service.foreground.windowsSafe()
            .firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    runCatching { it.root?.packageName?.toString() }.getOrNull() == pkg
            }
            ?.root?.let { service.urlReader.find(it, pkg, entry)?.first } ?: node
        fresh.refresh()
        val actions = fresh.actionList.map { it.id }
        val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        if (AccessibilityNodeInfo.ACTION_SET_TEXT !in actions || imeEnter !in actions) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!fresh.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        return fresh.performAction(imeEnter)
    }

    private fun viewIntent(pkg: String, uri: Uri): Boolean {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html").setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (service.packageManager.resolveActivity(i, 0) == null) return false
        return try {
            service.startActivity(i)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    // ---- overlay -----------------------------------------------------------------------------

    private fun showOverlay(pkg: String, m: BlockModel, web: Boolean, redirect: Boolean, reason: String) {
        overlayTarget = pkg
        overlayWeb = web
        redirectWorked = redirect
        overlay.show(m.copy(token = ++token), reason)
        Brp.event("enforced", "how" to "OVERLAY", "target" to pkg)
    }

    private fun housekeeping(fg: Set<String>, blocked: Set<String>) {
        val c = continuousUntilGone
        if (c != null && c !in fg) continuousUntilGone = null
        // PiP covers: keep only for blocked packages that still have a PiP window.
        val keep = HashSet<String>()
        for (pkg in blocked) for (w in pipWindows(pkg)) keep += "$pkg#${w.id}"
        overlay.uncover(keep)
    }

    override fun close() {
        main.post {
            val target = overlayTarget
            if (target == null) {
                overlay.hide("close")
                return@post
            }
            val lockTask = service.getSystemService(ActivityManager::class.java)?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            val inFront = service.foreground.current().any { it.packageName == target && !it.secondary }
            if (overlayWeb && redirectWorked && !lockTask) {
                overlay.hide("close")
                overlayTarget = null
                return@post
            }
            if ((inFront && g.engineHost.isBlocked(target)) || lockTask || continuousUntilGone == target) {
                overlay.current?.let { overlay.update(it.copy(stuck = true, message = "Exit the app first")) }
                goHome()
                Brp.event("enforced", "how" to "HOME", "target" to target, "from" to "close")
                return@post
            }
            overlay.hide("close")
            overlayTarget = null
            if (overlayWeb) goHome()
        }
    }

    override fun pause15() {
        val id = overlayScheduleIdForPause ?: return
        g.scope.launch {
            val r = g.data.schedules.pause(id, g.clock.now() + 15 * 60_000L)
            if (r is WriteResult.Ok) {
                main.post {
                    overlay.hide("pause")
                    overlayTarget = null
                }
                g.engineHost.requestEvaluate("pause")
            } else {
                main.post { overlay.current?.let { overlay.update(it.copy(message = "Locked by Strict Mode")) } }
            }
        }
    }

    override fun allowBrowser() {
        val pkg = overlayTarget ?: return
        g.scope.launch {
            val r = g.data.settings.excludeFromBlockScreen(pkg)
            if (r is WriteResult.Ok) {
                main.post {
                    overlay.hide("allow-browser")
                    overlayTarget = null
                }
                g.engineHost.requestEvaluate("allow-browser")
            } else {
                main.post { overlay.current?.let { overlay.update(it.copy(message = "Locked by Strict Mode")) } }
            }
        }
    }

    @Volatile private var overlayScheduleIdForPause: Long? = null

    fun hideAll(reason: String) = main.post {
        overlay.hideAll(reason)
        overlayTarget = null
    }

    // ---- model (§10.3) -----------------------------------------------------------------------

    private suspend fun model(
        pkg: String,
        title: String,
        icon: Drawable?,
        d: Decision.Block,
        snapshot: ConfigSnapshot,
        r: EvaluationResult,
        web: Boolean,
    ): BlockModel {
        val names = d.reasons.map { reasonText(it, snapshot) }
        val scheduleReasons = d.reasons.filter { it.source is ReasonSource.Schedule }
        val strictRow = snapshot.strict
        val strictDisplay = r.summary.strict
        val singleSchedule = (scheduleReasons.singleOrNull()?.source as? ReasonSource.Schedule)?.scheduleId
        val followed = singleSchedule != null &&
            dev.brainrotprevention.engine.StrictPolicy.followed(strictRow, snapshot.schedules)
                .contains(singleSchedule)
        val lockEditing = strictRow.isOn &&
            StrictOptions.has(strictRow.options, StrictOptions.LOCK_EDITING) &&
            (strictDisplay == StrictDisplayState.ACTIVE || (strictDisplay == StrictDisplayState.STANDBY && followed))
        val showPause = d.reasons.size == 1 && singleSchedule != null && !snapshot.prefs.hidePauseOption && !lockEditing
        overlayScheduleIdForPause = if (showPause) singleSchedule else null
        val allowBrowser = d.reasons.any { it.source is ReasonSource.UnsupportedBrowser } && !strictRow.isOn
        val day = Instant.ofEpochMilli(g.clock.now()).atZone(g.clock.zone()).toLocalDate().toString()
        val held = if (web) 0 else runCatching { g.data.notificationCounts.get(pkg, day) }.getOrDefault(0)
        return BlockModel(
            title = title,
            icon = icon?.let { runCatching { it.toBitmap(128, 128).asImageBitmap() }.getOrNull() },
            blockedBy = "Blocked by ${names.first()}",
            chips = names.drop(1),
            untilLine = untilText(d.until),
            showPause = showPause,
            showAllowBrowser = allowBrowser,
            motivation = Motivation.line(service),
            notificationsLine = if (held > 0) "$held notification${if (held == 1) "" else "s"} held today" else null,
        )
    }

    private fun reasonText(r: Reason, s: ConfigSnapshot): String {
        val base = when (val src = r.source) {
            is ReasonSource.Schedule -> s.schedules.firstOrNull { it.id == src.scheduleId }?.name ?: "a schedule"
            ReasonSource.QuickBlock -> "Quick Block"
            ReasonSource.StrictSettings -> "Strict Mode (Settings)"
            ReasonSource.StrictUninstall -> "Strict Mode (uninstall)"
            is ReasonSource.UnsupportedBrowser ->
                "unsupported browser" +
                    (
                        src.scheduleId?.takeIf {
                            it != QUICK_BLOCK_ID
                        }?.let { id -> s.schedules.firstOrNull { it.id == id }?.name?.let { " · $it" } }
                            ?: ""
                        )
        }
        return when (r.limit) {
            LimitKind.TIME -> "$base · time limit"
            LimitKind.LAUNCHES -> "$base · launch limit"
            null -> base
        }
    }

    private fun untilText(u: Until): String = when (u) {
        is Until.At -> {
            val now = g.clock.now()
            val zone = g.clock.zone()
            val at = Instant.ofEpochMilli(u.instant).atZone(zone)
            val sameDay = at.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            if (sameDay) {
                "until ${DateTimeFormatter.ofPattern(
                    "HH:mm"
                ).format(at)}"
            } else {
                "until ${DateTimeFormatter.ofPattern("EEE HH:mm").format(at)}"
            }
        }
        is Until.WhileConditions -> "while ${u.label}"
        Until.UntilStopped -> "until you stop it"
        Until.Ongoing -> "all week"
        is Until.UnlockCondition -> when (u.method) {
            dev.brainrotprevention.model.StrictMethod.TIMER -> u.endsAt?.let {
                "until Strict Mode ends at ${DateTimeFormatter.ofPattern(
                    "EEE HH:mm"
                ).format(Instant.ofEpochMilli(it).atZone(g.clock.zone()))}"
            } ?: "until Strict Mode ends"
            dev.brainrotprevention.model.StrictMethod.CHARGER -> "until you unlock Strict Mode with the charger"
            dev.brainrotprevention.model.StrictMethod.PIN -> "until the PIN is entered"
            dev.brainrotprevention.model.StrictMethod.COOLDOWN -> "until you wait out the cooldown"
            dev.brainrotprevention.model.StrictMethod.SCHEDULES -> "while followed schedules block"
        }
    }

    private fun label(pkg: String): String = g.apps.label(pkg)

    private fun icon(pkg: String): Drawable? = g.apps.icon(pkg)

    // ---- media (§10.2 step 1) ----------------------------------------------------------------

    private fun pauseMedia(pkg: String) {
        val msm = service.getSystemService(MediaSessionManager::class.java) ?: return
        val controllers = try {
            msm.getActiveSessions(ComponentName(service, NotificationBlockerService::class.java))
        } catch (_: SecurityException) {
            return
        }
        for (c in controllers) {
            if (c.packageName != pkg) continue
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
                Brp.event("media", "pkg" to pkg, "action" to "pause")
            }
            if (mediaCallbacks.keys.none { it.sessionToken == c.sessionToken }) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (state?.state == PlaybackState.STATE_PLAYING && g.engineHost.isBlocked(pkg)) {
                            c.transportControls.pause()
                            Brp.event("media", "pkg" to pkg, "action" to "re-pause")
                        }
                    }
                }
                c.registerCallback(cb, main)
                mediaCallbacks[c] = cb
            }
        }
    }

    private fun pauseMediaCallbacksCleanup(blocked: Set<String>) {
        val it = mediaCallbacks.entries.iterator()
        while (it.hasNext()) {
            val (c, cb) = it.next()
            if (c.packageName in blocked) continue
            runCatching { c.unregisterCallback(cb) }
            it.remove()
        }
    }

    companion object {
        const val DEBOUNCE_MS = 1_500L
    }
}
