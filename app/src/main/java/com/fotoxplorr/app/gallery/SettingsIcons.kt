package com.fotoxplorr.app.gallery

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The glyphs Settings draws, by name. Pure data: which glyph a tab, a row or a section wears is
 * decided by [iconFor] / [SettingsRow.icon] / [SettingsSection.icon], none of which touch Compose,
 * so a JVM test can say "every one has an icon" (SettingsIconsTest). [vector] is the one place a
 * key becomes a Material outlined vector (the house set: the app already uses Icons.Outlined
 * everywhere and `material-icons-extended` is on the classpath, so no dependency was added).
 */
enum class SettingsIcon(val vector: ImageVector) {
    PALETTE(Icons.Outlined.Palette),
    PHOTOS(Icons.Outlined.PhotoLibrary),
    FOLDER(Icons.Outlined.Folder),
    LOCK(Icons.Outlined.Lock),
    SLIDESHOW(Icons.Outlined.Slideshow),
    STORAGE(Icons.Outlined.Storage),
    CLOUD(Icons.Outlined.Cloud),
    SCHEDULE(Icons.Outlined.Schedule),
    PREMIUM(Icons.Outlined.WorkspacePremium),
    INFO(Icons.Outlined.Info),
    DARK_MODE(Icons.Outlined.DarkMode),
    COLOUR(Icons.Outlined.ColorLens),
    ZOOM(Icons.Outlined.ZoomIn),
    CROP(Icons.Outlined.Crop),
    JUSTIFY(Icons.Outlined.Dashboard),
    ANIMATION(Icons.Outlined.Animation),
    TOUCH(Icons.Outlined.TouchApp),
    SAVE(Icons.Outlined.Save),
    SORT(Icons.Outlined.SwapVert),
    TIMELINE(Icons.Outlined.Timeline),
    SWAP_SIDES(Icons.Outlined.SwapHoriz),
    EYE(Icons.Outlined.Visibility),
    EYE_OFF(Icons.Outlined.VisibilityOff),
    CALENDAR(Icons.Outlined.CalendarMonth),
    HOME(Icons.Outlined.Home),
    VIDEO(Icons.Outlined.Videocam),
    BLUR(Icons.Outlined.BlurOn),
    TIMER(Icons.Outlined.Timer),
    SHUFFLE(Icons.Outlined.Shuffle),
    SCREEN_ON(Icons.Outlined.LightMode),
    PLAY(Icons.Outlined.PlayCircle),
    FILMSTRIP(Icons.Outlined.Movie),
    TUNE(Icons.Outlined.Tune),
    IDLE(Icons.Outlined.Bedtime),
    CHARGING(Icons.Outlined.BatteryChargingFull),
    BATTERY(Icons.Outlined.BatteryAlert),
    ALARM(Icons.Outlined.Alarm),
    WIFI(Icons.Outlined.Wifi),
    BUILD(Icons.Outlined.Build),
    KEY(Icons.Outlined.Key),
    CHIP(Icons.Outlined.Memory),
    CLOUD_SYNC(Icons.Outlined.CloudSync),
    EXPORT(Icons.Outlined.FileUpload),
    IMPORT(Icons.Outlined.FileDownload),
    REFRESH(Icons.Outlined.Refresh),
    UNLOCK(Icons.Outlined.LockOpen),
    MAIL(Icons.Outlined.Mail),
    OPEN_LINK(Icons.Outlined.OpenInNew),
    BROWSE(Icons.Outlined.Explore),
    MAP(Icons.Outlined.Map),
    OFFLINE_MAP(Icons.Outlined.DownloadForOffline),
    WEB(Icons.Outlined.Language),
    BURST(Icons.Outlined.BurstMode),
    MOTION(Icons.Outlined.MotionPhotosOn),
}

/** The glyph of a Settings tab. Exhaustive, so a new tab does not compile without one. */
fun iconFor(tab: SettingsTab): SettingsIcon = when (tab) {
    SettingsTab.APPEARANCE -> SettingsIcon.PALETTE
    SettingsTab.MEDIA -> SettingsIcon.PHOTOS
    SettingsTab.LIBRARY -> SettingsIcon.FOLDER
    SettingsTab.PRIVACY -> SettingsIcon.LOCK
    SettingsTab.VIEWER -> SettingsIcon.SLIDESHOW
    SettingsTab.DATA -> SettingsIcon.STORAGE
    SettingsTab.SERVICES -> SettingsIcon.CLOUD
    SettingsTab.BACKGROUND -> SettingsIcon.SCHEDULE
    SettingsTab.PRO -> SettingsIcon.PREMIUM
    SettingsTab.ABOUT -> SettingsIcon.INFO
}

/**
 * Every fixed-label row of Settings. A row composable takes one of these instead of a bare string,
 * so a row cannot be drawn without a glyph; [label] is the words it shows.
 */
