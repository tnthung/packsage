package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Fresh, bounded reads of the loaded Immersive Engineering manual and active localized resources. */
public final class ImmersiveEngineeringGuideReader implements GuideDocumentReader {
    public static final String ID = "immersiveengineering";
    public static final String VERSION = "12.4.2-194";
    private static final String MANUAL_HELPER = "blusunrize.immersiveengineering.api.ManualHelper";
    private static final int MAX_ENTRY_ID_LENGTH = 256;
    private static final int MAX_TITLE_LENGTH = 512;
    private static final int MAX_SEEK_CHARS = 16 * 1024;
    private static final int MAX_RELATIONSHIP_RECORDS = 16;
    private static final int MAX_RELATIONSHIP_BYTES = 8 * 1024;
    private static final String MANUAL_INSTANCE = "blusunrize.lib.manual.ManualInstance";
    private final String readerVersion;

    public ImmersiveEngineeringGuideReader() { this(VERSION); }

    ImmersiveEngineeringGuideReader(String version) { this.readerVersion = version; }

    public static GuideDocumentProvider forMinecraft(Supplier<Evidence.Scope> scopeSource, Executor clientExecutor, String actualVersion) {
        return new GuideDocumentProvider(new ImmersiveEngineeringGuideReader(actualVersion), scopeSource, clientExecutor);
    }

    @Override public String providerId() { return ID; }
    @Override public String version() { return readerVersion; }

    @Override
    public String sourceIdentity(Evidence.Scope scope) {
        try {
            var helper = Class.forName(MANUAL_HELPER, false, ImmersiveEngineeringGuideReader.class.getClassLoader());
            var manual = helper.getMethod("getManual").invoke(null);
            var entries = (Map<?, ?>) manual.getClass().getField("contentsByName").get(manual);
            return readerVersion + ":" + scope.resourcesLocale() + ":" + System.identityHashCode(manual) + ":"
                    + System.identityHashCode(entries) + ":" + entries.size();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return readerVersion + ":unavailable:" + scope.resourcesLocale();
        }
    }

