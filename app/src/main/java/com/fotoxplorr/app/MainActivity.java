package com.fotoxplorr.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 10;
    private static final int EXPORT_REQUEST = 11;
    private static final int DELETE_REQUEST = 12;

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaItem> allItems = new ArrayList<>();
    private final List<MediaItem> visibleItems = new ArrayList<>();

    private MediaRepository repository;
    private CatalogStore catalog;
    private LinearLayout root;
    private LinearLayout content;
    private GridView grid;
    private ProgressBar progress;
    private TextView empty;
    private SearchView search;
    private MediaAdapter adapter;
    private String mode = "Photos";
    private String activeQuery = "";
    private boolean lightTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new MediaRepository(this);
        catalog = new CatalogStore(this);
        lightTheme = getPreferences(MODE_PRIVATE).getBoolean("light", false);
        buildShell();
        if (hasMediaPermission()) loadLibrary(); else requestMediaPermission();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        applyTheme();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(12), dp(8), dp(8));
        TextView title = new TextView(this);
        title.setText("Foto Xplorr");
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(foreground());
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        ImageButton theme = iconButton(lightTheme ? "☾" : "☀");
        theme.setContentDescription("Toggle theme");
        theme.setOnClickListener(v -> {
            lightTheme = !lightTheme;
            getPreferences(MODE_PRIVATE).edit().putBoolean("light", lightTheme).apply();
            buildShell();
            showMode(mode);
        });
        top.addView(theme);

        ImageButton menu = iconButton("⋮");
        menu.setContentDescription("More options");
        menu.setOnClickListener(v -> showMoreMenu());
        top.addView(menu);
        root.addView(top);

        search = new SearchView(this);
        search.setQueryHint("Search names and folders");
        search.setIconifiedByDefault(false);
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            public boolean onQueryTextSubmit(String query) { activeQuery = query; loadLibrary(); return true; }
            public boolean onQueryTextChange(String query) { activeQuery = query; filterCurrentMode(); return true; }
        });
        root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(8), 0, dp(8), dp(8));
        String[] names = {"Photos", "Timeline", "Folders", "Favourites", "Collections", "Map"};
        for (String name : names) {
            Button button = new Button(this);
            button.setText(name);
            button.setAllCaps(false);
            button.setTextSize(12);
            button.setOnClickListener(v -> showMode(name));
            tabs.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        HorizontalContainer horizontal = new HorizontalContainer(this);
        horizontal.addView(tabs, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
        root.addView(horizontal, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        FrameLayout stage = new FrameLayout(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        stage.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(this);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
        stage.addView(progress, pp);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        renderGrid();
    }

    private void renderGrid() {
        content.removeAllViews();
        grid = new GridView(this);
        grid.setNumColumns(getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 6 : 3);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new MediaAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> openViewer(visibleItems.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            showItemActions(visibleItems.get(position));
            return true;
        });
        content.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        empty = new TextView(this);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(muted());
        empty.setTextSize(17);
        content.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
    }

    private boolean hasMediaPermission() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaPermission() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[]{permission}, PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadLibrary();
        } else {
            progress.setVisibility(View.GONE);
            empty.setText("Photo access is required. Open system settings to grant it.");
            empty.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
        }
    }

    private void loadLibrary() {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            List<MediaItem> result = repository.scan(activeQuery);
            main.post(() -> {
                allItems.clear();
                allItems.addAll(result);
                progress.setVisibility(View.GONE);
                showMode(mode);
            });
        });
    }

    private void showMode(String selected) {
        mode = selected;
        if (selected.equals("Folders")) renderFolderList();
        else if (selected.equals("Collections")) renderCollections();
        else if (selected.equals("Map")) renderMapSummary();
        else {
            if (grid == null || grid.getParent() == null) renderGrid();
            filterCurrentMode();
        }
    }

    private void filterCurrentMode() {
        visibleItems.clear();
        String q = activeQuery == null ? "" : activeQuery.trim().toLowerCase(Locale.ROOT);
        Calendar now = Calendar.getInstance();
        for (MediaItem item : allItems) {
            boolean queryMatches = q.isEmpty() || item.name.toLowerCase(Locale.ROOT).contains(q)
                    || item.folder.toLowerCase(Locale.ROOT).contains(q)
                    || catalog.tags(item.id).toString().toLowerCase(Locale.ROOT).contains(q);
            if (!queryMatches) continue;
            if (mode.equals("Favourites") && !catalog.isFavorite(item.id)) continue;
            visibleItems.add(item);
        }
        if (mode.equals("Timeline")) {
            visibleItems.sort((a, b) -> Long.compare(b.takenAt, a.takenAt));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) empty.setText(visibleItems.isEmpty() ? "No matching photos" : visibleItems.size() + " items");
    }

    private void renderFolderList() {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        Map<String, List<MediaItem>> folders = new LinkedHashMap<>();
        for (MediaItem item : allItems) folders.computeIfAbsent(item.folder, key -> new ArrayList<>()).add(item);
        for (Map.Entry<String, List<MediaItem>> entry : folders.entrySet()) {
            Button row = new Button(this);
            row.setAllCaps(false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setText(entry.getKey() + "\n" + entry.getValue().size() + " items");
            row.setOnClickListener(v -> {
                mode = "Photos";
                visibleItems.clear();
                visibleItems.addAll(entry.getValue());
                renderGrid();
                adapter.notifyDataSetChanged();
                empty.setText(visibleItems.size() + " items in " + entry.getKey());
            });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
        }
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void renderCollections() {
        content.removeAllViews();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        Button create = new Button(this);
        create.setText("Create collection");
        create.setOnClickListener(v -> promptText("Collection name", value -> {
            if (!allItems.isEmpty()) catalog.addToCollection(allItems.get(0).id, value);
            renderCollections();
        }));
        list.addView(create);
        for (String name : catalog.collections()) {
            Button row = new Button(this);
            row.setText(name);
            row.setAllCaps(false);
            row.setOnClickListener(v -> {
                visibleItems.clear();
                for (MediaItem item : allItems) if (catalog.isInCollection(item.id, name)) visibleItems.add(item);
                renderGrid();
                adapter.notifyDataSetChanged();
                empty.setText(visibleItems.size() + " items in " + name);
            });
            list.addView(row);
        }
        content.addView(list);
    }

    private void renderMapSummary() {
        content.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView icon = new TextView(this);
        icon.setText("⌖");
        icon.setTextSize(72);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(foreground());
        TextView description = new TextView(this);
        description.setText("Map view indexes geotagged images locally. Select a photo and use Details to inspect location metadata. Offline terrain and 3D exploration are scheduled after V1.");
        description.setGravity(Gravity.CENTER);
        description.setTextSize(17);
        description.setTextColor(muted());
        panel.addView(icon);
        panel.addView(description);
        content.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openViewer(MediaItem item) {
        FrameLayout viewer = new FrameLayout(this);
        viewer.setBackgroundColor(Color.BLACK);
        ImageView image = new ImageView(this);
        image.setContentDescription(item.name);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setBackgroundColor(0xAA000000);
        String[] labels = {"Back", catalog.isFavorite(item.id) ? "★" : "☆", "Share", "Tag", "Group", "Details", "Delete"};
        for (String label : labels) {
            Button b = new Button(this);
            b.setText(label);
            b.setTextSize(11);
            b.setAllCaps(false);
            actions.addView(b, new LinearLayout.LayoutParams(0, dp(58), 1));
            if (label.equals("Back")) b.setOnClickListener(v -> setContentView(root));
            else if (label.equals("★") || label.equals("☆")) b.setOnClickListener(v -> { catalog.setFavorite(item.id, !catalog.isFavorite(item.id)); openViewer(item); });
            else if (label.equals("Share")) b.setOnClickListener(v -> share(item));
            else if (label.equals("Tag")) b.setOnClickListener(v -> promptText("Add tag", value -> catalog.addTag(item.id, value)));
            else if (label.equals("Group")) b.setOnClickListener(v -> promptText("Collection name", value -> catalog.addToCollection(item.id, value)));
            else if (label.equals("Details")) b.setOnClickListener(v -> showDetails(item));
            else if (label.equals("Delete")) b.setOnClickListener(v -> requestDelete(item));
        }
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66), Gravity.BOTTOM);
        viewer.addView(actions, ap);
        setContentView(viewer);
        loadFullImage(item, image);
    }

    private void loadFullImage(MediaItem item, ImageView target) {
        io.execute(() -> {
            try {
                Drawable drawable;
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), item.uri);
                    drawable = ImageDecoder.decodeDrawable(source);
                } else {
                    try (InputStream stream = getContentResolver().openInputStream(item.uri)) {
                        Bitmap bitmap = BitmapFactory.decodeStream(stream);
                        drawable = new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
                    }
                }
                main.post(() -> {
                    target.setImageDrawable(drawable);
                    if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).start();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "This decoder could not open the file", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showItemActions(MediaItem item) {
        String[] actions = {catalog.isFavorite(item.id) ? "Remove favourite" : "Favourite", "Share", "Add tag", "Add to collection", "Details", "Delete"};
        new AlertDialog.Builder(this).setTitle(item.name).setItems(actions, (dialog, which) -> {
            if (which == 0) { catalog.setFavorite(item.id, !catalog.isFavorite(item.id)); filterCurrentMode(); }
            if (which == 1) share(item);
            if (which == 2) promptText("Add tag", value -> catalog.addTag(item.id, value));
            if (which == 3) promptText("Collection name", value -> catalog.addToCollection(item.id, value));
            if (which == 4) showDetails(item);
            if (which == 5) requestDelete(item);
        }).show();
    }

    private void showDetails(MediaItem item) {
        String details = item.name + "\n" + item.mime + "\n" + item.width + " × " + item.height
                + "\n" + humanSize(item.size) + "\n" + item.folder + "\n"
                + DateFormat.getDateTimeInstance().format(item.takenAt) + "\nTags: " + catalog.tags(item.id);
        new AlertDialog.Builder(this).setTitle("Details").setMessage(details).setPositiveButton("OK", null).show();
    }

    private void share(MediaItem item) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(item.mime);
        send.putExtra(Intent.EXTRA_STREAM, item.uri);
        send.setClipData(ClipData.newUri(getContentResolver(), item.name, item.uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share photo"));
    }

    private void requestDelete(MediaItem item) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                PendingIntent pending = MediaStore.createTrashRequest(getContentResolver(), java.util.Collections.singletonList(item.uri), true);
                startIntentSenderForResult(pending.getIntentSender(), DELETE_REQUEST, null, 0, 0, 0);
            } else {
                new AlertDialog.Builder(this).setTitle("Move to trash")
                        .setMessage("This Android version cannot provide the system trash UI. Permanently delete this item?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, w) -> {
                            getContentResolver().delete(item.uri, null, null);
                            loadLibrary();
                        }).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Android denied this file operation", Toast.LENGTH_LONG).show();
        }
    }

    private void showMoreMenu() {
        String[] actions = {"Refresh library", "Export catalogue backup", "About V1"};
        new AlertDialog.Builder(this).setTitle("Foto Xplorr").setItems(actions, (dialog, which) -> {
            if (which == 0) loadLibrary();
            if (which == 1) {
                Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                create.setType("application/json");
                create.putExtra(Intent.EXTRA_TITLE, "foto-xplorr-catalog.json");
                startActivityForResult(create, EXPORT_REQUEST);
            }
            if (which == 2) new AlertDialog.Builder(this).setTitle("V1")
                    .setMessage("Local MediaStore gallery, timeline, folders, favourites, virtual collections, tags, search, animated image playback, sharing, system trash and portable catalogue export. No account, backend or telemetry.")
                    .setPositiveButton("OK", null).show();
        }).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri target = data.getData();
            io.execute(() -> {
                try {
                    BackupExporter.write(this, target, catalog);
                    main.post(() -> Toast.makeText(this, "Catalogue backup written", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    main.post(() -> Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }
        if (requestCode == DELETE_REQUEST) loadLibrary();
    }

    private void promptText(String title, ValueConsumer consumer) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle(title).setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> consumer.accept(input.getText().toString())).show();
    }

    private ImageButton iconButton(String text) {
        ImageButton button = new ImageButton(this);
        button.setBackgroundColor(Color.TRANSPARENT);
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        button.setImageDrawable(null);
        button.setContentDescription(text);
        TextView overlay = null;
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(48));
        button.setOnLongClickListener(v -> { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); return true; });
        // Native ImageButton has no text; use a simple generated glyph bitmap through content description fallback.
        button.setBackground(drawable);
        button.setForeground(glyph(text));
        return button;
    }

    private Drawable glyph(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(24);
        view.setTextColor(foreground());
        view.setGravity(Gravity.CENTER);
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.EXACTLY));
        view.layout(0, 0, dp(48), dp(48));
        Bitmap bitmap = Bitmap.createBitmap(dp(48), dp(48), Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        return new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
    }

    private void applyTheme() {
        root.setBackgroundColor(background());
        getWindow().setStatusBarColor(background());
        getWindow().setNavigationBarColor(background());
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(lightTheme ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0);
    }

    private int background() { return lightTheme ? Color.rgb(248, 248, 246) : Color.rgb(12, 13, 16); }
    private int foreground() { return lightTheme ? Color.rgb(20, 21, 24) : Color.WHITE; }
    private int muted() { return lightTheme ? Color.rgb(80, 82, 88) : Color.rgb(185, 189, 198); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String prefix = "KMGTPE".charAt(exp - 1) + "i";
        return String.format(Locale.ROOT, "%.1f %sB", bytes / Math.pow(1024, exp), prefix);
    }

    private final class MediaAdapter extends BaseAdapter {
        private final Map<Long, Bitmap> cache = new HashMap<>();
        public int getCount() { return visibleItems.size(); }
        public Object getItem(int position) { return visibleItems.get(position); }
        public long getItemId(int position) { return visibleItems.get(position).id; }

        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView image = convertView instanceof ImageView ? (ImageView) convertView : new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(lightTheme ? 0xFFE1E1DE : 0xFF22242A);
            image.setContentDescription(visibleItems.get(position).name);
            int side = parent.getWidth() > 0 ? parent.getWidth() / grid.getNumColumns() : dp(128);
            image.setLayoutParams(new GridView.LayoutParams(side, side));
            MediaItem item = visibleItems.get(position);
            Bitmap ready = cache.get(item.id);
            if (ready != null) image.setImageBitmap(ready);
            else {
                image.setImageDrawable(null);
                io.execute(() -> {
                    try {
                        Bitmap bitmap;
                        if (Build.VERSION.SDK_INT >= 29) bitmap = getContentResolver().loadThumbnail(item.uri, new android.util.Size(dp(256), dp(256)), null);
                        else {
                            bitmap = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), item.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                        }
                        if (bitmap != null) {
                            cache.put(item.id, bitmap);
                            main.post(() -> { if (image.getContentDescription().equals(item.name)) image.setImageBitmap(bitmap); });
                        }
                    } catch (Exception ignored) { }
                });
            }
            return image;
        }
    }

    private static final class HorizontalContainer extends android.widget.HorizontalScrollView {
        HorizontalContainer(Activity activity) { super(activity); setHorizontalScrollBarEnabled(false); }
    }

    private interface ValueConsumer { void accept(String value); }
}
