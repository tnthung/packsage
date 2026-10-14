package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded live reader for Artifacts item ability documentation. */
public final class ArtifactsGuideReader implements GuideDocumentReader {
    private static final String COMPONENTS = "artifacts.registry.ModDataComponents";
    private static final String ABILITY = "artifacts.component.ability.EquipmentAbility";

    ArtifactsGuideReader() { }

    public static GuideDocumentProvider forMinecraft(Supplier<Evidence.Scope> currentScope, Executor clientExecutor) {
        return new GuideDocumentProvider(new ArtifactsGuideReader(), currentScope, clientExecutor);
    }

    @Override public String providerId() { return "artifacts"; }
    @Override public String version() { return "13.2.3"; }
    @Override public String sourceIdentity(Evidence.Scope scope) {
        return "artifacts:registry=" + BuiltInRegistries.ITEM.size() + ":runtime=" + scope.providerRuntime()
                + ":locale=" + scope.resourcesLocale();
    }

    @Override
    public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        if (limit < 1) return new CandidatePage(List.of(), afterPosition, false, Evidence.Coverage.COMPLETE, null);
        var candidates = new ArrayList<Candidate>();
            String next = afterPosition;
        boolean more = false;
        boolean partial = false;
        try {
            var componentClass = Class.forName(COMPONENTS, false, ArtifactsGuideReader.class.getClassLoader());
            var abilityClass = Class.forName(ABILITY, false, ArtifactsGuideReader.class.getClassLoader());
            var ordered = componentClass.getField("TOOLTIP_ORDER").get(null);
            if (!(ordered instanceof List<?> componentTypes)) throw new ReflectiveOperationException();
            int afterItemIndex = -1;
            int afterComponentIndex = -1;
            if (afterPosition != null) {
                int separator = afterPosition.indexOf(':');
                if (separator < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Artifacts position");
                try {
                    afterItemIndex = Integer.parseInt(afterPosition.substring(0, separator));
                    afterComponentIndex = Integer.parseInt(afterPosition.substring(separator + 1));
                } catch (NumberFormatException failure) {
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Artifacts position");
                }
                if (afterItemIndex < -1 || afterItemIndex >= BuiltInRegistries.ITEM.size()
                        || afterComponentIndex < -1 || afterComponentIndex >= componentTypes.size())
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Artifacts position");
            }
            Object modItems = Class.forName("artifacts.registry.ModItems", false, ArtifactsGuideReader.class.getClassLoader())
                    .getField("ITEMS").get(null);
            if (!(modItems instanceof Iterable<?> artifactItems)) throw new ReflectiveOperationException();
            int itemIndex = 0;
            for (Object registeredItem : artifactItems) {
                if (itemIndex < afterItemIndex) { itemIndex++; continue; }
                if (!budget.value()) { next = itemIndex + ":-1"; more = true; partial = true; break; }
                if (!(registeredItem instanceof Item item)) { partial = true; itemIndex++; continue; }
                String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
                int currentItemIndex = itemIndex++;
                if (!itemId.startsWith("artifacts:")) { next = currentItemIndex + ":-1"; continue; }
                var stack = item.getDefaultInstance();
                int componentIndex = 0;
                for (Object supplier : componentTypes) {
                    if (currentItemIndex == afterItemIndex && componentIndex <= afterComponentIndex) { componentIndex++; continue; }
                    if (!budget.value()) { next = currentItemIndex + ":" + (componentIndex - 1); more = true; partial = true; break; }
                    if (!(supplier instanceof Supplier<?> componentSupplier)) {
                        partial = true;
                        next = currentItemIndex + ":" + componentIndex++;
                        continue;
                    }
                    Object component = componentSupplier.get();
                    if (!(component instanceof DataComponentType<?> type)) {
                        partial = true;
                        next = currentItemIndex + ":" + componentIndex;
                        componentIndex++;
                        continue;
                    }
                    String componentName = componentId(component);
                    Object ability = stack.get(type);
                    if (ability == null || !abilityClass.isInstance(ability)) {
                        next = currentItemIndex + ":" + componentIndex;
                        componentIndex++;
                        continue;
                    }
                    boolean nonCosmetic = (boolean) abilityClass.getMethod("isNonCosmetic").invoke(ability);
                    if (!nonCosmetic) {
                        next = currentItemIndex + ":" + componentIndex;
                        componentIndex++;
                        continue;
                    }
                    String id = itemId + "#" + componentName;
                    if (!budget.bytes(id.length() * 6 + 192)) { next = currentItemIndex + ":" + (componentIndex - 1); more = true; partial = true; break; }
                    String position = currentItemIndex + ":" + componentIndex;
                    candidates.add(new Candidate(id, itemId + " " + componentName, position,
                            List.of("artifacts:item:" + itemId, "artifacts:ability:" + componentName), Evidence.Coverage.COMPLETE));
                    next = position;
                    componentIndex++;
                    if (candidates.size() >= limit) { more = true; break; }
                }
                if (more) break;
            }
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            partial = true;
        }
        return new CandidatePage(candidates, more ? next : null, more,
                partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                partial ? "Artifacts ability registry traversal was bounded or its optional API was unavailable" : null);
    }

