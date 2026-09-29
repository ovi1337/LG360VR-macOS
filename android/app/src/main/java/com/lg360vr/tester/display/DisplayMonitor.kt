package com.lg360vr.tester.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    val isPresentation: Boolean,
) {
    val summary get() = "$name  •  ${width}×${height} @ ${"%.0f".format(refreshRate)}Hz"
}

/**
 * Watches for external (presentation-category) displays via DisplayManager and can
 * show/dismiss the [VrPresentation] test pattern on a chosen one. When the headset
 * successfully enters DP-alt-mode it appears here as a presentation display.
 */
class DisplayMonitor(private val context: Context) {

    private val dm: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val _displays = MutableStateFlow<List<DisplayInfo>>(emptyList())
    val displays: StateFlow<List<DisplayInfo>> = _displays.asStateFlow()

    private val _active = MutableStateFlow<Int?>(null)
    val activeDisplayId: StateFlow<Int?> = _active.asStateFlow()

    private var presentation: VrPresentation? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) {
            if (_active.value == displayId) dismiss()
            refresh()
        }
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun register() {
        dm.registerDisplayListener(listener, null)
        refresh()
    }

    fun unregister() {
        runCatching { dm.unregisterDisplayListener(listener) }
        dismiss()
    }

    fun refresh() {
        val presentationDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { it.displayId }.toSet()
        _displays.value = dm.displays.map { d ->
            val mode = d.mode
            DisplayInfo(
                id = d.displayId,
                name = d.name,
                width = mode?.physicalWidth ?: 0,
                height = mode?.physicalHeight ?: 0,
                refreshRate = d.refreshRate,
                isPresentation = d.displayId in presentationDisplays,
            )
        }
    }

    /** Returns the first external (presentation) display, if any. */
    fun firstExternal(): DisplayInfo? = _displays.value.firstOrNull { it.isPresentation }

    fun showPattern(displayId: Int, pattern: TestPattern): Boolean {
        val display: Display = dm.displays.firstOrNull { it.displayId == displayId } ?: return false
        dismiss()
        val p = VrPresentation(context, display, pattern)
        return try {
            p.show()
            presentation = p
            _active.value = displayId
            true
        } catch (t: Throwable) {
            runCatching { p.dismiss() }
            false
        }
    }

    fun dismiss() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        _active.value = null
    }
}
