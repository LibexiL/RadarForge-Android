package com.libexil.radarforge.gl

import com.libexil.radarforge.core.ColorTable
import com.libexil.radarforge.core.Field
import com.libexil.radarforge.core.ProjectedLayer

/** A map layer to draw: lines in km around the radar, colour 0xAARRGGBB, width in px. */
class LayerDraw(
    val layer: ProjectedLayer,
    val color: Int,
    val widthPx: Float,
    val maxViewKm: Float = Float.MAX_VALUE,     // hidden when the view is wider than this
    val halo: Boolean = false,                  // dark outline underneath (warnings)
    val innerPx: Float = 0f,                    // black centre stripe ("center" / "double" warning lines)
)

class PanelDraw(val field: Field?, val table: ColorTable, val smooth: Boolean)

/** Everything the renderer needs for one frame; replaced as a whole from the UI thread. */
class Scene(
    val mapBg: Int,
    val gapBg: Int,
    val layers: List<LayerDraw>,
    val overlays: List<LayerDraw>,              // drawn last (warnings)
    val panels: List<PanelDraw>,
    val keep: List<Field>,                      // fields to keep on the GPU (loop frames)
) {
    companion object {
        val EMPTY = Scene(0xff08080c.toInt(), 0xff1f1f24.toInt(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}
