package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Reflection-only controller for KX11_A2's built-in `ecarx.adaptapi.jar`
 * `DimInteraction` / `DimMenuInteraction` cluster layout APIs.
 *
 * Design guards:
 * 1. Holds a single process-wide `DimInteraction` / `DimMenuInteraction` instance created with
 *    `applicationContext` (never calling the abstract/stub constructor directly) to prevent
 *    ServiceConnection leaks and no-op stub bindings.
 * 2. Registers an `IConnectable.IConnectWatcher` dynamic proxy (with explicit `Object`
 *    `hashCode`/`equals`/`toString` handling to avoid NPE inside OEM watcher sets) so
 *    `getDimMenuInteraction()` and any pending `switchNaviMode()` call execute as soon as the
 *    OEM Binder actually connects.
 * 3. Edge-triggered only: switches layout once when CarPlay navigation starts and NEVER loops
 *    or re-asserts, so pressing the left stalk button to view fuel consumption / TPMS stays
 *    untouched. Always restores `MODE_DEFAULT (1)` when navigation ends, CarPlay disconnects,
 *    or the user turns off the setting.
 */
object Kx11DimMenuController {
    private const val TAG = "DiPlay-KX11DimMenu"
    private const val DIM_INTERACTION_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    private const val MENU_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction"
    private const val MENU_INTERFACE = "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction"
    private const val CONNECTABLE_INTERFACE = "com.ecarx.xui.adaptapi.binder.IConnectable"
    private val WATCHER_INTERFACE_CANDIDATES = listOf(
        "com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher",
        "com.ecarx.xui.adaptapi.binder.IConnectWatcher",
    )
    private const val FIRST_BIND_RETRY_DELAY_MS = 600L

