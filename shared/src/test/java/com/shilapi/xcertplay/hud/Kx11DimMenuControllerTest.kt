package com.shilapi.xcertplay.hud

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Kx11DimMenuControllerTest {

    interface FakeConnectWatcher {
        fun onConnected()
        fun onDisConnected()
        fun isReady(): Boolean
    }

    @Test
    fun connectWatcherProxyHandlesObjectMethodsWithoutNpeAndDispatchesCallbacks() {
        val connectedCount = AtomicInteger(0)
        val disconnectedCount = AtomicInteger(0)

        val proxy = Kx11DimMenuController.createConnectWatcherProxy(
            watcherInterface = FakeConnectWatcher::class.java,
            onConnected = { connectedCount.incrementAndGet() },
            onDisconnected = { disconnectedCount.incrementAndGet() },
        ) as FakeConnectWatcher

        // OEM AdaptAPI stores watchers inside HashSet/HashMap; hashCode/equals/toString must never NPE.
        val set = HashSet<FakeConnectWatcher>()
        assertTrue(set.add(proxy))
        assertTrue(set.contains(proxy))
        assertFalse(proxy.equals(Any()))
        assertTrue(proxy.equals(proxy))
        assertNotNull(proxy.toString())
        assertTrue(proxy.toString().startsWith("Kx11ConnectWatcher@"))

        // Unhandled primitive return methods must return safe defaults instead of null unboxing NPE.
        assertFalse(proxy.isReady())

        proxy.onConnected()
        proxy.onDisConnected()
        assertEquals(1, connectedCount.get())
        assertEquals(1, disconnectedCount.get())
    }
}
