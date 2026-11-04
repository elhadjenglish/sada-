package app.sada.poc

import android.media.AudioManager
import android.os.Build
import android.os.SystemClock

/**
 * Keeps Android's call-mode state from leaking between questions and test sessions.
 *
 * Selecting the earbud microphone puts the audio system in communication (call) mode and selects a Bluetooth
 * communication device. While that request is active, Android routes other recordings, including the phone-mic
 * wake-word recorder, to the Bluetooth headset input. If the request is ever left behind, the "phone mic" captures
 * near-silence until the process dies (e.g. reinstalling). Android applies these requests asynchronously, so we also
 * wait until the change has really taken effect.
 *
 * clearCommunicationDevice() and setMode(MODE_NORMAL) only withdraw THIS app's requests: they cannot end another
 * app's real call.
 */
object AudioRouteGuard {
    @Suppress("DEPRECATION")
    fun describe(am: AudioManager): String {
        val mode = when (am.mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            else -> "mode ${am.mode}"
        }
        val comm = if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.let { AudioNames.describe(it) } ?: "none" else "n/a"
        return "audio mode $mode; communication device $comm; Bluetooth SCO ${if (am.isBluetoothScoOn) "ON" else "off"}"
    }

    /** True when nothing call-related is active (what wake-word listening on the phone mic needs). */
    @Suppress("DEPRECATION")
    fun isClean(am: AudioManager): Boolean {
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) return false
        if (am.isBluetoothScoOn) return false
        if (Build.VERSION.SDK_INT >= 31) {
            val d = am.communicationDevice
            // On a phone with nothing selected, the default communication device is the earpiece/speaker.
            if (d != null && (d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)) return false
        }
        return true
    }

    /** Withdraws this app's call-mode and communication-device requests. Safe to call any number of times. */
    fun release(am: AudioManager) {
        try { if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() } catch (_: Exception) {}
        try { am.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
    }

    /** Releases, then waits (up to timeoutMs) until Android reports a clean state. Blocking. Returns true if clean. */
    fun releaseAndWait(am: AudioManager, timeoutMs: Long): Boolean {
        release(am)
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (isClean(am)) return true
            SystemClock.sleep(50)
        }
        return isClean(am)
    }

    /** Checks the state; if it is not clean, releases and records what was found. Returns true if a repair was needed. */
    fun repairIfNeeded(am: AudioManager, where: String): Boolean {
        if (isClean(am)) return false
        val before = describe(am)
        val ok = releaseAndWait(am, 2000)
        Diag.routeRepairs++
        Diag.log("route-repair", "$where: found $before → ${if (ok) "cleaned" else "STILL NOT CLEAN: " + describe(am)}")
        return true
    }
}
