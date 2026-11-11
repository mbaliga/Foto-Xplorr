# images/

Sizes and check commands: `Personal-Tracker/store/ASSET_SPECS.md`.

## Needed, none present yet

- `icon.png` 512x512 no alpha · `featureGraphic.png` 1024x500 no alpha
- `phoneScreenshots/` — 2 to 8, 1080x1920, no alpha.
- `tenInchScreenshots/` — 1600x2560. A gallery is a large-screen app; the
  adjustable grid and the editor's tool rail are both a tablet argument.

## Shoot these

1. The timeline grid, dense enough to show the column control matters.
2. The photo editor mid-edit (curves or HSL open), with a real adjustment visible.
3. The video editor's trim/export screen.
4. The full-screen viewer with EXIF/XMP details showing (pull the photo down).
5. The actions room pulled up a short way, so the quick-action strip shows over
   the photo, or the destination rail open.
6. A transparent sticker in the viewer with the background choice visible.
7. The similarity map or offline compass, if screenshotting the `connect` build.

The listing is for **Fotoz**. There is no Audio destination any more, so none of
the screenshots should show one; if an old capture does, retake it.

## Use a prepared library, not your own

Every frame of this app is full of real photographs. Build a folder of images you
are happy to publish forever, and shoot against that.

Specifically, do not screenshot: the sensitive-media smart album with real content,
the map with your actual home coordinates plotted, or any face grouping showing
people who have not agreed to appear in a public store listing.

```sh
adb exec-out screencap -p > shot.png
magick shot.png -background black -alpha remove -alpha off phoneScreenshots/01.png
```