enum class SettingsRow(val label: String, val icon: SettingsIcon) {
    THEME("Theme", SettingsIcon.DARK_MODE),
    ACCENT("Accent", SettingsIcon.COLOUR),
    GRID_ZOOM("Grid zoom", SettingsIcon.ZOOM),
    MANAGE_SELECTED("Manage selected photos and videos", SettingsIcon.PHOTOS),
    FILL_TILE("Fill the tile", SettingsIcon.CROP),
    JUSTIFIED("Justified rows", SettingsIcon.JUSTIFY),
    CULL_SIDECARS("Save ratings beside photos", SettingsIcon.SAVE),
    GROUP_BURSTS("Group bursts", SettingsIcon.BURST),
    PAIR_LIVE("Pair Live Photos", SettingsIcon.MOTION),
    PLAY_ANIMATIONS("Play animations", SettingsIcon.ANIMATION),
    LONG_PRESS("Long press does", SettingsIcon.TOUCH),
    SAVING_EDITS("Saving edits", SettingsIcon.SAVE),
    SORT("Sort", SettingsIcon.SORT),
    TIMELINE("Timeline", SettingsIcon.TIMELINE),
    TIMELINE_EDGE("Timeline edge", SettingsIcon.SWAP_SIDES),
    TIMELINE_SHOWS("Timeline shows", SettingsIcon.EYE),
    TIMELINE_GROUP("Group timeline by", SettingsIcon.CALENDAR),
    SCROLL_DIRECTION("Scroll direction", SettingsIcon.SORT),
    OPEN_ON("Open the app on", SettingsIcon.HOME),
    SHOW_VIDEOS("Show videos", SettingsIcon.VIDEO),
    BLUR_SENSITIVE("Blur sensitive photos", SettingsIcon.BLUR),
    HIDE_SENSITIVE("Hide sensitive photos", SettingsIcon.EYE_OFF),
    SLIDESHOW_INTERVAL("Slideshow interval", SettingsIcon.TIMER),
    SHUFFLE("Shuffle slideshows", SettingsIcon.SHUFFLE),
    KEEP_SCREEN_ON("Keep the screen on", SettingsIcon.SCREEN_ON),
    AUTOPLAY("Play videos automatically", SettingsIcon.PLAY),
    FILMSTRIP("Filmstrip", SettingsIcon.FILMSTRIP),
    SAVE_TAGS("Save tags and collections to a file", SettingsIcon.EXPORT),
    RESTORE_TAGS("Restore tags and collections from a file", SettingsIcon.IMPORT),
    RESCAN("Rescan for new photos", SettingsIcon.REFRESH),
    BACKGROUND_RULES("Use background rules", SettingsIcon.TUNE),
    IDLE("Only when the phone is idle", SettingsIcon.IDLE),
    CHARGING("Only while charging", SettingsIcon.CHARGING),
    MIN_BATTERY("Minimum battery", SettingsIcon.BATTERY),
    HOURS_FROM("From", SettingsIcon.ALARM),
    HOURS_UNTIL("Until", SettingsIcon.ALARM),
    WIFI_ONLY("Only on Wi-Fi", SettingsIcon.WIFI),
    UNLOCK_PRO("Unlock Pro", SettingsIcon.UNLOCK),
    VERSION("Version", SettingsIcon.INFO),
    BUILD("Build", SettingsIcon.BUILD),
    SUPPORT("Support", SettingsIcon.MAIL),
    MORE_APPS("More apps", SettingsIcon.OPEN_LINK),
    AI_KEYS("AI keys", SettingsIcon.KEY),
    MODELS("Models on this phone", SettingsIcon.CHIP),
    SYNC("Sync", SettingsIcon.CLOUD_SYNC),
}

/** Section headings of Settings, with the glyph that goes in the heading's chip. */
enum class SettingsSection(val label: String, val icon: SettingsIcon) {
    MEDIA_ACCESS("MEDIA ACCESS", SettingsIcon.PHOTOS),
    SAVING_EDITS("SAVING EDITS", SettingsIcon.SAVE),
    BROWSE("BROWSE", SettingsIcon.BROWSE),
    BACKUP_TAGS("BACKUP: TAGS AND COLLECTIONS", SettingsIcon.EXPORT),
    BACKUP_ENCRYPTED("BACKUP: EVERYTHING, ENCRYPTED", SettingsIcon.LOCK),
    MAPS("MAPS", SettingsIcon.MAP),
    LIBRARY("LIBRARY", SettingsIcon.FOLDER),
    WHEN("WHEN", SettingsIcon.SCHEDULE),
    ACTIVE_HOURS("ACTIVE HOURS", SettingsIcon.ALARM),
    NETWORK("NETWORK", SettingsIcon.WIFI),
    RIGHT_NOW("RIGHT NOW", SettingsIcon.TUNE),
    PLAN("PLAN", SettingsIcon.PREMIUM),
    WHAT_PRO_REMOVES("WHAT PRO REMOVES", SettingsIcon.PREMIUM),
    UNLOCK("UNLOCK", SettingsIcon.UNLOCK),
    SUPPORT("SUPPORT", SettingsIcon.MAIL),
    MORE_FROM_US("MORE FROM A SYSTEM OF CELLS", SettingsIcon.OPEN_LINK),
    MAP_DATA("MAP DATA", SettingsIcon.MAP),
    REACH("WHAT THIS BUILD CAN REACH", SettingsIcon.WEB),
    OPEN_SOURCE("OPEN SOURCE", SettingsIcon.INFO),
}
