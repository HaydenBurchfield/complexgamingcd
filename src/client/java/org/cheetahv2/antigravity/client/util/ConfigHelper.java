package org.cheetahv2.antigravity.client.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigHelper {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static <T> void save(Path file, T config) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(config));
        } catch (IOException e) {
            System.err.println("[CGC] Failed to save config to " + file + ": " + e.getMessage());
        }
    }

    public static <T> void save(Path file, T config, Type type) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(config, type));
        } catch (IOException e) {
            System.err.println("[CGC] Failed to save config to " + file + ": " + e.getMessage());
        }
    }

    public static <T> T load(Path file, Class<T> clazz) {
        if (!Files.exists(file)) return null;
        try {
            return GSON.fromJson(Files.readString(file), clazz);
        } catch (IOException e) {
            System.err.println("[CGC] Failed to load config from " + file + ": " + e.getMessage());
            return null;
        }
    }

    public static <T> T load(Path file, Type type) {
        if (!Files.exists(file)) return null;
        try {
            return GSON.fromJson(Files.readString(file), type);
        } catch (IOException e) {
            System.err.println("[CGC] Failed to load config from " + file + ": " + e.getMessage());
            return null;
        }
    }
}
