package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherAuto
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherHelper
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherManual
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative

enum class WifiLauncherMode(
    val id: Int,
    val factory: (WifiLauncherManager) -> WifiLauncher) {

    MANUAL(0, ::WifiLauncherManual),
    AUTO(1, ::WifiLauncherAuto),
    HELPER(2, ::WifiLauncherHelper),
    NATIVE(3, ::WifiLauncherNative);

    companion object {

        // Aiways: Native (Wi-Fi Direct / own hotspot) reboots the car's head unit the moment it
        // starts. Headunit Server -> Auto finds the phone by itself, on the T-Box's Wi-Fi (head
        // unit and phone both joined, the car's data keeps coming) as well as on the phone's
        // hotspot (gateway probe). Its auto-hotspot option stays off by default.
        val DEFAULT: WifiLauncherMode = AUTO


        fun byIdOrDefault(id: Int): WifiLauncherMode {
            for (mode in WifiLauncherMode.entries) {
                if (mode.id == id)
                    return mode
            }

            return DEFAULT
        }
    }
}
