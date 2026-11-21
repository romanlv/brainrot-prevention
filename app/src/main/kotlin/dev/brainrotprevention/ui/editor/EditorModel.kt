package dev.brainrotprevention.ui.editor

import dev.brainrotprevention.engine.BulkAddResult
import dev.brainrotprevention.engine.RuleInput
import dev.brainrotprevention.engine.ScheduleValidator
import dev.brainrotprevention.engine.UrlNormalizer
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.model.Weekdays
import dev.brainrotprevention.model.WifiCondition
import dev.brainrotprevention.ui.format.Format
import kotlinx.serialization.Serializable

/** Templates offered when creating a schedule (§12, our own wording). */
enum class Template(val title: String, val icon: String, val description: String) {
    WORKDAY("Workday focus", "💼", "Mon–Fri 09:00–17:00"),
    MORNING("Morning calm", "🌅", "Every day 06:00–09:00"),
    WIND_DOWN("Wind down", "🌙", "Every day 21:00–24:00"),
    SOCIAL_CAP("Daily social cap", "⏳", "30 min a day, then blocked"),
    SUNDAY("Sunday detox", "🌿", "Sunday, all day"),
    ;

    fun schedule(): Schedule = when (this) {
        WORKDAY -> Schedule(
            name = title,
            icon = icon,
            timeWindows = listOf(TimeWindow(days = Weekdays.WEEKDAYS, startMin = 540, endMin = 1020))
        )
        MORNING -> Schedule(name = title, icon = icon, timeWindows = listOf(TimeWindow(days = Weekdays.ALL, startMin = 360, endMin = 540)))
        WIND_DOWN -> Schedule(
            name = title,
            icon = icon,
            timeWindows = listOf(TimeWindow(days = Weekdays.ALL, startMin = 1260, endMin = 1440))
        )
        SOCIAL_CAP -> Schedule(
            name = title,
            icon = icon,
            limits = listOf(UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 30 * EditorOps.MIN)),
        )
        SUNDAY -> Schedule(name = title, icon = icon, timeWindows = listOf(TimeWindow(days = Weekdays.SUN, startMin = 0, endMin = 1440)))
    }
}

/** What the website input would add (live preview, §7.3). */
sealed interface RulePreview {
    data object Empty : RulePreview

    data class Adds(val rule: WebRule, val text: String) : RulePreview

    /** A URL with a path: whole site (domain) or pages containing this text (keyword, anywhere in URL). */
    data class Choose(val domain: WebRule, val keyword: WebRule) : RulePreview

    data class Invalid(val reason: String) : RulePreview
}

/** The website/keyword field (kind, typed text, anywhere-in-URL). Hoisted into the editor ViewModels so Save sees it. */
@Serializable
data class WebInput(val kind: WebRuleKind = WebRuleKind.DOMAIN, val text: String = "", val anywhere: Boolean = false)

/** What Save does with text still in the website field. */
sealed interface PendingWeb {
    /** Nothing typed, or the typed rule added to [rules] (unchanged when it was already there). */
    data class Ready(val rules: List<WebRule>, val consumedInput: Boolean) : PendingWeb

    /** The input needs the user first (a path to choose for, or invalid text): Save stops with [message]. */
    data class Unfinished(val message: String) : PendingWeb
}

/** Pure edits of a draft schedule. Every function returns a new draft; nothing here reads a clock. */
object EditorOps {
    const val MIN = 60_000L
    val ICONS = listOf("🛡️", "💼", "📚", "🌅", "🌙", "⏳", "🌿", "🎮", "📱", "🎬", "🧘", "🏃", "🍽️", "😴", "🎯", "🔕")
    val TIME_RANGE = ScheduleValidator.TIME_MIN_MS..ScheduleValidator.TIME_MAX_MS
    val LAUNCH_RANGE = 1L..ScheduleValidator.LAUNCHES_MAX

    fun blank(): Schedule = Schedule(name = "", icon = ICONS.first())

    fun preview(kind: WebRuleKind, input: String, anywhere: Boolean, verb: String = "Blocks"): RulePreview {
        if (input.isBlank()) return RulePreview.Empty
        return when (val r = UrlNormalizer.normalizeRuleInput(kind, input, anywhere)) {
            is RuleInput.Ok -> RulePreview.Adds(r.rule, Format.rule(r.rule, verb))
            is RuleInput.Choice -> RulePreview.Choose(r.domain, r.keyword)
            is RuleInput.Invalid -> RulePreview.Invalid(r.reason.replaceFirstChar { it.uppercase() })
        }
    }

    /**
     * Save with text still in the website field (§12): a valid entry is added as if Add had been tapped, so a typed
     * "reddit.com" is never dropped silently; an entry that needs a choice or is invalid stops the save.
     */
    fun commitPending(rules: List<WebRule>, input: WebInput, verb: String = "Blocks"): PendingWeb =
        when (val p = preview(input.kind, input.text, input.anywhere, verb)) {
            RulePreview.Empty -> PendingWeb.Ready(rules, consumedInput = false)
            is RulePreview.Adds -> PendingWeb.Ready(addRule(rules, p.rule) ?: rules, consumedInput = true)
            is RulePreview.Choose -> PendingWeb.Unfinished(
                "“${input.text.trim()}” has a path. Choose the whole site or only pages containing it, or clear the field, then save.",
            )
            is RulePreview.Invalid -> PendingWeb.Unfinished(
                "The website field still has “${input.text.trim()}”, which can't be added (${p.reason.trimEnd('.')}). " +
                    "Fix or clear it, then save.",
            )
        }

