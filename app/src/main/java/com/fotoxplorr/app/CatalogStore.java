package com.fotoxplorr.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CatalogStore {
    private static final String PREFS = "catalog";
    private final SharedPreferences prefs;

    public CatalogStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isFavorite(long id) {
        return prefs.getBoolean("favorite_" + id, false);
    }

    public void setFavorite(long id, boolean value) {
        prefs.edit().putBoolean("favorite_" + id, value).apply();
    }

    public Set<String> tags(long id) {
        return new HashSet<>(prefs.getStringSet("tags_" + id, new HashSet<>()));
    }

    public void addTag(long id, String tag) {
        if (tag == null || tag.trim().isEmpty()) return;
        Set<String> tags = tags(id);
        tags.add(tag.trim());
        prefs.edit().putStringSet("tags_" + id, tags).apply();
    }

    public List<String> collections() {
        return new ArrayList<>(prefs.getStringSet("collections", new HashSet<>()));
    }

    public void addToCollection(long id, String name) {
        if (name == null || name.trim().isEmpty()) return;
        String clean = name.trim();
        Set<String> names = new HashSet<>(prefs.getStringSet("collections", new HashSet<>()));
        names.add(clean);
        Set<String> ids = new HashSet<>(prefs.getStringSet("collection_" + clean, new HashSet<>()));
        ids.add(Long.toString(id));
        prefs.edit().putStringSet("collections", names).putStringSet("collection_" + clean, ids).apply();
    }

    public boolean isInCollection(long id, String name) {
        return prefs.getStringSet("collection_" + name, new HashSet<>()).contains(Long.toString(id));
    }

    public JSONObject exportJson() {
        JSONObject root = new JSONObject();
        try {
            JSONArray favorites = new JSONArray();
            JSONArray tags = new JSONArray();
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("favorite_") && prefs.getBoolean(key, false)) favorites.put(key.substring(9));
                if (key.startsWith("tags_")) {
                    JSONObject item = new JSONObject();
                    item.put("id", key.substring(5));
                    item.put("tags", new JSONArray(prefs.getStringSet(key, new HashSet<>())));
                    tags.put(item);
                }
            }
            root.put("schema", 1);
            root.put("favorites", favorites);
            root.put("tags", tags);
            root.put("collections", new JSONArray(collections()));
        } catch (Exception ignored) { }
        return root;
    }
}
