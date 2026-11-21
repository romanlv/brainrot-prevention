package dev.brainrotprevention.testing

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter

/**
 * `-e filter dev.brainrotprevention.testing.MilestoneFilter -e milestone M<n>`: keeps tests whose `@Milestone`
 * (method, else class) is at or below M<n>. Tests without `@Milestone` are dropped, so G3 runs only tagged tests.
 */
class MilestoneFilter : Filter() {
    private val limit: Int? = parse(runCatching { InstrumentationRegistry.getArguments().getString("milestone") }.getOrNull())

    override fun shouldRun(description: Description): Boolean {
        if (limit == null) return true
        if (!description.isTest) return description.children.any { shouldRun(it) }
        val m = description.getAnnotation(Milestone::class.java) ?: description.testClass?.getAnnotation(Milestone::class.java)
        val n = parse(m?.value) ?: return false
        return n <= limit
    }

    override fun describe(): String = "milestone <= M$limit"

    companion object {
        fun parse(s: String?): Int? = s?.trim()?.removePrefix("M")?.removePrefix("m")?.toIntOrNull()
    }
}
