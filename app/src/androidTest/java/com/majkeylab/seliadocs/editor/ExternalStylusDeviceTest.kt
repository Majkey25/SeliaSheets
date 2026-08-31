package com.majkeylab.seliadocs.editor

import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalStylusDeviceTest {
    @Test
    fun emulatorConsoleProducesPressureStylusMotionEvents() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("externalTabletStylus") == "true",
        )
        val finished = CountDownLatch(1)
        val toolType = AtomicReference<Int>()
        val pressures = mutableListOf<Float>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(
                    View(activity).apply {
                        setOnTouchListener { _, event ->
                            toolType.set(event.getToolType(event.actionIndex))
                            pressures += event.getPressure(event.actionIndex)
                            Log.i(
                                "SeliaSheetsStylusQA",
                                "EVENT action=${event.actionMasked} tool=${event.getToolType(event.actionIndex)} " +
                                    "source=${event.source} pressure=${event.getPressure(event.actionIndex)} " +
                                    "x=${event.x} y=${event.y}",
                            )
                            if (event.actionMasked == MotionEvent.ACTION_UP) finished.countDown()
                            true
                        }
                    },
                )
            }
            Log.i("SeliaSheetsStylusQA", "READY_DEVICE")
            assertTrue(finished.await(30, TimeUnit.SECONDS))
        }

        assertEquals(MotionEvent.TOOL_TYPE_STYLUS, toolType.get())
        assertTrue(pressures.min() <= 0.25f)
        assertTrue(pressures.max() >= 0.75f)
    }
}
