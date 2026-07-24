package com.zhuo.c1cam

import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max

data class StillCaptureGeometry(
    val outputWidth: Int,
    val outputHeight: Int,
    val outputToRawHomography: FloatArray
) {
    companion object {
        fun create(
            rawWidth: Int,
            rawHeight: Int,
            imageRotationDegrees: Int,
            normalizedViewPoints: List<PointF>,
            viewWidth: Int,
            viewHeight: Int,
            targetAspectRatio: Float,
            isCropModeOff: Boolean,
            focalLength: Int,
            noCropAspectRatio: Float,
            savedImageRotationDegrees: Int
        ): StillCaptureGeometry {
            require(rawWidth > 0 && rawHeight > 0)

            val imageRotation = normalizeRightAngle(imageRotationDegrees)
            val savedRotation = normalizeRightAngle(savedImageRotationDegrees)
            val uprightWidth = if (imageRotation == 90 || imageRotation == 270) rawHeight else rawWidth
            val uprightHeight = if (imageRotation == 90 || imageRotation == 270) rawWidth else rawHeight

            val uprightQuad: List<Coordinate>
            val preSaveWidth: Int
            val preSaveHeight: Int

            if (isCropModeOff) {
                val crop = CropFrameGuideModel.normalizedFrameRect(
                    sourceAspectRatio = uprightWidth.toFloat() / uprightHeight.toFloat(),
                    focalLength = focalLength,
                    aspectRatio = noCropAspectRatio
                )
                uprightQuad = listOf(
                    Coordinate(crop.left, crop.top),
                    Coordinate(crop.right, crop.top),
                    Coordinate(crop.right, crop.bottom),
                    Coordinate(crop.left, crop.bottom)
                )
                preSaveWidth = (crop.width * uprightWidth).toInt().coerceAtLeast(1)
                preSaveHeight = (crop.height * uprightHeight).toInt().coerceAtLeast(1)
            } else {
                uprightQuad = mapViewPointsToUprightImage(
                    normalizedViewPoints = normalizedViewPoints,
                    imageWidth = uprightWidth,
                    imageHeight = uprightHeight,
                    viewWidth = viewWidth,
                    viewHeight = viewHeight
                )
                val dimensions = rectifiedDimensions(
                    sourceWidth = uprightWidth,
                    sourceHeight = uprightHeight,
                    normalizedPoints = uprightQuad,
                    targetAspectRatio = targetAspectRatio
                )
                preSaveWidth = dimensions.first
                preSaveHeight = dimensions.second
            }

            val sourceQuadForFinalOutput = reorderQuadForClockwiseRotation(
                uprightQuad,
                savedRotation
            ).map { uprightPoint ->
                uprightToRaw(uprightPoint, imageRotation)
            }

            val outputWidth = if (savedRotation == 90 || savedRotation == 270) {
                preSaveHeight
            } else {
                preSaveWidth
            }
            val outputHeight = if (savedRotation == 90 || savedRotation == 270) {
                preSaveWidth
            } else {
                preSaveHeight
            }

            val outputCorners = listOf(
                Coordinate(0f, 0f),
                Coordinate(1f, 0f),
                Coordinate(1f, 1f),
                Coordinate(0f, 1f)
            )
            return StillCaptureGeometry(
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                outputToRawHomography = solveHomography(outputCorners, sourceQuadForFinalOutput)
            )
        }

        private fun mapViewPointsToUprightImage(
            normalizedViewPoints: List<PointF>,
            imageWidth: Int,
            imageHeight: Int,
            viewWidth: Int,
            viewHeight: Int
        ): List<Coordinate> {
            if (normalizedViewPoints.size != 4 || viewWidth <= 0 || viewHeight <= 0) {
                return listOf(
                    Coordinate(0f, 0f),
                    Coordinate(1f, 0f),
                    Coordinate(1f, 1f),
                    Coordinate(0f, 1f)
                )
            }

            val scale = minOf(
                viewWidth.toFloat() / imageWidth.toFloat(),
                viewHeight.toFloat() / imageHeight.toFloat()
            )
            val displayedWidth = imageWidth * scale
            val displayedHeight = imageHeight * scale
            val insetX = (viewWidth - displayedWidth) / 2f
            val insetY = (viewHeight - displayedHeight) / 2f

            return normalizedViewPoints.map { point ->
                val viewX = point.x * viewWidth
                val viewY = point.y * viewHeight
                Coordinate(
                    ((viewX - insetX) / displayedWidth).coerceIn(0f, 1f),
                    ((viewY - insetY) / displayedHeight).coerceIn(0f, 1f)
                )
            }
        }

        private fun rectifiedDimensions(
            sourceWidth: Int,
            sourceHeight: Int,
            normalizedPoints: List<Coordinate>,
            targetAspectRatio: Float
        ): Pair<Int, Int> {
            val points = if (normalizedPoints.size == 4) normalizedPoints else listOf(
                Coordinate(0f, 0f),
                Coordinate(1f, 0f),
                Coordinate(1f, 1f),
                Coordinate(0f, 1f)
            )
            val scaled = points.map {
                Coordinate(it.x * sourceWidth, it.y * sourceHeight)
            }
            val maxWidth = max(
                distance(scaled[0], scaled[1]),
                distance(scaled[3], scaled[2])
            )
            val maxHeight = max(
                distance(scaled[0], scaled[3]),
                distance(scaled[1], scaled[2])
            )
            val sourceIsLandscape = maxWidth > maxHeight
            val targetIsLandscape = targetAspectRatio > 1f
            val finalAspectRatio = when {
                targetAspectRatio == 0f -> maxWidth / maxHeight
                sourceIsLandscape == targetIsLandscape -> targetAspectRatio
                else -> 1f / targetAspectRatio
            }

            val width: Float
            val height: Float
            if (finalAspectRatio >= 1f) {
                width = maxWidth
                height = width / finalAspectRatio
            } else {
                height = maxHeight
                width = height * finalAspectRatio
            }
            return width.toInt().coerceAtLeast(1) to height.toInt().coerceAtLeast(1)
        }

        private fun distance(a: Coordinate, b: Coordinate): Float {
            return hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
        }

        private fun reorderQuadForClockwiseRotation(
            quad: List<Coordinate>,
            rotationDegrees: Int
        ): List<Coordinate> {
            require(quad.size == 4)
            return when (rotationDegrees) {
                90 -> listOf(quad[3], quad[0], quad[1], quad[2])
                180 -> listOf(quad[2], quad[3], quad[0], quad[1])
                270 -> listOf(quad[1], quad[2], quad[3], quad[0])
                else -> quad
            }
        }

        private fun uprightToRaw(point: Coordinate, rotationDegrees: Int): Coordinate {
            return when (rotationDegrees) {
                90 -> Coordinate(point.y, 1f - point.x)
                180 -> Coordinate(1f - point.x, 1f - point.y)
                270 -> Coordinate(1f - point.y, point.x)
                else -> Coordinate(point.x, point.y)
            }
        }

        private fun solveHomography(
            from: List<Coordinate>,
            to: List<Coordinate>
        ): FloatArray {
            require(from.size == 4 && to.size == 4)
            val matrix = Array(8) { FloatArray(8) }
            val values = FloatArray(8)

            for (index in 0 until 4) {
                val x = from[index].x
                val y = from[index].y
                val u = to[index].x
                val v = to[index].y
                val row0 = index * 2
                val row1 = row0 + 1

                matrix[row0][0] = x
                matrix[row0][1] = y
                matrix[row0][2] = 1f
                matrix[row0][6] = -u * x
                matrix[row0][7] = -u * y
                values[row0] = u

                matrix[row1][3] = x
                matrix[row1][4] = y
                matrix[row1][5] = 1f
                matrix[row1][6] = -v * x
                matrix[row1][7] = -v * y
                values[row1] = v
            }

            val coefficients = gaussianElimination(matrix, values)
            return floatArrayOf(
                coefficients[0], coefficients[3], coefficients[6],
                coefficients[1], coefficients[4], coefficients[7],
                coefficients[2], coefficients[5], 1f
            )
        }

        private fun gaussianElimination(matrix: Array<FloatArray>, values: FloatArray): FloatArray {
            for (column in 0 until 8) {
                var pivot = column
                for (row in column + 1 until 8) {
                    if (kotlin.math.abs(matrix[row][column]) >
                        kotlin.math.abs(matrix[pivot][column])
                    ) {
                        pivot = row
                    }
                }
                require(kotlin.math.abs(matrix[pivot][column]) >= 1e-6f) {
                    "Capture transform is singular"
                }

                if (pivot != column) {
                    val row = matrix[column]
                    matrix[column] = matrix[pivot]
                    matrix[pivot] = row
                    val value = values[column]
                    values[column] = values[pivot]
                    values[pivot] = value
                }

                val divisor = matrix[column][column]
                for (entry in column until 8) matrix[column][entry] /= divisor
                values[column] /= divisor

                for (row in 0 until 8) {
                    if (row == column) continue
                    val factor = matrix[row][column]
                    for (entry in column until 8) {
                        matrix[row][entry] -= factor * matrix[column][entry]
                    }
                    values[row] -= factor * values[column]
                }
            }
            return values
        }

        private fun normalizeRightAngle(rotationDegrees: Int): Int {
            val normalized = ((rotationDegrees % 360) + 360) % 360
            return when (normalized) {
                in 45 until 135 -> 90
                in 135 until 225 -> 180
                in 225 until 315 -> 270
                else -> 0
            }
        }

        private data class Coordinate(val x: Float, val y: Float)
    }
}
