package dev.brainrotprevention.platform.enforce

import android.content.Context
import kotlin.random.Random

/** Our own bundled motivational lines (`assets/motivation.txt`, one per line). */
internal object Motivation {
    @Volatile private var lines: List<String>? = null

    fun line(context: Context): String? {
        val l = lines ?: try {
            context.assets.open("motivation.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }.also { lines = it }
        return if (l.isEmpty()) null else l[Random.nextInt(l.size)]
    }
}
