# Home-screen widgets

Fotoz ships six standard Android App Widgets (`AppWidgetProvider` + `appwidget-provider` XML +
`RemoteViews`), so any launcher can host them: Pixel Launcher, One UI, Nova, Orrery Home. Each one
has three layouts (small, medium, large) and draws the app's real data. They were built on
4 Nov 2026 at the owner's request ("at least 5 widgets each … usable by Android launchers"), on
today's stores, ahead of the plan's WP 7.5 (which names "memories, a random favourite").

Code: `app/src/main/java/com/fotoxplorr/app/widget/`. Layouts: `res/layout/widget_*.xml`. Provider
declarations: `res/xml/widget_info_*.xml`. Colours: `res/values/widget_colors.xml` and
`res/values-night/widget_colors.xml`.

**Nothing here has been seen on a phone.** The layouts are rendered on the JVM by
`WidgetRenderTest` (PNG files under `app/build/renders/widgets/`); everything a launcher, a phone or
a battery manager does is on the owner-verify list at the end.

## The six widgets

| Widget | Template | Small | Medium | Large |
|---|---|---|---|---|
| **A favourite** | `media` | the photo, labelled "★ A favourite" | photo beside label, date taken and how many favourites | large photo over the same three lines |
| **On this day** | `media` (strip) | the most recent year's photo, "On this day · 2025" | header (date, item count) and four tiles, each with its year | that year's photo large ("2025 · 1 year ago") above four more years |
| **Recent photos** | `media` (strip/grid) | the newest item, "Latest · Oct 29" over "As of Nov 3" | header with the "As of" time and the four newest | two rows: the eight newest |
| **Library at a glance** | `stat` | the total, "photos and videos" | header with the "As of" time; photos, videos, favourites | total in the header; adds screenshots, albums, collections and a footer: "As of" time and what is not counted |
| **Collections** | `list` | the first collection's cover, "Oct – Nov 2026 · N items" | header (how many) and three rows: cover, the months its pictures span, item count | five rows |
| **Indexing** | `progress` | percentage of photos indexed, as digits, words and a bar | photos (People, Pets, text) and videos (key moments), each "N of M" with a bar, and the "As of" time | adds what the background work is doing when this process knows it, and what the counts leave out |

"As of" is when Fotoz last finished reading the library from the phone, not when the widget was
drawn (see "Stale data" below): the time on the same day, otherwise the date (headers and the small
layout) or the date and time (footers), with the year only when it is not this year.

Which layout a size gets (`WidgetKind.kt` and `WidgetSizing.kt`, one table per widget for both API
paths). Each minimum is a width and a height at the default font size; the height grows with the
system font size (`LayoutMinimum`: a measured height at each font step, linear between them), so at
a large font a widget keeps a smaller layout rather than cutting text. Every minimum was measured from the layouts themselves:
`WidgetRenderTest` draws each layout at exactly its minimum at each of Android's font sizes from 1x
to 2x (1, 1.15, 1.3, 1.5, 1.8, 2), with real and five-digit data, and fails on anything clipped, any
line of text cut, or a picture under 40 dp.

Minimum width x height in dp at 1x font, then the height at 1.3x and 2x (the table in
`WidgetKind.kt` also holds 1.15x, 1.5x and 1.8x):

| Widget | Small | Medium | Large |
|---|---|---|---|
| A favourite | 110 x 100 (100, 100) | 250 x 103 (125, 204) | 250 x 250 (250, 250) |
| On this day | 110 x 100 (100, 100) | 250 x 100 (100, 122) | 250 x 250 (250, 251) |
| Recent photos | 110 x 100 (100, 100) | 250 x 100 (100, 108) | 250 x 250 (250, 250) |
| Library at a glance | 110 x 72 (86, 119) | 250 x 98 (116, 160) | 250 x 204 (248, 353) |
| Collections | 110 x 100 (100, 100) | 200 x 202 (208, 281) | 200 x 306 (312, 422) |
| Indexing | 110 x 83 (97, 129) | 250 x 117 (132, 169) | 250 x 206 (240, 324) |

