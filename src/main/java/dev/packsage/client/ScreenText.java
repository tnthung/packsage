package dev.packsage.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Player-facing text helpers for the assistant screen; display only, never changes stored or sent text. */
final class ScreenText {
    static final int CODE_COLOR = 0xffd27f;
    static final int DIM_COLOR = 0x808080;
    private static final String CELL_GAP = "   ";
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(native|jei|ftb_quests|patchouli|modonomicon|guideme|immersiveengineering|actuallyadditions|relics|artifacts|ponder|config|history):([^\\]\\s]+)\\]");
    private static final String BULLET = "• ";
    private static final String SEPARATOR = " · ";
    private static final Pattern HEADING = Pattern.compile("^(#{1,6}) +(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)[-*] +(.*)$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^(\\s*> ?)");
    private static final Pattern COORDINATE = Pattern.compile("([xyz])=(-?\\d+)");
    private static final Pattern JEI_RECIPE = Pattern.compile("^jei/\\d+/\\d+/.+?/recipe-(.+)-\\d+$");
    private static final Pattern HASH_SUFFIX = Pattern.compile("#\\d+$");

    record InlineSpan(String text, Evidence.NavigationTarget target, int color, boolean bold, boolean code,
                      Evidence.Source source, String externalUrl) {
        InlineSpan(String text, Evidence.NavigationTarget target, int color, boolean bold, boolean code) {
            this(text, target, color, bold, code, null, null);
        }

        InlineSpan(String text, Evidence.NavigationTarget target, int color, boolean bold, boolean code,
                   Evidence.Source source) {
            this(text, target, color, bold, code, source, null);
        }

        InlineSpan {
            if (text == null) throw new NullPointerException("text");
        }
    }

    record InlineLine(List<InlineSpan> spans, boolean quote) {
        InlineLine(List<InlineSpan> spans) {
            this(spans, false);
        }

        InlineLine {
            spans = List.copyOf(spans);
        }
    }

    record TooltipField(String label, String value) {
    }

    private ScreenText() {
    }

    static List<Component> markdownLines(String text) {
        return markdownLines(text, List.of());
    }

    /** Renders Markdown for display; markers covered by one of the given navigation targets are omitted. */
    static List<Component> markdownLines(String text, List<Evidence.NavigationTarget> targets) {
        Set<String> covered = new HashSet<>();
        for (Evidence.NavigationTarget target : targets) covered.add(target.providerId() + ":" + target.sourceHandle());
        List<Component> lines = new ArrayList<>();
        String[] rawLines = text.split("\r?\n", -1);
        boolean inCode = false;
        for (int index = 0; index < rawLines.length; index++) {
            String line = rawLines[index];
            if (line.trim().startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                lines.add(Component.literal(line).withStyle(style -> style.withColor(CODE_COLOR)));
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                lines.add(inline(heading.group(2), covered).withStyle(style -> style.withBold(true)));
                continue;
            }
            Matcher item = LIST_ITEM.matcher(line);
            if (item.matches()) {
                lines.add(Component.literal(item.group(1) + BULLET).append(inline(item.group(2), covered)));
                continue;
            }
            if (line.trim().startsWith("|")) {
                if (isTableSeparator(line)) continue;
                MutableComponent row = tableRow(line, covered);
                if (index + 1 < rawLines.length && isTableSeparator(rawLines[index + 1])) row = row.withStyle(style -> style.withBold(true));
                lines.add(row);
                continue;
            }
            lines.add(inline(line, covered));
        }
        return lines;
    }

    private static boolean isTableSeparator(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.contains("-") && trimmed.matches("[|:\\s-]+");
    }

    private static MutableComponent tableRow(String line, Set<String> covered) {
        String trimmed = line.trim();
        trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        MutableComponent row = Component.empty();
        boolean first = true;
        for (String cell : trimmed.split("\\|", -1)) {
            if (!first) row.append(Component.literal(CELL_GAP));
            first = false;
            row.append(inline(cell.trim(), covered));
        }
        return row;
    }

    private static String markerTag(String provider, String handle) {
        return "[" + switch (provider) {
            case "native" -> "game";
            case "ftb_quests" -> "quest";
            case "patchouli" -> "book";
            case "modonomicon", "guideme", "actuallyadditions", "relics", "artifacts" -> "guide";
            case "immersiveengineering" -> "manual";
            case "jei" -> handle.contains("recipe") ? "recipe" : "jei";
            default -> provider; // config
        } + "]";
    }

    private static MutableComponent inline(String text, Set<String> covered) {
        MutableComponent result = Component.empty();
        StringBuilder plain = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) == '[') {
                Matcher marker = CITATION_MARKER.matcher(text).region(index, text.length());
                if (marker.lookingAt()) {
                    index = marker.end();
                    if (covered.contains(marker.group(1) + ":" + marker.group(2))) {
                        boolean gap = index >= text.length() || ".,;:!?)".indexOf(text.charAt(index)) >= 0 || text.charAt(index) == ' ';
                        if (gap && plain.length() > 0 && plain.charAt(plain.length() - 1) == ' ') plain.setLength(plain.length() - 1);
                    } else {
                        flush(result, plain);
                        result.append(Component.literal(markerTag(marker.group(1), marker.group(2)))
                                .withStyle(style -> style.withColor(DIM_COLOR)));
                    }
                    continue;
                }
            }
            if (text.startsWith("**", index)) {
                int close = text.indexOf("**", index + 2);
                if (close > index + 2) {
                    flush(result, plain);
                    result.append(inline(text.substring(index + 2, close), covered).withStyle(style -> style.withBold(true)));
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

    /** Splits answer text into display spans so a validated target can be painted at its citation position. */
    static List<InlineLine> inlineLines(String text, List<Evidence.NavigationTarget> targets) {
        return inlineLines(text, targets, List.of());
    }

    static List<InlineLine> inlineLines(String text, List<Evidence.NavigationTarget> targets,
                                        List<ModelBackend.UrlCitation> webCitations) {
        return inlineLines(text, targets, webCitations, List.of());
    }

    static List<InlineLine> inlineLines(String text, List<Evidence.NavigationTarget> targets,
                                        List<ModelBackend.UrlCitation> webCitations,
                                        List<Evidence.Source> sources) {
        List<InlineLine> lines = new ArrayList<>();
        String[] rawLines = text.split("\\r?\\n", -1);
        int absolute = 0;
        boolean inCode = false;
        for (int lineIndex = 0; lineIndex < rawLines.length; lineIndex++) {
            String rawLine = rawLines[lineIndex];
            int lineOffset = absolute;
            absolute += rawLine.length();
            if (absolute < text.length() && text.charAt(absolute) == '\r') absolute++;
            if (absolute < text.length() && text.charAt(absolute) == '\n') absolute++;
            if (rawLine.trim().startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            String line = rawLine;
            boolean quote = false;
            boolean bold = false;
            List<InlineSpan> spans = new ArrayList<>();
            if (inCode) {
                spans.add(new InlineSpan(line, null, CODE_COLOR, false, true));
            } else {
                Matcher blockquote = BLOCKQUOTE.matcher(line);
                if (blockquote.find()) {
                    quote = true;
                    lineOffset += blockquote.end();
                    line = line.substring(blockquote.end());
                }
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    line = heading.group(2);
                    lineOffset += heading.start(2);
                    bold = true;
                } else {
                    Matcher item = LIST_ITEM.matcher(line);
                    if (item.matches()) {
                        String prefix = item.group(1) + BULLET;
                        spans.add(new InlineSpan(prefix, null, TEXT_COLOR, false, false));
                        line = item.group(2);
                        lineOffset += item.start(2);
                    }
                }
                boolean table = line.trim().startsWith("|");
                if (table && isTableSeparator(line)) continue;
                if (table && lineIndex + 1 < rawLines.length && isTableSeparator(rawLines[lineIndex + 1])) bold = true;
                inlineSpans(line, lineOffset, bold, targets, webCitations, sources, spans);
                if (table) {
                    for (int index = 0; index < spans.size(); index++) {
                        InlineSpan span = spans.get(index);
                        if (span.target() == null && !span.code()) {
                            spans.set(index, new InlineSpan(span.text().replace("|", CELL_GAP), span.target(), span.color(), span.bold(), false,
                                    span.source(), span.externalUrl()));
                        }
                    }
                }
            }
            lines.add(new InlineLine(spans.isEmpty() ? List.of(new InlineSpan("", null, TEXT_COLOR, false, false)) : spans, quote));
        }
        return lines;
    }

    static List<InlineLine> inlineLines(String text, List<Evidence.NavigationTarget> targets,
                                        List<ModelBackend.UrlCitation> webCitations, ChatContracts.Turn turn) {
        return inlineLines(text, targets, webCitations, turn.sources());
    }

    private static final int TEXT_COLOR = ModernUi.TEXT;

    private static void inlineSpans(String text, int absoluteStart, boolean inheritedBold,
                                    List<Evidence.NavigationTarget> targets, List<ModelBackend.UrlCitation> citations,
                                    List<Evidence.Source> sources, List<InlineSpan> spans) {
        StringBuilder plain = new StringBuilder();
        boolean bold = inheritedBold;
        boolean strong = false;
        boolean code = false;
        int index = 0;
        while (index < text.length()) {
            ModelBackend.UrlCitation citation = null;
            for (ModelBackend.UrlCitation value : citations) {
                if (!code && value.startIndex() == absoluteStart + index && value.endIndex() > value.startIndex()) {
                    citation = value;
                    break;
                }
            }
            if (citation != null && citation.endIndex() <= absoluteStart + text.length()) {
                flushInline(spans, plain, bold, code);
                Evidence.NavigationTarget target = null;
                for (Evidence.NavigationTarget value : targets) {
                    if (value.providerId().equals("web") && value.sourceHandle().equals(citation.url())) {
                        target = value;
                        break;
                    }
                }
                if (target != null) {
                    int firstSpan = spans.size();
                    inlineSpans(text.substring(index, citation.endIndex() - absoluteStart), absoluteStart + index,
                            bold, targets, List.of(), sources, spans);
                    if (spans.subList(firstSpan, spans.size()).stream().map(InlineSpan::target).noneMatch(target::equals)) {
                        spans.add(new InlineSpan(citation.title() == null || citation.title().isBlank() ? "Source" : citation.title(),
                                target, ModernUi.TEXT, false, false));
                    }
                    index = citation.endIndex() - absoluteStart;
                    continue;
                }
            }
            if (!code && text.startsWith("\uE200cite\uE202", index)) {
                int end = text.indexOf('\uE201', index + 6);
                if (end > index) {
                    String references = text.substring(index + 6, end);
                    String[] values = references.split("\uE202", -1);
                    boolean recognized = true;
                    for (String value : values) {
                        Matcher reference = CITATION_MARKER.matcher("[" + value + "]");
                        if (!reference.matches()) {
                            recognized = false;
                            break;
                        }
                    }
                    if (recognized) {
                        flushInline(spans, plain, bold, code);
                        for (String value : values) {
                            Matcher reference = CITATION_MARKER.matcher("[" + value + "]");
                            reference.matches();
                            Evidence.NavigationTarget target = targets.stream().filter(candidate ->
                                    candidate.providerId().equals(reference.group(1))
                                            && candidate.sourceHandle().equals(reference.group(2)))
                                    .findFirst().orElse(null);
                            Evidence.Source source = sources.stream().filter(candidate ->
                                    candidate.providerId().equals(reference.group(1))
                                            && candidate.sourceHandle().equals(reference.group(2)))
                                    .reduce((first, latest) -> latest).orElse(null);
                            if (target != null) spans.add(new InlineSpan(switch (target.providerId()) {
                                case "ftb_quests" -> "Quest";
                                case "patchouli", "modonomicon", "guideme", "actuallyadditions", "relics", "artifacts" -> "Guide";
                                case "immersiveengineering" -> "Manual";
                                case "ponder" -> "Ponder";
                                case "jei" -> "Recipe";
                                case "config" -> "Config";
                                default -> "Source";
                            }, target, ModernUi.TEXT, false, false, source));
                            else {
                                spans.add(new InlineSpan(source == null ? reference.group(1).equals("native")
                                        ? displayName(reference.group(2)) : markerTag(reference.group(1), reference.group(2))
                                        : reference.group(1).equals("config") ? "Config"
                                        : source.sourceHandle().startsWith("inventory:") ? "Inventory" : displayName(reference.group(2)),
                                        null, source == null ? DIM_COLOR : ModernUi.TEXT, false, false, source));
                            }
                        }
                        index = end + 1;
                        continue;
                    }
                }
            }
            if (!code && text.charAt(index) == '[') {
                Matcher marker = CITATION_MARKER.matcher(text).region(index, text.length());
                if (marker.lookingAt()) {
                    flushInline(spans, plain, bold, code);
                    Evidence.NavigationTarget target = targets.stream().filter(value ->
                            value.providerId().equals(marker.group(1)) && value.sourceHandle().equals(marker.group(2)))
                            .findFirst().orElse(null);
                    Evidence.Source source = sources.stream().filter(value -> value.providerId().equals(marker.group(1))
                            && value.sourceHandle().equals(marker.group(2))).reduce((first, latest) -> latest).orElse(null);
                    if (target != null) spans.add(new InlineSpan(switch (target.providerId()) {
                        case "ftb_quests" -> "Quest";
                        case "patchouli", "modonomicon", "guideme", "actuallyadditions", "relics", "artifacts" -> "Guide";
                        case "immersiveengineering" -> "Manual";
                        case "ponder" -> "Ponder";
                        case "jei" -> "Recipe";
                        case "config" -> "Config";
                        default -> "Source";
                    }, target, ModernUi.TEXT, false, false, source));
                    else if (source != null) {
                        spans.add(new InlineSpan(source.providerId().equals("config") ? "Config"
                                : source.sourceHandle().startsWith("inventory:") ? "Inventory" : displayName(marker.group(2)),
                                null, ModernUi.TEXT, false, false, source));
                    } else spans.add(new InlineSpan(marker.group(1).equals("native") ? displayName(marker.group(2))
                            : markerTag(marker.group(1), marker.group(2)), null, DIM_COLOR, false, false));
                    index = marker.end();
                    continue;
                }
                int close = text.indexOf("](", index + 1);
                int closeUrl = close < 0 ? -1 : text.indexOf(')', close + 2);
                if (close > index && closeUrl > close + 2) {
                    int end = closeUrl + 1;
                    String url = text.substring(close + 2, closeUrl);
                    if (url.startsWith("jei/")) {
                        List<Evidence.Source> matchingSources = sources.stream()
                                .filter(value -> value.sourceHandle().equals(url)).toList();
                        Evidence.Source source = !matchingSources.isEmpty()
                                && matchingSources.stream().map(Evidence.Source::providerId).distinct().count() == 1
                                && matchingSources.getFirst().providerId().equals("jei")
                                ? matchingSources.getLast() : null;
                        flushInline(spans, plain, bold, code);
                        spans.add(new InlineSpan(text.substring(index + 1, close), source == null ? null : targets.stream().filter(value ->
                                value.providerId().equals("jei") && value.sourceHandle().equals(url)
                                        && value.scope().equals(source.scope())).findFirst().orElse(null), ModernUi.TEXT, false, false, source));
                        index = end;
                        continue;
                    }
                    Matcher localReference = CITATION_MARKER.matcher("[" + url + "]");
                    if (localReference.matches()) {
                        flushInline(spans, plain, bold, code);
                        Evidence.NavigationTarget target = targets.stream().filter(value ->
                                value.providerId().equals(localReference.group(1))
                                        && value.sourceHandle().equals(localReference.group(2)))
                                .findFirst().orElse(null);
                        Evidence.Source source = sources.stream().filter(value -> value.providerId().equals(localReference.group(1))
                                && value.sourceHandle().equals(localReference.group(2)))
                                .reduce((first, latest) -> latest).orElse(null);
                        if (target != null) spans.add(new InlineSpan(text.substring(index + 1, close), target, ModernUi.TEXT, false, false, source));
                        else if (source != null) spans.add(new InlineSpan(text.substring(index + 1, close), null, ModernUi.TEXT, false, false, source));
                        else spans.add(new InlineSpan(text.substring(index + 1, close), null, DIM_COLOR, false, false));
                        index = end;
                        continue;
                    }
                    Evidence.NavigationTarget target = targets.stream().filter(value ->
                            value.providerId().equals("web") && value.sourceHandle().equals(url)).findFirst().orElse(null);
                    boolean externalUrl = false;
                    if (target == null) {
                        try {
                            URI uri = new URI(url);
                            externalUrl = ("http".equalsIgnoreCase(uri.getScheme())
                                    || "https".equalsIgnoreCase(uri.getScheme()))
                                    && uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null;
                        } catch (URISyntaxException ignored) {
                        }
                    }
                    if (target != null || externalUrl) {
                        flushInline(spans, plain, bold, code);
                        spans.add(new InlineSpan(text.substring(index + 1, close), target,
                                ModernUi.TEXT, false, false, null, target == null ? url : null));
                        index = end;
                        continue;
                    }
                }
            }
            if (!code && text.startsWith("**", index)) {
                int close = text.indexOf("**", index + 2);
                if (strong || close > index + 2) {
                    flushInline(spans, plain, bold, code);
                    strong = !strong;
                    bold = inheritedBold || strong;
                    index += 2;
                    continue;
                }
            } else if (text.charAt(index) == '`') {
                int close = text.indexOf('`', index + 1);
                if (code || close > index + 1) {
                    flushInline(spans, plain, bold, code);
                    code = !code;
                    index++;
                    continue;
                }
            }
            plain.append(text.charAt(index++));
        }
        flushInline(spans, plain, bold, code);
    }

    private static void flushInline(List<InlineSpan> spans, StringBuilder plain, boolean bold, boolean code) {
        if (plain.length() == 0) return;
        spans.add(new InlineSpan(plain.toString(), null, code ? CODE_COLOR : TEXT_COLOR, bold, code));
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

    static List<TooltipField> gameplayContextFields(ChatContracts.Context context) {
        JsonObject object = null;
        try {
            JsonElement parsed = JsonParser.parseString(context.serializedGameplayContext());
            if (parsed.isJsonObject()) object = parsed.getAsJsonObject();
        } catch (RuntimeException ignored) {
            // malformed gameplay context is shown as unknown values
        }
        List<TooltipField> fields = new ArrayList<>();
        String position = object == null ? null : string(object, "position");
        String positionText = position == null ? null : coordinates(position);
        fields.add(new TooltipField("Coordinates", positionText == null ? "Unknown" : positionText));
        String dimension = object == null ? null : string(object, "dimension");
        String dimensionName = dimension == null || dimension.equals("unavailable") ? "Unknown"
                : switch (withoutNamespace(dimension)) {
                    case "overworld" -> "Overworld";
                    case "the_nether" -> "Nether";
                    case "the_end" -> "The End";
                    default -> displayName(dimension);
                };
        fields.add(new TooltipField("Dimension", dimensionName));
        String biome = object == null ? null : string(object, "biome");
        fields.add(new TooltipField("Biome", biome == null || biome.equals("unavailable") ? "Unknown" : displayName(biome)));

        String held = object == null ? null : string(object, "held");
        String heldValue = object == null ? "Unknown" : "None";
        if (held != null && !held.equals("unavailable")) {
            heldValue = "Unknown";
            if (held.startsWith("item:")) {
                int countStart = held.indexOf(";count=");
                int nameStart = held.indexOf(";name=");
                String itemId = held.substring("item:".length(), held.indexOf(';') < 0 ? held.length() : held.indexOf(';'));
                int nameEnd = nameStart < 0 ? -1 : held.indexOf(';', nameStart + 6);
                String itemName = nameStart < 0 ? null : held.substring(nameStart + 6,
                        nameEnd < 0 ? held.length() : nameEnd);
                String itemCount = countStart < 0 ? null : held.substring(countStart + 7,
                        held.indexOf(';', countStart + 7) < 0 ? held.length() : held.indexOf(';', countStart + 7));
                if (itemName != null && !itemName.isBlank()) {
                    try {
                        heldValue = itemName + " ×" + NumberFormat.getIntegerInstance().format(Integer.parseInt(itemCount));
                    } catch (NumberFormatException | NullPointerException ignored) {
                        heldValue = itemName;
                    }
                } else if (!itemId.isBlank() && !itemId.endsWith(":")) {
                    heldValue = displayName(itemId);
                }
            }
        }
        fields.add(new TooltipField("Held item", heldValue));

        String focusKind = object == null ? null : string(object, "focusKind");
        String targeted = object == null ? null : string(object, "targeted");
        String targetValue = "Unknown";
        String targetCoordinates = null;
        if ("miss".equals(focusKind)) targetValue = "None";
        else if ("block".equals(focusKind) || "entity".equals(focusKind)) {
            String prefix = focusKind + ":";
            if (targeted != null && targeted.startsWith(prefix)) {
                String target = targeted.substring(prefix.length());
                int detailsStart = target.indexOf('@');
                String targetId = detailsStart < 0 ? target : target.substring(0, detailsStart);
                if (!targetId.isBlank() && !targetId.endsWith(":")) targetValue = displayName(targetId);
                if (focusKind.equals("block") && detailsStart >= 0) {
                    targetCoordinates = coordinates(target.substring(detailsStart + 1));
                }
            }
        } else if ("unavailable".equals(focusKind)) targetValue = "Unknown";
        fields.add(new TooltipField("Looking at", targetValue));
        if (targetCoordinates != null) fields.add(new TooltipField("Target at", targetCoordinates));
        return List.copyOf(fields);
    }

    static List<TooltipField> historyContextFields(ChatContracts.HistoryPreview preview) {
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        int usage = preview.selection().tokenCount();
        int turns = preview.selection().turns().size();
        int percent = (int) Math.round((double) usage * 100 / preview.effectiveTokens());
        return List.of(
                new TooltipField("Usage", "~" + numbers.format(usage) + " / " + numbers.format(preview.effectiveTokens())
                        + " (" + numbers.format(percent) + "%)"),
                new TooltipField("Included", numbers.format(turns * 2) + " messages (" + numbers.format(turns)
                        + " / " + numbers.format(preview.configuredTurns()) + " turns)"),
                new TooltipField("Your limit", numbers.format(preview.configuredTokens()) + " tokens"),
                new TooltipField("Model limit", preview.modelTokens() == null ? "Unknown" : numbers.format(preview.modelTokens()) + " tokens"));
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
        return x == null || y == null || z == null ? null : x + ", " + y + ", " + z;
    }

    /** Returns the visible outcome line, or null when the turn completed normally. */
    static String outcomeLine(ChatContracts.Outcome outcome) {
        if (outcome.status() == ChatContracts.OutcomeStatus.COMPLETED) return null;
        return "Outcome: " + outcome.status() + (outcome.detail() == null ? "" : " (" + outcome.detail() + ")");
    }

    static String navigationLabel(Evidence.NavigationTarget target) {
        String id = target.targetId();
        if (target.providerId().equals("jei")) {
            // handle format from PackSageJeiPlugin.sourceHandle: jei/<gen>/<ver>/<category>/recipe-<id>-<ordinal>
            Matcher recipe = JEI_RECIPE.matcher(id);
            return recipe.matches() ? "Open recipe: " + displayName(recipe.group(1)) : "Open recipe";
        }
        String tail = id.substring(id.lastIndexOf('/') + 1);
        String readable = HASH_SUFFIX.matcher(tail.isEmpty() ? id : tail).replaceFirst("");
        return "Open " + target.providerId() + ": " + readable;
    }

    static String displayName(String resourceId) {
        String path = withoutNamespace(resourceId);
        path = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder name = new StringBuilder();
        for (String word : path.split("_+")) {
            if (word.isEmpty()) continue;
            if (name.length() > 0) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.length() == 0 ? resourceId : name.toString();
    }
}
