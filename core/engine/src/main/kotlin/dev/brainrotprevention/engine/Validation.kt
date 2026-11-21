package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Backup
import dev.brainrotprevention.model.BackupCodec
import dev.brainrotprevention.model.BackupFormatException
import dev.brainrotprevention.model.BackupPrefs
import dev.brainrotprevention.model.BackupWebRule
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.Weekdays

/** The first offending row: [path] like `schedules[2].webRules[0]`. */
data class ValidationError(val path: String, val message: String) {
    override fun toString(): String = "$path: $message"
}

/** Editor rules (§4.1 ranges, §5.4, §6.3, §7.3). */
object ScheduleValidator {
    const val NAME_MAX = 40
    const val TIME_MIN_MS = Periods.MINUTE
    const val TIME_MAX_MS = 23 * Periods.HOUR
    const val LAUNCHES_MAX = 500L
    const val RADIUS_MIN = 100
    const val RADIUS_MAX = 5000
    const val MAX_GEOFENCES = 100

    /**
     * Websites and keywords per schedule and in Quick Block (review round 2, adversarial 1): a 50 000-line hosts
     * file would otherwise be parsed and laid out in the one process that also hosts the accessibility service.
     */
    const val MAX_WEB_RULES = 2_000
    val TOO_MANY_RULES = "at most ${String.format(java.util.Locale.ROOT, "%,d", MAX_WEB_RULES)} websites and keywords per schedule"
    private val packageRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun isPackageName(s: String): Boolean = packageRegex.matches(s)

    /** Normalises a stored/imported rule; null when invalid. A DOMAIN with a path keeps the domain. */
    fun normalizeRule(
        rule: WebRule
    ): WebRule? = when (val r = UrlNormalizer.normalizeRuleInput(rule.kind, rule.value, rule.anywhereInUrl)) {
        is RuleInput.Ok -> r.rule.copy(id = rule.id, anywhereInUrl = rule.anywhereInUrl && rule.kind == r.rule.kind)
        is RuleInput.Choice -> r.domain.copy(id = rule.id)
        is RuleInput.Invalid -> null
    }

    /** Validates one schedule; returns the first error or null. Rules must already be normalised. */
    fun validate(s: Schedule, path: String = "schedule"): ValidationError? {
        val name = s.name.trim()
        if (name.isEmpty() || name.length > NAME_MAX) return ValidationError("$path.name", "name must be 1–$NAME_MAX characters")
        s.timeWindows.forEachIndexed { i, w ->
            val p = "$path.timeWindows[$i]"
            if (!Weekdays.isValid(w.days)) return ValidationError(p, "days must be a weekday mask 1–127")
            if (w.startMin !in 0..1439) return ValidationError(p, "startMin must be 0–1439")
            if (w.endMin !in 1..1440) return ValidationError(p, "endMin must be 1–1440")
            if (w.startMin == w.endMin) return ValidationError(p, "start and end must differ")
        }
        if (s.mode == Mode.ALLOWLIST &&
            s.limits.isNotEmpty()
        ) {
            return ValidationError("$path.limits[0]", "limits are not allowed in allowlist mode")
        }
        if (s.mode == Mode.ALLOWLIST &&
            s.strictFollowed
        ) {
            return ValidationError("$path.strictFollowed", "allowlist schedules can't be followed by Strict Mode")
        }
        val kinds = HashSet<LimitKind>()
        s.limits.forEachIndexed { i, l ->
            val p = "$path.limits[$i]"
            if (!kinds.add(l.kind)) return ValidationError(p, "only one ${l.kind} limit per schedule")
            when (l.kind) {
                LimitKind.TIME -> if (l.allowance !in
                    TIME_MIN_MS..TIME_MAX_MS
                ) {
                    return ValidationError(p, "TIME allowance must be 1 min–23 h (ms)")
                }
                LimitKind.LAUNCHES -> if (l.allowance !in 1..LAUNCHES_MAX) return ValidationError(p, "LAUNCHES allowance must be 1–500")
            }
        }
        val ssids = HashSet<String>()
        s.wifi.forEachIndexed { i, w ->
            if (w.ssid.isBlank()) return ValidationError("$path.wifi[$i]", "SSID is empty")
            if (!ssids.add(w.ssid)) return ValidationError("$path.wifi[$i]", "duplicate SSID")
        }
        s.locations.forEachIndexed { i, l ->
            val p = "$path.locations[$i]"
            if (l.radiusM !in RADIUS_MIN..RADIUS_MAX) return ValidationError(p, "radiusM must be 100–5000")
            if (l.lat !in -90.0..90.0 || l.lon !in -180.0..180.0) return ValidationError(p, "coordinates out of range")
        }
        validateApps(s.apps, "$path.apps")?.let { return it }
        return validateRules(s.webRules, "$path.webRules")
    }