    @Override
    public Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget) {
        if (maxChars < 1 || !budget.value()) return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "read budget exhausted");
        int requestedLine = 0;
        int requestedOffset = 0;
        if (fragmentPosition != null) {
            int separator = fragmentPosition.indexOf(':');
            try {
                if (separator < 1) throw new NumberFormatException();
                requestedLine = Integer.parseInt(fragmentPosition.substring(0, separator));
                requestedOffset = Integer.parseInt(fragmentPosition.substring(separator + 1));
            } catch (NumberFormatException failure) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Artifacts text position");
            }
            if (requestedLine < 0 || requestedOffset < 0)
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Artifacts text position");
        }
        final int targetLine = requestedLine;
        final int targetOffset = requestedOffset;
        try {
            int separator = id.indexOf('#');
            if (separator < 1) return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "invalid Artifacts document identity");
            var itemKey = net.minecraft.resources.ResourceLocation.parse(id.substring(0, separator));
            Item item = BuiltInRegistries.ITEM.getOptional(itemKey).orElse(null);
            if (item == null) return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "current Artifacts item definition is unavailable");
            var componentClass = Class.forName(COMPONENTS, false, ArtifactsGuideReader.class.getClassLoader());
            var abilityClass = Class.forName(ABILITY, false, ArtifactsGuideReader.class.getClassLoader());
            Object component = null;
            for (Object supplier : (Iterable<?>) componentClass.getField("TOOLTIP_ORDER").get(null)) {
                if (supplier instanceof Supplier<?> componentSupplier) {
                    Object candidate = componentSupplier.get();
                    if (componentId(candidate).equals(id.substring(separator + 1))) { component = candidate; break; }
                }
            }
            if (!(component instanceof DataComponentType<?> type))
                return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "current Artifacts ability component is unavailable");
            ItemStack stack = item.getDefaultInstance();
            Object ability = stack.get(type);
            if (ability == null || !abilityClass.isInstance(ability))
                return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "current default item has no documented ability component");
            var output = new StringBuilder();
            boolean[] missingTranslation = {false};
            boolean[] foundLine = {false};
            boolean[] laterLine = {false};
            boolean[] sourceChanged = {false};
            int[] lineIndex = {0};
            int[] overlapChars = {0};
            String[] nextPosition = {null};
            Class<?> writerClass = Class.forName(ABILITY + "$TooltipWriter", false,
                    ArtifactsGuideReader.class.getClassLoader());
            var client = Minecraft.getInstance();
            if (client.player == null || client.level == null)
                return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "Artifacts tooltip requires the current local player and level");
            Consumer<Component> tooltip = text -> {
                int currentLine = lineIndex[0]++;
                if (currentLine < targetLine) return;
                if (currentLine > targetLine) { laterLine[0] = true; return; }
                foundLine[0] = true;
                String rendered = text.getString();
                if (targetOffset > rendered.length()) { sourceChanged[0] = true; return; }
                int overlap = Math.min(targetOffset, Math.min(FRAGMENT_OVERLAP_CHARS, maxChars - 1));
                int start = targetOffset - overlap;
                int availableChars = Math.min(maxChars - overlap, Math.max(0, (budget.remainingBytes() - 16) / 6));
                int end = targetOffset + Math.min(rendered.length() - targetOffset, availableChars);
                if (end < rendered.length() && end > targetOffset && Character.isHighSurrogate(rendered.charAt(end - 1))) end--;
                if (end == targetOffset && end < rendered.length()) { sourceChanged[0] = true; return; }
                int copied = end - start;
                if (!budget.bytes(copied * 6 + 16)) { sourceChanged[0] = true; return; }
                output.append(rendered, start, end);
                overlapChars[0] = overlap;
                if (end < rendered.length()) nextPosition[0] = targetLine + ":" + end;
                else nextPosition[0] = null;
                if (text.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translated
                        && !Language.getInstance().has(translated.getKey())) missingTranslation[0] = true;
            };
            Object tooltipWriter = writerClass.getConstructor(DataComponentType.class, Consumer.class,
                    Item.TooltipContext.class, ItemStack.class).newInstance(type, tooltip, Item.TooltipContext.of(client.level), stack);
            Method addToTooltip = abilityClass.getMethod("addToTooltip", writerClass);
            try {
                addToTooltip.invoke(ability, tooltipWriter);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                if (failure.getCause() instanceof Error errorFailure) throw errorFailure;
                throw new IllegalStateException(failure.getCause());
            }
            if (foundLine[0] && !sourceChanged[0] && nextPosition[0] == null && laterLine[0])
                nextPosition[0] = (targetLine + 1) + ":0";
            String sourcePosition = id + "|tooltip:" + targetLine + ":" + Math.max(0, targetOffset - overlapChars[0]);
            if (sourceChanged[0]) return new Fragment("", sourcePosition, 0, null, Evidence.Coverage.PARTIAL,
                    "Artifacts tooltip changed or exceeded the remaining read budget; restart the lookup");
            if (!foundLine[0]) return new Fragment("", sourcePosition, 0, null,
                    fragmentPosition == null ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                    fragmentPosition == null ? null : "Artifacts tooltip changed before the continuation could be read");
            boolean partial = nextPosition[0] != null || missingTranslation[0];
            return new Fragment(output.toString(), sourcePosition, overlapChars[0], nextPosition[0],
                    partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    nextPosition[0] != null ? "Artifacts ability tooltip continues at the supplied text position"
                            : missingTranslation[0] ? "Artifacts ability documentation has untranslated keys" : null);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "current Artifacts ability documentation is unavailable");
        }
    }

    @Override
    public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        int separator = id.indexOf('#');
        var candidate = new Candidate(id, separator < 0 ? id : id.substring(0, separator), id,
                List.of("artifacts default item ability documentation"), Evidence.Coverage.COMPLETE);
        return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
    }

    private static String componentId(Object component) {
        return String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey((DataComponentType<?>) component));
    }

}
