package dev.packsage.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Model-only locators; canonical identities and the readers' live validation remain authoritative. */
public final class ReferenceAliasBackend implements ModelBackend {
    private final ModelBackend backend;
    private final String nonce = UUID.randomUUID().toString().substring(0, 8);
    private long requestCounter;

    public ReferenceAliasBackend(ModelBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    @Override
    public ProviderInfo providerInfo() { return backend.providerInfo(); }

    @Override
    public CompletionStage<AuthState> signIn() { return backend.signIn(); }

    @Override
    public CompletionStage<AuthState> restoreSession() { return backend.restoreSession(); }

    @Override
    public void cancelSignIn() { backend.cancelSignIn(); }

    @Override
    public void signOut() { backend.signOut(); }

    @Override
    public CompletionStage<Capabilities> capabilities() { return backend.capabilities(); }

    @Override
    public Exchange start(Request request, Consumer<Event> events) {
        String prefix;
        synchronized (this) {
            if (requestCounter == Long.MAX_VALUE) throw new IllegalStateException("reference request counter exhausted");
            prefix = nonce + "." + Long.toString(++requestCounter, 36) + ".";
        }
        var exchange = new AliasExchange(prefix, events);
        exchange.delegate = backend.start(request, exchange::accept);
        if (exchange.rejected) exchange.delegate.cancel();
        return exchange;
    }

    private static final class AliasExchange implements Exchange {
        private static final int MAX_OUTPUT_BYTES = 131_072;
        private static final int MAX_RETAINED_BYTES = 4_194_304;
        private static final int MAX_REFERENCES = 4096;
        private static final int MAX_CALLS = 512;
        private static final int MAX_PENDING_EVENTS = 1024;
        private static final int MAX_TEXT_CHARS = 1_048_576;
        private final Object lock = new Object();
        private final String prefix;
        private final Consumer<Event> events;
        private final Map<String, String> evidence = new HashMap<>();
        private final Map<String, String> evidenceAliases = new HashMap<>();
        private final Map<String, ToolCall> wireCalls = new HashMap<>();
        private final Map<String, ToolCall> canonicalCalls = new HashMap<>();
        private final Map<String, ToolResult> wireResults = new HashMap<>();
        private final Map<String, ToolResult> canonicalResults = new HashMap<>();
        private final ArrayDeque<Event> pending = new ArrayDeque<>();
        private CitationText streaming;
        private volatile Exchange delegate;
        private long entryCounter;
        private int retainedBytes;
        private int pendingTextChars;
        private boolean publishing;
        private boolean closed;
        private volatile boolean rejected;

        private AliasExchange(String prefix, Consumer<Event> events) {
            this.prefix = prefix;
            this.events = Objects.requireNonNull(events);
            streaming = new CitationText(evidence, evidenceAliases, false);
        }

        @Override
        public void submit(ToolResult result) {
            ToolResult encoded = null;
            synchronized (lock) {
                if (closed) return;
                try {
                    if (!wireCalls.containsKey(result.callId()) || wireResults.containsKey(result.callId())) {
                        throw new IllegalArgumentException("unknown or duplicate submitted tool result: " + result.callId());
                    }
                    encoded = encodeResult(result);
                    retain((long) result.serializedResult().getBytes(StandardCharsets.UTF_8).length
                            + encoded.serializedResult().getBytes(StandardCharsets.UTF_8).length);
                    canonicalResults.put(result.callId(), result);
                    wireResults.put(result.callId(), encoded);
                } catch (RuntimeException failure) {
                    encoded = null;
                    fail(failure);
                }
            }
            publish();
            if (encoded != null) delegate.submit(encoded);
            else delegate.cancel();
        }

        @Override
        public boolean steer(Message message) {
            synchronized (lock) {
                if (closed) return false;
            }
            return delegate.steer(message);
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                closed = true;
                pending.clear();
                pendingTextChars = 0;
                clear();
            }
            delegate.cancel();
        }

        private void accept(Event event) {
            synchronized (lock) {
                if (closed) return;
                try {
                    if (event.kind() == EventKind.TEXT_DELTA && event.text() != null) {
                        String text = streaming.append(event.text(), false);
                        if (!text.isEmpty()) enqueue(new Event(event.kind(), text, event.toolCall(), event.blocks(),
                                event.replayItems(), event.detail(), event.activityId()));
                    } else {
                        if (event.kind() == EventKind.RETRYING) streaming = new CitationText(evidence, evidenceAliases, false);
                        if (event.kind() == EventKind.ROUND_STARTED || event.kind() == EventKind.CLIENT_TOOL_CALL
                                || event.kind() == EventKind.COMPLETED || event.kind() == EventKind.FAILED) {
                            String text = streaming.append("", true);
                            if (!text.isEmpty()) enqueue(new Event(EventKind.TEXT_DELTA, text, null, List.of(), null));
                            streaming = new CitationText(evidence, evidenceAliases, false);
                        }
                        if (event.kind() == EventKind.CLIENT_TOOL_CALL && event.toolCall() != null) {
                            ToolCall wire = event.toolCall();
                            ToolCall canonical = decodeCall(wire);
                            if (wireCalls.containsKey(wire.callId()) && !wire.equals(wireCalls.get(wire.callId()))) {
                                throw new IllegalArgumentException("backend changed observed tool call: " + wire.callId());
                            }
                            if (!wireCalls.containsKey(wire.callId())) {
                                if (wireCalls.size() == MAX_CALLS) throw new IllegalStateException("reference call limit reached");
                                retain((long) wire.serializedArguments().getBytes(StandardCharsets.UTF_8).length
                                        + canonical.serializedArguments().getBytes(StandardCharsets.UTF_8).length
                                        + wire.callId().getBytes(StandardCharsets.UTF_8).length
                                        + wire.name().getBytes(StandardCharsets.UTF_8).length
                                        + (wire.namespace() == null ? 0 : wire.namespace().getBytes(StandardCharsets.UTF_8).length));
                                wireCalls.put(wire.callId(), wire);
                                canonicalCalls.put(wire.callId(), canonical);
                            }
                            enqueue(new Event(event.kind(), event.text(), canonical, event.blocks(), event.replayItems(),
                                    event.detail(), event.activityId()));
                        } else if (event.kind() == EventKind.COMPLETED) {
                            var replay = new ArrayList<InputItem>();
                            int outputChars = 0;
                            if (event.replayItems().size() > MAX_CALLS * 8) throw new IllegalStateException("reference replay item limit reached");
                            for (InputItem item : event.replayItems()) {
                                if (item instanceof ToolResult result) {
                                    if (!result.equals(wireResults.get(result.callId()))) {
                                        throw new IllegalArgumentException("backend replay changed submitted tool result: " + result.callId());
                                    }
                                    replay.add(canonicalResults.get(result.callId()));
                                } else if (item instanceof ToolCall call) {
                                    if (!call.equals(wireCalls.get(call.callId()))) {
                                        throw new IllegalArgumentException("backend replay changed observed tool call: " + call.callId());
                                    }
                                    replay.add(canonicalCalls.get(call.callId()));
                                } else if (item instanceof AssistantOutput output) {
                                    var content = new ArrayList<OutputContent>();
                                    for (OutputContent part : output.content()) {
                                        if (part instanceof OutputText text) {
                                            if (text.text().length() > MAX_TEXT_CHARS - outputChars) {
                                                throw new IllegalStateException("reference output text limit reached");
                                            }
                                            var decoder = new CitationText(evidence, evidenceAliases, true);
                                            String canonical = decoder.append(text.text(), true);
                                            if (canonical.length() > MAX_TEXT_CHARS - outputChars) {
                                                throw new IllegalStateException("reference expanded text limit reached");
                                            }
                                            outputChars += canonical.length();
                                            var citations = new ArrayList<UrlCitation>();
                                            for (UrlCitation citation : text.citations()) {
                                                if (citation.endIndex() > text.text().length()) {
                                                    throw new IllegalArgumentException("backend web citation exceeds output text");
                                                }
                                                citations.add(new UrlCitation(decoder.offset(citation.startIndex(), false),
                                                        decoder.offset(citation.endIndex(), true), citation.title(), citation.url()));
                                            }
                                            content.add(new OutputText(canonical, citations));
                                        } else content.add(part);
                                    }
                                    replay.add(new AssistantOutput(output.id(), output.status(), output.phase(), content));
                                } else replay.add(item);
                            }
                            enqueue(new Event(event.kind(), event.text(), event.toolCall(), event.blocks(), replay,
                                    event.detail(), event.activityId()));
                            closed = true;
                            clear();
                        } else {
                            enqueue(event);
                            if (event.kind() == EventKind.FAILED) {
                                closed = true;
                                clear();
                            }
                        }
                    }
                } catch (RuntimeException failure) {
                    fail(failure);
                }
            }
            publish();
            if (rejected && delegate != null) delegate.cancel();
        }

        private ToolResult encodeResult(ToolResult result) {
            JsonElement parsed = null;
            try {
                parsed = JsonParser.parseString(result.serializedResult());
            } catch (com.google.gson.JsonParseException ignored) { }
            if (parsed == null || !parsed.isJsonObject()) {
                if (result.serializedResult().getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
                    throw new IllegalStateException("reference encoded tool output limit reached; narrow the lookup");
                }
                return result;
            }
            JsonObject json = parsed.getAsJsonObject();
            if (json.get("records") instanceof com.google.gson.JsonArray records) {
                for (JsonElement value : records) {
                    if (!value.isJsonObject()) continue;
                    JsonObject record = value.getAsJsonObject();
                    if (record.get("reference") instanceof com.google.gson.JsonPrimitive reference && reference.isString()
                            && record.get("source") instanceof JsonObject source
                            && source.get("providerId") instanceof com.google.gson.JsonPrimitive provider && provider.isString()
                            && source.get("sourceHandle") instanceof com.google.gson.JsonPrimitive handle && handle.isString()
                            && reference.getAsString().equals(provider.getAsString() + ":" + handle.getAsString())) {
                        String alias = register(reference.getAsString());
                        if (!alias.equals(reference.getAsString())) {
                            record.addProperty("reference", alias);
                            source.addProperty("sourceHandle", alias);
                        }
                    }
                }
            }
            for (String key : List.of("nextCall", "restartCall")) {
                if (json.get(key) instanceof JsonObject continuation
                        && continuation.get("name") instanceof com.google.gson.JsonPrimitive name && name.isString()
                        && continuation.get("arguments") instanceof JsonObject arguments) {
                    transformArguments(name.getAsString(), arguments, true);
                }
            }
            String encoded = json.toString();
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
                throw new IllegalStateException("reference encoded tool output limit reached; narrow the lookup");
            }
            return new ToolResult(result.callId(), encoded, result.error());
        }

