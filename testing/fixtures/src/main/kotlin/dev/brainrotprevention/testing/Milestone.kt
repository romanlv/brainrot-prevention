package dev.brainrotprevention.testing

/** The §16 milestone a G3 test belongs to, e.g. `@Milestone("M4")` (TAGS.md "G3 instrumentation contract"). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Milestone(val value: String)

/** Runs in the first G3 pass, after `ungrant.sh` (no accessibility, usage access, …). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class NoGrant
