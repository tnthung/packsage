package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Shared bounded discovery and fresh evidence reads for installed guide sources. */
public final class GuideDocumentProvider implements KnowledgeProvider {
    private static final int DEFAULT_LIMIT = 16;
    private static final int MAX_LIMIT = 64;
    private static final int MAX_CANDIDATES_PER_PAGE = 16;
    private static final int MAX_CANDIDATES_PER_CALL = 256;
    private static final int MAX_PAGE_READS_PER_CALL = 128;
    private static final int MAX_FRAGMENT_CHARS = 2048;
    private static final int MAX_TITLE_CHARS = 1024;
    private static final int MAX_SOURCE_TITLE_CHARS = 4096;
    private static final int MAX_ID_CHARS = 1024;
    private static final int MAX_POSITION_CHARS = 2048;
    private static final int MAX_PROVENANCE_ITEMS = 8;
    private static final int MAX_PROVENANCE_CHARS = 256;
    private static final int MAX_DETAIL_CHARS = 2048;
    private static final int MAX_RELATIONSHIP_BYTES = 8 * 1024;
    private static final int MAX_RELATIONSHIP_RECORDS = 16;
    private static final int MAX_RELATIONSHIP_DEPTH = 64;
    private static final java.util.Set<String> RELATIONSHIP_GROUPS = java.util.Set.of("items", "recipes", "prerequisites");
    private static final java.util.Set<String> RELATIONSHIP_IDENTITIES = java.util.Set.of("itemId", "tagId", "recipeId",
            "fluidId", "entryId", "advancementId", "categoryId", "modId");
    private static final int MAX_CURSORS = 128;
    private static final long CURSOR_TTL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);

    private record Search(String query, List<String> types, int limit, String cursor) { }

    private record DocumentCursor(String query, List<String> types, Evidence.Scope scope, String sourceIdentity,
                                  String afterPosition, String candidateId, String fragmentPosition,
                                  long createdAtNanos) { }

    private record CursorPosition(String afterPosition, String candidateId, String fragmentPosition) { }

    private final GuideDocumentReader reader;
    private final Supplier<Evidence.Scope> currentScope;
    private final Executor clientExecutor;
    private final int maxCandidatesPerCall;
    private final Map<String, DocumentCursor> documentCursors = new LinkedHashMap<>(MAX_CURSORS, 0.75f, true);

    public GuideDocumentProvider(GuideDocumentReader reader, Supplier<Evidence.Scope> currentScope, Executor clientExecutor) {
        this(reader, currentScope, clientExecutor, MAX_CANDIDATES_PER_CALL);
    }

    public GuideDocumentProvider(GuideDocumentReader reader, Supplier<Evidence.Scope> currentScope, Executor clientExecutor,
                                 int maxCandidatesPerCall) {
        this.reader = Objects.requireNonNull(reader);
        this.currentScope = Objects.requireNonNull(currentScope);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        if (maxCandidatesPerCall < 1 || maxCandidatesPerCall > MAX_CANDIDATES_PER_CALL) {
            throw new IllegalArgumentException("guide candidate bound must be from 1 to 256");
        }
        this.maxCandidatesPerCall = maxCandidatesPerCall;
        if (reader.providerId() == null || reader.providerId().isBlank() || reader.providerId().length() > 128) {
            throw new IllegalArgumentException("guide provider id is invalid");
        }
        if (reader.version() == null || reader.version().isBlank() || reader.version().length() > 128) {
            throw new IllegalArgumentException("guide provider version is invalid");
        }
    }

    @Override public String id() { return reader.providerId(); }

    @Override public Toolbox.Metadata metadata() {
        return new Toolbox.Metadata(id(), List.of("guide"), false, List.of(), Map.of());
    }

    @Override public ProviderStatus status() {
        return new ProviderStatus(Availability.AVAILABLE, reader.version(), null);
    }

    @Override
    public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target,
                                                                         Evidence.Scope scope) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(scope);
        if (!id().equals(target.providerId())) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    requireScope(scope);
                    var refreshed = reader.refreshNavigation(target, scope);
                    requireScope(scope);
                    return refreshed != null && id().equals(refreshed.providerId()) && scope.equals(refreshed.scope())
                            ? refreshed : null;
                } catch (RuntimeException | LinkageError failure) {
                    return null;
                }
            }, clientExecutor);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(scope);
        if (query.operation() == Evidence.Operation.SEARCH) {
            if (query.cursor() != null && query.cursorScope() != null && !scope.equals(query.cursorScope())) {
                return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.STALE_SCOPE,
                        "guide continuation scope changed"));
            }
            var arguments = new LinkedHashMap<>(query.arguments().values());
            Evidence.JsonValue text = arguments.get("query");
            if (!(text instanceof Evidence.JsonString) && arguments.get("text") instanceof Evidence.JsonString value) text = value;
            if (text instanceof Evidence.JsonString value) arguments.put("query", value);
            arguments.put("filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString(id()),
                    "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("guide"))))));
            arguments.put("limit", new Evidence.JsonNumber(BigDecimal.valueOf(query.limit())));
            if (query.cursor() != null) arguments.put("cursor", new Evidence.JsonString(query.cursor()));
            var request = new PackSageTools.ReadRequest("search_document", new Evidence.JsonObject(arguments), scope);
            return searchDocument(request, new Toolbox.Context(currentScope, Map.of(), new AdapterRegistry(),
                    new Toolbox.DocumentBudget(), null));
        }
        if (query.operation() == Evidence.Operation.GET) {
            var idValue = query.arguments().values().get("id");
            if (!(idValue instanceof Evidence.JsonString handle) || handle.value().isBlank() || handle.value().length() > MAX_ID_CHARS) {
                return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                        "guide get requires an id"));
            }
            return schedule(() -> {
                requireScope(scope);
                var sourceIdentity = sourceIdentity(scope);
                var budget = new Toolbox.DocumentBudget();
                if (!budget.document()) return result(List.of(), Evidence.Coverage.PARTIAL, null, "guide evidence budget exhausted");
                int maxChars = Math.min(MAX_FRAGMENT_CHARS, budget.remainingBytes() / 4);
                GuideDocumentReader.ResolvedDocument resolved;
                try {
                    resolved = reader.resolve(handle.value(), maxChars, budget);
                } catch (Toolbox.Failure failure) {
                    throw failure;
                } catch (RuntimeException | LinkageError failure) {
                    throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                            "guide evidence resolution failed: " + failure);
                }
                validateFragment(resolved.fragment(), null, maxChars);
                if (!handle.value().equals(resolved.candidate().id())) throw new Toolbox.Failure(
                        Toolbox.Code.INVALID_REFERENCE, "guide source resolved a different identity");
                var record = documentRecord(scope, resolved.candidate(), resolved.fragment());
                requireStableRead(scope, sourceIdentity);
                String detail = resolved.fragment().detail();
                if (resolved.fragment().nextPosition() != null) detail = detail == null || detail.isBlank()
                        ? "guide evidence is a bounded current fragment"
                        : boundedDetail(detail + "; guide evidence is a bounded current fragment");
                return result(List.of(record), record.source().coverage(), null, detail);
            });
        }
        return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED,
                "guide providers support search and current document reads"));
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!id().equals(target.providerId()) || !scope.equals(target.scope()) || !scope.equals(currentScope.get())) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "guide target is outside the active scope");
        }
        try {
            return reader.open(target, scope);
        } catch (RuntimeException | LinkageError failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, "guide navigation failed: " + failure);
        }
    }

    @Override
    public CompletionStage<Evidence.Result> searchDocument(PackSageTools.ReadRequest request, Toolbox.Context context) {
        Search search;
        try {
            search = parseSearch(request);
        } catch (Toolbox.Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return schedule(() -> searchLive(request.scope(), context, search));
    }

    @Override
    public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Toolbox.Context context) {
        return schedule(() -> {
            requireScope(request.scope());
            var reference = context.reference(request);
            if (!id().equals(reference.providerId())) {
                throw new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "guide reference belongs to another provider");
            }
            if (reference.kind() != Evidence.RecordKind.GUIDE) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "expected an observed guide document reference");
            }
            if (reference.sourceHandle().isBlank() || reference.sourceHandle().length() > MAX_ID_CHARS) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "guide reference identity is invalid");
            }
            var sourceIdentity = sourceIdentity(request.scope());
            var budget = context.documentBudget();
            if (!budget.document()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide evidence budget exhausted");
            int maxChars = Math.min(MAX_FRAGMENT_CHARS, budget.remainingBytes() / 4);
            GuideDocumentReader.ResolvedDocument resolved;
            try {
                resolved = reader.resolve(reference.sourceHandle(), maxChars, budget);
            } catch (Toolbox.Failure failure) {
                throw failure;
            } catch (RuntimeException | LinkageError failure) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "guide evidence resolution failed: " + failure);
            }
            validateFragment(resolved.fragment(), null, maxChars);
            if (!reference.sourceHandle().equals(resolved.candidate().id())) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "guide source resolved a different identity");
            }
            var record = documentRecord(request.scope(), resolved.candidate(), resolved.fragment());
            requireStableRead(request.scope(), sourceIdentity);
            String detail = resolved.fragment().detail();
            if (resolved.fragment().nextPosition() != null) detail = detail == null || detail.isBlank()
                    ? "guide evidence is a bounded current fragment"
                    : boundedDetail(detail + "; guide evidence is a bounded current fragment");
            return result(List.of(record), record.source().coverage(), null, detail);
        });
    }

    private Search parseSearch(PackSageTools.ReadRequest request) {
        if (!"search_document".equals(request.tool())) {
            throw new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "guide document search");
        }
        var arguments = request.arguments().values();
        if (!(arguments.get("query") instanceof Evidence.JsonString query)) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "search_document requires a literal query");
        }
        if (query.value().length() > GuideDocumentReader.MAX_QUERY_LENGTH) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "document query exceeds 512 characters");
        }
        var filterValue = arguments.get("filter");
        Map<String, Evidence.JsonValue> filter = filterValue instanceof Evidence.JsonObject object ? object.values() : Map.of();
        if (filterValue != null && !(filterValue instanceof Evidence.JsonObject) && !(filterValue instanceof Evidence.JsonNull)) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "document filter must be an object or null");
        }
        if (filter.keySet().stream().anyMatch(key -> !key.equals("provider") && !key.equals("types"))) {
            throw new Toolbox.Failure(Toolbox.Code.UNSUPPORTED_FILTER, "guide document filter contains an unsupported field");
        }
        if (!(filter.get("provider") instanceof Evidence.JsonString provider)) {
            throw new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "guide providers require a provider-qualified document search");
        }
        if (!id().equals(provider.value())) throw new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "search belongs to another provider");
        var types = new ArrayList<String>();
        if (filter.get("types") instanceof Evidence.JsonArray selectedTypes) {
            if (selectedTypes.values().size() > 16) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                    "document type filter is too large");
            for (var selectedType : selectedTypes.values()) {
                if (!(selectedType instanceof Evidence.JsonString value) || value.value().isBlank() || value.value().length() > 64) {
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "document types must be non-empty strings");
                }
                types.add(value.value());
            }
        } else if (filter.containsKey("types") && !(filter.get("types") instanceof Evidence.JsonNull)) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "document types must be an array or null");
        }
        if (!types.isEmpty() && !types.contains("guide")) {
            throw new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "guide provider does not contribute the requested document types");
        }
        int limit = DEFAULT_LIMIT;
        if (arguments.get("limit") instanceof Evidence.JsonNumber number) {
            try {
                limit = number.value().intValueExact();
            } catch (ArithmeticException invalidLimit) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "document limit must be an integer from 1 to 64");
            }
        } else if (arguments.containsKey("limit") && !(arguments.get("limit") instanceof Evidence.JsonNull)) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "document limit must be an integer");
        }
        if (limit < 1 || limit > MAX_LIMIT) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                "document limit must be from 1 to 64");
        String cursor = null;
        if (arguments.get("cursor") instanceof Evidence.JsonString value) {
            cursor = value.value();
            if (cursor.isBlank() || cursor.length() > 256) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "guide continuation is invalid");
        } else if (arguments.containsKey("cursor") && !(arguments.get("cursor") instanceof Evidence.JsonNull)) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "document cursor must be a string");
        }
        return new Search(query.value(), List.copyOf(types), limit, cursor);
    }

    private Evidence.Result searchLive(Evidence.Scope scope, Toolbox.Context context, Search search) {
        requireScope(scope);
        var sourceIdentity = sourceIdentity(scope);
        var position = search.cursor() == null ? new CursorPosition(null, null, null)
                : decodeCursor(search, scope, sourceIdentity);
        var afterPosition = position.afterPosition();
        var activeCandidateId = position.candidateId();
        var fragmentPosition = position.fragmentPosition();
        var query = search.query().toLowerCase(Locale.ROOT);
        var records = new ArrayList<Evidence.Record>();
        var details = new ArrayList<String>();
        var coverage = Evidence.Coverage.COMPLETE;
        int inspectedCandidates = 0;
        int pageReads = 0;
        String nextCursor = null;
        boolean sourceComplete = false;
        boolean stopped = false;

        while (!stopped) {
            if (++pageReads > MAX_PAGE_READS_PER_CALL || inspectedCandidates >= maxCandidatesPerCall
                    || context.documentBudget().exhausted() || context.documentBudget().remainingBytes() < 4) {
                coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                details.add("guide traversal reached its per-call work bound");
                if (activeCandidateId != null) nextCursor = saveCursor(search, scope, sourceIdentity,
                        afterPosition, activeCandidateId, fragmentPosition);
                else nextCursor = saveCursor(search, scope, sourceIdentity, afterPosition, null, null);
                break;
            }
            int pageLimit = Math.min(MAX_CANDIDATES_PER_PAGE, maxCandidatesPerCall - inspectedCandidates);
            GuideDocumentReader.CandidatePage page;
            try {
                page = reader.candidates(search.query(), afterPosition, pageLimit, context.documentBudget());
            } catch (Toolbox.Failure failure) {
                if (failure.code() != Toolbox.Code.UNAVAILABLE && failure.code() != Toolbox.Code.EXTRACTION_FAILED) throw failure;
                coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                details.add(boundedDetail(failure.getMessage()));
                break;
            } catch (RuntimeException | LinkageError failure) {
                coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                details.add(boundedDetail("guide candidate traversal failed: " + failure));
                break;
            }
            if (page == null || page.candidates().size() > pageLimit) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader exceeded its candidate page limit");
            }
            try {
                validateCoverage(page.coverage());
            } catch (Toolbox.Failure failure) {
                coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                details.add(boundedDetail(failure.getMessage()));
                break;
            }
            inspectedCandidates += page.candidates().size();
            coverage = mergeCoverage(coverage, page.coverage());
            if (page.detail() != null) details.add(boundedDetail(page.detail()));
            boolean stalledPage = Objects.equals(page.nextPosition(), afterPosition);
            boolean budgetBlocked = context.documentBudget().exhausted()
                    || context.documentBudget().remainingBytes() < 4;
            if (page.more() && page.nextPosition() == null && afterPosition != null) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "guide reader reset its source position");
            }
            if (page.more() && stalledPage && (!page.candidates().isEmpty() || !budgetBlocked)) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "guide reader did not advance its source position");
            }
            if (page.candidates().isEmpty()) {
                if (!page.more()) {
                    sourceComplete = page.coverage() == Evidence.Coverage.COMPLETE;
                    break;
                }
                if (stalledPage) {
                    coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                    if (page.detail() == null) details.add("guide source budget stopped before the next candidate");
                    nextCursor = saveCursor(search, scope, sourceIdentity, afterPosition, null, null);
                    stopped = true;
                    break;
                }
                afterPosition = page.nextPosition();
                continue;
            }

            for (int candidateIndex = 0; candidateIndex < page.candidates().size() && !stopped; candidateIndex++) {
                var candidate = page.candidates().get(candidateIndex);
                try {
                    validateCandidate(candidate);
                } catch (Toolbox.Failure failure) {
                    coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                    details.add(boundedDetail(failure.getMessage()));
                    stopped = true;
                    break;
                }
                if (Objects.equals(candidate.sourcePosition(), afterPosition)) {
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "guide reader repeated a candidate source position");
                }
                if (activeCandidateId != null) {
                    if (candidateIndex != 0 || !activeCandidateId.equals(candidate.id())) {
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                "guide continuation candidate changed; restart the search");
                    }
                    activeCandidateId = null;
                }
                if (!context.documentBudget().document()) {
                    coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                    details.add("guide document budget exhausted");
                    nextCursor = saveCursor(search, scope, sourceIdentity, afterPosition,
                            candidate.id(), fragmentPosition);
                    stopped = true;
                    break;
                }
                coverage = mergeCoverage(coverage, candidate.coverage());
                var title = boundedTitle(candidate.title(), details);
                var titleMatches = query.isEmpty() || title.toLowerCase(Locale.ROOT).contains(query);
                while (true) {
                    if (context.documentBudget().exhausted() || context.documentBudget().remainingBytes() < 4) {
                        coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                        details.add("guide extraction budget exhausted");
                        nextCursor = saveCursor(search, scope, sourceIdentity, afterPosition,
                                candidate.id(), fragmentPosition);
                        stopped = true;
                        break;
                    }
                    int maxChars = Math.min(MAX_FRAGMENT_CHARS, context.documentBudget().remainingBytes() / 4);
                    GuideDocumentReader.Fragment fragment;
                    try {
                        fragment = reader.readFragment(candidate.id(), fragmentPosition, maxChars, context.documentBudget());
                    } catch (Toolbox.Failure failure) {
                        if (failure.code() == Toolbox.Code.EXTRACTION_FAILED && context.documentBudget().exhausted()) {
                            coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                            details.add(boundedDetail(failure.getMessage()));
                            nextCursor = saveCursor(search, scope, sourceIdentity, afterPosition,
                                    candidate.id(), fragmentPosition);
                            stopped = true;
                            break;
                        }
                        if (failure.code() != Toolbox.Code.UNAVAILABLE && failure.code() != Toolbox.Code.EXTRACTION_FAILED) throw failure;
                        coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                        details.add(boundedDetail(failure.getMessage()));
                        stopped = true;
                        break;
                    } catch (RuntimeException | LinkageError failure) {
                        coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                        details.add(boundedDetail("guide fragment extraction failed: " + failure));
                        stopped = true;
                        break;
                    }
                    try {
                        validateFragment(fragment, fragmentPosition, maxChars);
                    } catch (Toolbox.Failure failure) {
                        coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                        details.add(boundedDetail(failure.getMessage()));
                        stopped = true;
                        break;
                    }
                    var fragmentCoverage = mergeCoverage(fragment.coverage(), validateRelationships(fragment.relationships()));
                    coverage = mergeCoverage(coverage, fragmentCoverage);
                    if (fragment.detail() != null) details.add(boundedDetail(fragment.detail()));
                    var text = fragment.text();
                    var matches = titleMatches || query.isEmpty() || containsNewText(text.toLowerCase(Locale.ROOT), query,
                            fragment.overlapChars()) || relationshipMatches(fragment.relationships(), query);
                    if (matches) {
                        var record = documentRecord(scope, candidate, fragment);
                        records.add(record);
                    }
                    if (fragment.nextPosition() != null) {
                        fragmentPosition = fragment.nextPosition();
                        if (records.size() >= search.limit()) {
                            coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                            nextCursor = saveCursor(search, scope, sourceIdentity, afterPosition,
                                    candidate.id(), fragmentPosition);
                            stopped = true;
                            break;
                        }
                        continue;
                    }
                    fragmentPosition = null;
                    afterPosition = candidate.sourcePosition();
                    if (records.size() >= search.limit()) {
                        if (candidateIndex + 1 < page.candidates().size() || page.more()) {
                            coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                            nextCursor = saveCursor(search, scope, sourceIdentity, afterPosition, null, null);
                        } else sourceComplete = page.coverage() == Evidence.Coverage.COMPLETE;
                        stopped = true;
                    }
                    break;
                }
            }
            if (stopped) break;
            if (page.more()) {
                afterPosition = page.nextPosition();
            } else {
                sourceComplete = page.coverage() == Evidence.Coverage.COMPLETE;
                break;
            }
        }

        requireStableRead(scope, sourceIdentity);
        if (!sourceComplete && nextCursor == null && coverage == Evidence.Coverage.COMPLETE) {
            coverage = Evidence.Coverage.PARTIAL;
        }
        var detail = details.isEmpty() ? null : String.join("; ", details.stream().distinct().toList());
        return result(records, coverage, nextCursor, detail);
    }

    private Evidence.Result result(List<Evidence.Record> records, Evidence.Coverage coverage, String nextCursor, String detail) {
        var outcome = switch (coverage) {
            case COMPLETE -> Evidence.Outcome.COMPLETE;
            case PARTIAL -> Evidence.Outcome.PARTIAL;
            case OPAQUE, UNAVAILABLE -> Evidence.Outcome.UNKNOWN;
            case ERROR -> Evidence.Outcome.ERROR;
        };
        return new Evidence.Result(records, coverage, outcome, nextCursor,
                detail == null || detail.isBlank() ? null : boundedDetail(detail));
    }

    private Evidence.Record documentRecord(Evidence.Scope scope, GuideDocumentReader.Candidate candidate,
                                           GuideDocumentReader.Fragment fragment) {
        validateCandidate(candidate);
        var coverage = mergeCoverage(candidate.coverage(),
                mergeCoverage(fragment.coverage(), validateRelationships(fragment.relationships())));
        if (fragment.nextPosition() != null) coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
        var title = boundedTitle(candidate.title(), new ArrayList<>());
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("id", new Evidence.JsonString(candidate.id()));
        payload.put("title", new Evidence.JsonString(title));
        payload.put("text", new Evidence.JsonString(fragment.text()));
        payload.put("fragment", new Evidence.JsonString(fragment.sourcePosition()));
        if (!fragment.relationships().values().isEmpty()) payload.put("relationships", fragment.relationships());
        payload.put("complete", new Evidence.JsonBoolean(fragment.nextPosition() == null && coverage == Evidence.Coverage.COMPLETE));
        payload.put("provenance", new Evidence.JsonArray(candidate.provenance().stream().limit(MAX_PROVENANCE_ITEMS)
                .map(value -> (Evidence.JsonValue) new Evidence.JsonString(value.length() > MAX_PROVENANCE_CHARS
                        ? value.substring(0, MAX_PROVENANCE_CHARS) : value)).toList()));
        var source = new Evidence.Source(scope, id(), reader.version(), candidate.id(), scope.requestGeneration(),
                Instant.now(), Evidence.Activity.ACTIVE, coverage, Evidence.Authority.INSTALLED_DOCUMENTATION,
                candidate.provenance().stream().limit(MAX_PROVENANCE_ITEMS)
                        .map(value -> value.length() > MAX_PROVENANCE_CHARS ? value.substring(0, MAX_PROVENANCE_CHARS) : value).toList());
        var targets = reader.navigationTargets(scope, candidate);
        if (targets == null || targets.size() > 16 || targets.stream().anyMatch(target -> target == null
                || !scope.equals(target.scope()) || !id().equals(target.providerId())
                || !candidate.id().equals(target.sourceHandle()) || target.targetId().isBlank()
                || target.targetId().length() > MAX_ID_CHARS)) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned invalid navigation targets");
        }
        return new Evidence.Record(source, Evidence.RecordKind.GUIDE, title,
                new Evidence.JsonObject(payload), targets);
    }

    private void validateCandidate(GuideDocumentReader.Candidate candidate) {
        if (candidate == null || candidate.id().isBlank() || candidate.id().length() > MAX_ID_CHARS
                || candidate.title().length() > MAX_SOURCE_TITLE_CHARS || candidate.sourcePosition() == null
                || candidate.sourcePosition().length() > MAX_POSITION_CHARS
                || candidate.provenance().size() > MAX_PROVENANCE_ITEMS
                || candidate.provenance().stream().anyMatch(value -> value == null || value.length() > MAX_PROVENANCE_CHARS)) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned unbounded candidate metadata");
        }
        validateCoverage(candidate.coverage());
    }

    private void validateFragment(GuideDocumentReader.Fragment fragment, String previousPosition, int maxChars) {
        if (fragment == null || fragment.text().length() > maxChars
                || fragment.sourcePosition().length() > MAX_POSITION_CHARS
                || fragment.nextPosition() != null && fragment.nextPosition().length() > MAX_POSITION_CHARS
                || fragment.overlapChars() > GuideDocumentReader.FRAGMENT_OVERLAP_CHARS
                || previousPosition == null && fragment.overlapChars() != 0
                || previousPosition != null && Objects.equals(fragment.nextPosition(), previousPosition)) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned an invalid or unbounded fragment");
        }
        validateCoverage(fragment.coverage());
        validateRelationships(fragment.relationships());
    }

    private Evidence.Coverage validateRelationships(Evidence.JsonObject relationships) {
        if (relationships.values().isEmpty()) return Evidence.Coverage.COMPLETE;
        if (relationships.values().keySet().stream().anyMatch(key -> !RELATIONSHIP_GROUPS.contains(key))) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned an unknown relationship group");
        }
        int[] limits = {0, 0};
        int valueCount = 0;
        var coverage = Evidence.Coverage.COMPLETE;
        for (var group : relationships.values().values()) {
            if (!(group instanceof Evidence.JsonObject object)
                    || !(object.values().get("coverage") instanceof Evidence.JsonString coverageValue)
                    || !(object.values().get("values") instanceof Evidence.JsonArray values)
                    || object.values().keySet().stream().anyMatch(key -> !java.util.Set.of("coverage", "values", "detail").contains(key))) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned a malformed relationship group");
            }
            Evidence.Coverage groupCoverage;
            try {
                groupCoverage = Evidence.Coverage.valueOf(coverageValue.value());
            } catch (IllegalArgumentException failure) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned invalid relationship coverage");
            }
            if (groupCoverage == Evidence.Coverage.ERROR) validateCoverage(groupCoverage);
            if (object.values().containsKey("detail")
                    && (!(object.values().get("detail") instanceof Evidence.JsonString detail) || detail.value().length() > MAX_DETAIL_CHARS)) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned invalid relationship detail");
            }
            if ((groupCoverage == Evidence.Coverage.PARTIAL || groupCoverage == Evidence.Coverage.OPAQUE)
                    && (!(object.values().get("detail") instanceof Evidence.JsonString detail) || detail.value().isBlank())) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "incomplete guide relationship group requires an explanation");
            }
            valueCount += values.values().size();
            if (valueCount > MAX_RELATIONSHIP_RECORDS) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned too many relationship records");
            }
            for (var value : values.values()) {
                if (!(value instanceof Evidence.JsonObject)) {
                    throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned a malformed relationship value");
                }
            }
            coverage = mergeCoverage(coverage, groupCoverage == Evidence.Coverage.UNAVAILABLE
                    ? Evidence.Coverage.PARTIAL : groupCoverage);
        }
        validateRelationshipValue(relationships, 0, limits, null, false, false);
        return coverage;
    }

    private void validateRelationshipValue(Evidence.JsonValue value, int depth, int[] limits,
                                           String groupName, boolean countArrayElements, boolean inComponents) {
        if (depth > MAX_RELATIONSHIP_DEPTH) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned an over-deep relationship value");
        }
        if (value instanceof Evidence.JsonObject object) {
            limits[0] += 2;
            if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "guide reader returned oversized relationships");
            boolean first = true;
            for (var entry : object.values().entrySet()) {
                if (!first) {
                    limits[0]++;
                    if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                            "guide reader returned oversized relationships");
                }
                first = false;
                addRelationshipStringBytes(entry.getKey(), limits);
                limits[0]++;
                if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "guide reader returned oversized relationships");
                var nestedGroup = depth == 0 ? entry.getKey() : groupName;
                boolean countElements = !inComponents && (entry.getKey().equals("values")
                        || entry.getKey().equals("alternatives")
                        || nestedGroup.equals("prerequisites") && entry.getKey().equals("children"));
                validateRelationshipValue(entry.getValue(), depth + 1, limits, nestedGroup, countElements,
                        inComponents || entry.getKey().equals("components"));
            }
        } else if (value instanceof Evidence.JsonArray array) {
            if (countArrayElements && array.values().size() > MAX_RELATIONSHIP_RECORDS - limits[1]) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned too many relationship records");
            }
            limits[0] += 2;
            if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "guide reader returned oversized relationships");
            boolean first = true;
            for (var nested : array.values()) {
                if (!first) {
                    limits[0]++;
                    if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                            "guide reader returned oversized relationships");
                }
                first = false;
                if (countArrayElements && ++limits[1] > MAX_RELATIONSHIP_RECORDS) {
                    throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                            "guide reader returned too many relationship records");
                }
                validateRelationshipValue(nested, depth + 1, limits, groupName, false, inComponents);
            }
        } else if (value instanceof Evidence.JsonString string && string.value().length() > MAX_RELATIONSHIP_BYTES) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned an oversized relationship value");
        } else if (value instanceof Evidence.JsonString string) {
            addRelationshipStringBytes(string.value(), limits);
        } else if (value instanceof Evidence.JsonNumber number) {
            var decimal = number.value();
            long estimatedLength = (long) decimal.precision() + Math.abs((long) decimal.scale()) + 16;
            if (estimatedLength > MAX_RELATIONSHIP_BYTES) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide reader returned an oversized relationship number");
            }
            limits[0] += decimal.toString().length();
            if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "guide reader returned oversized relationships");
        } else if (value instanceof Evidence.JsonBoolean booleanValue) {
            limits[0] += booleanValue.value() ? 4 : 5;
            if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "guide reader returned oversized relationships");
        } else if (value instanceof Evidence.JsonNull) {
            limits[0] += 4;
            if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "guide reader returned oversized relationships");
        }
    }

    private void addRelationshipStringBytes(String value, int[] limits) {
        limits[0] += 2;
        if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "guide reader returned oversized relationships");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\' || character == '\b' || character == '\f'
                    || character == '\n' || character == '\r' || character == '\t'
                    || character <= 0x1f || character == '<' || character == '>' || character == '&'
                    || character == '=' || character == '\'' || character == '\u2028' || character == '\u2029') {
                limits[0] += character == '"' || character == '\\' || character == '\b' || character == '\f'
                        || character == '\n' || character == '\r' || character == '\t' ? 2 : 6;
            } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                limits[0] += 4;
                index++;
            } else if (character <= 0x7f) limits[0]++;
            else if (character <= 0x7ff) limits[0] += 2;
            else limits[0] += 3;
            if (limits[0] > MAX_RELATIONSHIP_BYTES) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "guide reader returned oversized relationships");
        }
    }

    private boolean relationshipMatches(Evidence.JsonObject relationships, String query) {
        for (var group : relationships.values().values()) {
            var values = (Evidence.JsonArray) ((Evidence.JsonObject) group).values().get("values");
            for (var value : values.values()) {
                if (relationshipIdentityMatches(value, query)) return true;
            }
        }
        return false;
    }

    private boolean relationshipIdentityMatches(Evidence.JsonValue value, String query) {
        if (value instanceof Evidence.JsonObject object) {
            for (var entry : object.values().entrySet()) {
                if (RELATIONSHIP_IDENTITIES.contains(entry.getKey())) {
                    if (identityValueMatches(entry.getValue(), query)) return true;
                }
                if (entry.getKey().equals("alternatives") && entry.getValue() instanceof Evidence.JsonArray alternatives) {
                    for (var alternative : alternatives.values()) {
                        if (alternative instanceof Evidence.JsonString string
                                && string.value().toLowerCase(Locale.ROOT).contains(query)) return true;
                        if (alternative instanceof Evidence.JsonObject && relationshipIdentityMatches(alternative, query)) return true;
                    }
                } else if (!entry.getKey().equals("components")
                        && relationshipIdentityMatches(entry.getValue(), query)) return true;
            }
        } else if (value instanceof Evidence.JsonArray array) {
            for (var nested : array.values()) {
                if (nested instanceof Evidence.JsonObject && relationshipIdentityMatches(nested, query)) return true;
            }
        }
        return false;
    }

    private boolean identityValueMatches(Evidence.JsonValue value, String query) {
        if (value instanceof Evidence.JsonString string) return string.value().toLowerCase(Locale.ROOT).contains(query);
        if (value instanceof Evidence.JsonArray array) {
            for (var nested : array.values()) if (identityValueMatches(nested, query)) return true;
        }
        return false;
    }

    private void validateCoverage(Evidence.Coverage coverage) {
        if (coverage == Evidence.Coverage.UNAVAILABLE) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                "guide source is unavailable");
        if (coverage == Evidence.Coverage.ERROR) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "guide source extraction failed");
    }

    private String boundedTitle(String title, List<String> details) {
        if (title.length() <= MAX_TITLE_CHARS) return title;
        details.add("guide title was truncated to the output limit");
        return title.substring(0, MAX_TITLE_CHARS);
    }

    private boolean containsNewText(String text, String query, int overlapChars) {
        int index = text.indexOf(query);
        while (index >= 0) {
            if (index + query.length() > overlapChars) return true;
            index = text.indexOf(query, index + 1);
        }
        return false;
    }

    private CursorPosition decodeCursor(Search search, Evidence.Scope scope, String sourceIdentity) {
        synchronized (documentCursors) {
            var now = System.nanoTime();
            for (var iterator = documentCursors.values().iterator(); iterator.hasNext();) {
                if (now - iterator.next().createdAtNanos() >= CURSOR_TTL_NANOS) iterator.remove();
            }
            var cursor = documentCursors.get(search.cursor());
            if (cursor == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "guide cursor is invalid or expired; restart the search");
            if (!cursor.query().equals(search.query()) || !cursor.types().equals(search.types())) throw new Toolbox.Failure(
                    Toolbox.Code.INVALID_CURSOR, "guide cursor does not match this query or filter");
            if (!cursor.scope().equals(scope)) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE,
                    "guide cursor belongs to another active scope");
            if (!cursor.sourceIdentity().equals(sourceIdentity)) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "guide source changed; restart the search");
            return new CursorPosition(cursor.afterPosition(), cursor.candidateId(), cursor.fragmentPosition());
        }
    }

    private String saveCursor(Search search, Evidence.Scope scope, String sourceIdentity, String afterPosition,
                              String candidateId, String fragmentPosition) {
        if (afterPosition != null && afterPosition.length() > MAX_POSITION_CHARS
                || candidateId != null && candidateId.length() > MAX_ID_CHARS
                || fragmentPosition != null && fragmentPosition.length() > MAX_POSITION_CHARS) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide continuation metadata exceeds its bound");
        }
        var token = "guide:" + UUID.randomUUID();
        synchronized (documentCursors) {
            var now = System.nanoTime();
            for (var iterator = documentCursors.values().iterator(); iterator.hasNext();) {
                if (now - iterator.next().createdAtNanos() >= CURSOR_TTL_NANOS) iterator.remove();
            }
            documentCursors.put(token, new DocumentCursor(search.query(), search.types(), scope, sourceIdentity,
                    afterPosition, candidateId, fragmentPosition, now));
            while (documentCursors.size() > MAX_CURSORS) documentCursors.remove(documentCursors.keySet().iterator().next());
        }
        return token;
    }

    private String sourceIdentity(Evidence.Scope scope) {
        String identity;
        try {
            identity = reader.sourceIdentity(scope);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "guide source identity could not be read: " + failure);
        }
        if (identity == null || identity.isBlank() || identity.length() > 512) {
            throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "guide source identity is unavailable");
        }
        return identity;
    }

    private void requireScope(Evidence.Scope scope) {
        Evidence.Scope current;
        try {
            current = currentScope.get();
        } catch (RuntimeException | LinkageError failure) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "current guide scope could not be read: " + failure);
        }
        if (!scope.equals(current)) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE,
                "repeat the guide lookup in the current scope");
    }

    private void requireStableRead(Evidence.Scope scope, String sourceIdentity) {
        requireScope(scope);
        if (!sourceIdentity.equals(sourceIdentity(scope))) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE,
                "guide source changed during the live read");
    }

    private CompletionStage<Evidence.Result> schedule(Supplier<Evidence.Result> action) {
        try {
            return CompletableFuture.supplyAsync(action, clientExecutor);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                    "guide client executor is unavailable: " + failure));
        }
    }

    private static Evidence.Coverage mergeCoverage(Evidence.Coverage current, Evidence.Coverage next) {
        if (current == Evidence.Coverage.ERROR || next == Evidence.Coverage.ERROR) return Evidence.Coverage.ERROR;
        if (current == Evidence.Coverage.UNAVAILABLE || next == Evidence.Coverage.UNAVAILABLE) return Evidence.Coverage.UNAVAILABLE;
        if (current == Evidence.Coverage.PARTIAL || next == Evidence.Coverage.PARTIAL) return Evidence.Coverage.PARTIAL;
        if (current == Evidence.Coverage.OPAQUE || next == Evidence.Coverage.OPAQUE) return Evidence.Coverage.OPAQUE;
        return Evidence.Coverage.COMPLETE;
    }

    private static String boundedDetail(String value) {
        if (value == null) return null;
        return value.length() <= MAX_DETAIL_CHARS ? value : value.substring(0, MAX_DETAIL_CHARS);
    }
}
