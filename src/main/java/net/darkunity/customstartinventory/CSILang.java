package net.darkunity.customstartinventory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves our own translation keys to plain text server-side, instead of relying on
 * Component.translatable (which only resolves on the CLIENT, using the client's own loaded
 * lang files). CSI is intentionally usable without being installed on the client at all (it
 * registers no items/blocks/network channels), so nothing sent to a player can depend on their
 * client having assets/customstartinventory/lang/*.json - otherwise they just see the raw
 * translation key instead of real text.
 *
 * Picks English or Russian per-player based on their client's own language setting
 * (ServerPlayer#clientInformation, sent during login), so the localization work isn't lost -
 * it just happens on the server instead of relying on the client to do it.
 */
public final class CSILang {
    private static final Map<String, String> EN = load("en_us.json");
    private static final Map<String, String> RU = load("ru_ru.json");

    private CSILang() {
    }

    private static Map<String, String> load(String fileName) {
        String path = "/assets/customstartinventory/lang/" + fileName;
        try (Reader reader = new InputStreamReader(CSILang.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            return new Gson().fromJson(reader, type);
        } catch (Exception e) {
            System.out.println("[CSI] Failed to load " + fileName + ": " + e.getMessage());
            return Map.of();
        }
    }

    private static String template(String key, String language) {
        Map<String, String> table = language != null && language.toLowerCase(Locale.ROOT).startsWith("ru") ? RU : EN;
        String value = table.get(key);
        if (value == null) {
            value = EN.get(key);
        }
        return value != null ? value : key;
    }

    /** Plain resolved string (no Component wrapping) - useful when building up a larger message. */
    public static String getString(ServerPlayer player, String key) {
        String language = player != null ? player.clientInformation().language() : null;
        return template(key, language);
    }

    public static Component get(ServerPlayer player, String key, Object... args) {
        String template = getString(player, key);
        if (args.length == 0) {
            return Component.literal(template);
        }
        Object[] resolvedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            resolvedArgs[i] = args[i] instanceof Component component ? component.getString() : args[i];
        }
        return Component.literal(String.format(template, resolvedArgs));
    }

    public static Component get(CommandSourceStack source, String key, Object... args) {
        return get(source.getPlayer(), key, args);
    }
}