What that means in cells at the default font (portrait 2x2 130x220, 4x1 276x102, 4x2 276x220, 4x3
276x337): the picture widgets give one picture at 2x2, the strip at 4x2 and the large layout at 4x3;
Library gives its row of counts at 4x1 and the large layout at 4x2; Indexing keeps the percentage at
4x1 (its two progress sections need 117 dp), shows them at 4x3 landscape and the large layout at
4x2 portrait; Collections gives three rows at 4x2 and five at 4x3. At 1.3x a 4x2 Library shows its
row of counts rather than a large layout cut at the bottom. The small Library and Indexing layouts
put the number above its words, and the number shrinks to the room left (never below 12 sp), so a
five-digit count at a large font is never wrapped or pushed out. `minResizeHeight` is 72 dp for
Library and 83 dp for Indexing, their small layouts' 1x height.

- **API 31 and up:** one `RemoteViews(Map<SizeF, RemoteViews>)` holds all three layouts, keyed by
  those minimums at the current font size; the launcher switches layout on resize without waking
  the app. A change of font size takes effect at the next redraw.
- **API 26 to 30:** the reported options pick one layout for landscape and one for portrait, combined
  with `RemoteViews(landscape, portrait)`; a resize arrives as an options change and is drawn again.
- Below a widget's smallest minimum the small layout is still used (nothing else fits), and every
  small layout is a single tap target. That is where text can still be cut: a one-row widget in
  landscape (about 51 dp tall) at 2x font has no room for a line of text and its padding.
- Default sizes: A favourite 2x2; Collections 4x3; the rest 4x2. All resize both ways.

### What each one reads, and what a tap does

| Widget | Data (all through `WidgetDataSource`) | Tap |
|---|---|---|
| A favourite | `FavoriteStore` ∩ the pictures a widget may draw. One favourite per local day: a pure function of the date over the favourites sorted by id, so it does not flicker between redraws and changes at midnight. | anywhere: opens that photo in the viewer |
| On this day | the pictures a widget may draw, by `dateTakenMillis` in the device's time zone: same month and day, earlier years. 29 February photos show on 28 February in a year without one. Years are interleaved, most recent first. | a picture: opens it in the viewer, able to swipe through that day's items (up to 60); elsewhere: opens the app |
| Recent photos | the pictures a widget may draw, in the app's own NEWEST order | a picture: opens it, with the eight as the viewer's run; elsewhere: opens the app |
| Library at a glance | counts over the visible catalogue; collections counted by the Collections widget's own rule (`shownCollections`), so the two always agree | opens the app |
| Collections | `LibraryStore` collections in the app's own order (by name), each with the members a widget may draw, newest first; a collection with none is left out | a row or the small card: opens the cover in the viewer, able to swipe through that collection's visible items (up to 60); elsewhere: opens the app |
| Indexing | visible stills against `RecognitionStore.pendingAssets`; visible videos against `VideoMomentStore.hasBeenScanned`; `BackgroundWorkStatusCenter` and `LibraryRuntime.scanState` for the status line | opens the app |

"Visible" is the forced home-screen filter below; "the pictures a widget may draw" is that, minus
screenshots and identity documents.

