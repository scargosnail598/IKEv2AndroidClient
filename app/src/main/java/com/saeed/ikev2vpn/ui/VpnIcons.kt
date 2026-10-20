package com.saeed.ikev2vpn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small, consistent outline icons; no extra icon pack or bitmap assets. */
internal object VpnIcons {
    val Bell = icon("Bell", "M18 8 A6 6 0 0 0 6 8 C6 15 3 15 3 17 H21 C21 15 18 15 18 8 M10 21 H14")
    val Info = icon("Info", "M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 M12 11 V17 M12 7 H12.1")
    val Shield = icon("Shield", "M12 3 L20 6 V11 C20 16 16 20 12 22 C8 20 4 16 4 11 V6 Z M9 12 L11 14 L15 10")
    val Download = icon("Download", "M12 3 V16 M7 11 L12 16 L17 11 M4 17 V21 H20 V17")
    val Upload = icon("Upload", "M12 17 V4 M7 9 L12 4 L17 9 M4 17 V21 H20 V17")
    val Power = icon("Power", "M12 3 V11 M6 5.5 A9 9 0 1 0 18 5.5")
    val Profiles = icon("Profiles", "M4 4 H20 V9 H4 Z M4 15 H20 V20 H4 Z M8 6.5 H8.1 M8 17.5 H8.1")
    val Add = icon("Add", "M12 5 V19 M5 12 H19")
    val More = icon("More", "M5 12 H5.1 M12 12 H12.1 M19 12 H19.1")
    val Back = icon("Back", "M19 12 H5 M11 6 L5 12 L11 18")
    val Chevron = icon("Chevron", "M9 6 L15 12 L9 18")
    val Check = icon("Check", "M5 12 L10 17 L19 7")
    val Edit = icon("Edit", "M4 16 L15 5 L19 9 L8 20 H4 Z M13 7 L17 11")
    val Trash = icon("Trash", "M4 6 H20 M9 6 V3 H15 V6 M6 6 L7 21 H17 L18 6 M10 10 V17 M14 10 V17")
    val Qr = icon("Qr", "M3 3 H9 V9 H3 Z M15 3 H21 V9 H15 Z M3 15 H9 V21 H3 Z M15 15 H18 V18 H21 V21 M15 21 H15.1 M21 15 H21.1")
    val Link = icon("Link", "M10 14 L14 10 M8 16 L6 18 A4 4 0 0 1 1 13 L6 8 A4 4 0 0 1 12 9 M16 8 L18 6 A4 4 0 0 1 23 11 L18 16 A4 4 0 0 1 12 15")
    val File = icon("File", "M6 3 H14 L19 8 V21 H6 Z M14 3 V8 H19 M9 13 H16 M9 17 H14")
    val Photo = icon("Photo", "M3 4 H21 V20 H3 Z M3 17 L9 11 L14 16 L17 13 L21 17 M16 8 H16.1")
    val Moon = icon("Moon", "M20 15 A9 9 0 1 1 9 4 A7 7 0 0 0 20 15 Z")
    val Palette = icon("Palette", "M12 3 A9 9 0 1 0 12 21 H14 A2 2 0 0 0 14 17 H13 A1.5 1.5 0 0 1 13 14 H17 A4 4 0 0 0 21 10 C21 6 17 3 12 3 M7 10 H7.1 M10 6.5 H10.1 M15 7 H15.1 M6.5 15 H6.6")
    val Activity = icon("Activity", "M2 12 H6 L9 4 L14 20 L17 12 H22")
    val Apps = icon("Apps", "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14 14 H20 V20 H14 Z")
    val Lock = icon("Lock", "M5 11 H19 V21 H5 Z M8 11 V7 A4 4 0 0 1 16 7 V11 M12 15 V17")

    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(), fill = null,
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
}