    /** Adds [rule] unless a rule with the same (kind, value) exists; returns null for a duplicate. */
    fun addRule(rules: List<WebRule>, rule: WebRule): List<WebRule>? =
        if (rules.any { it.kind == rule.kind && it.value == rule.value }) null else rules + rule

    fun bulk(rules: List<WebRule>, text: String): Pair<List<WebRule>, BulkAddResult> {
        val r = UrlNormalizer.bulkAdd(text, rules)
        return (rules + r.added) to r
    }

    /** [current] plus the [added] rules it doesn't have yet, capped at the per-schedule maximum. */
    fun mergeAdded(current: List<WebRule>, added: List<WebRule>): List<WebRule> {
        val have = current.mapTo(HashSet()) { it.kind to it.value }
        return (current + added.filter { (it.kind to it.value) !in have }).take(maxOf(current.size, ScheduleValidator.MAX_WEB_RULES))
    }

    fun bulkSummary(r: BulkAddResult): String {
        val dup = if (r.duplicates == 1) "1 duplicate" else "${r.duplicates} duplicates"
        val base = "Added ${r.added.size} · $dup · ${r.invalid} invalid"
        return if (r.overLimit > 0) "$base. ${r.overLimit} not added: $LIMIT_TEXT." else base
    }

    /** The per-schedule cap (review round 2, adversarial 1), as shown in the editor. */
    val LIMIT_TEXT = "a schedule can have at most ${"%,d".format(
        java.util.Locale.ROOT,
        ScheduleValidator.MAX_WEB_RULES
    )} websites and keywords"

    fun atLimit(rules: List<WebRule>): Boolean = rules.size >= ScheduleValidator.MAX_WEB_RULES

    /** Bulk paste text kept in the dialog (and its saved state); longer pastes are cut here. */
    const val MAX_PASTE_CHARS = 64_000

    /** Rule rows shown before "Show more" (the editor column isn't lazy). */
    const val RULES_PAGE = 50

    fun setMode(s: Schedule, mode: Mode): Schedule = if (mode == Mode.ALLOWLIST) {
        // §5.4: no limits in ALLOWLIST; §6.3: ALLOWLIST can't be followed by Strict Mode SCHEDULES.
        s.copy(mode = mode, limits = emptyList(), strictFollowed = false)
    } else {
        s.copy(mode = mode)
    }

    fun defaultWindow(existing: List<TimeWindow>): TimeWindow =
        if (existing.isEmpty()) {
            TimeWindow(
                days = Weekdays.WEEKDAYS,
                startMin = 540,
                endMin = 1020
            )
        } else {
            TimeWindow(days = Weekdays.ALL, startMin = 1260, endMin = 1440)
        }

    /** Stores an end of 00:00 as 24:00 (§4). */
    fun endMinute(hour: Int, minute: Int): Int = (hour * 60 + minute).let { if (it == 0) 1440 else it }

    fun toggleDay(mask: Int, dayIndex: Int): Int = mask xor (1 shl dayIndex)

    fun setLimit(s: Schedule, kind: LimitKind, allowance: Long?, period: Period = s.limit(kind)?.period ?: Period.DAILY): Schedule {
        val others = s.limits.filter { it.kind != kind }
        if (allowance == null) return s.copy(limits = others)
        val existing = s.limit(kind)
        val l = existing?.copy(allowance = allowance, period = period) ?: UsageLimit(kind = kind, period = period, allowance = allowance)
        return s.copy(limits = (others + l).sortedBy { it.kind })
    }

    fun defaultAllowance(kind: LimitKind): Long = if (kind == LimitKind.TIME) 30 * MIN else 10

    /** TIME stepper: 1, 5, 10 … 60 by 5, then by 15 up to 23 h. */
    fun stepTime(v: Long, up: Boolean): Long {
        val m = v / MIN
        val next = if (up) {
            when {
                m < 5 -> 5
                m < 60 -> (m / 5 + 1) * 5
                else -> (m / 15 + 1) * 15
            }
        } else {
            when {
                m <= 5 -> 1
                m <= 60 -> ((m - 1) / 5) * 5
                else -> ((m - 1) / 15) * 15
            }
        }
        return next.coerceAtLeast(1) * MIN
    }

    fun stepLaunches(v: Long, up: Boolean): Long = if (up) v + 1 else v - 1

    /** Strips surrounding quotes (§4.1 SSID storage); null when blank or already present. */
    fun addWifi(rows: List<WifiCondition>, ssid: String): List<WifiCondition>? {
        val clean = ssid.trim().removeSurrounding("\"")
        if (clean.isEmpty() || rows.any { it.ssid == clean }) return null
        return rows + WifiCondition(ssid = clean)
    }

    fun addLocation(rows: List<LocationCondition>, row: LocationCondition): List<LocationCondition> = rows + row

    /** "Block these all the time?" (§12): no trigger and no budget. */
    fun needsAlwaysPrompt(s: Schedule): Boolean = s.triggerTypes.isEmpty() && s.limits.isEmpty()

    /** Domains from `app_domains.json` not yet in the list. */
    fun domainSuggestions(pkg: String, appDomains: Map<String, List<String>>, rules: List<WebRule>): List<String> =
        appDomains[pkg].orEmpty().filter { d -> rules.none { it.kind == WebRuleKind.DOMAIN && it.value == d } }
}
