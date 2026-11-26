package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Reflection-only controller for KX11_A2's built-in `ecarx.adaptapi.jar`
 * `DimMenuInteraction` / `DimInteraction` cluster layout APIs.
 *
 * Design guards:
 * 1. Holds a single process-wide instance constructed with `applicationContext` to prevent
 *    ServiceConnection leaks, initialized off the main thread.
 * 2. Edge-triggered only: switches layout once when CarPlay navigation starts (with at most one
 *    300ms delayed retry if the underlying OEM Binder was still connecting) and NEVER loops or
 *    re-asserts, so pressing the left stalk button to view fuel consumption / TPMS stays untouched.
 * 3. Always restores `MODE_DEFAULT (1)` when navigation ends, CarPlay disconnects, or the user
 *    turns off the experimental toggle.
 */
object Kx11DimMenuController {
    private const val TAG = "DiPlay-KX11DimMenu"
    private const val MENU_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction"
    private const val DIM_INTERACTION_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    private const val MENU_INTERFACE = "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction"
    private const val CONNECTABLE_INTERFACE = "com.ecarx.xui.adaptapi.binder.IConnectable"
    private const val FIRST_BIND_RETRY_DELAY_MS = 300L

    const val MODE_DEFAULT = 1
    const val MODE_SIMPLIFY = 2
    const val MODE_FULL_NAVI = 3

    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-kx11-dim-menu").apply { isDaemon = true }
    }
    private var initialized = false
    private var menuInstance: Any? = null
    private var switchNaviModeMethod: Method? = null
    private var naviActive = false
    private var layoutRequested = false
    private var pendingRetry: ScheduledFuture<*>? = null

    fun warmup(context: Context) {
        val app = context.applicationContext
        if (!Kx11DimSettings.isSupported()) return
        if (!Kx11DimSettings.clusterFullNaviLayoutEnabled(app)) return
        worker.execute { ensureInitialized(app) }
    }

    fun onNaviActiveChanged(context: Context, active: Boolean) {
        val app = context.applicationContext
        if (!Kx11DimSettings.isSupported()) return
        worker.execute {
            val enabled = Kx11DimSettings.clusterFullNaviLayoutEnabled(app)
            val targetMode: Int? = synchronized(lock) {
                naviActive = active
                pendingRetry?.cancel(false)
                pendingRetry = null
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
                dispatchSwitch(app, targetMode, allowSingleRetry = targetMode == MODE_FULL_NAVI)
            }
        }
    }

    fun settingChanged(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (!Kx11DimSettings.isSupported()) return
        worker.execute {
            if (enabled) {
                ensureInitialized(app)
                val shouldApplyNow = synchronized(lock) {
                    if (naviActive && !layoutRequested) {
                        layoutRequested = true
                        true
                    } else {
                        false
                    }
                }
                if (shouldApplyNow) {
                    dispatchSwitch(app, MODE_FULL_NAVI, allowSingleRetry = true)
                }
            } else {
                val shouldRestore = synchronized(lock) {
                    pendingRetry?.cancel(false)
                    pendingRetry = null
                    val prev = layoutRequested
                    layoutRequested = false
                    prev
                }
                if (shouldRestore) {
                    dispatchSwitch(app, MODE_DEFAULT, allowSingleRetry = false)
                }
            }
        }
    }

    private fun dispatchSwitch(app: Context, mode: Int, allowSingleRetry: Boolean) {
        val justCreated = ensureInitialized(app)
        val ok = invokeSwitch(mode)
        if (ok && !justCreated) return
        if (allowSingleRetry) {
            // Single delayed retry for asynchronous OEM Binder connection on first init; never loops.
            val future = worker.schedule({
                val stillWanted = synchronized(lock) {
                    pendingRetry = null
                    layoutRequested && naviActive && Kx11DimSettings.clusterFullNaviLayoutEnabled(app)
                }
                if (!stillWanted) return@schedule
                val retryOk = invokeSwitch(mode)
                if (!retryOk) {
                    synchronized(lock) { layoutRequested = false }
                }
            }, FIRST_BIND_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
            synchronized(lock) { pendingRetry = future }
        } else if (!ok && mode == MODE_FULL_NAVI) {
            synchronized(lock) { layoutRequested = false }
        }
    }

    /** Returns true if the AdaptAPI instance was freshly created on this call. */
    private fun ensureInitialized(app: Context): Boolean {
        synchronized(lock) {
            if (initialized && menuInstance != null && switchNaviModeMethod != null) return false
            if (initialized && menuInstance == null) return false
            initialized = true
            val resolved = resolveMenuInteraction(app)
            if (resolved != null) {
                menuInstance = resolved.first
                switchNaviModeMethod = resolved.second
                Log.i(TAG, "Initialized DimMenuInteraction via ${resolved.first.javaClass.name}")
                return true
            }
            Log.w(TAG, "DimMenuInteraction unavailable on this firmware")
            return false
        }
    }

    private fun resolveMenuInteraction(app: Context): Pair<Any, Method>? {
        // Strategy 1: direct DimMenuInteraction(Context) constructor.
        runCatching {
            val cls = Class.forName(MENU_CLASS)
            val instance = cls.getConstructor(Context::class.java).newInstance(app)
            val method = findSwitchMethod(instance)
            if (method != null) return instance to method
        }
        // Strategy 2: static DimMenuInteraction.create(Context).
        runCatching {
            val cls = Class.forName(MENU_CLASS)
            val instance = cls.getMethod("create", Context::class.java).invoke(null, app)
            if (instance != null) {
                connectIfNeeded(instance)
                val method = findSwitchMethod(instance)
                if (method != null) return instance to method
            }
        }
        // Strategy 3: factory DimInteraction.create(Context) -> getDimMenuInteraction()/getMenuInteraction().
        runCatching {
            val dimCls = Class.forName(DIM_INTERACTION_CLASS)
            val dim = dimCls.getMethod("create", Context::class.java).invoke(null, app)
            if (dim != null) {
                connectIfNeeded(dim)
                val getter = listOf("getDimMenuInteraction", "getMenuInteraction").firstNotNullOfOrNull { name ->
                    runCatching { dim.javaClass.getMethod(name) }.getOrNull()
                }
                val menu = getter?.invoke(dim)
                if (menu != null) {
                    val method = findSwitchMethod(menu)
                    if (method != null) return menu to method
                }
            }
        }
        return null
    }

    private fun connectIfNeeded(target: Any) {
        runCatching {
            val connectable = Class.forName(CONNECTABLE_INTERFACE)
            if (connectable.isInstance(target)) {
                connectable.getMethod("connect").invoke(target)
            }
        }
    }

    private fun findSwitchMethod(instance: Any): Method? {
        val ifaceMethod = runCatching {
            val iface = Class.forName(MENU_INTERFACE)
            if (iface.isInstance(instance)) iface.getMethod("switchNaviMode", Int::class.javaPrimitiveType) else null
        }.getOrNull()
        val method = ifaceMethod ?: runCatching {
            instance.javaClass.getMethod("switchNaviMode", Int::class.javaPrimitiveType)
        }.getOrNull()
        method?.isAccessible = true
        return method
    }

    private fun invokeSwitch(mode: Int): Boolean {
        val (instance, method) = synchronized(lock) { menuInstance to switchNaviModeMethod }
        if (instance == null || method == null) return false
        return runCatching {
            val result = method.invoke(instance, mode)
            Log.i(TAG, "switchNaviMode($mode) -> $result")
            result != false
        }.onFailure {
            Log.w(TAG, "switchNaviMode($mode) failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }
}
