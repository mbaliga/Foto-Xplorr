# images/

Sizes and check commands: `Personal-Tracker/store/ASSET_SPECS.md`.

## Needed, none present yet

- `icon.png` 512x512 no alpha · `featureGraphic.png` 1024x500 no alpha
- `phoneScreenshots/` — 2 to 8, 1080x1920, no alpha.
- `tenInchScreenshots/` — 1600x2560. A gallery is a large-screen app; the two-to-
  seven-column grid is the tablet argument. Do not skip these.

## Shoot these

1. The timeline grid, dense enough to show the column control matters.
2. The similarity map.
3. The 3D photo wall.
4. The full-screen viewer with metadata showing.

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
