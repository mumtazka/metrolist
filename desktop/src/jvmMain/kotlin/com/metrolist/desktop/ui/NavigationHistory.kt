package com.metrolist.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Represents a single navigation destination, capturing the screen type and
 * any supplemental context needed to restore the view (e.g., playlist IDs).
 */
data class NavEntry(
    val screen: NavScreen,
    /** Non-null when screen == PLAYLIST and the target is a remote YT Music playlist. */
    val playlistId: String? = null,
    /** Non-null when screen == PLAYLIST and the target is a device-local playlist. */
    val localPlaylistId: String? = null,
)

/**
 * Browser-style navigation history with back / forward stacks.
 *
 * Compose-observable: [canGoBack] and [canGoForward] are backed by [mutableStateOf]
 * so any composable reading them will recompose automatically on change.
 *
 * Thread-safety: Must only be called from the Compose main thread.
 */
class NavigationHistory(initialScreen: NavScreen = NavScreen.HOME) {

    private val backStack  = ArrayDeque<NavEntry>()
    private val fwdStack   = ArrayDeque<NavEntry>()

    /** The currently visible destination. */
    var current: NavEntry by mutableStateOf(NavEntry(initialScreen))
        private set

    /** True when there is at least one entry to go back to. */
    var canGoBack: Boolean by mutableStateOf(false)
        private set

    /** True when there is at least one entry to go forward to. */
    var canGoForward: Boolean by mutableStateOf(false)
        private set

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Navigate to [entry]. If [entry] is identical to the current destination
     * (same screen + same IDs) the call is a no-op to prevent duplicate entries.
     */
    fun navigate(entry: NavEntry) {
        if (entry == current) return
        backStack.addLast(current)
        fwdStack.clear()
        current = entry
        updateFlags()
    }

    /** Convenience overload for screens that carry no supplemental context. */
    fun navigate(screen: NavScreen) = navigate(NavEntry(screen))

    /**
     * Go back one step. Returns the [NavEntry] that becomes current,
     * or null if the back stack is empty.
     */
    fun back(): NavEntry? {
        if (backStack.isEmpty()) return null
        fwdStack.addFirst(current)
        current = backStack.removeLast()
        updateFlags()
        return current
    }

    /**
     * Go forward one step. Returns the [NavEntry] that becomes current,
     * or null if the forward stack is empty.
     */
    fun forward(): NavEntry? {
        if (fwdStack.isEmpty()) return null
        backStack.addLast(current)
        current = fwdStack.removeFirst()
        updateFlags()
        return current
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun updateFlags() {
        canGoBack    = backStack.isNotEmpty()
        canGoForward = fwdStack.isNotEmpty()
    }
}
