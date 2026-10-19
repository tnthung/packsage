package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Bounded live reader for registry-reachable Relics ability documentation. */
public final class RelicsGuideReader implements GuideDocumentReader {
    private static final String RELIC_ITEM = "it.hurts.sskirillss.relics.api.relics.IRelicItem";
    private static final TagKey<Item> RELIC_TAG = TagKey.create(Registries.ITEM, ResourceLocation.parse("relics:relic"));
    private static final TagKey<Item> ARTIFACTS_TAG = TagKey.create(Registries.ITEM, ResourceLocation.parse("artifacts:artifacts"));
    private final String readerProviderId;
    private final String readerVersion;
    private final String itemNamespace;
    private final TagKey<Item> discoveryTag;

    private RelicsGuideReader() { this("relics", "unknown", null); }

    RelicsGuideReader(String providerId, String version, String itemNamespace) {
        this.readerProviderId = providerId;
        this.readerVersion = version;
        this.itemNamespace = itemNamespace;
        this.discoveryTag = "artifacts".equals(itemNamespace) ? ARTIFACTS_TAG : RELIC_TAG;
    }

    public static GuideDocumentProvider forMinecraft(Supplier<Evidence.Scope> currentScope, Executor clientExecutor, String actualVersion) {
        return new GuideDocumentProvider(new RelicsGuideReader("relics", actualVersion, null), currentScope, clientExecutor);
    }

