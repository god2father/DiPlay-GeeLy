package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.shilapi.xcertplay.media.AudioOutputDevice

/**
 * One-tap driver-headrest navigation routing helper for KX11_A2.
 *
 * KX11_A2's CarAudioService maps `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` to `bus1_navigation_out`
 * (48 kHz mono `TYPE_BUS`). Combined with the factory head unit's "Driver Focus" headrest speaker
 * mode, routing CarPlay guidance to `bus1_navigation_out` plays prompts through the driver's
 * headrest speakers without muting cabin media on `bus0_media_out`.
 *
 * State is derived directly from [VehicleAudioRoutes] so this switch never diverges from
 * Settings -> Audio -> Navigation output.
 */
internal object Kx11HeadrestAudioRoute {
    const val NAVIGATION_BUS_ADDRESS = "bus1_navigation_out"

    fun isHeadrestBus(device: AudioOutputDevice?): Boolean =
        device != null &&
            device.type == AudioDeviceInfo.TYPE_BUS &&
            device.address.equals(NAVIGATION_BUS_ADDRESS, ignoreCase = true)

    fun isEnabled(context: Context): Boolean =
        isHeadrestBus(VehicleAudioRoutes.get(context, VehicleAudioRole.NAVIGATION))

    fun findDevice(devices: List<AudioOutputDevice>): AudioOutputDevice? =
        devices.firstOrNull(::isHeadrestBus)

    fun findDevice(manager: AudioManager?): AudioOutputDevice? {
        if (Build.VERSION.SDK_INT < 28) return null
        return findDevice(AudioOutputDevice.outputs(manager).map(AudioOutputDevice::from))
    }

    /**
     * Enables or disables driver-headrest routing for navigation prompts.
     * Returns `true` when the requested state was applied (or already satisfied), and `false`
     * when enabling failed because `bus1_navigation_out` is not present on this head unit.
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled) {
            val manager = context.getSystemService(AudioManager::class.java)
            val matched = findDevice(manager) ?: return false
            VehicleAudioRoutes.set(context, VehicleAudioRole.NAVIGATION, matched)
            return true
        }
        val current = VehicleAudioRoutes.get(context, VehicleAudioRole.NAVIGATION)
        if (isHeadrestBus(current)) {
            VehicleAudioRoutes.set(context, VehicleAudioRole.NAVIGATION, null)
        }
        return true
    }
}
