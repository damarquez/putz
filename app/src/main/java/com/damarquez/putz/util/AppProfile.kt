package com.damarquez.putz.util

import android.os.Process

/**
 * Which Android profile (user) this copy of Putz runs in. A phone can have Putz installed in
 * both the main profile and Samsung's Secure Folder (or a work profile), each with its own
 * settings, and each profile runs its own VPN: Tailscale active in the main profile does nothing
 * for the Secure Folder copy, so that copy can't reach the daemon's Tailscale address and shows
 * it as unreachable. Easy to mix the two copies up, hence the badge and hints.
 */
object AppProfile {
    /** Android user id: 0 is the main profile. Same as UserHandle.getUserId(uid), which isn't public API. */
    val userId: Int = Process.myUid() / 100_000

    val isSecondaryProfile: Boolean = userId != 0

    /** Samsung puts Secure Folder at user ids 150-160; any other secondary user is most likely a work profile. */
    val label: String = when {
        !isSecondaryProfile -> "Main profile"
        userId in 150..160 -> "Secure Folder"
        else -> "Work profile"
    }

    /** Why the daemon may be unreachable from this copy, or null in the main profile. */
    val unreachableHint: String? =
        if (isSecondaryProfile) {
            "This copy of Putz runs in $label, which has its own VPN. Tailscale in your main " +
                "profile doesn't cover it, so the daemon's Tailscale address can't be reached from here. " +
                "Use the Putz in your main profile, or set the host to the PC's local Wi-Fi address."
        } else {
            null
        }
}
