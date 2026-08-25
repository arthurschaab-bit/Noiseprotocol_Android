package com.example.lrmprotokoll.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Handgezeichnete Icons ohne extra material-icons-extended Abhängigkeit (Owner-Entscheidung M9).
 * Performant mit `by lazy` und stabiler Compose ImageVector.Builder API implementiert.
 */
object AppIcons {

    val Sensors: ImageVector by lazy {
        ImageVector.Builder(
            name = "Sensors",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(12.0f, 15.0f)
            curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
            reflectiveCurveToRelative(1.34f, -3.0f, 3.0f, -3.0f)
            reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
            reflectiveCurveToRelative(-1.34f, 3.0f, -3.0f, 3.0f)
            close()
            moveTo(12.0f, 7.0f)
            curveToRelative(-2.76f, 0.0f, -5.0f, 2.24f, -5.0f, 5.0f)
            reflectiveCurveToRelative(2.24f, 5.0f, 5.0f, 5.0f)
            reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
            reflectiveCurveToRelative(-2.24f, -5.0f, -5.0f, -5.0f)
            close()
            moveTo(12.0f, 3.0f)
            curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
            reflectiveCurveToRelative(4.03f, 9.0f, 9.0f, 9.0f)
            reflectiveCurveToRelative(9.0f, -4.03f, 9.0f, -9.0f)
            reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
            close()
        }.build()
    }