    @Override public String providerId() { return readerProviderId; }
    @Override public String version() { return readerVersion; }
    @Override public String sourceIdentity(Evidence.Scope scope) {
        var client = Minecraft.getInstance();
        var tag = client.level == null ? null : client.level.registryAccess().registryOrThrow(Registries.ITEM).getTag(discoveryTag).orElse(null);
        String tagIdentity = "none";
        if (tag != null) {
            try {
                var contents = tag.getClass().getDeclaredField("contents");
                contents.setAccessible(true);
                tagIdentity = System.identityHashCode(contents.get(tag)) + ":" + tag.size();
            } catch (ReflectiveOperationException | RuntimeException failure) {
                throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "cannot identify the current Relics tag generation");
            }
        }
        return readerProviderId + ":" + readerVersion + ":namespace=" + itemNamespace + ":tag=" + discoveryTag.location() + ":" + tagIdentity
                + ":player=" + (client.player == null ? "none" : client.player.getUUID()) + ":locale=" + scope.resourcesLocale();
    }

    @Override
    public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        if (limit < 1) return new CandidatePage(List.of(), afterPosition, false, Evidence.Coverage.COMPLETE, null);
        var candidates = new ArrayList<Candidate>();
        String next = afterPosition;
        boolean more = false;
        boolean partial = false;
        int afterItem = -1;
        int afterAbility = -1;
        if (afterPosition != null) {
            int separator = afterPosition.indexOf(':');
            try {
                if (separator < 1 || separator == afterPosition.length() - 1 || afterPosition.indexOf(':', separator + 1) >= 0)
                    throw new NumberFormatException();
                afterItem = Integer.parseInt(afterPosition.substring(0, separator));
                afterAbility = Integer.parseInt(afterPosition.substring(separator + 1));
            } catch (NumberFormatException failure) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Relics registry cursor");
            }
            if (afterItem < 0 || afterAbility < -1)
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Relics registry cursor is out of range");
        }
        try {
            var relicType = Class.forName(RELIC_ITEM, false, RelicsGuideReader.class.getClassLoader());
            var client = Minecraft.getInstance();
            if (client.player == null || client.level == null) return new CandidatePage(List.of(), afterPosition, false,
                    Evidence.Coverage.PARTIAL, "Relics requires the current player and loaded item tags to enumerate ability definitions");
            var itemRegistry = client.level.registryAccess().registryOrThrow(Registries.ITEM);
            var relicTag = itemRegistry.getTag(discoveryTag).orElse(null);
            if (relicTag == null) return new CandidatePage(List.of(), afterPosition, false,
                    Evidence.Coverage.PARTIAL, "the loaded item registry has no " + discoveryTag.location() + " tag");
            if (relicTag.size() == 0) {
                if (afterPosition != null) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                        "Relics tag changed; restart the lookup");
                return new CandidatePage(List.of(), null, false, Evidence.Coverage.COMPLETE, null);
            }
            if (afterItem >= relicTag.size()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Relics registry cursor is out of range");
            var relicItems = relicTag.iterator();
            int currentItemPosition = 0;
            while (relicItems.hasNext()) {
                if (!budget.value()) { more = next != null && !next.equals(afterPosition); partial = true; break; }
                Item item = relicItems.next().value();
                int itemPosition = currentItemPosition++;
                if (itemPosition < afterItem) continue;
                var itemKey = BuiltInRegistries.ITEM.getKey(item);
                if (itemNamespace != null && !itemNamespace.equals(itemKey.getNamespace())) continue;
                String itemId = itemKey.toString();
                if (!relicType.isInstance(item)) {
                    if (itemPosition == afterItem && afterAbility != -1)
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Relics cursor does not match its current tag item");
                    next = itemPosition + ":-1";
                    continue;
                }
                Object relicData = relicType.getMethod("getRelicData", net.minecraft.world.entity.LivingEntity.class,
                        net.minecraft.world.item.ItemStack.class).invoke(item, client.player, item.getDefaultInstance());
                if (relicData == null) {
                    if (itemPosition == afterItem && afterAbility != -1)
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Relics cursor item definition changed");
                    partial = true;
                    next = itemPosition + ":-1";
                    continue;
                }
                Object abilities = relicData.getClass().getMethod("getAbilitiesData").invoke(relicData);
                Object ids = abilities.getClass().getMethod("getAbilityIDs").invoke(abilities);
                if (!(ids instanceof Set<?> abilityIds)) { partial = true; next = itemPosition + ":-1"; continue; }
                if (itemPosition == afterItem && afterAbility >= abilityIds.size())
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Relics ability cursor is out of range");
                int abilityPosition = 0;
                for (Object abilityId : abilityIds) {
                    int currentAbilityPosition = abilityPosition++;
                    if (!budget.value()) { more = next != null && !next.equals(afterPosition); partial = true; break; }
                    if (itemPosition == afterItem && currentAbilityPosition <= afterAbility) continue;
                    String id = String.valueOf(abilityId);
                    String itemName = Language.getInstance().getOrDefault(item.getDescriptionId());
                    boolean titleTruncated = itemName.length() > 256;
                    if (titleTruncated) itemName = itemName.substring(0, 256);
                    if (id.length() > 256 || itemId.length() > 256
                            || !budget.bytes(id.length() * 6 + itemId.length() * 6 + itemName.length() * 6 + 128)) {
                        more = next != null && !next.equals(afterPosition); partial = true; break;
                    }
                    String documentId = itemId + "#" + id;
                    candidates.add(new Candidate(documentId, itemName + " — " + id, itemPosition + ":" + currentAbilityPosition,
                            List.of("relics:item:" + itemId, "relics:ability:" + id), Evidence.Coverage.COMPLETE));
                    partial |= titleTruncated;
                    next = itemPosition + ":" + currentAbilityPosition;
                    if (candidates.size() >= limit) { more = true; break; }
                }
                if (more) break;
                if (abilityPosition == 0) next = itemPosition + ":-1";
            }
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            partial = true;
        }
        return new CandidatePage(candidates, more ? next : null, more,
                partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                partial ? "Relics registry traversal was bounded or its optional API was unavailable" : null);
    }

    @Override
    public Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget) {
        if (maxChars < 1 || !budget.value()) return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "read budget exhausted");
        try {
            int separator = id.indexOf('#');
            if (separator < 1) return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "invalid Relics document identity");
            var itemKey = net.minecraft.resources.ResourceLocation.parse(id.substring(0, separator));
            if (itemNamespace != null && !itemNamespace.equals(itemKey.getNamespace()))
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "item belongs to another guide namespace");
            Item item = BuiltInRegistries.ITEM.getOptional(itemKey).orElse(null);
            var relicType = Class.forName(RELIC_ITEM, false, RelicsGuideReader.class.getClassLoader());
            var client = Minecraft.getInstance();
            if (item == null || !relicType.isInstance(item))
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "Relics item is no longer registered");
            if (client.player == null || client.level == null)
                throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Relics requires a current player and loaded level");
            var itemRegistry = client.level.registryAccess().registryOrThrow(Registries.ITEM);
            var relicTag = itemRegistry.getTag(discoveryTag).orElse(null);
            var holder = itemRegistry.getHolder(itemKey).orElse(null);
            if (relicTag == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                    "the loaded item registry has no " + discoveryTag.location() + " tag");
            if (holder == null || !relicTag.contains(holder))
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                        "item is no longer a member of " + discoveryTag.location());
            Object relicData = relicType.getMethod("getRelicData", net.minecraft.world.entity.LivingEntity.class,
                    net.minecraft.world.item.ItemStack.class).invoke(item, client.player, item.getDefaultInstance());
            Object abilities = relicData.getClass().getMethod("getAbilitiesData").invoke(relicData);
            Object ability = abilities.getClass().getMethod("getAbilityData", String.class).invoke(abilities, id.substring(separator + 1));
            Object template = ability.getClass().getMethod("getTemplate").invoke(ability);
            Object modesValue = template.getClass().getMethod("getModes").invoke(template);
            if (!(modesValue instanceof List<?> modes)) return new Fragment("", id, 0, null,
                    Evidence.Coverage.PARTIAL, "Relics ability mode definitions are unavailable");
            int modePosition = 0;
            int textOffset = 0;
            if (fragmentPosition != null) {
                int separatorPosition = fragmentPosition.indexOf(':');
                if (separatorPosition < 1) return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "invalid Relics fragment position");
                modePosition = Integer.parseInt(fragmentPosition.substring(0, separatorPosition));
                textOffset = Integer.parseInt(fragmentPosition.substring(separatorPosition + 1));
            }
            int modeCount = Math.max(1, modes.size());
            for (int index = modePosition; index < modeCount; index++) {
                if (!budget.value()) return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "Relics extraction budget exhausted");
                String mode = modes.isEmpty() ? "description" : String.valueOf(modes.get(index));
                if (mode.length() > 256) return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "Relics mode key exceeds the read bound");
                String itemPath = BuiltInRegistries.ITEM.getKey(item).getPath();
                String key = "relics.description." + itemPath + ".ability." + id.substring(separator + 1) + "."
                        + (modes.isEmpty() ? "description" : mode + ".description");
                String rendered = Language.getInstance().getOrDefault(key);
                if (rendered.equals(key)) {
                    textOffset = 0;
                    return new Fragment("", id, 0, index + 1 < modeCount ? index + 1 + ":0" : null,
                            Evidence.Coverage.PARTIAL, "Relics ability description translation is missing for " + key);
                }
                return textFragment(id, rendered, mode, index, textOffset, maxChars, modeCount, budget);
            }
            return new Fragment("", id, 0, null, Evidence.Coverage.COMPLETE, null);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL, "current Relics ability documentation is unavailable");
        }
    }

    @Override
    public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        var split = id.indexOf('#');
        String title = split < 0 ? id : id.substring(split + 1);
        Evidence.Coverage coverage = Evidence.Coverage.COMPLETE;
        if (split > 0) {
            var itemKey = ResourceLocation.parse(id.substring(0, split));
            var item = BuiltInRegistries.ITEM.getOptional(itemKey).orElse(null);
            if (item != null && (itemNamespace == null || itemNamespace.equals(itemKey.getNamespace()))) {
                String itemName = Language.getInstance().getOrDefault(item.getDescriptionId());
                if (itemName.length() > 256) {
                    itemName = itemName.substring(0, 256);
                    coverage = Evidence.Coverage.PARTIAL;
                }
                if (!budget.bytes(itemName.length() * 6 + 128)) coverage = Evidence.Coverage.PARTIAL;
                title = itemName + " — " + title;
            }
        }
        var candidate = new Candidate(id, title, id,
                List.of("relics ability documentation"), coverage);
        return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
    }

    static Fragment textFragment(String id, String rendered, String label, int modePosition, int textOffset,
                                 int maxChars, int modeCount, Toolbox.DocumentBudget budget) {
        if (textOffset < 0 || textOffset > rendered.length()) return new Fragment("", id, 0, null,
                Evidence.Coverage.PARTIAL, "Relics ability text changed; restart the lookup");
        int overlap = Math.min(FRAGMENT_OVERLAP_CHARS, textOffset);
        int copyStart = textOffset - overlap;
        String prefix = textOffset == 0 ? label + ": " : "";
        int end = Math.min(rendered.length(), textOffset + Math.max(0, maxChars - overlap - prefix.length()));
        if (end == textOffset && end < rendered.length()) return new Fragment("", id, 0, null,
                Evidence.Coverage.PARTIAL, "Relics fragment limit is too small to advance safely");
        if (!budget.bytes((end - copyStart + prefix.length()) * 6 + 24)) return new Fragment("", id, 0, null,
                Evidence.Coverage.PARTIAL, "Relics extraction budget exhausted");
        String fragment = prefix + rendered.substring(copyStart, end);
        if (end < rendered.length()) return new Fragment(fragment, id, overlap, modePosition + ":" + end,
                Evidence.Coverage.PARTIAL, "bounded live ability documentation fragment");
        return new Fragment(fragment, id, overlap, modePosition + 1 < modeCount ? modePosition + 1 + ":0" : null,
                Evidence.Coverage.COMPLETE, null);
    }
}
