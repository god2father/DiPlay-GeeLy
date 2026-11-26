package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log

/**
 * Reflection-only controller for KX11_A2's built-in `ecarx.adaptapi.jar`
 * `com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction`.
 *
 * Design guards:
 * 1. Holds a single process-wide instance constructed with `applicationContext` to prevent
 *    ServiceConnection leaks.
 * 2. Edge-triggered only: switches layout once when CarPlay navigation starts and NEVER loops
 *    or re-asserts, so pressing the left stalk button to view fuel consumption / TPMS stays
 *    untouched.
 * 3. Always restores `MODE_DEFAULT (1)` when navigation ends, CarPlay disconnects, or the user
 *    turns off the experimental toggle.
 */
object Kx11DimMenuController {
    private const val TAG = "DiPlay-KX11DimMenu"
    private const val CLASS_NAME = "com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction"

    const val MODE_DEFAULT = 1
    const val MODE_SIMPLIFY = 2
    const val MODE_FULL_NAVI = 3

    private val lock = Any()
    private var initialized = false
    private var menuInstance: Any? = null
    private var switchNaviModeMethod: java.lang.reflect.Method? = null
    private var layoutRequested = false

    fun onNaviActiveChanged(context: Context, active: Boolean) {
        val app = context.applicationContext
        if (!Kx11DimSettings.isSupported()) return
        val enabled = Kx11DimSettings.clusterFullNaviLayoutEnabled(app)
        val targetMode: Int? = synchronized(lock) {
            if (active && enabled) {
                if (layoutRequested) null
                else {
                    layoutRequested = true
                    MODE_FULL_NAVI
                }
            } else {
                if (!layoutRequested) null
                else {
                    layoutRequested = false
                    MODE_DEFAULT
                }
            }
        }
        if (targetMode != null) {
            switchNow(app, targetMode)
        }
    }

    fun settingChanged(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (!enabled) {
            val shouldRestore = synchronized(lock) {
                val prev = layoutRequested
                layoutRequested = false
                prev
            }
            if (shouldRestore) {
                switchNow(app, MODE_DEFAULT)
            }
        }
    }

    internal fun switchNow(context: Context, mode: Int): Boolean {
        val app = context.applicationContext
        val (instance, method) = synchronized(lock) {
            if (!initialized) {
                initialized = true
                runCatching {
                    val cls = Class.forName(CLASS_NAME)
                    val ctor = cls.getConstructor(Context::class.java)
                    val m = cls.getMethod("switchNaviMode", Int::class.javaPrimitiveType)
                    menuInstance = ctor.newInstance(app)
                    switchNaviModeMethod = m
                    Log.i(TAG, "Initialized DimMenuInteraction via reflection")
                }.onFailure {
                    Log.w(TAG, "DimMenuInteraction unavailable: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
            menuInstance to switchNaviModeMethod
        }
        if (instance == null || method == null) return false
        return runCatching {
            val result = method.invoke(instance, mode)
            Log.i(TAG, "switchNaviMode($mode) -> $result")
            true
        }.onFailure {
            Log.w(TAG, "switchNaviMode($mode) failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }
}