The catalogue comes from `LibraryRuntime.get(context).repository.awaitLoaded()` (TRAPS #21, never a
second repository), `LibraryStore.get` is the one instance (TRAPS #26), and the favourite, sensitive
and protected-folder stores are only read. None of this goes through an Activity or a ViewModel.

"Opens the app" means the launcher-icon intent: an existing task comes forward as it was. The app has
no way to navigate to a destination (Favourites, Protected, Settings › Background) from outside, so
those taps land wherever the app already is, or on its home. There is no in-app "On this day" screen
yet (WP 6.11); the widget's taps open the viewer on that day's items instead.

## Privacy: a home screen is a public surface

- **One rule, forced arguments.** Everything a widget draws comes from `WidgetPrivacy.visibleAssets`,
  which calls `browsableAssets(...)` (TRAPS #33) with `unlockedFolders = emptySet()` and
  `hideSensitive = true`, always, whatever the app session has unlocked and whatever the in-app
  "hide sensitive" preference (default off) says. Trashed, archived, sensitive-flagged and
  protected-folder items never reach a widget. `WidgetPrivacyTest` records both arguments and checks
  every builder against the filtered list.
- **Pictures are narrower than counts.** A widget never draws, and a tap never opens, a screenshot
  (they hold chats, bank screens, one-time codes) or a photo that on-device recognition has read as
  an identity document (passport, ID card; `RecognitionStore.identityDocumentIds`, read straight
  from its database). Both stay in the app; they are only kept off the home screen
  (`WidgetPrivacy.picturedAssets`). Counts still include them, since a number names nothing.
  Recognition runs after a photo arrives, so a document photographed a moment ago can show until
  its text has been read; the end of each recognition pass redraws the widgets at once.
- **Taps are re-checked.** A widget can be out of date, so a tap carries only ids, and
  `FotoXplorrActivity` re-checks each against the stores as they stand at that moment through the
  same picture rule (`resolveWidgetOpen`) before opening anything. Something archived, flagged,
  protected or read as an identity document since the last redraw opens nothing. It reads the stores directly, not the Activity's collected
  state, whose first-frame values are empty. The same rule bounds what any other app could open by
  sending that intent to the (already exported) Activity.
- **Hidden reads like empty.** When everything of a kind is hidden (all favourites sensitive, every
  photo from this day in a protected folder), the widget says exactly what it says when there is
  nothing at all. A different message would tell anyone holding the phone that hidden items exist.
  The logic keeps the two apart (`WidgetState.Withheld`) so tests can prove nothing came through.
- **Counts leave hidden items out**, and the large layouts say so. Library's collection count uses
  the Collections widget's own rule, so comparing the two never hints that a collection holds only
  hidden items.
- **No file names anywhere**, including picture descriptions for TalkBack ("Photo taken 4 Nov 2025").
- **No typed text, so no collection names.** A collection's name is text the person typed ("Divorce
  evidence", someone's name), so no row, label or TalkBack description carries it, by construction:
  `CollectionRow` has no name field. Rows are told apart by their cover and the months their
  pictures span ("Oct – Nov 2026"). Showing names would need an explicit owner decision and a
  per-widget setting, which the widgets do not have.
- **Protected folders**, never "vault": they are an in-app access gate, not encryption (README).
  The widgets never show their contents, locked or not.
- **No backup leak.** Nothing is cached on disk for the widgets except the time of the last
  completed scan, under `noBackupFilesDir`; every redraw reads the live stores.
  (`android:allowBackup` is already `false`.)

## Pictures

Every picture is a bitmap embedded in the update (`setImageViewBitmap`), never a uri for the launcher
to open. Photos are decoded through `decodeUpright` (TRAPS #34: EXIF orientation, an exact bound,
software pixels); videos take one representative frame from `MediaMetadataRetriever`, as the
key-moment sampler does, and carry a ▶ mark plus "Video taken …" for TalkBack. Each picture is bounded
by pixel count, and one update's pictures together stay within a third of AppWidgetManager's limit
(1.5 x the screen's bytes), shrinking evenly on small screens (`planDecodeEdges`). The same bitmap is
shared by all three layouts, so the platform counts it once. If the platform still refuses an update
for its size, the widget is redrawn with placeholders and the same words. A file that cannot be read
shows a plain picture glyph; it never fails the widget.

## When widgets redraw

Pushed, never polled: `updatePeriodMillis` is 0 everywhere. `FotozWidgets.requestRefresh` hands
every redraw to a JobScheduler job (`WidgetRefreshJob`, id 20 932, run by `WidgetJobService`), not
an in-process timer. The job lives in the system: a redraw asked for just before the app is swiped
away from Recents, or frozen in the background, still runs (the system starts the process for it),
and while it runs it keeps the process alive. Nothing is read or scheduled unless a widget is
placed.

- **Ordinary changes** wait 1.5 s, so a burst of writes shares one redraw. Later writes never
  postpone it: the waiting redraw reads the stores when it runs, so it covers them.
- **Changes that take something off the home screen are urgent** and are drawn with no wait: a photo
  flagged sensitive, archived, put in a protected folder, a restored backup, and the end of a
  recognition pass (which can mark identity documents).
- A write while a redraw is running makes it draw again when it finishes (at once if urgent), since
  it may have read the stores before the write.

Called from: `FavoriteStore` and `SensitiveStore` on every write; `LibraryStore` when collections or
archive state change (tags, captions and curation do not affect the widgets, so a bulk tag pass
never redraws them); `PrivateFolderStore` on protect and unprotect (unlocking needs no redraw:
widgets treat every protected folder as locked); `LibraryRuntime` when a scan completes; the end of
the in-app recognition pass. `BackgroundWorkJobService` draws the widgets itself before it calls
`jobFinished`, while its own job still keeps the process alive (bounded to 30 s, after which the
redraw job takes over).

Time: while "A favourite" or "On this day" is placed, `WidgetDayJob` (id 20 931) keeps one one-off
job armed for just after local midnight (no deadline: with no other constraint it would not make the
job run sooner). `OnThisDayWidgetProvider` also hears `TIME_SET`, `TIMEZONE_CHANGED` and
`LOCALE_CHANGED` and redraws everything. Launchers also ask for updates after a reboot, an app update
and when a widget is placed; such an update re-arms the midnight job before it reads anything.

A launcher's update request has 8 s. The broadcast is finished on time whatever the drawing is doing
(the time limit runs beside the work, so a decoder or `MediaMetadataRetriever` stuck in a blocking
call cannot hold it open). An update that ran out of time shows "Couldn't load" only on widgets
nothing has drawn since it arrived (it may simply have waited behind another redraw), and the redraw
is retried in the job, which a broadcast's time limit does not bound.

**Stale data is labelled, not hidden.** Only the app's own screens start a library scan (its
MediaStore observer lives with them); a process started for a widget, the midnight job or the
background pass never scans. So "As of" prints when Fotoz last finished reading the library
(`LibraryReadStamp`: the wall-clock time of the last completed scan, kept in one small file under
`noBackupFilesDir`), with the date when it was not today, and "As of: unknown" when no read has
been recorded yet (the first run after updating to this version). Recent's small layout prints it
too, because "Latest" is only true as of that read. On this day prints the date it is showing.
Indexing's status line ("● Indexing now", "◐ Held back, as of …: <the rule's own reason>",
"○ Scheduled …") is in-memory state of the process that drew it, so in a fresh process it is simply
left out rather than guessed. Every state is a shape and a word, never a colour.

A widget update in a process that is not running yet constructs `LibraryRuntime`, which (exactly as
when the background job wakes the process) runs its once-per-process catalogue migration check and
MediaStore sync in the background.

## States, in the app's own words

| State | When | Says |
|---|---|---|
| first run | the launcher's `initialLayout`, until the first update lands | "Fotoz · Loading…" |
| no access | no photo/video permission (full or selected-photos) | "Photos not available · Open Fotoz and allow access to your photos and videos." |
| empty / withheld | nothing of this kind may be shown | per widget, e.g. "No favourites to show · Star photos in Fotoz and one appears here each day. Archived, sensitive and protected photos never appear on the home screen." |
| error | reading or drawing failed, or ran out of time | "Couldn't load · Fotoz couldn't read your library just now. Tap to open the app." |

Every state opens the app on tap.

## Look

The app's default Violet preset (`ui/ThemeJson.kt`) in its light and dark forms, following the
system's day/night setting. The widgets do not follow an in-app accent change or a custom theme: the
launcher draws them, and dynamic colour never touches a semantic role. Root views use
`@android:id/background`, the system widget corner radius from API 31 (16 dp below), 12 dp padding,
14 sp body and 12 sp secondary text, `maxLines` and ellipsis on every line, `start`/`end` padding.
Previews: `previewLayout` (API 31+, sample content, no data) and a drawn `previewImage` below 31.

## Nothing new

No new dependency, permission, network access, scheduling library or telemetry; no change to either
flavour's gates. Six providers are exported, as every App Widget provider must be; the job service
(`WidgetJobService`) is not. Strings are English only, like the rest of the app (no other locales exist yet).

## Tests

- `WidgetPrivacyTest`: pins `unlockedFolders = emptySet()` and `hideSensitive = true`; every builder
  draws only from the filtered list; identity documents and screenshots are never drawn or opened
  but are counted; taps re-check and fail closed.
- `WidgetContentTest`: each widget's content, states, date logic (time zone, 29 February, DST at
  midnight), counts (Library's collection count follows the Collections widget's rule), collection
  rows by id and date span, percent rounding, status mapping, the "As of" date-or-time rule.
