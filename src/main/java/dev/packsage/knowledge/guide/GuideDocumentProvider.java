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
    private final Map<String, DocumentCursor> documentCursors = new LinkedHashMap<>(MAX_CURSORS, 0.75f, true);

    public GuideDocumentProvider(GuideDocumentReader reader, Supplier<Evidence.Scope> currentScope, Executor clientExecutor) {
        this.reader = Objects.requireNonNull(reader);
        this.currentScope = Objects.requireNonNull(currentScope);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
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
                int maxChars = fragmentLimit(budget);
                GuideDocumentReader.ResolvedDocument resolved;
                try {
                    resolved = reader.resolve(handle.value(), maxChars, budget);
                } catch (Toolbox.Failure failure) {
                    throw failure;
                } catch (RuntimeException | LinkageError failure) {
                    throw extractionFailure("guide evidence resolution failed", failure);
                }
                validateFragment(resolved.fragment(), null, maxChars);
                if (!handle.value().equals(resolved.candidate().id())) throw new Toolbox.Failure(
                        Toolbox.Code.INVALID_REFERENCE, "guide source resolved a different identity");
                var record = documentRecord(scope, resolved.candidate(), resolved.fragment());
                requireStableRead(scope, sourceIdentity);
                return result(List.of(record), record.source().coverage(), null,
                        resolved.fragment().nextPosition() == null ? resolved.fragment().detail()
                                : joinDetail(resolved.fragment().detail(), "guide evidence is a bounded current fragment"));
            });
        }
        return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED,
                "guide providers support search and current document reads"));
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED,
                "this guide source does not expose PackSage navigation");
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
            if (!id().equals(reference.providerId()) || reference.kind() != Evidence.RecordKind.GUIDE) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "expected an observed guide document reference");
            }
            if (reference.sourceHandle().isBlank() || reference.sourceHandle().length() > MAX_ID_CHARS) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "guide reference identity is invalid");
            }
            var sourceIdentity = sourceIdentity(request.scope());
            var budget = context.documentBudget();
            if (!budget.document()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "guide evidence budget exhausted");
            int maxChars = fragmentLimit(budget);
            GuideDocumentReader.ResolvedDocument resolved;
            try {
                resolved = reader.resolve(reference.sourceHandle(), maxChars, budget);
            } catch (Toolbox.Failure failure) {
                throw failure;
            } catch (RuntimeException | LinkageError failure) {
                throw extractionFailure("guide evidence resolution failed", failure);
            }
            validateFragment(resolved.fragment(), null, maxChars);
            if (!reference.sourceHandle().equals(resolved.candidate().id())) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "guide source resolved a different identity");
            }
            var record = documentRecord(request.scope(), resolved.candidate(), resolved.fragment());
            requireStableRead(request.scope(), sourceIdentity);
            return result(List.of(record), record.source().coverage(), null,
                    resolved.fragment().nextPosition() == null ? resolved.fragment().detail()
                            : joinDetail(resolved.fragment().detail(), "guide evidence is a bounded current fragment"));
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
            if (++pageReads > MAX_PAGE_READS_PER_CALL || inspectedCandidates >= MAX_CANDIDATES_PER_CALL
                    || context.documentBudget().exhausted() || context.documentBudget().remainingBytes() < 4) {
                coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
                details.add("guide traversal reached its per-call work bound");
                if (activeCandidateId != null) nextCursor = saveCursor(search, scope, sourceIdentity,
                        afterPosition, activeCandidateId, fragmentPosition);
                else if (afterPosition != null) nextCursor = saveCursor(search, scope, sourceIdentity,
                        afterPosition, null, null);
                break;
            }
            int pageLimit = Math.min(MAX_CANDIDATES_PER_PAGE, MAX_CANDIDATES_PER_CALL - inspectedCandidates);
            GuideDocumentReader.CandidatePage page;
            try {
                page = reader.candidates(afterPosition, pageLimit, context.documentBudget());
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
            if (page.more() && Objects.equals(page.nextPosition(), afterPosition)) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "guide reader did not advance its source position");
            }
            if (page.candidates().isEmpty()) {
                if (!page.more()) {
                    sourceComplete = page.coverage() == Evidence.Coverage.COMPLETE;
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
                    int maxChars = fragmentLimit(context.documentBudget());
                    GuideDocumentReader.Fragment fragment;
                    try {
                        fragment = reader.readFragment(candidate.id(), fragmentPosition, maxChars, context.documentBudget());
                    } catch (Toolbox.Failure failure) {
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
                    coverage = mergeCoverage(coverage, fragment.coverage());
                    if (fragment.detail() != null) details.add(boundedDetail(fragment.detail()));
                    var text = fragment.text();
                    var matches = titleMatches || query.isEmpty() || containsNewText(text.toLowerCase(Locale.ROOT), query,
                            fragment.overlapChars());
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
        var coverage = mergeCoverage(candidate.coverage(), fragment.coverage());
        if (fragment.nextPosition() != null) coverage = mergeCoverage(coverage, Evidence.Coverage.PARTIAL);
        var title = boundedTitle(candidate.title(), new ArrayList<>());
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("id", new Evidence.JsonString(candidate.id()));
        payload.put("title", new Evidence.JsonString(title));
        payload.put("text", new Evidence.JsonString(fragment.text()));
        payload.put("fragment", new Evidence.JsonString(fragment.sourcePosition()));
        payload.put("complete", new Evidence.JsonBoolean(fragment.nextPosition() == null));
        payload.put("provenance", new Evidence.JsonArray(candidate.provenance().stream().limit(MAX_PROVENANCE_ITEMS)
                .map(value -> (Evidence.JsonValue) new Evidence.JsonString(value.length() > MAX_PROVENANCE_CHARS
                        ? value.substring(0, MAX_PROVENANCE_CHARS) : value)).toList()));
        var source = new Evidence.Source(scope, id(), reader.version(), candidate.id(), scope.requestGeneration(),
                Instant.now(), Evidence.Activity.ACTIVE, coverage, Evidence.Authority.INSTALLED_DOCUMENTATION,
                candidate.provenance().stream().limit(MAX_PROVENANCE_ITEMS)
                        .map(value -> value.length() > MAX_PROVENANCE_CHARS ? value.substring(0, MAX_PROVENANCE_CHARS) : value).toList());
        return new Evidence.Record(source, Evidence.RecordKind.GUIDE, title,
                new Evidence.JsonObject(payload), List.of());
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

    private int fragmentLimit(Toolbox.DocumentBudget budget) {
        return Math.min(MAX_FRAGMENT_CHARS, budget.remainingBytes() / 4);
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
            throw extractionFailure("guide source identity could not be read", failure);
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
            throw extractionFailure("current guide scope could not be read", failure);
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

    private static String joinDetail(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return boundedDetail(first + "; " + second);
    }

    private static String boundedDetail(String value) {
        if (value == null) return null;
        return value.length() <= MAX_DETAIL_CHARS ? value : value.substring(0, MAX_DETAIL_CHARS);
    }

    private static Toolbox.Failure extractionFailure(String message, Throwable cause) {
        return new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, message + ": " + cause);
    }
}