        private String register(String canonical) {
            if (evidenceAliases.containsKey(canonical)) return evidenceAliases.get(canonical);
            if (evidence.size() == MAX_REFERENCES) throw new IllegalStateException("reference registry limit reached");
            String alias = canonical.length() <= 32 ? canonical : "ref:" + prefix + Long.toString(++entryCounter, 36);
            retain((long) canonical.getBytes(StandardCharsets.UTF_8).length + alias.length());
            evidenceAliases.put(canonical, alias);
            evidence.put(alias, canonical);
            return alias;
        }

        private ToolCall decodeCall(ToolCall call) {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(call.serializedArguments());
            } catch (com.google.gson.JsonParseException ignored) {
                return call;
            }
            if (!parsed.isJsonObject() || !transformArguments(call.name(), parsed.getAsJsonObject(), false)) return call;
            return new ToolCall(call.callId(), call.namespace(), call.name(), parsed.toString());
        }

        private boolean transformArguments(String name, JsonObject arguments, boolean encode) {
            boolean changed = false;
            Map<String, String> references = encode ? evidenceAliases : evidence;
            if (List.of("read_evidence", "read_history").contains(name)
                    && arguments.get("handles") instanceof com.google.gson.JsonArray handles) {
                for (int index = 0; index < handles.size(); index++) {
                    if (handles.get(index) instanceof com.google.gson.JsonPrimitive handle && handle.isString()
                            && references.containsKey(handle.getAsString())) {
                        handles.set(index, new com.google.gson.JsonPrimitive(references.get(handle.getAsString())));
                        changed = true;
                    }
                }
            }
            if (name.equals("read_config") && arguments.get("handle") instanceof com.google.gson.JsonPrimitive handle
                    && handle.isString() && references.containsKey(handle.getAsString())) {
                arguments.addProperty("handle", references.get(handle.getAsString()));
                changed = true;
            }
            return changed;
        }

        private void retain(long bytes) {
            if (bytes > MAX_RETAINED_BYTES - retainedBytes) throw new IllegalStateException("reference retained state limit reached");
            retainedBytes += bytes;
        }

        private void fail(RuntimeException failure) {
            pending.clear();
            pendingTextChars = 0;
            pending.addLast(new Event(EventKind.FAILED, null, null, List.of(), failure.getMessage()));
            closed = true;
            rejected = true;
            clear();
        }

        private void clear() {
            evidence.clear();
            evidenceAliases.clear();
            wireCalls.clear();
            canonicalCalls.clear();
            wireResults.clear();
            canonicalResults.clear();
            streaming = new CitationText(evidence, evidenceAliases, false);
        }

        private void enqueue(Event event) {
            int characters = event.text() == null ? 0 : event.text().length();
            if (pending.size() == MAX_PENDING_EVENTS || characters > MAX_TEXT_CHARS - pendingTextChars) {
                throw new IllegalStateException("reference pending event limit reached");
            }
            pendingTextChars += characters;
            pending.addLast(event);
        }

        private void publish() {
            synchronized (lock) {
                if (publishing) return;
                publishing = true;
            }
            try {
                while (true) {
                    Event event;
                    synchronized (lock) {
                        event = pending.pollFirst();
                        if (event == null) {
                            publishing = false;
                            return;
                        }
                        if (event.text() != null) pendingTextChars -= event.text().length();
                    }
                    // Controller callbacks may submit or steer while holding their own state lock.
                    events.accept(event);
                }
            } catch (RuntimeException failure) {
                synchronized (lock) {
                    publishing = false;
                    closed = true;
                    rejected = true;
                    pending.clear();
                    pendingTextChars = 0;
                    clear();
                }
                if (delegate != null) delegate.cancel();
                throw failure;
            }
        }
    }

    private static final class CitationText {
        private static final int MAX_TOKEN_CHARS = 8192;
        private static final String GROUP_START = "\uE200cite\uE202";
        private final Map<String, String> evidence;
        private final Map<String, String> references;
        private final boolean recordPositions;
        private final StringBuilder token = new StringBuilder();
        private final List<Replacement> replacements = new ArrayList<>();
        private int position;
        private int tokenStart;
        // Bracket body, after label, destination, group prefix, group body, pending citation/link, label spacing; zero means plain text.
        private int mode;
        private boolean code;

        private record Replacement(int start, int end, String text) { }

        private CitationText(Map<String, String> evidence, Map<String, String> references, boolean recordPositions) {
            this.evidence = evidence;
            this.references = references;
            this.recordPositions = recordPositions;
        }

        private String append(String text, boolean finished) {
            if (text.length() > AliasExchange.MAX_TEXT_CHARS) throw new IllegalStateException("reference output text limit reached");
            var output = new StringBuilder();
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (!token.isEmpty() && (mode == 2 || mode == 6 || mode == 7) && character != '(' && !Character.isWhitespace(character)) emit(output);
                else if (!token.isEmpty() && token.length() == MAX_TOKEN_CHARS) {
                    emit(output);
                }
                if (token.isEmpty()) {
                    if (character == '`') code = !code;
                    if (!code && (character == '[' || character == '\uE200')) {
                        tokenStart = position;
                        token.append(character);
                        mode = character == '[' ? 1 : 4;
                    } else if (character >= '\uE200' && character <= '\uE202') {
                        if (recordPositions) replacements.add(new Replacement(position, position + 1, ""));
                    } else output.append(character);
                } else {
                    token.append(character);
                    if (mode == 1 && character == ']') {
                        if (resolve(token.substring(1, token.length() - 1)) != null) mode = 6;
                        else mode = 2;
                    } else if (mode == 1 && character == ')'
                            && evidence.containsKey(token.substring(1, token.length() - 1))) {
                        token.setCharAt(token.length() - 1, ']');
                        emit(output);
                    } else if (mode == 2 && Character.isWhitespace(character)) mode = 7;
                    else if (mode == 2) mode = 3;
                    else if (mode == 6 && Character.isWhitespace(character)) mode = 7;
                    else if (mode == 6 && character == '(') mode = 3;
                    else if (mode == 7 && character == '(') mode = 3;
                    else if (mode == 3 && character == ')') emit(output);
                    else if (mode == 4) {
                        if (!GROUP_START.startsWith(token.toString())) {
                            emit(output);
                        } else if (token.length() == GROUP_START.length()) mode = 5;
                    } else if (mode == 5 && character == '\uE201') emit(output);
                }
                position = Math.incrementExact(position);
            }
            if (finished && !token.isEmpty()) {
                emit(output);
            }
            return output.toString();
        }

        private void emit(StringBuilder output) {
            var changes = new ArrayList<Replacement>();
            if (token.charAt(0) == '[' && token.indexOf("]") >= 0
                    && (mode != 3 || token.charAt(token.length() - 1) == ')')) {
                int close = token.indexOf("]");
                int open = token.indexOf("(", close + 1);
                if (open < 0) {
                    String canonical = resolve(token.substring(1, close));
                    if (canonical != null) changes.add(new Replacement(1, close, canonical));
                } else {
                    int start = open + 1;
                    int end = token.length() - 1;
                    String canonical = resolve(token.substring(start, end));
                    if (canonical != null) {
                        if (open > close + 1) changes.add(new Replacement(close + 1, open, ""));
                        changes.add(new Replacement(start, end, canonical));
                    } else {
                        canonical = resolve(token.substring(1, close));
                        if (canonical != null) changes.add(new Replacement(1, close, canonical));
                    }
                }
            } else if (mode == 5 && token.charAt(token.length() - 1) == '\uE201') {
                changes.add(new Replacement(0, GROUP_START.length(), ""));
                int start = GROUP_START.length();
                for (int index = start; index < token.length(); index++) {
                    if (token.charAt(index) == '\uE202' || token.charAt(index) == '\uE201') {
                        String reference = token.substring(start, index);
                        String canonical = resolve(reference);
                        if (canonical == null && references.containsKey(reference)) canonical = reference;
                        changes.add(new Replacement(start, index, canonical == null ? "" : "[" + canonical + "]"));
                        changes.add(new Replacement(index, index + 1, ""));
                        start = index + 1;
                    }
                }
            } else if (mode == 4 || mode == 5) {
                changes.add(new Replacement(0, token.length(), ""));
            }
            for (int index = 0; index < token.length(); index++) {
                if (token.charAt(index) >= '\uE200' && token.charAt(index) <= '\uE202') {
                    boolean covered = false;
                    for (Replacement change : changes) {
                        if (index >= change.start() && index < change.end()) {
                            covered = true;
                            break;
                        }
                    }
                    if (!covered) changes.add(new Replacement(index, index + 1, ""));
                }
            }
            changes.sort(java.util.Comparator.comparingInt(Replacement::start));
            int previous = 0;
            long expanded = token.length();
            for (Replacement change : changes) expanded += change.text().length() - (change.end() - change.start());
            if (expanded > AliasExchange.MAX_TEXT_CHARS - output.length()) throw new IllegalStateException("reference expanded text limit reached");
            for (Replacement change : changes) {
                output.append(token, previous, change.start()).append(change.text());
                if (recordPositions) replacements.add(new Replacement(tokenStart + change.start(), tokenStart + change.end(), change.text()));
                previous = change.end();
            }
            output.append(token, previous, token.length());
            token.setLength(0);
            mode = 0;
        }

        private String resolve(String reference) {
            String canonical = evidence.get(reference);
            if (canonical != null) return canonical;
            int separator = reference.indexOf(':');
            if (separator < 1) return null;
            canonical = evidence.get(reference.substring(separator + 1));
            return canonical != null && canonical.startsWith(reference.substring(0, separator + 1)) ? canonical : null;
        }

        private int offset(int index, boolean end) {
            int difference = 0;
            for (Replacement replacement : replacements) {
                if (index <= replacement.start()) break;
                if (index < replacement.end()) return replacement.start() + difference + (end ? replacement.text().length() : 0);
                difference += replacement.text().length() - (replacement.end() - replacement.start());
            }
            return index + difference;
        }
    }
}
