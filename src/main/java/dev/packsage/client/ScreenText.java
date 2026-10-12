package dev.packsage.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Player-facing text helpers for the assistant screen; display only, never changes stored or sent text. */
final class ScreenText {
    static final int CODE_COLOR = 0xffd27f;
    private static final String BULLET = "• ";
    private static final String SEPARATOR = " · ";
    private static final Pattern HEADING = Pattern.compile("^(#{1,6}) +(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)[-*] +(.*)$");
    private static final Pattern COORDINATE = Pattern.compile("([xyz])=(-?\\d+)");
    private static final Pattern RECIPE_TAIL = Pattern.compile("^recipe-(.+?)-\\d+$");
    private static final Pattern HASH_SUFFIX = Pattern.compile("#\\d+$");

    private ScreenText() {
    }

    static List<Component> markdownLines(String text) {
        List<Component> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                lines.add(inline(heading.group(2)).withStyle(style -> style.withBold(true)));
                continue;
            }
            Matcher item = LIST_ITEM.matcher(line);
            if (item.matches()) {
                lines.add(Component.literal(item.group(1) + BULLET).append(inline(item.group(2))));
                continue;
            }
            lines.add(inline(line));
        }
        return lines;
    }

    private static MutableComponent inline(String text) {
        MutableComponent result = Component.empty();
        StringBuilder plain = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("**", index)) {
                int close = text.indexOf("**", index + 2);
                if (close > index + 2) {
                    flush(result, plain);
                    result.append(inline(text.substring(index + 2, close)).withStyle(style -> style.withBold(true)));
                    index = close + 2;
                    continue;
                }
            } else if (text.charAt(index) == '`') {
                int close = text.indexOf('`', index + 1);
                if (close > index + 1) {
                    flush(result, plain);
                    result.append(Component.literal(text.substring(index + 1, close))
                            .withStyle(style -> style.withColor(CODE_COLOR)));
                    index = close + 1;
                    continue;
                }
            }
            plain.append(text.charAt(index++));
        }
        flush(result, plain);
        return result;
    }

    private static void flush(MutableComponent result, StringBuilder plain) {
        if (plain.length() == 0) return;
        result.append(Component.literal(plain.toString()));
        plain.setLength(0);
    }

    static String contextSummary(ChatContracts.Context context) {
        List<String> parts = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(context.serializedGameplayContext());
            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                String dimension = string(object, "dimension");
                if (dimension != null) parts.add(withoutNamespace(dimension));
                String position = string(object, "position");
                if (position != null) {
                    String coordinates = coordinates(position);
                    if (coordinates != null) parts.add(coordinates);
                }
                String biome = string(object, "biome");
                if (biome != null) parts.add(withoutNamespace(biome));
            }
        } catch (RuntimeException ignored) {
            // unparseable context degrades to the generic line
        }
        boolean screenshot = context.screenshot() != null && !context.screenshot().assetId().equals("none");
        if (parts.isEmpty() && !screenshot) return "Context attached";
        parts.add(screenshot ? "screenshot attached" : "no screenshot");
        return "Context: " + String.join(SEPARATOR, parts);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return null;
        String text = value.getAsString();
        return text.isBlank() ? null : text;
    }

    private static String withoutNamespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static String coordinates(String position) {
        String x = null;
        String y = null;
        String z = null;
        Matcher matcher = COORDINATE.matcher(position);
        while (matcher.find()) {
            switch (matcher.group(1)) {
                case "x" -> x = matcher.group(2);
                case "y" -> y = matcher.group(2);
                default -> z = matcher.group(2);
            }
        }
        return x == null || y == null || z == null ? null : "x" + x + " y" + y + " z" + z;
    }

    /** Returns the visible outcome line, or null when the turn completed normally. */
    static String outcomeLine(ChatContracts.Outcome outcome) {
        if (outcome.status() == ChatContracts.OutcomeStatus.COMPLETED) return null;
        return "Outcome: " + outcome.status() + (outcome.detail() == null ? "" : " (" + outcome.detail() + ")");
    }

    static String navigationLabel(Evidence.NavigationTarget target) {
        String id = target.targetId();
        String tail = id.substring(id.lastIndexOf('/') + 1);
        Matcher recipe = RECIPE_TAIL.matcher(tail);
        if (target.providerId().equals("jei") && recipe.matches()) {
            return "Open recipe: " + displayName(recipe.group(1));
        }
        String readable = HASH_SUFFIX.matcher(tail.isEmpty() ? id : tail).replaceFirst("");
        return "Open " + target.providerId() + ": " + readable;
    }

    private static String displayName(String resourceId) {
        String path = withoutNamespace(resourceId);
        StringBuilder name = new StringBuilder();
        for (String word : path.split("[_/]+")) {
            if (word.isEmpty()) continue;
            if (name.length() > 0) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.length() == 0 ? resourceId : name.toString();
    }
}
