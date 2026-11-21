package dev.brainrotprevention.model

/** Strict Mode option bitmask (§9.2). */
object StrictOptions {
    const val LOCK_EDITING = 1
    const val BLOCK_SETTINGS = 2
    const val BLOCK_UNINSTALL = 4
    const val ALL = LOCK_EDITING or BLOCK_SETTINGS or BLOCK_UNINSTALL

    fun has(mask: Int, option: Int): Boolean = mask and option == option

    /** BLOCK_UNINSTALL requires BLOCK_SETTINGS; no unknown bits. */
    fun isValid(mask: Int): Boolean = mask and ALL.inv() == 0 && (!has(mask, BLOCK_UNINSTALL) || has(mask, BLOCK_SETTINGS))
}