    val Diagnose: ImageVector by lazy {
        ImageVector.Builder(
            name = "Diagnose",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(19.0f, 3.0f)
            horizontalLineTo(5.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(18.0f, 11.5f)
            horizontalLineToRelative(-3.0f)
            lineToRelative(-1.5f, 4.5f)
            lineToRelative(-3.0f, -9.0f)
            lineToRelative(-1.5f, 4.5f)
            horizontalLineTo(6.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            lineToRelative(1.5f, -4.5f)
            lineToRelative(3.0f, 9.0f)
            lineToRelative(1.5f, -4.5f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(2.0f)
            close()
        }.build()
    }

    val FilterList: ImageVector by lazy {
        ImageVector.Builder(
            name = "FilterList",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(10.0f, 18.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-4.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(3.0f, 6.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(18.0f)
            verticalLineTo(6.0f)
            horizontalLineTo(3.0f)
            close()
            moveTo(6.0f, 13.0f)
            horizontalLineToRelative(12.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineTo(6.0f)
            verticalLineToRelative(2.0f)
            close()
        }.build()
    }

    val Bookmark: ImageVector by lazy {
        ImageVector.Builder(
            name = "Bookmark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(17.0f, 3.0f)
            horizontalLineTo(7.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(16.0f)
            lineToRelative(7.0f, -3.0f)
            lineToRelative(7.0f, 3.0f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
        }.build()
    }

    val BarChart: ImageVector by lazy {
        ImageVector.Builder(
            name = "BarChart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(4.0f, 9.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(11.0f)
            horizontalLineTo(4.0f)
            close()
            moveTo(10.0f, 4.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(16.0f)
            horizontalLineToRelative(-4.0f)
            close()
            moveTo(16.0f, 13.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(7.0f)
            horizontalLineToRelative(-4.0f)
            close()
        }.build()
    }

    val Trash: ImageVector by lazy {
        ImageVector.Builder(
            name = "Trash",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(6.0f, 19.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(7.0f)
            horizontalLineTo(6.0f)
            verticalLineToRelative(12.0f)
            close()
            moveTo(19.0f, 4.0f)
            horizontalLineToRelative(-3.5f)
            lineToRelative(-1.0f, -1.0f)
            horizontalLineToRelative(-5.0f)
            lineToRelative(-1.0f, 1.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(14.0f)
            verticalLineTo(4.0f)
            close()
        }.build()
    }

    val Restore: ImageVector by lazy {
        ImageVector.Builder(
            name = "Restore",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(13.0f, 3.0f)
            curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
            horizontalLineTo(1.0f)
            lineToRelative(3.89f, 3.89f)
            lineToRelative(0.07f, 0.14f)
            lineTo(9.0f, 12.0f)
            horizontalLineTo(6.0f)
            curveToRelative(0.0f, -3.87f, 3.13f, -7.0f, 7.0f, -7.0f)
            reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
            reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
            curveToRelative(-1.93f, 0.0f, -3.68f, -0.79f, -4.94f, -2.06f)
            lineToRelative(-1.42f, 1.42f)
            curveTo(8.27f, 19.99f, 10.51f, 21.0f, 13.0f, 21.0f)
            curveToRelative(4.97f, 0.0f, 9.0f, -4.03f, 9.0f, -9.0f)
            reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
            close()
            moveTo(12.0f, 8.0f)
            verticalLineToRelative(5.0f)
            lineToRelative(4.28f, 2.54f)
            lineToRelative(0.72f, -1.21f)
            lineToRelative(-3.5f, -2.08f)
            verticalLineTo(8.0f)
            horizontalLineTo(12.0f)
            close()
        }.build()
    }

    val Folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "Folder",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(10.0f, 4.0f)
            horizontalLineTo(4.0f)
            curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(2.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineToRelative(-8.0f)
            lineToRelative(-2.0f, -2.0f)
            close()
        }.build()
    }

    val FolderOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "FolderOpen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(20.0f, 6.0f)
            horizontalLineToRelative(-8.0f)
            lineToRelative(-2.0f, -2.0f)
            horizontalLineTo(4.0f)
            curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(2.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(20.0f, 18.0f)
            horizontalLineTo(4.0f)
            verticalLineTo(8.0f)
            horizontalLineToRelative(16.0f)
            verticalLineToRelative(10.0f)
            close()
        }.build()
    }

    val Hammer: ImageVector by lazy {
        ImageVector.Builder(
            name = "Hammer",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(2.0f, 19.5f)
            lineToRelative(9.0f, -9.0f)
            lineToRelative(2.5f, 2.5f)
            lineToRelative(-9.0f, 9.0f)
            close()
            moveTo(14.0f, 8.0f)
            lineToRelative(2.0f, -2.0f)
            lineToRelative(5.0f, 1.5f)
            lineToRelative(-1.5f, 3.5f)
            lineToRelative(-3.5f, -1.0f)
            lineToRelative(-3.0f, 3.0f)
            lineToRelative(-2.5f, -2.5f)
            lineToRelative(3.0f, -3.0f)
            lineToRelative(-0.5f, -2.5f)
            lineTo(15.0f, 3.0f)
            lineToRelative(3.5f, 1.5f)
            close()
        }.build()
    }

    val Drill: ImageVector by lazy {
        ImageVector.Builder(
            name = "Drill",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(22.7f, 19.3f)
            lineToRelative(-6.4f, -6.4f)
            curveToRelative(0.5f, -1.6f, 0.2f, -3.4f, -1.0f, -4.6f)
            curveToRelative(-1.2f, -1.2f, -3.0f, -1.5f, -4.6f, -1.0f)
            lineToRelative(3.3f, 3.3f)
            lineToRelative(-2.1f, 2.1f)
            lineTo(8.6f, 9.4f)
            curveToRelative(-0.5f, 1.6f, -0.2f, 3.4f, 1.0f, 4.6f)
            curveToRelative(1.2f, 1.2f, 3.0f, 1.5f, 4.6f, 1.0f)
            lineToRelative(6.4f, 6.4f)
            curveToRelative(0.4f, 0.4f, 1.0f, 0.4f, 1.4f, 0.0f)
            lineToRelative(0.7f, -0.7f)
            curveToRelative(0.4f, -0.4f, 0.4f, -1.0f, 0.0f, -1.4f)
            close()
        }.build()
    }

    val Footsteps: ImageVector by lazy {
        ImageVector.Builder(
            name = "Footsteps",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(13.5f, 5.5f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            close()
            moveTo(9.8f, 8.9f)
            lineTo(7.0f, 23.0f)
            horizontalLineToRelative(2.1f)
            lineToRelative(1.8f, -8.0f)
            lineToRelative(2.1f, 2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-7.5f)
            lineToRelative(-2.1f, -2.0f)
            lineToRelative(0.6f, -3.0f)
            curveTo(14.8f, 12.0f, 16.8f, 13.0f, 19.0f, 13.0f)
            verticalLineToRelative(-2.0f)
            curveToRelative(-1.9f, 0.0f, -3.5f, -1.0f, -4.3f, -2.4f)
            lineToRelative(-1.0f, -1.6f)
            curveToRelative(-0.4f, -0.6f, -1.0f, -1.0f, -1.7f, -1.0f)
            curveToRelative(-0.3f, 0.0f, -0.5f, 0.1f, -0.8f, 0.1f)
            lineTo(6.0f, 8.3f)
            verticalLineTo(13.0f)
            horizontalLineToRelative(2.0f)
            verticalLineTo(9.6f)
            lineToRelative(1.8f, -0.7f)
            close()
        }.build()
    }

    val Voices: ImageVector by lazy {
        ImageVector.Builder(
            name = "Voices",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(9.0f, 13.0f)
            curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
            reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
            reflectiveCurveToRelative(-4.0f, 1.79f, -4.0f, 4.0f)
            reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
            close()
            moveTo(9.0f, 15.0f)
            curveToRelative(-2.67f, 0.0f, -8.0f, 1.34f, -8.0f, 4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(16.0f)
            verticalLineToRelative(-2.0f)
            curveToRelative(0.0f, -2.66f, -5.33f, -4.0f, -8.0f, -4.0f)
            close()
            moveTo(16.5f, 9.0f)
            curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
            verticalLineToRelative(8.05f)
            curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
            close()
            moveTo(14.0f, 1.05f)
            verticalLineToRelative(2.07f)
            curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
            reflectiveCurveToRelative(-2.11f, 5.85f, -5.0f, 6.71f)
            verticalLineToRelative(2.07f)
            curveToRelative(4.01f, -0.91f, 7.0f, -4.49f, 7.0f, -8.78f)
            reflectiveCurveToRelative(-2.99f, -7.87f, -7.0f, -8.78f)
            close()
        }.build()
    }

    val Music: ImageVector by lazy {
        ImageVector.Builder(
            name = "Music",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(12.0f, 3.0f)
            verticalLineToRelative(10.55f)
            curveToRelative(-0.59f, -0.34f, -1.27f, -0.55f, -2.0f, -0.55f)
            curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
            reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
            reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
            verticalLineTo(7.0f)
            horizontalLineToRelative(4.0f)
            verticalLineTo(3.0f)
            horizontalLineToRelative(-6.0f)
            close()
        }.build()
    }

    val Traffic: ImageVector by lazy {
        ImageVector.Builder(
            name = "Traffic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(18.92f, 6.01f)
            curveTo(18.72f, 5.42f, 18.16f, 5.0f, 17.5f, 5.0f)
            horizontalLineToRelative(-11.0f)
            curveToRelative(-0.66f, 0.0f, -1.21f, 0.42f, -1.42f, 1.01f)
            lineTo(3.0f, 12.0f)
            verticalLineToRelative(8.0f)
            curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
            horizontalLineToRelative(1.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
            verticalLineToRelative(-1.0f)
            horizontalLineToRelative(12.0f)
            verticalLineToRelative(1.0f)
            curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
            horizontalLineToRelative(1.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
            verticalLineToRelative(-8.0f)
            lineToRelative(-2.08f, -5.99f)
            close()
            moveTo(6.5f, 16.0f)
            curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveTo(5.67f, 13.0f, 6.5f, 13.0f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            reflectiveCurveTo(7.33f, 16.0f, 6.5f, 16.0f)
            close()
            moveTo(17.5f, 16.0f)
            curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
            close()
            moveTo(5.0f, 11.0f)
            lineToRelative(1.5f, -4.5f)
            horizontalLineToRelative(11.0f)
            lineTo(19.0f, 11.0f)
            horizontalLineTo(5.0f)
            close()
        }.build()
    }

    val Dogs: ImageVector by lazy {
        ImageVector.Builder(
            name = "Dogs",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(4.5f, 9.5f)
            curveToRelative(1.38f, 0.0f, 2.5f, -1.12f, 2.5f, -2.5f)
            reflectiveCurveTo(5.88f, 4.5f, 4.5f, 4.5f)
            reflectiveCurveTo(2.0f, 5.62f, 2.0f, 7.0f)
            reflectiveCurveToRelative(1.12f, 2.5f, 2.5f, 2.5f)
            close()
            moveTo(8.5f, 6.0f)
            curveToRelative(1.38f, 0.0f, 2.5f, -1.12f, 2.5f, -2.5f)
            reflectiveCurveTo(9.88f, 1.0f, 8.5f, 1.0f)
            reflectiveCurveTo(6.0f, 2.12f, 6.0f, 3.5f)
            reflectiveCurveTo(7.12f, 6.0f, 8.5f, 6.0f)
            close()
            moveTo(15.5f, 6.0f)
            curveToRelative(1.38f, 0.0f, 2.5f, -1.12f, 2.5f, -2.5f)
            reflectiveCurveTo(16.88f, 1.0f, 15.5f, 1.0f)
            reflectiveCurveTo(13.0f, 2.12f, 13.0f, 3.5f)
            reflectiveCurveToRelative(1.12f, 2.5f, 2.5f, 2.5f)
            close()
            moveTo(19.5f, 9.5f)
            curveToRelative(1.38f, 0.0f, 2.5f, -1.12f, 2.5f, -2.5f)
            reflectiveCurveTo(20.88f, 4.5f, 19.5f, 4.5f)
            reflectiveCurveTo(17.0f, 5.62f, 17.0f, 7.0f)
            reflectiveCurveToRelative(1.12f, 2.5f, 2.5f, 2.5f)
            close()
            moveTo(12.0f, 10.0f)
            curveToRelative(-3.04f, 0.0f, -5.5f, 2.01f, -5.5f, 4.5f)
            curveToRelative(0.0f, 1.49f, 0.93f, 2.78f, 2.33f, 3.61f)
            lineTo(8.0f, 21.5f)
            curveToRelative(0.0f, 0.83f, 1.79f, 1.5f, 4.0f, 1.5f)
            reflectiveCurveToRelative(4.0f, -0.67f, 4.0f, -1.5f)
            lineToRelative(-0.83f, -3.39f)
            curveToRelative(1.4f, -0.83f, 2.33f, -2.12f, 2.33f, -3.61f)
            curveToRelative(0.0f, -2.49f, -2.46f, -4.5f, -5.5f, -4.5f)
            close()
        }.build()
    }

    val Alarms: ImageVector by lazy {
        ImageVector.Builder(
            name = "Alarms",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(12.0f, 22.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            horizontalLineToRelative(-4.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            close()
            moveTo(18.0f, 16.0f)
            verticalLineToRelative(-5.0f)
            curveToRelative(0.0f, -3.07f, -1.63f, -5.64f, -4.5f, -6.32f)
            verticalLineTo(4.0f)
            curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
            reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
            verticalLineToRelative(0.68f)
            curveTo(7.64f, 5.36f, 6.0f, 7.92f, 6.0f, 11.0f)
            verticalLineToRelative(5.0f)
            lineToRelative(-2.0f, 2.0f)
            verticalLineToRelative(1.0f)
            horizontalLineToRelative(16.0f)
            verticalLineToRelative(-1.0f)
            lineToRelative(-2.0f, -2.0f)
            close()
        }.build()
    }

    val Other: ImageVector by lazy {
        ImageVector.Builder(
            name = "Other",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(6.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(12.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(18.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
        }.build()
    }

    val Sparkle: ImageVector by lazy {
        ImageVector.Builder(
            name = "Sparkle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(19.0f, 9.0f)
            lineToRelative(1.25f, -2.75f)
            lineTo(23.0f, 5.0f)
            lineToRelative(-2.75f, -1.25f)
            lineTo(19.0f, 1.0f)
            lineToRelative(-1.25f, 2.75f)
            lineTo(15.0f, 5.0f)
            lineToRelative(2.75f, 1.25f)
            close()
            moveTo(9.0f, 11.0f)
            lineToRelative(2.5f, -5.5f)
            lineTo(14.0f, 11.0f)
            lineToRelative(5.5f, 2.5f)
            lineTo(14.0f, 16.0f)
            lineToRelative(-2.5f, 5.5f)
            lineTo(9.0f, 16.0f)
            lineToRelative(-5.5f, -2.5f)
            close()
        }.build()
    }

    val Speedometer: ImageVector by lazy {
        ImageVector.Builder(
            name = "Speedometer",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(12.0f, 4.0f)
            curveTo(6.48f, 4.0f, 2.0f, 8.48f, 2.0f, 14.0f)
            curveToRelative(0.0f, 2.41f, 0.86f, 4.62f, 2.29f, 6.34f)
            lineToRelative(1.42f, -1.42f)
            curveTo(4.62f, 17.51f, 4.0f, 15.83f, 4.0f, 14.0f)
            curveToRelative(0.0f, -4.41f, 3.59f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
            curveToRelative(0.0f, 1.83f, -0.62f, 3.51f, -1.71f, 4.92f)
            lineToRelative(1.42f, 1.42f)
            curveTo(21.14f, 18.62f, 22.0f, 16.41f, 22.0f, 14.0f)
            curveToRelative(0.0f, -5.52f, -4.48f, -10.0f, -10.0f, -10.0f)
            close()
            moveTo(12.0f, 12.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(13.41f, 12.59f)
            lineTo(17.0f, 9.0f)
            lineToRelative(-1.41f, -1.41f)
            lineToRelative(-3.59f, 3.59f)
            close()
        }.build()
    }

    val Bed: ImageVector by lazy {
        ImageVector.Builder(
            name = "Bed",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(20.0f, 9.5f)
            verticalLineTo(6.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(3.5f)
            curveToRelative(-1.16f, 0.41f, -2.0f, 1.51f, -2.0f, 2.81f)
            verticalLineTo(19.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(16.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-6.69f)
            curveToRelative(0.0f, -1.3f, -0.84f, -2.4f, -2.0f, -2.81f)
            close()
            moveTo(6.0f, 6.0f)
            horizontalLineToRelative(5.0f)
            verticalLineToRelative(3.0f)
            horizontalLineTo(6.0f)
            verticalLineTo(6.0f)
            close()
            moveTo(13.0f, 6.0f)
            horizontalLineToRelative(5.0f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(-5.0f)
            verticalLineTo(6.0f)
            close()
            moveTo(4.0f, 14.0f)
            verticalLineToRelative(-1.5f)
            curveToRelative(0.0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
            horizontalLineToRelative(13.0f)
            curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
            verticalLineTo(14.0f)
            horizontalLineTo(4.0f)
            close()
        }.build()
    }

    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronRight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(10.0f, 6.0f)
            lineTo(8.59f, 7.41f)
            lineTo(13.17f, 12.0f)
            lineToRelative(-4.58f, 4.59f)
            lineTo(10.0f, 18.0f)
            lineToRelative(6.0f, -6.0f)
            close()
        }.build()
    }

    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(12.0f, 14.0f)
            curveToRelative(1.66f, 0.0f, 2.99f, -1.34f, 2.99f, -3.0f)
            lineTo(15.0f, 5.0f)
            curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
            reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
            verticalLineToRelative(6.0f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            close()
            moveTo(17.3f, 11.0f)
            curveToRelative(0.0f, 3.0f, -2.54f, 5.1f, -5.3f, 5.1f)
            reflectiveCurveTo(6.7f, 14.0f, 6.7f, 11.0f)
            horizontalLineTo(5.0f)
            curveToRelative(0.0f, 3.41f, 2.72f, 6.23f, 6.0f, 6.72f)
            verticalLineTo(21.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-3.28f)
            curveToRelative(3.28f, -0.48f, 6.0f, -3.3f, 6.0f, -6.72f)
            horizontalLineToRelative(-1.7f)
            close()
        }.build()
    }

    val MicOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "MicOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(19.0f, 11.0f)
            horizontalLineToRelative(-1.7f)
            curveToRelative(0.0f, 0.58f, -0.1f, 1.13f, -0.27f, 1.64f)
            lineToRelative(1.27f, 1.27f)
            curveToRelative(0.44f, -0.88f, 0.7f, -1.87f, 0.7f, -2.91f)
            close()
            moveTo(4.41f, 2.86f)
            lineTo(3.0f, 4.27f)
            lineToRelative(6.0f, 6.0f)
            verticalLineTo(11.0f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            curveToRelative(0.23f, 0.0f, 0.44f, -0.03f, 0.65f, -0.08f)
            lineToRelative(4.07f, 4.07f)
            curveToRelative(-1.18f, 0.78f, -2.59f, 1.26f, -4.12f, 1.33f)
            verticalLineTo(21.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-1.68f)
            curveToRelative(0.96f, -0.08f, 1.87f, -0.34f, 2.7f, -0.74f)
            lineToRelative(2.49f, 2.49f)
            lineToRelative(1.41f, -1.41f)
            lineTo(4.41f, 2.86f)
            close()
            moveTo(12.0f, 4.0f)
            curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
            verticalLineToRelative(4.0f)
            lineToRelative(-3.0f, -3.0f)
            verticalLineTo(4.0f)
            close()
        }.build()
    }
}