- `WidgetSizingTest`: layout per cell size for each widget (Indexing keeps its small layout at a
  4x1), the two API paths agreeing at 1x, 1.3x and 2x font, minimums growing with the font and
  ordered small to large at every font size, the bitmap budget, request-code uniqueness.
- `RefreshCoalescerTest` and `WidgetRefreshJobTest`: the redraw rules (a burst shares one redraw and
  never postpones it, urgent changes go at once, a write during a redraw draws again, nothing is
  scheduled over a running job), and that the job is real and schedulable on API 26 (which refuses
  a job with no constraint) and is not scheduled when no widget is placed.
- `WidgetRenderTest` (Robolectric): inflates every layout the way a launcher does, in day, night and
  at 1.3x font, and draws them to PNG; draws every layout at exactly its minimum size at every font
  step from 1x to 2x (API 31, linear font scaling), with the fixture library and a 21,486-item one,
  and fails on clipping, cut text or squeezed pictures (`WidgetLayoutFit`);
  checks the words (no collection name in text or TalkBack descriptions, the "As of" read time with
  its date), the tap intents (explicit, immutable, ids only, no hidden ids), the API 26
  landscape/portrait path, and the placeholder redraw.

## Owner-verify (needs the phone)

1. Each widget appears in the picker of Pixel Launcher, One UI, Nova and Orrery Home with its name,
   description and preview, and can be placed.
