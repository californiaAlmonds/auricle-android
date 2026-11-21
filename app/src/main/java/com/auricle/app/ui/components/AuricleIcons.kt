package com.auricle.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object AuricleIcons {

    private fun lucideStroke(
        name: String,
        vararg pathStrings: String
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        for (pathData in pathStrings) {
            builder.addPath(
                pathData = addPathNodes(pathData),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero
            )
        }
        return builder.build()
    }

    private fun lucideFill(
        name: String,
        vararg pathStrings: String
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        for (pathData in pathStrings) {
            builder.addPath(
                pathData = addPathNodes(pathData),
                fill = SolidColor(Color.Black),
                stroke = null,
                pathFillType = PathFillType.NonZero
            )
        }
        return builder.build()
    }

    val Home = lucideStroke("Home", "M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M9 22V12h6v10")
    val Search = lucideStroke("Search", "M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0z", "M21 21l-4.3-4.3")
    val Library = lucideStroke("Library", "M16 6l4 14", "M12 6v14", "M8 8v12", "M4 4v16")
    val Explore = lucideStroke("Explore", "M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z", "m16.24 7.76-2.12 6.36-6.36 2.12 2.12-6.36 6.36-2.12z")
    val Settings = lucideStroke("Settings", "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z")
    val Heart = lucideStroke("Heart", "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z")
    val HeartFill = lucideFill("HeartFill", "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z")
    val Shuffle = lucideStroke("Shuffle", "M16 3h5v5", "M4 20L21 3", "M21 16v5h-5", "M15 15l6 6", "M4 4l5 5")
    val Repeat = lucideStroke("Repeat", "m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3")
    val RepeatOne = lucideStroke("RepeatOne", "m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3", "M11 10h1v4")
    val Queue = lucideStroke("Queue", "M21 15V6", "M18.5 18a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z", "M12 12H3", "M16 6H3", "M12 18H3")
    val Radio = lucideStroke("Radio", "M4.9 19.1C3.1 17.3 2 14.8 2 12s1.1-5.3 2.9-7.1", "M8.4 15.6c-1-1-1.4-2.3-1.4-3.6s.4-2.6 1.4-3.6", "M15.6 8.4c1 1 1.4 2.3 1.4 3.6s-.4 2.6-1.4 3.6", "M19.1 4.9C20.9 6.7 22 9.2 22 12s-1.1 5.3-2.9 7.1", "M12 12h.01")
    val Lyrics = lucideStroke("Lyrics", "M4 6h16", "M4 12h10", "M4 18h7", "M19.5 18V10l2 1", "M17 20.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z")
    val ArrowUpLeft = lucideStroke("ArrowUpLeft", "M17 17L7 7", "M7 17V7h10")
    val Timer = lucideStroke("Timer", "M10 2h4", "M12 14l3-3", "M12 22a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z")
    val Plus = lucideStroke("Plus", "M5 12h14", "M12 5v14")
    val Close = lucideStroke("Close", "M18 6L6 18", "M6 6l12 12")
    val Check = lucideStroke("Check", "M20 6L9 17l-5-5")
    val ChevronDown = lucideStroke("ChevronDown", "m6 9 6 6 6-6")
    val ChevronLeft = lucideStroke("ChevronLeft", "m15 18-6-6 6-6")
    val ChevronRight = lucideStroke("ChevronRight", "m9 18 6-6-6-6")
    val Grip = lucideStroke("Grip", "M9 6h.01", "M9 12h.01", "M9 18h.01", "M15 6h.01", "M15 12h.01", "M15 18h.01")
    val ListPlus = lucideStroke("ListPlus", "M11 12H3", "M16 6H3", "M16 18H3", "M19 10v6", "M22 13h-6")
    val ListStart = lucideStroke("ListStart", "M16 12H3", "M16 6H3", "M10 18H3", "M21 15l-4-4-4 4", "M17 11v10")
    val Album = lucideStroke("Album", "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z", "M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z", "M20 12A8 8 0 1 1 4 12a8 8 0 0 1 16 0Z")
    val Artist = lucideStroke("Artist", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z")
    val Share = lucideStroke("Share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "m16 6-4-4-4 4", "M12 2v13")
    val Link = lucideStroke("Link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
    val Copy = lucideStroke("Copy", "M9 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9.828a2 2 0 0 0-.586-1.414l-4-4A2 2 0 0 0 13.172 3H11", "M13 3v4a2 2 0 0 0 2 2h4")
    val Download = lucideStroke("Download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3")
    val Refresh = lucideStroke("Refresh", "M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16", "M16 21v-5h5")
    val Sparkles = lucideStroke("Sparkles", "m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z", "M5 3v4", "M19 17v4", "M3 5h4", "M17 19h4")
    val Flame = lucideStroke("Flame", "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z")
    val Clock = lucideStroke("Clock", "M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z", "M12 6v6l4 2")
    val Trash = lucideStroke("Trash", "M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val Sun = lucideStroke("Sun", "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z", "M12 1v2", "M12 21v2", "M4.22 4.22l1.42 1.42", "M18.36 18.36l1.42 1.42", "M1 12h2", "M21 12h2", "M4.22 19.78l1.42-1.42", "M18.36 5.64l1.42-1.42")
    val Moon = lucideStroke("Moon", "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z")
    val Phone = lucideStroke("Phone", "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z")
    val Database = lucideStroke("Database", "M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5", "M12 8c4.97 0 9-1.34 9-3s-4.03-3-9-3-9 1.34-9 3 4.03 3 9 3z", "M3 12c0 1.66 4 3 9 3s9-1.34 9-3")
    val Info = lucideStroke("Info", "M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z", "M12 16v-4", "M12 8h.01")
    val Update = lucideStroke("Update", "M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.59-9.21l-3.25 1.57") // Actually "RotateCw" or "ArrowRightCycle" - closest to update
    val Infinity = lucideStroke("Infinity", "M12 12c-2-2.67-4-4-6-4a4 4 0 1 0 0 8c2 0 4-1.33 6-4Zm0 0c2 2.67 4 4 6 4a4 4 0 1 0 0-8c-2 0-4 1.33-6 4Z")
    val Logout = lucideStroke("Logout", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "m16 17 5-5-5-5", "M21 12H9")
    val Music = lucideStroke("Music", "M9 18V5l12-2v13", "M6 15a3 3 0 1 0 3 3v-3Z", "M18 13a3 3 0 1 0 3 3v-3Z")
    val Translate = lucideStroke("Translate", "m5 8 6 6", "m4 14 6-6 2-3", "M2 5h12", "M7 2h1", "m22 22-5-10-5 10", "m14 18h6")
    val Palette = lucideStroke("Palette", "M12 22a10 10 0 1 1 10-10c0 2.2-2 4-4 4a2 2 0 0 0-2 2c0 1.1.9 2 2 2h0a2 2 0 0 1-2 2z", "M7.5 9.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z", "M10.5 5.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z", "M14.5 5.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z", "M17.5 9.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z")
    val Github = lucideStroke("Github", "M15 22v-4a4.8 4.8 0 0 0-1-3.5c3 0 6-2 6-5.5.08-1.25-.27-2.48-1-3.5.28-1.15.28-2.35 0-3.5 0 0-1 0-3 1.5-2.64-.5-5.36-.5-8 0C6 2 5 2 5 2c-.3 1.15-.3 2.35 0 3.5A5.403 5.403 0 0 0 4 9c0 3.5 3 5.5 6 5.5-.39.49-.68 1.05-.85 1.65-.17.6-.22 1.23-.15 1.85v4", "M9 18c-4.51 2-5-2-7-2")
    val Wand = lucideStroke("Wand", "M15 4V2", "M15 16v-2", "M8 9h2", "M20 9h2", "M17.8 11.8l1.4 1.4", "M10.6 6.2l1.4 1.4", "M17.8 6.2l1.4-1.4", "M10.6 11.8l1.4-1.4", "m3 21 9-9", "M12.2 13.8l8-8a1.41 1.41 0 0 0-2-2l-8 8") // Simplified Wand/Magic
    val Qr = lucideStroke("Qr", "M3 3h8v8H3z", "M13 3h8v8h-8z", "M3 13h8v8H3z", "M13 13h2v2h-2z", "M19 13h2v2h-2z", "M16 16h2v2h-2z", "M13 19h2v2h-2z", "M19 19h2v2h-2z") // simplified QR code
    val Clipboard = lucideStroke("Clipboard", "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", "M15 2H9a1 1 0 0 0-1 1v2a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1V3a1 1 0 0 0-1-1Z")
    val Edit = lucideStroke("Edit", "M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z")
    val Volume = lucideStroke("Volume", "M11 5L6 9H2v6h4l5 4V5z", "M15.54 8.46a5 5 0 0 1 0 7.07", "M19.07 4.93a10 10 0 0 1 0 14.14")
    
    val Play = lucideFill("Play", "M6 4.5v15a1 1 0 0 0 1.5.87l13-7.5a1 1 0 0 0 0-1.74l-13-7.5A1 1 0 0 0 6 4.5z")
    val Pause = lucideFill("Pause", "M6 5a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5z", "M14 5a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V5z")
    val Next = lucideFill("Next", "M13 19V5l9 7-9 7z", "M2 19V5l9 7-9 7z")
    val Prev = lucideFill("Prev", "M11 19l-9-7 9-7v14z", "M22 19l-9-7 9-7v14z")
    
    val More = lucideStroke("More", "M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2z", "M19 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2z", "M5 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2z")
    val MoreVertical = lucideStroke("MoreVertical", "M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2z", "M12 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2z", "M12 6a1 1 0 1 0 0-2 1 1 0 0 0 0 2z")
    val Bars = lucideStroke("Bars", "M4 18v-6", "M9 18V6", "M14 18v-9", "M19 18v-4")
    val Explicit = lucideStroke("Explicit", "M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M14 12H9", "M14 8H9v8h5")
}