    @Override
    public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        try {
            var helper = Class.forName(MANUAL_HELPER, false, ImmersiveEngineeringGuideReader.class.getClassLoader());
            var manual = helper.getMethod("getManual").invoke(null);
            var entries = (Map<?, ?>) manual.getClass().getField("contentsByName").get(manual);
            if (entries.isEmpty()) return new CandidatePage(List.of(), null, false, Evidence.Coverage.PARTIAL,
                    "Immersive Engineering manual entries have not been loaded");
            var candidates = new ArrayList<Candidate>();
            String lastPosition = afterPosition;
            boolean passedCursor = afterPosition == null;
            boolean partial = false;
            boolean seekBudgetEnded = false;
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (!budget.value()) {
                    partial = true;
                    seekBudgetEnded = true;
                    break;
                }
                var sourceEntry = iterator.next();
                if (!(sourceEntry.getKey() instanceof ResourceLocation location)) {
                    throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                            "Immersive Engineering manual entry keys changed unexpectedly");
                }
                Object entry = sourceEntry.getValue();
                String id = location.toString();
                if (id.length() > MAX_ENTRY_ID_LENGTH) {
                    throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                            "Immersive Engineering entry ID exceeds the bounded cursor limit");
                }
                if (!passedCursor) {
                    if (id.equals(afterPosition)) passedCursor = true;
                    lastPosition = id;
                    continue;
                }
                var node = entry.getClass().getMethod("getTreeNode").invoke(entry);
                var abstractNode = Class.forName("blusunrize.lib.manual.Tree$AbstractNode", false,
                        ImmersiveEngineeringGuideReader.class.getClassLoader());
                if (node == null || !(boolean) manual.getClass().getMethod("showNodeInList", abstractNode).invoke(manual, node)) {
                    lastPosition = id;
                    continue;
                }
                if (!budget.bytes(id.length() * 6 + 1024)) {
                    partial = true;
                    break;
                }
                Object rawTitle = entry.getClass().getMethod("getTitle").invoke(entry);
                String title = rawTitle instanceof String value ? value : null;
                if (title == null || title.isBlank()) title = id;
                if (title.length() > MAX_TITLE_LENGTH) {
                    title = title.substring(0, MAX_TITLE_LENGTH);
                    partial = true;
                }
                candidates.add(new Candidate(id, title, id, List.of("Immersive Engineering manual entry " + id),
                        partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE));
                lastPosition = id;
                if (candidates.size() >= Math.max(1, limit)) break;
            }
            if (!passedCursor) throw new Toolbox.Failure(seekBudgetEnded ? Toolbox.Code.EXTRACTION_FAILED : Toolbox.Code.INVALID_CURSOR,
                    seekBudgetEnded ? "Immersive Engineering manual cursor seek exceeds the read budget; restart the lookup"
                            : "Immersive Engineering manual changed; restart the lookup");
            boolean more = iterator.hasNext();
            if (more && lastPosition == null) more = false;
            return new CandidatePage(candidates, more ? lastPosition : null, more,
                    partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    partial ? "manual traversal or candidate metadata exceeded the read budget" : null);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new CandidatePage(List.of(), null, false, Evidence.Coverage.PARTIAL,
                    "Immersive Engineering manual entries are not currently available");
        }
    }

    @Override
    public Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget) {
        if (maxChars < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                "Immersive Engineering fragment limit must be positive");
        String relationshipPosition = null;
        int relationshipFlags = 0;
        int offset = 0;
        boolean relationshipsRequested = fragmentPosition == null;
        if (fragmentPosition != null && fragmentPosition.startsWith("ie1|")) {
            if (fragmentPosition.length() > 2048) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "invalid Immersive Engineering fragment position");
            var cursor = fragmentPosition.split("\\|", -1);
            if (cursor.length != 4) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "invalid Immersive Engineering fragment position");
            try {
                relationshipPosition = cursor[1].equals("done") ? null : cursor[1];
                relationshipFlags = Integer.parseInt(cursor[2]);
                offset = cursor[3].equals("done") ? -1 : Integer.parseInt(cursor[3]);
                if (relationshipPosition != null) {
                    var indices = relationshipPosition.split("\\.", -1);
                    if (indices.length != 5) throw new NumberFormatException();
                    for (String index : indices) if (Integer.parseInt(index) < 0) throw new NumberFormatException();
                }
            } catch (NumberFormatException failure) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Immersive Engineering fragment position");
            }
            if (relationshipFlags < 0 || relationshipFlags > 1 || offset < -1)
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Immersive Engineering fragment position");
            relationshipsRequested = relationshipPosition != null;
        } else if (fragmentPosition != null) {
            try {
                offset = Integer.parseInt(fragmentPosition);
            } catch (NumberFormatException failure) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Immersive Engineering text position");
            }
            if (offset < 0 || offset > MAX_SEEK_CHARS) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Immersive Engineering text changed; restart the lookup");
        }
        try {
            if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Immersive Engineering entry traversal exceeds the remaining read budget");
            var helper = Class.forName(MANUAL_HELPER, false, ImmersiveEngineeringGuideReader.class.getClassLoader());
            var manual = helper.getMethod("getManual").invoke(null);
            var entryMap = (Map<?, ?>) manual.getClass().getField("contentsByName").get(manual);
            var entry = entryMap.get(ResourceLocation.parse(id));
            if (entry == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                    "Immersive Engineering manual entry is no longer visible");
            var node = entry.getClass().getMethod("getTreeNode").invoke(entry);
            var abstractNode = Class.forName("blusunrize.lib.manual.Tree$AbstractNode", false,
                    ImmersiveEngineeringGuideReader.class.getClassLoader());
            if (node == null || !(boolean) manual.getClass().getMethod("showNodeInList", abstractNode).invoke(manual, node)) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                        "Immersive Engineering manual entry is no longer visible");
            }
            final String resumePosition = relationshipPosition;
            class RelationshipScan {
                final List<Evidence.JsonValue> values = new ArrayList<>();
                int semanticRecords;
                int bytes;
                int[] next;
                boolean partial;
                boolean opaque;
                boolean unavailable;
                String currentAnchor = "";
                int currentOffset;

                String encode(int[] position) {
                    return position[0] + "." + position[1] + "." + position[2] + "." + position[3] + "." + position[4];
                }

                String source(int[] position) {
                    return "entry:" + id + ":anchor:" + currentAnchor + ":offset:" + currentOffset
                            + ":special:" + position[0] + ":row:" + position[1]
                            + ":alternative:" + position[2] + ":cell:" + position[3] + ":variant:" + position[4];
                }

                int compare(int[] left, int[] right) {
                    for (int index = 0; index < left.length; index++) {
                        if (left[index] != right[index]) return Integer.compare(left[index], right[index]);
                    }
                    return 0;
                }

                int[] parseResume() {
                    if (resumePosition == null) return new int[] {0, 0, 0, 0, 0};
                    var strings = resumePosition.split("\\.", -1);
                    var result = new int[5];
                    for (int index = 0; index < strings.length; index++) result[index] = Integer.parseInt(strings[index]);
                    return result;
                }

                boolean append(Map<String, Evidence.JsonValue> value, int count, int estimate, int[] following) {
                    if (semanticRecords + count > MAX_RELATIONSHIP_RECORDS || bytes + estimate > MAX_RELATIONSHIP_BYTES
                            || !budget.value() || !budget.bytes(estimate)) {
                        next = following;
                        partial = true;
                        return false;
                    }
                    values.add(new Evidence.JsonObject(value));
                    semanticRecords += count;
                    bytes += estimate;
                    next = following;
                    return true;
                }

                Evidence.JsonObject stack(ItemStack stack) {
                    var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (itemId == null || itemId.toString().length() > 256) {
                        partial = true;
                        return null;
                    }
                    var projected = new LinkedHashMap<String, Evidence.JsonValue>();
                    projected.put("itemId", new Evidence.JsonString(itemId.toString()));
                    var components = new LinkedHashMap<String, Evidence.JsonValue>();
                    var patch = stack.getComponentsPatch();
                    if (patch.size() > 16) partial = true;
                    else for (var component : patch.entrySet()) {
                        if (!budget.value()) { partial = true; break; }
                        if (component.getKey() == DataComponents.POTION_CONTENTS
                                && component.getValue().orElse(null) instanceof PotionContents potion
                                && potion.potion().isPresent() && potion.customColor().isEmpty()
                                && potion.customEffects().isEmpty()) {
                            var potionId = potion.potion().get().unwrapKey()
                                    .map(key -> key.location().toString()).orElse(null);
                            if (potionId == null || potionId.length() > 256) partial = true;
                            else components.put("minecraft:potion_contents", new Evidence.JsonObject(Map.of(
                                    "potion", new Evidence.JsonString(potionId))));
                        } else partial = true;
                    }
                    if (patch.size() > 16) components.put("encoding", new Evidence.JsonString("partial"));
                    if (!components.isEmpty()) projected.put("components", new Evidence.JsonObject(components));
                    return new Evidence.JsonObject(projected);
                }

                void read(Object currentManual, Object currentEntry) throws ReflectiveOperationException {
                    Class<?> manualType = Class.forName(MANUAL_INSTANCE, false, getClass().getClassLoader());
                    var initialized = manualType.getDeclaredField("initialized");
                    if (!initialized.trySetAccessible()) { unavailable = true; partial = true; return; }
                    if (!initialized.getBoolean(currentManual)) {
                        unavailable = true;
                        partial = true;
                        next = parseResume();
                        return;
                    }
                    Class<?> specialType = Class.forName("blusunrize.lib.manual.ManualEntry$SpecialElementData",
                            false, getClass().getClassLoader());
                    Class<?> recipeRefType = Class.forName("blusunrize.lib.manual.utils.ManualRecipeRef", false,
                            getClass().getClassLoader());
                    Class<?> craftingType = Class.forName("blusunrize.lib.manual.ManualElementCrafting", false,
                            getClass().getClassLoader());
                    Class<?> ieCraftingType = Class.forName(
                            "blusunrize.immersiveengineering.client.manual.ManualElementIECrafting", false,
                            getClass().getClassLoader());
                    Class<?> mixerType = Class.forName(
                            "blusunrize.immersiveengineering.client.manual.ManualElementMixer", false,
                            getClass().getClassLoader());
                    if (!budget.value()) { partial = true; next = parseResume(); return; }
                    Object rawSpecials = currentEntry.getClass().getMethod("getSpecialData").invoke(currentEntry);
                    if (!(rawSpecials instanceof List<?> specials)) { unavailable = true; partial = true; return; }
                    int[] start = parseResume();
                    boolean found = resumePosition == null;
                    for (int specialIndex = start[0]; specialIndex < specials.size(); specialIndex++) {
                        if (!budget.value()) { partial = true; next = new int[] {specialIndex, 0, 0, 0, 0}; return; }
                        Object special = specials.get(specialIndex);
                        if (!specialType.isInstance(special)) { opaque = true; partial = true; continue; }
                        if (!budget.value()) { partial = true; next = new int[] {specialIndex, 0, 0, 0, 0}; return; }
                        Object anchor = specialType.getMethod("getAnchor").invoke(special);
                        Object offset = specialType.getMethod("getOffset").invoke(special);
                        if (anchor instanceof String text && text.length() <= 256) currentAnchor = text;
                        else { currentAnchor = "opaque"; opaque = true; partial = true; }
                        currentOffset = offset instanceof Integer value ? value : 0;
                        Object element = specialType.getMethod("getElement").invoke(special);
                        if (element == null) { opaque = true; partial = true; continue; }
                        if (craftingType.isInstance(element)) {
                            var field = craftingType.getDeclaredField("recipeRows");
                            if (!field.trySetAccessible()) { opaque = true; partial = true; continue; }
                            Object rawRows = field.get(element);
                            if (!(rawRows instanceof Object[] rows)) { opaque = true; partial = true; continue; }
                            int rowStart = specialIndex == start[0] ? start[1] : 0;
                            for (int rowIndex = rowStart; rowIndex < rows.length; rowIndex++) {
                                if (!budget.value()) { partial = true; next = new int[] {specialIndex, rowIndex, 0, 0, 0}; return; }
                                if (!(rows[rowIndex] instanceof Object[] row)) { opaque = true; partial = true; continue; }
                                int refStart = specialIndex == start[0] && rowIndex == start[1] ? start[2] : 0;
                                for (int refIndex = refStart; refIndex < row.length; refIndex++) {
                                    int[] position = {specialIndex, rowIndex, refIndex, 0, 0};
                                    if (!found) {
                                        int[] cursor = start;
                                        int[] refPosition = {position[0], position[1], position[2], 0, 0};
                                        int[] cursorRef = {cursor[0], cursor[1], cursor[2], 0, 0};
                                        if (compare(refPosition, cursorRef) < 0) continue;
                                        if (compare(refPosition, cursorRef) > 0) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                                "Immersive Engineering declarations changed; restart the lookup");
                                        found = true;
                                    }
                                    if (!budget.value()) { partial = true; next = position; return; }
                                    Object rawRef = row[refIndex];
                                    if (!recipeRefType.isInstance(rawRef)) { opaque = true; partial = true; continue; }
                                    int[] following = refIndex + 1 < row.length ? new int[] {specialIndex, rowIndex, refIndex + 1, 0, 0}
                                            : rowIndex + 1 < rows.length ? new int[] {specialIndex, rowIndex + 1, 0, 0, 0}
                                            : new int[] {specialIndex + 1, 0, 0, 0, 0};
                                    if (!readRef(rawRef, recipeRefType, position, following)) return;
                                }
                            }
                        } else if (ieCraftingType.isInstance(element)) {
                            var field = ieCraftingType.getDeclaredField("stacks");
                            if (!field.trySetAccessible()) { opaque = true; partial = true; continue; }
                            Object rawRefs = field.get(element);
                            if (!(rawRefs instanceof Object[] refs)) { opaque = true; partial = true; continue; }
                            int refStart = specialIndex == start[0] ? start[2] : 0;
                            for (int refIndex = refStart; refIndex < refs.length; refIndex++) {
                                int[] position = {specialIndex, 0, refIndex, 0, 0};
                                if (!found) {
                                    int[] cursor = start;
                                    int[] cursorRef = {cursor[0], cursor[1], cursor[2], 0, 0};
                                    if (compare(position, cursorRef) < 0) continue;
                                    if (compare(position, cursorRef) > 0) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                            "Immersive Engineering declarations changed; restart the lookup");
                                    found = true;
                                }
                                if (!budget.value()) { partial = true; next = position; return; }
                                int[] following = refIndex + 1 < refs.length ? new int[] {specialIndex, 0, refIndex + 1, 0, 0}
                                        : new int[] {specialIndex + 1, 0, 0, 0, 0};
                                if (!readRef(refs[refIndex], recipeRefType, position, following)) return;
                            }
                        } else if (mixerType.isInstance(element)) {
                            var field = mixerType.getDeclaredField("fluids");
                            if (!field.trySetAccessible()) { opaque = true; partial = true; continue; }
                            Object rawFluids = field.get(element);
                            if (!(rawFluids instanceof Object[] fluids)) { opaque = true; partial = true; continue; }
                            int fluidStart = specialIndex == start[0] ? start[2] : 0;
                            for (int fluidIndex = fluidStart; fluidIndex < fluids.length; fluidIndex++) {
                                int[] position = {specialIndex, 0, fluidIndex, 0, 0};
                                if (!found) {
                                    int[] cursor = start;
                                    int[] cursorRef = {cursor[0], cursor[1], cursor[2], 0, 0};
                                    if (compare(position, cursorRef) < 0) continue;
                                    if (compare(position, cursorRef) > 0) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                            "Immersive Engineering declarations changed; restart the lookup");
                                    found = true;
                                }
                                if (!budget.value()) { partial = true; next = position; return; }
                                Object fluid = fluids[fluidIndex];
                                var fluidId = fluid instanceof net.minecraft.world.level.material.Fluid value
                                        ? BuiltInRegistries.FLUID.getKey(value) : null;
                                if (fluidId == null || fluidId.toString().length() > 256) { opaque = true; partial = true; continue; }
                                var value = new LinkedHashMap<String, Evidence.JsonValue>();
                                value.put("kind", new Evidence.JsonString("fluid"));
                                value.put("sourcePosition", new Evidence.JsonString(source(position)));
                                value.put("fluidId", new Evidence.JsonString(fluidId.toString()));
                                int[] following = fluidIndex + 1 < fluids.length ? new int[] {specialIndex, 0, fluidIndex + 1, 0, 0}
                                        : new int[] {specialIndex + 1, 0, 0, 0, 0};
                                if (!append(value, 1, 512 + id.length() * 6 + fluidId.toString().length() * 6, following)) return;
                            }
                        } else { opaque = true; partial = true; }
                    }
                    if (!found && resumePosition != null) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                            "Immersive Engineering declarations changed; restart the lookup");
                    next = null;
                }

                boolean readRef(Object ref, Class<?> type, int[] position, int[] following) throws ReflectiveOperationException {
                    if (ref == null) { opaque = true; partial = true; return true; }
                    if (!type.isInstance(ref)) { opaque = true; partial = true; return true; }
                    boolean recipe = (boolean) type.getMethod("isRecipeName").invoke(ref);
                    boolean output = (boolean) type.getMethod("isResult").invoke(ref);
                    boolean layout = (boolean) type.getMethod("isLayout").invoke(ref);
                    var value = new LinkedHashMap<String, Evidence.JsonValue>();
                    value.put("sourcePosition", new Evidence.JsonString(source(position)));
                    int estimate = 512 + id.length() * 6;
                    if (recipe) {
                        Object rawName = type.getMethod("getRecipeName").invoke(ref);
                        if (!(rawName instanceof ResourceLocation recipeId) || recipeId.toString().length() > 256) {
                            opaque = true;
                            partial = true;
                            return true;
                        }
                        value.put("kind", new Evidence.JsonString("recipe"));
                        value.put("recipeId", new Evidence.JsonString(recipeId.toString()));
                        estimate += recipeId.toString().length() * 6;
                        return append(value, 1, estimate, following);
                    }
                    if (output) {
                        Object rawStack = type.getMethod("getResult").invoke(ref);
                        if (!(rawStack instanceof ItemStack stack) || stack.isEmpty()) { opaque = true; partial = true; return true; }
                        var projected = stack(stack);
                        if (projected == null) return true;
                        value.put("kind", new Evidence.JsonString("output_item"));
                        value.put("itemId", projected.values().get("itemId"));
                        if (projected.values().containsKey("components")) value.put("components", projected.values().get("components"));
                        estimate += Evidence.serialize(projected).length() * 6;
                        return append(value, 1, estimate, following);
                    }
                    if (!layout) { opaque = true; partial = true; return true; }
                    Object rawCells = type.getMethod("getLayout").invoke(ref);
                    if (!(rawCells instanceof Object[] cells)) { opaque = true; partial = true; return true; }
                    int[] resume = parseResume();
                    int[] refPosition = {position[0], position[1], position[2], 0, 0};
                    int[] cursorRef = {resume[0], resume[1], resume[2], 0, 0};
                    int cellStart = resumePosition != null && compare(refPosition, cursorRef) == 0 ? resume[3] : 0;
                    int variantStart = resumePosition != null && compare(refPosition, cursorRef) == 0 ? resume[4] : 0;
                    if (cellStart >= cells.length) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                            "Immersive Engineering layout changed; restart the lookup");
                    var cellValues = new ArrayList<Evidence.JsonValue>();
                    int semanticCount = 1;
                    int nextCell = cellStart;
                    int nextVariant = variantStart;
                    for (; nextCell < cells.length; nextCell++, nextVariant = 0) {
                        if (semanticRecords + semanticCount + 1 > MAX_RELATIONSHIP_RECORDS) break;
                        if (!budget.value()) { partial = true; break; }
                        Object cell = cells[nextCell];
                        if (cell == null) { opaque = true; partial = true; continue; }
                        var cellType = Class.forName("blusunrize.lib.manual.PositionedItemStack", false, getClass().getClassLoader());
                        if (!cellType.isInstance(cell)) { opaque = true; partial = true; continue; }
                        Object rawDisplay = cellType.getMethod("displayList").invoke(cell);
                        if (!(rawDisplay instanceof List<?> displayList)) { opaque = true; partial = true; continue; }
                        var alternatives = new ArrayList<Evidence.JsonValue>();
                        var available = MAX_RELATIONSHIP_RECORDS - semanticRecords - semanticCount - 1;
                        int copyCount = Math.min(displayList.size() - nextVariant, Math.max(0, available));
                        for (int variant = nextVariant; variant < nextVariant + copyCount; variant++) {
                            if (!budget.value()) { partial = true; break; }
                            Object rawAlternative = displayList.get(variant);
                            if (!(rawAlternative instanceof ItemStack stack)) { opaque = true; partial = true; continue; }
                            var projected = stack(stack);
                            if (projected == null) continue;
                            alternatives.add(projected);
                            semanticCount++;
                        }
                        var cellValue = new LinkedHashMap<String, Evidence.JsonValue>();
                        cellValue.put("x", new Evidence.JsonNumber(java.math.BigDecimal.valueOf((int) cellType.getMethod("x").invoke(cell))));
                        cellValue.put("y", new Evidence.JsonNumber(java.math.BigDecimal.valueOf((int) cellType.getMethod("y").invoke(cell))));
                        Object amount = cellType.getMethod("amount").invoke(cell);
                        if (amount instanceof String amountText) cellValue.put("amount", new Evidence.JsonString(amountText));
                        cellValue.put("alternatives", new Evidence.JsonArray(alternatives));
                        cellValues.add(new Evidence.JsonObject(cellValue));
                        semanticCount++;
                        if (nextVariant + copyCount < displayList.size()) {
                            nextVariant += copyCount;
                            break;
                        }
                    }
                    if (cellValues.isEmpty()) {
                        next = new int[] {position[0], position[1], position[2], nextCell, nextVariant};
                        partial = true;
                        return false;
                    }
                    value.put("kind", new Evidence.JsonString("layout"));
                    value.put("cells", new Evidence.JsonArray(cellValues));
                    estimate += 256 + Evidence.serialize(new Evidence.JsonArray(cellValues)).length() * 6;
                    int[] afterLayout = nextCell < cells.length
                            ? new int[] {position[0], position[1], position[2], nextCell, nextVariant}
                            : following;
                    boolean completed = append(value, semanticCount, estimate, afterLayout);
                    return completed && (nextCell >= cells.length);
                }
            }
            var scan = new RelationshipScan();
            if (relationshipsRequested) {
                try {
                    scan.read(manual, entry);
                } catch (Toolbox.Failure failure) {
                    throw failure;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
                    scan.partial = true;
                    scan.unavailable = true;
                }
            }
            if (!relationshipsRequested && relationshipFlags != 0) scan.partial = scan.opaque = true;
            var relationshipValues = new LinkedHashMap<String, Evidence.JsonValue>();
            if (relationshipsRequested || relationshipFlags != 0) {
                var group = new LinkedHashMap<String, Evidence.JsonValue>();
                boolean remains = scan.next != null;
                boolean groupPartial = scan.partial || scan.opaque || relationshipFlags != 0 || remains;
                group.put("coverage", new Evidence.JsonString(scan.unavailable ? "UNAVAILABLE" : groupPartial ? "PARTIAL" : "COMPLETE"));
                group.put("values", new Evidence.JsonArray(scan.values));
                if (groupPartial) group.put("detail", new Evidence.JsonString(scan.unavailable
                        ? "Immersive Engineering manual recipe declarations are unavailable or not initialized"
                        : "Immersive Engineering manual recipe declarations remain unread or partly unsupported"));
                relationshipValues.put("recipes", new Evidence.JsonObject(group));
            }
            var relationshipObject = new Evidence.JsonObject(relationshipValues);
            var resourceManager = Minecraft.getInstance().getResourceManager();
            String locale = Minecraft.getInstance().getLanguageManager().getSelected();
            Object location = entry.getClass().getMethod("getLocation").invoke(entry);
            String entryPath = (String) location.getClass().getMethod("getPath").invoke(location);
            var resource = resourceManager.getResource(ResourceLocation.fromNamespaceAndPath("immersiveengineering",
                    "manual/" + locale + "/" + entryPath + ".txt"));
            if (resource.isEmpty()) resource = resourceManager.getResource(ResourceLocation.fromNamespaceAndPath(
                    "immersiveengineering", "manual/en_us/" + entryPath + ".txt"));
            Fragment textFragment;
            if (offset < 0) textFragment = new Fragment("", id + "@done", 0, null,
                    Evidence.Coverage.COMPLETE, null);
            else if (budget.remainingBytes() < 6 || budget.exhausted()) textFragment = new Fragment("", id + "@" + offset,
                    0, Integer.toString(offset), Evidence.Coverage.PARTIAL,
                    "localized manual text continues after the relationship fragment");
            else if (resource.isEmpty()) textFragment = new Fragment("", id + "@" + offset, 0, null,
                    Evidence.Coverage.PARTIAL, "localized manual text is missing");
            else try (var stream = resource.get().open(); Reader text = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                textFragment = readText(text, id, offset, maxChars, FRAGMENT_OVERLAP_CHARS, budget);
            }
            String textNext = textFragment.nextPosition();
            String relationshipNext = scan.next == null ? null : scan.encode(scan.next);
            boolean relationshipMore = relationshipNext != null;
            String next = relationshipMore || textNext != null
                    ? "ie1|" + (relationshipMore ? relationshipNext : "done") + "|"
                    + (scan.opaque || scan.unavailable ? "1" : relationshipFlags) + "|"
                    + (textNext == null ? "done" : textNext) : null;
            boolean partial = scan.partial || scan.unavailable || scan.opaque || relationshipFlags != 0
                    || relationshipMore || textFragment.coverage() != Evidence.Coverage.COMPLETE;
            String detail = textFragment.detail() != null ? textFragment.detail()
                    : partial ? "Immersive Engineering manual relationships or localized text remain incomplete" : null;
            return new Fragment(textFragment.text(), textFragment.sourcePosition(), textFragment.overlapChars(), next,
                    partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, detail, relationshipObject);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError failure) {
            return new Fragment("", id + "@0", 0, null, Evidence.Coverage.PARTIAL,
                    "Immersive Engineering manual text could not be read");
        }
    }

    @Override
    public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        try {
            var helper = Class.forName(MANUAL_HELPER, false, ImmersiveEngineeringGuideReader.class.getClassLoader());
            var manual = helper.getMethod("getManual").invoke(null);
            var entryMap = (Map<?, ?>) manual.getClass().getField("contentsByName").get(manual);
            var entry = entryMap.get(ResourceLocation.parse(id));
            if (entry == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                    "Immersive Engineering manual entry is no longer visible");
            var node = entry.getClass().getMethod("getTreeNode").invoke(entry);
            var abstractNode = Class.forName("blusunrize.lib.manual.Tree$AbstractNode", false,
                    ImmersiveEngineeringGuideReader.class.getClassLoader());
            if (node == null || !(boolean) manual.getClass().getMethod("showNodeInList", abstractNode).invoke(manual, node)) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                        "Immersive Engineering manual entry is no longer visible");
            }
            if (!budget.document() || !budget.value() || !budget.bytes(id.length() * 6 + 1024)) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Immersive Engineering entry exceeds the remaining read budget");
            }
            Object rawTitle = entry.getClass().getMethod("getTitle").invoke(entry);
            String title = rawTitle instanceof String value ? value : null;
            if (title == null || title.isBlank()) title = id;
            boolean truncated = title.length() > MAX_TITLE_LENGTH;
            if (truncated) title = title.substring(0, MAX_TITLE_LENGTH);
            var candidate = new Candidate(id, title, id, List.of("Immersive Engineering manual entry " + id),
                    truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE);
            return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Immersive Engineering manual entry is unavailable");
        }
    }

    static Fragment readText(Reader source, String id, int offset, int maxChars, int overlapLimit,
                             Toolbox.DocumentBudget budget) {
        if (offset < 0 || offset > MAX_SEEK_CHARS) throw new Toolbox.Failure(
                Toolbox.Code.INVALID_CURSOR, "invalid Immersive Engineering text position");
        try {
            if (maxChars < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                    "Immersive Engineering fragment limit must be positive");
            int requestedOverlap = Math.min(Math.max(0, overlapLimit), offset);
            int overlap = Math.min(requestedOverlap,
                    Math.min(maxChars - 1, Math.max(0, budget.remainingBytes() / 6 - 1)));
            boolean overlapShortened = overlap < requestedOverlap;
            int seek = offset - overlap;
            char[] buffer = new char[Math.min(1024, Math.max(1, seek))];
            int skipped = 0;
            while (skipped < seek) {
                if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Immersive Engineering text seek exceeds the read budget");
                int count = source.read(buffer, 0, Math.min(buffer.length, seek - skipped));
                if (count < 0) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                        "Immersive Engineering text changed; restart the lookup");
                if (count == 0) continue;
                skipped += count;
            }
            int maxNewChars = maxChars - overlap;
            int boundedNewChars = Math.min(Math.min(maxNewChars, MAX_SEEK_CHARS - offset),
                    Math.max(0, budget.remainingBytes() / 6 - overlap));
            if (boundedNewChars == 0 && MAX_SEEK_CHARS > offset && budget.remainingBytes() < (overlap + 1) * 6) {
                budget.bytes(6);
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Immersive Engineering text exceeds the remaining read budget");
            }
            if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Immersive Engineering text exceeds the remaining read budget");
            int requested = overlap + boundedNewChars;
            if (!budget.bytes(requested * 6)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Immersive Engineering text exceeds the remaining read budget");
            char[] part = new char[requested];
            int count = 0;
            while (count < requested) {
                int read = source.read(part, count, requested - count);
                if (read < 0) break;
                if (read == 0) continue;
                count += read;
            }
            if (count < overlap) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Immersive Engineering text changed; restart the lookup");
            int newChars = Math.max(0, count - overlap);
            boolean hasMoreText = false;
            if (count == requested) {
                if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Immersive Engineering text exceeds the read budget");
                hasMoreText = source.read() >= 0;
            }
            boolean seekLimitReached = offset + newChars >= MAX_SEEK_CHARS && hasMoreText;
            boolean truncated = hasMoreText;
            String text = new String(part, 0, count);
            if (newChars > 0 && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                text = text.substring(0, text.length() - 1);
                newChars--;
            }
            int nextOffset = offset + newChars;
            String next = truncated && !seekLimitReached ? Integer.toString(nextOffset) : null;
            boolean formattingPartial = text.contains("<") || text.contains(">") || text.contains("\\\n");
            boolean partial = truncated || overlapShortened || seekLimitReached || formattingPartial;
            return new Fragment(text, id + "@" + offset, overlap, next,
                    partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    formattingPartial ? "manual formatting tokens are preserved without expansion"
                            : seekLimitReached ? "manual text exceeded the bounded seek limit"
                            : overlapShortened ? "query overlap was shortened to the fragment budget"
                            : truncated ? "bounded localized manual text fragment" : null);
        } catch (IOException failure) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Immersive Engineering manual text could not be read");
        }
    }

}
