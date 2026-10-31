package ru.n08i40k.guides.util

import android.graphics.Matrix
import android.graphics.PointF
import android.view.View

internal fun guidePointIn(view: View, destination: View, point: PointF): PointF {
    val coordinates = floatArrayOf(point.x, point.y)
    val sourceTransform = Matrix()
    view.transformMatrixToGlobal(sourceTransform)
    sourceTransform.mapPoints(coordinates)

    val destinationTransform = Matrix()
    destination.transformMatrixToGlobal(destinationTransform)
    val inverse = Matrix()

    check(destinationTransform.invert(inverse)) { "Guide target has a non-invertible transform" }
    inverse.mapPoints(coordinates)

    return PointF(coordinates[0], coordinates[1])
}

internal fun View.centerPoint() =
    PointF(width / 2f, height / 2f)
