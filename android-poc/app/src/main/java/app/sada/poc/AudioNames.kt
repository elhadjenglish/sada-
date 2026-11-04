package app.sada.poc

import android.media.AudioDeviceInfo

object AudioNames {
    fun type(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "phone earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP (music quality)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (CALL quality)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Audio headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        else -> "device type $t"
    }

    fun describe(d: AudioDeviceInfo?): String {
        if (d == null) return "unknown (not reported)"
        val name = d.productName?.toString()?.trim().orEmpty()
        return if (name.isEmpty()) type(d.type) else "${type(d.type)} — $name"
    }
}
