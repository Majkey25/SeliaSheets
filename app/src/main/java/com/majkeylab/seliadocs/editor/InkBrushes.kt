package com.majkeylab.seliadocs.editor

import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.SourceNode.Source
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.TargetNode.Target

@OptIn(ExperimentalInkCustomBrushApi::class)
internal object SeliaInkBrushes {
    val pencil: BrushFamily by lazy {
        val base = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        val tip =
            BrushTip(
                scaleX = 1f,
                scaleY = 0.55f,
                cornerRounding = 0.35f,
                behaviors =
                    listOf(
                        behavior(Source.NORMALIZED_PRESSURE, 0f, 1f, Target.SIZE_MULTIPLIER, 0.45f, 1.2f),
                        behavior(Source.TILT_IN_RADIANS, 0f, HALF_PI, Target.WIDTH_MULTIPLIER, 1f, 2.4f),
                        behavior(Source.TILT_IN_RADIANS, 0f, HALF_PI, Target.OPACITY_MULTIPLIER, 1f, 0.58f),
                        behavior(
                            Source.ORIENTATION_ABOUT_ZERO_IN_RADIANS,
                            -PI,
                            PI,
                            Target.ROTATION_OFFSET_IN_RADIANS,
                            -PI,
                            PI,
                        ),
                    ),
            )
        BrushFamily.builder()
            .setCoat(BrushCoat(tip, base.coats.single().paintPreferences))
            .setInputModel(base.inputModel)
            .setDeveloperComment(
                "Pressure controls size; tilt controls width and opacity; orientation rotates the tip.",
            )
            .build()
    }

    private fun behavior(
        source: Source,
        sourceStart: Float,
        sourceEnd: Float,
        target: Target,
        targetStart: Float,
        targetEnd: Float,
    ) =
        BrushBehavior(
            TargetNode(
                target,
                targetStart,
                targetEnd,
                DampingNode(
                    ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE,
                    0.35f,
                    SourceNode(source, sourceStart, sourceEnd),
                ),
            ),
        )

    private const val PI = 3.1415927f
    private const val HALF_PI = PI / 2f
}