    fun validateApps(apps: List<String>, path: String): ValidationError? {
        val seen = HashSet<String>()
        apps.forEachIndexed { i, a ->
            if (!isPackageName(a)) return ValidationError("$path[$i]", "not a package name")
            if (!seen.add(a)) return ValidationError("$path[$i]", "duplicate package")
        }
        return null
    }

    /** Rules must be in normalised form ([normalizeRule] returns them unchanged) and unique by (kind, value). */
    fun validateRules(rules: List<WebRule>, path: String): ValidationError? {
        if (rules.size > MAX_WEB_RULES) return ValidationError(path, TOO_MANY_RULES)
        val seen = HashSet<Pair<Any, String>>()
        rules.forEachIndexed { i, r ->
            val n = normalizeRule(r) ?: return ValidationError("$path[$i]", "invalid ${r.kind.name.lowercase()} \"${r.value}\"")
            if (n.value != r.value) return ValidationError("$path[$i]", "not normalised (expected \"${n.value}\")")
            if (!seen.add(r.kind to r.value)) return ValidationError("$path[$i]", "duplicate rule")
        }
        return null
    }
}

sealed interface BackupValidation {
    /** [backup] has normalised web rules; write it with `BackupCodec.toImportPlan`. */
    data class Valid(val backup: Backup) : BackupValidation

    data class Invalid(val error: ValidationError) : BackupValidation
}

/** Import validation (§4.3): every row with the editor rules; the first offending row is reported. */
object BackupValidator {
    fun validateText(text: String): BackupValidation = try {
        validate(BackupCodec.decode(text))
    } catch (e: BackupFormatException) {
        BackupValidation.Invalid(ValidationError("file", e.message ?: "invalid"))
    }

    fun validate(backup: Backup): BackupValidation {
        val normalized = ArrayList<dev.brainrotprevention.model.BackupSchedule>()
        var geofences = 0
        backup.schedules.forEachIndexed { i, bs ->
            val path = "schedules[$i]"
            val rules = normalizeRules(bs.webRules, "$path.webRules") { return BackupValidation.Invalid(it) }
            val n = bs.copy(webRules = rules)
            val schedule = with(BackupCodec) { n.toSchedule() }
            ScheduleValidator.validate(schedule, path)?.let { return BackupValidation.Invalid(it) }
            geofences += bs.locations.size
            if (geofences > ScheduleValidator.MAX_GEOFENCES) {
                return BackupValidation.Invalid(ValidationError("$path.locations", "at most ${ScheduleValidator.MAX_GEOFENCES} locations"))
            }
            normalized += n
        }
        val qbRules = normalizeRules(backup.quickBlock.webRules, "quickBlock.webRules") { return BackupValidation.Invalid(it) }
        ScheduleValidator.validateApps(backup.quickBlock.apps, "quickBlock.apps")?.let { return BackupValidation.Invalid(it) }
        ScheduleValidator.validateRules(qbRules.map { with(BackupCodec) { it.toRule() } }, "quickBlock.webRules")
            ?.let { return BackupValidation.Invalid(it) }
        ScheduleValidator.validateApps(backup.excludedBrowsers, "excludedBrowsers")?.let { return BackupValidation.Invalid(it) }
        validatePrefs(backup.prefs)?.let { return BackupValidation.Invalid(it) }
        return BackupValidation.Valid(backup.copy(schedules = normalized, quickBlock = backup.quickBlock.copy(webRules = qbRules)))
    }

    fun validatePrefs(p: BackupPrefs): ValidationError? = when {
        p.dayStartHour !in 0..23 -> ValidationError("prefs.dayStartHour", "must be 0–23")
        p.limitWarningMin !in 0..120 -> ValidationError("prefs.limitWarningMin", "must be 0–120")
        p.notifyBeforeStartMin !in 0..120 -> ValidationError("prefs.notifyBeforeStartMin", "must be 0–120")
        p.notifyBeforePauseEndMin !in 0..60 -> ValidationError("prefs.notifyBeforePauseEndMin", "must be 0–60")
        else -> null
    }

    private inline fun normalizeRules(rules: List<BackupWebRule>, path: String, fail: (ValidationError) -> Nothing): List<BackupWebRule> {
        // Checked before normalising: an oversized file is refused without parsing every rule.
        if (rules.size > ScheduleValidator.MAX_WEB_RULES) fail(ValidationError(path, ScheduleValidator.TOO_MANY_RULES))
        return rules.mapIndexed { i, r ->
            val n = ScheduleValidator.normalizeRule(WebRule(kind = r.kind, value = r.value, anywhereInUrl = r.anywhereInUrl))
                ?: fail(ValidationError("$path[$i]", "invalid ${r.kind.name.lowercase()} \"${r.value}\""))
            BackupWebRule(n.kind, n.value, n.anywhereInUrl)
        }
    }
}