    const val MODE_DEFAULT = 1
    const val MODE_SIMPLIFY = 2
    const val MODE_FULL_NAVI = 3

    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-kx11-dim-menu").apply { isDaemon = true }
    }
    private var initAttempted = false
    private var rootInstance: Any? = null
    private var watcherRegistered = false
    private var connected = false
    private var menuInstance: Any? = null
    private var switchNaviModeMethod: Method? = null
    private var naviActive = false
    private var layoutRequested = false
    private var pendingMode: Int? = null
    private var pendingRetry: ScheduledFuture<*>? = null

    private fun isLayoutSyncEnabled(app: Context): Boolean =
        Kx11DimSettings.clusterFullNaviLayoutEnabled(app) ||
            app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
                .getBoolean("cluster_map_enabled", false)

    fun warmup(context: Context) {
        val app = context.applicationContext
        if (!Kx11DimSettings.isSupported()) return
        if (!isLayoutSyncEnabled(app)) return
        worker.execute { ensureInitialized(app) }
    }

    fun onNaviActiveChanged(context: Context, active: Boolean) {
        val app = context.applicationContext
        if (!Kx11DimSettings.isSupported()) return
        worker.execute {
            val enabled = isLayoutSyncEnabled(app)
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

    fun settingChanged(context: Context, @Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        val app = context.applicationContext
        if (!Kx11DimSettings.isSupported()) return
        worker.execute {
            val effectiveEnabled = isLayoutSyncEnabled(app)
            if (effectiveEnabled) {
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
                    pendingMode = null
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
        ensureInitialized(app)
        synchronized(lock) { pendingMode = mode }
        val ok = resolveMenuIfReadyAndInvoke(mode)
        if (ok) {
            synchronized(lock) {
                if (pendingMode == mode) pendingMode = null
            }
            return
        }
        if (allowSingleRetry) {
            // Single delayed fallback in case the OEM Binder finishes connecting without invoking
            // IConnectWatcher; never loops.
            val future = worker.schedule({
                val stillWanted = synchronized(lock) {
                    pendingRetry = null
                    pendingMode == mode &&
                        layoutRequested &&
                        naviActive &&
                        isLayoutSyncEnabled(app)
                }
                if (!stillWanted) return@schedule
                val retryOk = resolveMenuIfReadyAndInvoke(mode)
                synchronized(lock) {
                    if (retryOk && pendingMode == mode) {
                        pendingMode = null
                    } else if (!retryOk && !connected) {
                        // Keep pendingMode armed for onConnected() callback if binder connects later.
                        Log.i(TAG, "Deferred switchNaviMode($mode) awaiting IConnectWatcher.onConnected")
                    }
                }
            }, FIRST_BIND_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
            synchronized(lock) { pendingRetry = future }
        }
    }

    private fun ensureInitialized(app: Context) {
        synchronized(lock) {
            if (initAttempted && rootInstance != null) return
            if (initAttempted && rootInstance == null) return
            initAttempted = true
        }
        val created = createRootInstance(app)
        if (created == null) {
            Log.w(TAG, "AdaptAPI DimInteraction/DimMenuInteraction unavailable on this firmware")
            return
        }
        synchronized(lock) {
            rootInstance = created
        }
        val watcherRegisteredNow = registerConnectWatcherAndConnect(created)
        Log.i(TAG, "Initialized root=${created.javaClass.name} watcherRegistered=$watcherRegisteredNow")
        // Eagerly resolve menu handle in case the OEM SDK is synchronously ready.
        resolveMenuFromRoot(created)
    }

    private fun createRootInstance(app: Context): Any? {
        // Strategy 1 (Primary): DimInteraction.create(Context) factory.
        runCatching {
            val dimCls = Class.forName(DIM_INTERACTION_CLASS)
            val dim = dimCls.getMethod("create", Context::class.java).invoke(null, app)
            if (dim != null) {
                Log.i(TAG, "Strategy 1 DimInteraction.create() -> ${dim.javaClass.name}")
                return dim
            }
        }.onFailure {
            Log.w(TAG, "Strategy 1 DimInteraction.create() failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // Strategy 2 (Fallback): DimMenuInteraction.create(Context) factory.
        runCatching {
            val menuCls = Class.forName(MENU_CLASS)
            val menu = menuCls.getMethod("create", Context::class.java).invoke(null, app)
            if (menu != null) {
                Log.i(TAG, "Strategy 2 DimMenuInteraction.create() -> ${menu.javaClass.name}")
                return menu
            }
        }.onFailure {
            Log.w(TAG, "Strategy 2 DimMenuInteraction.create() failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        return null
    }

    private fun registerConnectWatcherAndConnect(target: Any): Boolean {
        synchronized(lock) {
            if (watcherRegistered) return true
        }
        val connectableCls = runCatching { Class.forName(CONNECTABLE_INTERFACE) }.getOrNull()
        val isConnectable = connectableCls != null && connectableCls.isInstance(target)
        var registered = false
        if (isConnectable) {
            val watcherInterface = resolveWatcherInterface(target.javaClass.classLoader)
            if (watcherInterface != null) {
                runCatching {
                    val proxy = createConnectWatcherProxy(
                        watcherInterface = watcherInterface,
                        onConnected = { onBinderConnected() },
                        onDisconnected = { onBinderDisconnected() },
                    )
                    connectableCls!!.getMethod("registerConnectWatcher", watcherInterface).invoke(target, proxy)
                    synchronized(lock) { watcherRegistered = true }
                    registered = true
                }.onFailure {
                    Log.w(TAG, "registerConnectWatcher failed: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
            runCatching {
                connectableCls!!.getMethod("connect").invoke(target)
                Log.i(TAG, "Invoked IConnectable.connect() on ${target.javaClass.name}")
            }.onFailure {
                Log.w(TAG, "IConnectable.connect() failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        return registered
    }

    private fun onBinderConnected() {
        Log.i(TAG, "IConnectWatcher.onConnected()")
        worker.execute {
            val root: Any?
            val modeToReplay: Int?
            synchronized(lock) {
                connected = true
                root = rootInstance
                modeToReplay = when (val pending = pendingMode) {
                    MODE_FULL_NAVI -> if (layoutRequested && naviActive) pending else null
                    else -> pending
                }
            }
            if (root != null) {
                resolveMenuFromRoot(root)
            }
            if (modeToReplay != null) {
                val ok = invokeSwitch(modeToReplay)
                if (ok) {
                    synchronized(lock) {
                        if (pendingMode == modeToReplay) pendingMode = null
                    }
                }
            }
        }
    }

    private fun onBinderDisconnected() {
        Log.w(TAG, "IConnectWatcher.onDisConnected()")
        synchronized(lock) {
            connected = false
            menuInstance = null
            switchNaviModeMethod = null
        }
    }

    private fun resolveMenuIfReadyAndInvoke(mode: Int): Boolean {
        val root = synchronized(lock) { rootInstance } ?: return false
        val hasMenu = synchronized(lock) { menuInstance != null && switchNaviModeMethod != null }
        if (!hasMenu) {
            resolveMenuFromRoot(root)
        }
        return invokeSwitch(mode)
    }

    private fun resolveMenuFromRoot(root: Any): Boolean {
        val direct = findSwitchMethod(root)
        if (direct != null) {
            synchronized(lock) {
                menuInstance = root
                switchNaviModeMethod = direct
            }
            Log.i(TAG, "Resolved menu directly on ${root.javaClass.name}")
            return true
        }
        val getter = listOf("getDimMenuInteraction", "getMenuInteraction").firstNotNullOfOrNull { name ->
            runCatching { root.javaClass.getMethod(name) }.getOrNull()
        } ?: return false
        val menu = runCatching { getter.invoke(root) }.onFailure {
            Log.w(TAG, "${getter.name}() failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull() ?: return false
        val method = findSwitchMethod(menu) ?: return false
        synchronized(lock) {
            menuInstance = menu
            switchNaviModeMethod = method
        }
        Log.i(TAG, "Resolved menu via ${getter.name}() -> ${menu.javaClass.name}")
        return true
    }

    private fun resolveWatcherInterface(fallbackLoader: ClassLoader?): Class<*>? {
        for (name in WATCHER_INTERFACE_CANDIDATES) {
            runCatching { Class.forName(name) }.getOrNull()?.let { return it }
            if (fallbackLoader != null) {
                runCatching { Class.forName(name, false, fallbackLoader) }.getOrNull()?.let { return it }
            }
        }
        return null
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

    /**
     * Creates a dynamic proxy implementing the OEM `IConnectWatcher` interface.
     * Explicitly handles [Object.hashCode], [Object.equals], and [Object.toString] so OEM SDK
     * collections (`HashSet`/`HashMap`) never NPE when unboxing proxy return values.
     */
    internal fun createConnectWatcherProxy(
        watcherInterface: Class<*>,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
    ): Any = Proxy.newProxyInstance(
        watcherInterface.classLoader,
        arrayOf(watcherInterface),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            "toString" -> "Kx11ConnectWatcher@${Integer.toHexString(System.identityHashCode(proxy))}"
            "onConnected" -> {
                onConnected()
                null
            }
            "onDisConnected", "onDisconnected" -> {
                onDisconnected()
                null
            }
            else -> defaultReturnValue(method.returnType)
        }
    }

    private fun defaultReturnValue(returnType: Class<*>): Any? = when (returnType) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    /** Test-only reset helper. */
    internal fun resetForTest() {
        synchronized(lock) {
            pendingRetry?.cancel(false)
            pendingRetry = null
            initAttempted = false
            rootInstance = null
            watcherRegistered = false
            connected = false
            menuInstance = null
            switchNaviModeMethod = null
            naviActive = false
            layoutRequested = false
            pendingMode = null
        }
    }
}
