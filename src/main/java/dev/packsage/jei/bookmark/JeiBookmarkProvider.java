package dev.packsage.jei.bookmark;

import dev.packsage.jei.JeiIndex;
import dev.packsage.jei.JeiProjection;
import dev.packsage.jei.JeiRuntimeCache;
import dev.packsage.jei.JeiRuntimeIndex;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

public final class JeiBookmarkProvider implements KnowledgeProvider {
    /** The ready JEI index with the bookmark access built on the same runtime. */
    public record Session(JeiIndex index, JeiBookmarks bookmarks) { }

    private final String version;
    private final Supplier<Optional<Session>> session;
    private final Executor executor;
    private final Supplier<Evidence.Scope> currentScope;

    public JeiBookmarkProvider(Supplier<Optional<Session>> session, Executor clientExecutor, Supplier<Evidence.Scope> scope) {
        this.session = session;
        this.executor = clientExecutor;
        this.currentScope = scope;
        this.version = "19.53";
    }

    public JeiBookmarkProvider(Minecraft client, Executor clientExecutor, Supplier<Evidence.Scope> scope) {
        var mods = ModList.get();
        this.version = mods == null ? "unknown" : mods.getModContainerById("jei")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("unknown");
        this.executor = clientExecutor;
        this.currentScope = scope;
        this.session = new JeiRuntimeCache<>(snapshot -> {
            var index = new JeiRuntimeIndex(snapshot);
            return new Session(index, JeiBookmarks.of(snapshot.runtime(), index));
        });
    }

    @Override public String id() { return "jei_bookmarks"; }
    @Override public Metadata metadata() { return new Metadata("jei_bookmarks", List.of(), false, List.of()); }
    @Override public boolean enabled() { return session.get().isPresent(); }
    @Override public ProviderStatus status() { return new ProviderStatus(enabled() ? Availability.AVAILABLE : Availability.UNAVAILABLE, version, null); }

    @Override public CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request, Toolbox.Context context) {
        if (!request.tool().equals("bookmark")) return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, request.tool()));
        try {
            var arguments = request.arguments().values();
            JeiBookmarks.Action action = null;
            if (arguments.get("action") instanceof Evidence.JsonString string) {
                try { action = JeiBookmarks.Action.valueOf(string.value().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { }
            }
            if (action == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "action must be add, remove or check");
            var item = arguments.get("item");
            var recipe = arguments.get("recipe");
            boolean hasItem = item != null && !(item instanceof Evidence.JsonNull);
            boolean hasRecipe = recipe != null && !(recipe instanceof Evidence.JsonNull);
            if (hasItem == hasRecipe) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "give exactly one of item or recipe");
            String target;
            String itemId = null;
            String components = "{}";
            Toolbox.Reference observed = null;
            if (hasItem) {
                if (!(item instanceof Evidence.JsonObject object) || !(object.values().get("id") instanceof Evidence.JsonString id))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "item needs an id");
                var parsed = ResourceLocation.tryParse(id.value());
                if (parsed == null || !parsed.toString().equals(id.value()))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "item id must be canonical");
                var raw = object.values().get("components");
                if (raw != null && !(raw instanceof Evidence.JsonNull)) {
                    if (!(raw instanceof Evidence.JsonObject)) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "components must be an object");
                    components = Evidence.serialize(raw);
                }
                itemId = id.value();
                target = itemId;
            } else {
                if (!(recipe instanceof Evidence.JsonString string))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "recipe must be a reference");
                target = string.value();
                observed = context.references().get(target);
                if (observed == null || !observed.scope().equals(request.scope()) || !observed.providerId().equals("jei")
                        || observed.kind() != Evidence.RecordKind.RECIPE)
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "recipe reference was not observed in this scope; repeat search_recipe");
            }
            var finalAction = action;
            var finalItem = itemId;
            var finalComponents = components;
            var finalObserved = observed;
            var scope = request.scope();
            return CompletableFuture.supplyAsync(() -> {
                if (!scope.equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed");
                var current = session.get().orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "JEI bookmarks unavailable"));
                boolean before;
                boolean after;
                if (finalItem != null) {
                    before = current.bookmarks().item(JeiBookmarks.Action.CHECK, finalItem, finalComponents);
                    after = finalAction == JeiBookmarks.Action.CHECK ? before : current.bookmarks().item(finalAction, finalItem, finalComponents);
                } else {
                    var ref = resolve(current.index(), finalObserved.sourceHandle());
                    before = current.bookmarks().recipe(JeiBookmarks.Action.CHECK, ref);
                    after = finalAction == JeiBookmarks.Action.CHECK ? before : current.bookmarks().recipe(finalAction, ref);
                }
                if (!scope.equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed");
                var name = finalAction.name().toLowerCase(Locale.ROOT);
                var payload = new LinkedHashMap<String, Evidence.JsonValue>();
                payload.put("action", new Evidence.JsonString(name));
                payload.put("target", new Evidence.JsonString(target));
                payload.put("bookmarked", new Evidence.JsonBoolean(after));
                payload.put("changed", new Evidence.JsonBoolean(before != after));
                var source = new Evidence.Source(scope, id(), version, "bookmark|" + name + "|" + target, scope.requestGeneration(),
                        Instant.now(), Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.VIEWER, List.of("jei"));
                return new Evidence.Result(List.of(new Evidence.Record(source, Evidence.RecordKind.OTHER, "JEI bookmark",
                        new Evidence.JsonObject(payload), List.of())), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null);
            }, executor);
        } catch (Toolbox.Failure failure) { return CompletableFuture.failedFuture(failure); }
    }

    private static JeiIndex.RecipeRef resolve(JeiIndex index, String handle) {
        var parsed = JeiProjection.destination(handle).flatMap(JeiProjection::parseHandle)
                .orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "invalid JEI recipe reference"));
        JeiIndex.RecipeRef ref = null;
        if (parsed.recipeId() != null) ref = index.byRecipeId(parsed.categoryUid(), parsed.recipeId()).orElse(null);
        else if (parsed.generation() == index.generation() && parsed.ordinal() >= 0
                && parsed.ordinal() < index.inCategory(parsed.categoryUid()).size())
            ref = new JeiIndex.RecipeRef(parsed.categoryUid(), parsed.ordinal());
        if (ref == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "JEI recipe is no longer available; repeat search_recipe");
        return ref;
    }

    @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "unsupported JEI bookmark query"));
    }

    @Override public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "JEI bookmark navigation unavailable"));
    }

    @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "JEI bookmark navigation unavailable");
    }
}
