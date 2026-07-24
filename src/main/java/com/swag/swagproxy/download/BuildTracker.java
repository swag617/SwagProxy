package com.swag.swagproxy.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.swag.swagproxy.util.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks, per component ("velocity", "geyser", "floodgate"), which build is
 * currently live and which build ids have been marked bad by a rollback —
 * mirrors the AutoUpdateGeyser approach of tracking last-applied builds so
 * unchanged builds are never re-downloaded (§4.2).
 */
public final class BuildTracker {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, ComponentBuildState>>() {
    }.getType();

    private final Path file;
    private final Map<String, ComponentBuildState> state;

    private BuildTracker(Path file, Map<String, ComponentBuildState> state) {
        this.file = file;
        this.state = state;
    }

    public static BuildTracker load(Path buildsJsonFile) {
        Map<String, ComponentBuildState> state = new LinkedHashMap<>();
        if (Files.exists(buildsJsonFile)) {
            try {
                String json = Files.readString(buildsJsonFile);
                Map<String, ComponentBuildState> parsed = GSON.fromJson(json, MAP_TYPE);
                if (parsed != null) {
                    state = parsed;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + buildsJsonFile, e);
            } catch (RuntimeException e) {
                Log.warn("builds.json was unreadable/corrupt (" + e.getMessage() + ") — starting fresh.");
            }
        }
        return new BuildTracker(buildsJsonFile, state);
    }

    public ComponentBuildState of(String component) {
        return state.computeIfAbsent(component, k -> new ComponentBuildState());
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "builds-", ".json.tmp");
            Files.writeString(temp, GSON.toJson(state, MAP_TYPE));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.error("Could not save builds.json", e);
        }
    }
}
