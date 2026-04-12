/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * SyncConfig - Relay server configuration.
 */

package com.metrolist.shared.sync

/**
 * Configuration for the sync relay server.
 */
object SyncConfig {
    /** The live relay server deployed on Back4App */
    const val SERVER_URL = "wss://metrolistsyncrelay-ooae5v0w.b4a.run/sync"

    /** Reconnection delay after disconnect (ms) */
    const val RECONNECT_DELAY_MS = 3000L

    /** Maximum reconnection attempts before giving up */
    const val MAX_RECONNECT_ATTEMPTS = 10

    /** How often to send position updates while playing (ms) */
    const val POSITION_UPDATE_INTERVAL_MS = 2000L
}