2. Resizing switches small / medium / large at sensible sizes on each launcher; nothing is clipped at
   the owner's font size and display size.
3. Photos are upright (photos and video frames), sharp enough, and the corners are rounded on Android
   12+ (rounded clipping is not visible in the JVM renders).
4. Taps: a picture opens the viewer on it and swipes through the widget's run; the rest opens the app
   as the launcher icon does. Tapping a photo that was flagged sensitive since the last redraw opens
   nothing.
5. Flag a photo sensitive, archive it, protect its folder: it leaves every widget within a couple of
   seconds, also when the app is swiped away from Recents straight after. Trash a photo: it leaves
   once the app has re-read the library.
6. "A favourite" and "On this day" change just after midnight, and after changing the time zone or
   the date by hand.
7. TalkBack reads each widget sensibly ("Photo taken …", the counts, "Collection of photos from Oct
   2026, 4 items"), and never a collection name.
8. Day and night follow the system setting; text is readable on both.
9. With the app force-stopped or kept from running by the phone's battery manager, the widgets keep
   their last content and its "As of" read time (Android 15+ may grey a force-stopped app's widgets
   until it is next opened). A battery manager that holds back JobScheduler (RedMagic and other OEM
   managers) also delays the redraws; note how long.
10. A large library (tens of thousands of items): the first redraw after a reboot finishes and does
    not show "Couldn't load".
11. At the owner's own font size and at the largest, each widget picks a layout that fits; Indexing
    at 4x1 shows its percentage, not two cut-off sections. Resize after changing the font size.
12. A photo of an ID card or passport, once recognition has run, never appears in Recent, On this
    day, A favourite or a Collections cover; screenshots never do.
