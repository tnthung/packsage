package dev.packsage.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

public final class ConversationStore {
    record HistoryEntry(String sessionId, ChatContracts.RequestId requestId, Instant sentAt, String question,
                        String answer, List<ChatContracts.SteeringMessage> steeringMessages, HistoryLocator locator) {
        HistoryEntry {
            steeringMessages = List.copyOf(steeringMessages);
        }
    }

    record HistoryLocator(Path file, long byteOffset, int byteLength, String textFingerprint) {
    }

    record HistoryFileStamp(Path file, long size, FileTime modifiedTime, String fileKey) {
    }

    record HistoryCursor(List<HistoryFileStamp> files, int fileIndex, long byteOffset,
                         boolean skippingOversizedRecord, boolean partial, String worldId,
                         String query, String excludedRequestId, String sessionId) {
        HistoryCursor {
            files = List.copyOf(files);
        }
    }

    record HistoryPage(List<HistoryEntry> entries, HistoryCursor nextCursor,
                       Evidence.Coverage coverage, String detail) {
        HistoryPage {
            entries = List.copyOf(entries);
        }
    }

    private record CompletionState(String latestCompletedRequestId, String acknowledgedRequestId) {
    }

    private static final int HISTORY_MAX_ROOT_ENTRIES = 512;
    private static final int HISTORY_MAX_SESSIONS = 256;
    private static final int HISTORY_MAX_SCAN_BYTES = 8 * 1024 * 1024;
    private static final int HISTORY_MAX_RECORDS = 4096;
    private static final int HISTORY_MAX_RECORD_BYTES = 2 * 1024 * 1024;
    private static final int HISTORY_MAX_HEADER_BYTES = 16 * 1024;

    private final Path root;
    private final Executor ioExecutor;
    private final Executor catalogExecutor;
    private final Object writeLock = new Object();
    private CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);
    private final List<String> errors = new ArrayList<>();

    public ConversationStore(Path root, Executor ioExecutor) {
        this(root, ioExecutor, ioExecutor);
    }

    public ConversationStore(Path root, Executor ioExecutor, Executor catalogExecutor) {
        this.root = root;
        this.ioExecutor = ioExecutor;
        this.catalogExecutor = catalogExecutor;
    }

    CompletionStage<HistoryPage> searchHistory(ChatContracts.WorldIdentity world, String text,
                                               ChatContracts.RequestId excluded, int limit, HistoryCursor cursor) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(text);
        if (text.isBlank() || text.length() > 512) throw new IllegalArgumentException("history search text must contain 1 to 512 characters");
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("history result limit must be between 1 and 16");
        return enqueueRead(() -> {
            List<String> discoveryDetails = new ArrayList<>();
            List<HistoryFileStamp> files = new ArrayList<>();
            int inspectedEntries = 0;
            boolean discoveryPartial = false;
            String worldSuffix = "-" + digest(world.worldOrServer());
            try {
                if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !safePath(root)) {
                        return new HistoryPage(List.of(), null, Evidence.Coverage.PARTIAL, "Conversation root is unavailable or unsafe.");
                    }
                    try (var rootPaths = Files.list(root)) {
                        var rootIterator = rootPaths.iterator();
                        while (rootIterator.hasNext()) {
                            Path directory = rootIterator.next();
                            if (++inspectedEntries > HISTORY_MAX_ROOT_ENTRIES) {
                                discoveryPartial = true;
                                discoveryDetails.add("Conversation root entry limit reached.");
                                break;
                            }
                            String name = directory.getFileName().toString();
                            if (!name.endsWith(worldSuffix) || Files.isSymbolicLink(directory)
                                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !safePath(directory)) continue;
                            try (var children = Files.list(directory)) {
                                var childIterator = children.iterator();
                                while (childIterator.hasNext()) {
                                    Path file = childIterator.next();
                                    if (++inspectedEntries > HISTORY_MAX_ROOT_ENTRIES) {
                                        discoveryPartial = true;
                                        discoveryDetails.add("Conversation directory entry limit reached.");
                                        break;
                                    }
                                    if (!file.getFileName().toString().endsWith(".jsonl")
                                            || Files.isSymbolicLink(file)
                                            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !safePath(file)) continue;
                                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                                            LinkOption.NOFOLLOW_LINKS);
                                    files.add(new HistoryFileStamp(file.toAbsolutePath().normalize(), attributes.size(),
                                            attributes.lastModifiedTime(), String.valueOf(attributes.fileKey())));
                                    if (files.size() > HISTORY_MAX_SESSIONS) {
                                        files.removeLast();
                                        discoveryPartial = true;
                                        discoveryDetails.add("Conversation session limit reached.");
                                        break;
                                    }
                                    if (files.size() == HISTORY_MAX_SESSIONS) {
                                        discoveryPartial = true;
                                        discoveryDetails.add("Conversation session limit reached.");
                                        break;
                                    }
                                }
                            }
                            if (inspectedEntries > HISTORY_MAX_ROOT_ENTRIES || files.size() >= HISTORY_MAX_SESSIONS) break;
                        }
                    }
                }
                files.sort(Comparator.comparing(HistoryFileStamp::modifiedTime).reversed()
                        .thenComparing(stamp -> stamp.file().getFileName().toString()));
                if (cursor != null) {
                    if (!world.worldOrServer().equals(cursor.worldId()) || !text.equals(cursor.query())
                            || !Objects.equals(excluded == null ? null : excluded.value(), cursor.excludedRequestId())) {
                        throw new IllegalArgumentException("history cursor belongs to another search");
                    }
                    if (!files.equals(cursor.files())) throw new IllegalArgumentException("history cursor is stale");
                    if (cursor.fileIndex() < 0 || cursor.fileIndex() > files.size() || cursor.byteOffset() < 0) {
                        throw new IllegalArgumentException("history cursor position is invalid");
                    }
                }
            } catch (IOException exception) {
                return new HistoryPage(List.of(), null, Evidence.Coverage.PARTIAL, "Could not enumerate conversation history safely.");
            }

            int fileIndex = cursor == null ? 0 : cursor.fileIndex();
            long byteOffset = cursor == null ? 0 : cursor.byteOffset();
            boolean skippingOversizedRecord = cursor != null && cursor.skippingOversizedRecord();
            String sessionId = cursor == null ? null : cursor.sessionId();
            boolean partial = discoveryPartial || cursor != null && cursor.partial();
            int scanBytes = 0;
            int physicalRecords = 0;
            String query = text.toLowerCase(Locale.ROOT);
            List<HistoryEntry> entries = new ArrayList<>();
            boolean more = false;
            while (fileIndex < files.size()) {
                HistoryFileStamp stamp = files.get(fileIndex);
                try (FileChannel channel = FileChannel.open(stamp.file(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    if (byteOffset == 0) {
                        if (scanBytes >= HISTORY_MAX_SCAN_BYTES || physicalRecords >= HISTORY_MAX_RECORDS) {
                            more = true;
                            break;
                        }
                        PhysicalRecord headerRecord = readPhysicalRecord(channel, 0,
                                HISTORY_MAX_SCAN_BYTES - scanBytes, HISTORY_MAX_HEADER_BYTES, false);
                        scanBytes += headerRecord.scannedBytes();
                        physicalRecords++;
                        byteOffset = headerRecord.nextOffset();
                        if (!headerRecord.complete() || headerRecord.oversized()) {
                            if (headerRecord.oversized()) partial = true;
                            if (!headerRecord.complete() && !headerRecord.oversized()) {
                                more = true;
                                byteOffset = 0;
                                break;
                            }
                            fileIndex++;
                            byteOffset = 0;
                            skippingOversizedRecord = false;
                            sessionId = null;
                            continue;
                        }
                        try {
                            JsonObject header = JsonParser.parseString(new String(headerRecord.bytes(), StandardCharsets.UTF_8).trim()).getAsJsonObject();
                            String foundSessionId = header.get("id").getAsString();
                            if (!"conversation".equals(header.get("type").getAsString())
                                    || !world.worldOrServer().equals(header.get("world").getAsString())
                                    || !(safeName(foundSessionId) + "-" + digest(foundSessionId) + ".jsonl").equals(stamp.file().getFileName().toString())) {
                                fileIndex++;
                                byteOffset = 0;
                                sessionId = null;
                                continue;
                            }
                            sessionId = foundSessionId;
                        } catch (RuntimeException exception) {
                            partial = true;
                            fileIndex++;
                            byteOffset = 0;
                            sessionId = null;
                            continue;
                        }
                    }
                    boolean fileFinished = false;
                    while (!fileFinished) {
                        if (scanBytes >= HISTORY_MAX_SCAN_BYTES || physicalRecords >= HISTORY_MAX_RECORDS) {
                            more = true;
                            break;
                        }
                        long recordStart = byteOffset;
                        PhysicalRecord record = readPhysicalRecord(channel, recordStart,
                                HISTORY_MAX_SCAN_BYTES - scanBytes, HISTORY_MAX_RECORD_BYTES, skippingOversizedRecord);
                        scanBytes += record.scannedBytes();
                        if (record.scannedBytes() == 0 && record.complete()) {
                            fileIndex++;
                            byteOffset = 0;
                            skippingOversizedRecord = false;
                            sessionId = null;
                            fileFinished = true;
                            continue;
                        }
                        physicalRecords++;
                        if (!record.complete()) {
                            partial |= record.oversized();
                            more = true;
                            if (record.oversized()) {
                                byteOffset = record.nextOffset();
                                skippingOversizedRecord = true;
                            } else {
                                byteOffset = recordStart;
                                skippingOversizedRecord = false;
                            }
                            break;
                        }
                        byteOffset = record.nextOffset();
                        skippingOversizedRecord = false;
                        if (record.oversized()) {
                            partial = true;
                            continue;
                        }
                        if (record.bytes().length == 0) continue;
                        try {
                            HistoryRecord value = readHistoryRecord(record.bytes());
                            if (!"turn".equals(value.type()) || value.world() != null && !world.worldOrServer().equals(value.world())
                                    || value.status() != ChatContracts.OutcomeStatus.COMPLETED || value.requestId() == null
                                    || value.question() == null || value.answer() == null
                                    || excluded != null && excluded.value().equals(value.requestId())) continue;
                            if (!value.question().toLowerCase(Locale.ROOT).contains(query)
                                    && !value.answer().toLowerCase(Locale.ROOT).contains(query)
                                    && value.steeringMessages().stream().noneMatch(message ->
                                    message.text().toLowerCase(Locale.ROOT).contains(query))) continue;
                            if (!value.question().isEmpty() || !value.answer().isEmpty() || !value.steeringMessages().isEmpty()) {
                                entries.add(new HistoryEntry(sessionId, new ChatContracts.RequestId(value.requestId()), value.sentAt(),
                                        value.question(), value.answer(), value.steeringMessages(), new HistoryLocator(stamp.file(), recordStart,
                                        record.bytes().length, sha256(record.bytes()))));
                            }
                            if (entries.size() >= limit) {
                                more = byteOffset < channel.size() || fileIndex + 1 < files.size();
                                break;
                            }
                        } catch (RuntimeException exception) {
                            partial = true;
                        }
                    }
                    if (more) break;
                } catch (IOException exception) {
                    partial = true;
                    fileIndex++;
                    byteOffset = 0;
                    skippingOversizedRecord = false;
                    sessionId = null;
                }
                if (fileIndex < files.size() && byteOffset == 0) continue;
            }
            HistoryCursor next = more ? new HistoryCursor(files, fileIndex, byteOffset, skippingOversizedRecord, partial,
                    world.worldOrServer(), text, excluded == null ? null : excluded.value(), sessionId) : null;
            String detail = partial ? String.join(" ", discoveryDetails) : more ? "More history remains; continue with the returned cursor." : "";
            if (partial && discoveryDetails.isEmpty()) detail = "Some conversation records were malformed, oversized, or unavailable.";
            return new HistoryPage(entries, next, partial || more ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, detail);
        });
    }

    CompletionStage<HistoryEntry> readHistory(ChatContracts.WorldIdentity world, HistoryLocator locator) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(locator);
        return enqueueRead(() -> {
            try {
                Path file = locator.file().toAbsolutePath().normalize();
                if (!safePath(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || locator.byteOffset() < 0 || locator.byteLength() < 1
                        || locator.byteLength() > HISTORY_MAX_RECORD_BYTES) throw new IOException("history record is stale or unsafe");
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    PhysicalRecord headerRecord = readPhysicalRecord(channel, 0, HISTORY_MAX_HEADER_BYTES,
                            HISTORY_MAX_HEADER_BYTES, false);
                    if (!headerRecord.complete() || headerRecord.oversized()) throw new IOException("history session header is invalid");
                    JsonObject header = JsonParser.parseString(new String(headerRecord.bytes(), StandardCharsets.UTF_8).trim()).getAsJsonObject();
                    String sessionId = header.get("id").getAsString();
                    if (!"conversation".equals(header.get("type").getAsString())
                            || !world.worldOrServer().equals(header.get("world").getAsString())
                            || !(safeName(sessionId) + "-" + digest(sessionId) + ".jsonl").equals(file.getFileName().toString())) {
                        throw new IOException("history session scope changed");
                    }
                    ByteBuffer bytes = ByteBuffer.allocate(locator.byteLength());
                    long position = locator.byteOffset();
                    while (bytes.hasRemaining()) {
                        int count = channel.read(bytes, position);
                        if (count < 0) throw new IOException("history record was deleted");
                        if (count == 0) continue;
                        position += count;
                    }
                    byte[] recordBytes = bytes.array();
                    if (!sha256(recordBytes).equals(locator.textFingerprint())) throw new IOException("history record was replaced");
                    HistoryRecord record = readHistoryRecord(recordBytes);
                    if (!"turn".equals(record.type()) || record.status() != ChatContracts.OutcomeStatus.COMPLETED
                            || record.requestId() == null || record.question() == null || record.answer() == null
                            || record.world() != null && !world.worldOrServer().equals(record.world())) {
                        throw new IOException("history record is no longer a completed exchange");
                    }
                    return new HistoryEntry(sessionId, new ChatContracts.RequestId(record.requestId()), record.sentAt(),
                            record.question(), record.answer(), record.steeringMessages(), locator);
                }
            } catch (IOException | RuntimeException exception) {
                throw new IllegalArgumentException("history record is stale or unavailable", exception);
            }
        });
    }

    private record PhysicalRecord(byte[] bytes, long nextOffset, int scannedBytes, boolean complete, boolean oversized) {
    }

    private record HistoryRecord(String type, String world, String requestId, String question,
                                 String answer, ChatContracts.OutcomeStatus status, Instant sentAt,
                                 List<ChatContracts.SteeringMessage> steeringMessages) {
        private HistoryRecord {
            steeringMessages = List.copyOf(steeringMessages);
        }
    }

    private static PhysicalRecord readPhysicalRecord(FileChannel channel, long startOffset, int byteBudget,
                                                     int maximumRecordBytes, boolean discard) throws IOException {
        if (byteBudget <= 0) return new PhysicalRecord(new byte[0], startOffset, 0, false, discard);
        ByteArrayOutputStream record = new ByteArrayOutputStream(Math.min(maximumRecordBytes, 8192));
        ByteBuffer buffer = ByteBuffer.allocate(Math.min(8192, byteBudget));
        long position = startOffset;
        int scanned = 0;
        boolean oversized = discard;
        while (scanned < byteBudget) {
            buffer.clear();
            buffer.limit(Math.min(buffer.capacity(), byteBudget - scanned));
            int count = channel.read(buffer, position);
            if (count < 0) return new PhysicalRecord(record.toByteArray(), position, scanned, true, oversized);
            if (count == 0) continue;
            scanned += count;
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte value = buffer.get();
                position++;
                if (value == '\n') return new PhysicalRecord(record.toByteArray(), position, scanned, true, oversized);
                if (!oversized) {
                    if (record.size() >= maximumRecordBytes) {
                        oversized = true;
                        record.reset();
                    } else record.write(value);
                }
            }
        }
        return new PhysicalRecord(record.toByteArray(), position, scanned, false, oversized);
    }

    private static HistoryRecord readHistoryRecord(byte[] bytes) {
        String type = null;
        String world = null;
        String requestId = null;
        String question = null;
        String answer = null;
        ChatContracts.OutcomeStatus status = null;
        Instant sentAt = null;
        List<ChatContracts.SteeringMessage> steeringMessages = new ArrayList<>();
        JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8).stripTrailing()));
        try {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "type" -> type = reader.nextString();
                    case "world" -> world = reader.nextString();
                    case "requestId" -> requestId = reader.nextString();
                    case "question" -> question = reader.nextString();
                    case "answer" -> answer = reader.nextString();
                    case "outcome" -> status = ChatContracts.OutcomeStatus.valueOf(reader.nextString());
                    case "sentAt" -> {
                        if (reader.peek() == JsonToken.NULL) reader.nextNull();
                        else sentAt = Instant.parse(reader.nextString());
                    }
                    case "steeringMessages" -> {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            String steeringText = null;
                            int answerOffset = -1;
                            reader.beginObject();
                            while (reader.hasNext()) {
                                switch (reader.nextName()) {
                                    case "text" -> steeringText = reader.nextString();
                                    case "answerOffset" -> answerOffset = reader.nextInt();
                                    default -> reader.skipValue();
                                }
                            }
                            reader.endObject();
                            steeringMessages.add(new ChatContracts.SteeringMessage(steeringText, answerOffset));
                        }
                        reader.endArray();
                    }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing JSON data");
            int previousOffset = -1;
            for (ChatContracts.SteeringMessage message : steeringMessages) {
                if (message.answerOffset() < previousOffset || answer == null || message.answerOffset() > answer.length()) {
                    throw new IllegalArgumentException("history steering offsets are invalid");
                }
                previousOffset = message.answerOffset();
            }
            return new HistoryRecord(type, world, requestId, question, answer, status, sentAt, steeringMessages);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid history record", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public CompletionStage<ChatContracts.Conversation> openLatest(ChatContracts.WorldIdentity world) {
        return enqueueRead(() -> {
            Path directory = worldDirectory(world);
            try {
                if (Files.isSymbolicLink(directory)) throw new IOException("world directory is a symbolic link");
                if (!Files.isDirectory(directory)) {
                    ChatContracts.Conversation conversation = emptyConversation(world, UUID.randomUUID().toString());
                    Files.createDirectories(directory);
                    if (!safePath(directory)) throw new IOException("world directory escapes conversation root");
                    Files.writeString(conversationPath(world, conversation.id()), conversationJson(conversation) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    writeSummary(conversationPath(world, conversation.id()), conversation);
                    writeLatest(world, conversation.id());
                    return conversation;
                }
                if (!safePath(directory)) throw new IOException("world directory escapes conversation root");
                if (Files.exists(latestPath(world), LinkOption.NOFOLLOW_LINKS)) {
                    if (!safePath(latestPath(world))) throw new IOException("latest marker escapes conversation root");
                    Path latest = conversationPath(world, Files.readString(latestPath(world), StandardCharsets.UTF_8).trim());
                    if (Files.exists(latest) && safePath(latest)) {
                        ChatContracts.Conversation conversation = readConversation(latest, world);
                        if (conversation != null) return conversation;
                    }
                }
                List<Path> files;
                try (var paths = Files.list(directory)) {
                    files = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl") && safePath(path))
                            .sorted(Comparator.comparingLong(this::modifiedAt).reversed()).toList();
                }
                for (Path file : files) {
                    ChatContracts.Conversation conversation = readConversation(file, world);
                    if (conversation != null) return conversation;
                }
                ChatContracts.Conversation conversation = emptyConversation(world, UUID.randomUUID().toString());
                Files.writeString(conversationPath(world, conversation.id()), conversationJson(conversation) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                writeSummary(conversationPath(world, conversation.id()), conversation);
                writeLatest(world, conversation.id());
                return conversation;
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletionStage<ChatContracts.Conversation> openConversation(ChatContracts.WorldIdentity world, String conversationId) {
        return enqueueRead(() -> {
            Path file = conversationPath(world, conversationId);
            if (!Files.isRegularFile(file) || !safePath(file)) throw new IllegalArgumentException("conversation does not exist: " + conversationId);
            ChatContracts.Conversation conversation = readConversation(file, world);
            if (conversation == null) throw new IllegalArgumentException("conversation does not belong to world: " + conversationId);
            return conversation;
        });
    }

    public CompletionStage<List<ChatContracts.SessionSummary>> sessions() {
        return enqueueCatalogRead(() -> {
            List<ChatContracts.SessionSummary> summaries = new ArrayList<>();
            if (!Files.isDirectory(root)) return summaries;
            try (var paths = Files.walk(root, 2)) {
                for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && safePath(path)).toList()) {
                    try {
                        String firstLine;
                        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                            firstLine = reader.readLine();
                        }
                        if (firstLine == null) continue;
                        JsonObject header = JsonParser.parseString(firstLine).getAsJsonObject();
                        if (!"conversation".equals(header.get("type").getAsString())) continue;
                        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity(header.get("world").getAsString(),
                                header.has("label") ? header.get("label").getAsString() : header.get("world").getAsString());
                        ChatContracts.SessionSummary summary = readSummary(file, world, false);
                        if (summary != null) summaries.add(summary);
                    } catch (IOException exception) {
                        report(exception);
                    } catch (RuntimeException ignored) { }
                }
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
            summaries.sort(Comparator.comparing(ChatContracts.SessionSummary::updatedAt).reversed());
            return summaries;
        });
    }

    public record SessionPage(List<ChatContracts.SessionSummary> sessions, boolean hasMore, int nextOffset) {
        public SessionPage(List<ChatContracts.SessionSummary> sessions, boolean hasMore) {
            this(sessions, hasMore, sessions.size());
        }

        public SessionPage {
            sessions = List.copyOf(sessions);
        }
    }

    public CompletionStage<SessionPage> sessionPage(ChatContracts.WorldIdentity currentWorld, boolean otherWorlds,
                                                    int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("invalid session page");
        return enqueueCatalogRead(() -> {
            List<java.util.Map.Entry<Path, Long>> files = new ArrayList<>();
            List<Path> directories = new ArrayList<>();
            try {
                if (otherWorlds) {
                    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !safePath(root)) return new SessionPage(List.of(), false);
                    try (var paths = Files.list(root)) {
                        directories.addAll(paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                && safePath(path)).toList());
                    }
                } else {
                    Path directory = worldDirectory(currentWorld);
                    if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && safePath(directory)) directories.add(directory);
                }
                for (Path directory : directories) {
                    try (var paths = Files.list(directory)) {
                        for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
                            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            if (attributes.isRegularFile()) files.add(java.util.Map.entry(file, attributes.lastModifiedTime().toMillis()));
                        }
                    }
                }
                files.sort(java.util.Map.Entry.<Path, Long>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().getFileName().toString()));
                List<ChatContracts.SessionSummary> summaries = new ArrayList<>();
                int end = (int) Math.min(files.size(), (long) offset + limit);
                for (int index = offset; index < end; index++) {
                    Path file = files.get(index).getKey();
                    if (!safePath(file)) continue;
                    ChatContracts.SessionSummary summary = readSummary(file,
                            file.getParent().equals(worldDirectory(currentWorld)) ? currentWorld : null, false);
                    if (summary != null) summaries.add(summary);
                }
                return new SessionPage(summaries, end < files.size(), end);
            } catch (IOException | RuntimeException failure) {
                report(failure);
                throw new RuntimeException(failure);
            }
        });
    }

    public CompletionStage<ChatContracts.SessionSummary> summary(ChatContracts.WorldIdentity world, String conversationId) {
        return enqueueRead(() -> {
            try {
                return readSummary(findConversation(world, conversationId), world, true);
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletionStage<List<ChatContracts.SessionSummary>> loadSummaries(List<ChatContracts.SessionSummary> sessions) {
        List<ChatContracts.SessionSummary> loaded = new ArrayList<>();
        CompletionStage<Void> pending = CompletableFuture.completedFuture(null);
        for (ChatContracts.SessionSummary session : sessions) {
            pending = pending.thenCompose(ignored -> enqueueCatalogRead(() -> {
                try {
                    return readSummary(findConversation(session.world(), session.id()), session.world(), false);
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            }).handle((summary, failure) -> {
                loaded.add(failure == null && summary != null ? summary : session);
                return null;
            }));
        }
        return pending.thenApply(ignored -> List.copyOf(loaded));
    }

    private ChatContracts.SessionSummary readSummary(Path file, ChatContracts.WorldIdentity world, boolean loadTurns) throws IOException {
        Path summaryFile = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.jsonl$", ".summary.json"));
        JsonObject value = null;
        if (safePath(summaryFile) && Files.isRegularFile(summaryFile, LinkOption.NOFOLLOW_LINKS)
                && Files.size(summaryFile) <= 65_536) {
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(summaryFile, StandardCharsets.UTF_8)).getAsJsonObject();
                saved.get("title").getAsString();
                saved.get("lastMessage").getAsString();
                if ((world == null || saved.get("world").getAsString().equals(world.worldOrServer()))
                        && file.getParent().getFileName().toString().endsWith("-" + digest(saved.get("world").getAsString()))
                        && (safeName(saved.get("id").getAsString()) + "-" + digest(saved.get("id").getAsString()) + ".jsonl")
                        .equals(file.getFileName().toString())
                        && saved.get("size").getAsLong() == Files.size(file)
                        && saved.get("modifiedAt").getAsLong() == modifiedAt(file)) value = saved;
            } catch (RuntimeException ignored) { }
        }
        if (value == null) {
            if (!loadTurns) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject header = JsonParser.parseString(reader.readLine()).getAsJsonObject();
                    if (!"conversation".equals(header.get("type").getAsString())
                            || world != null && !world.worldOrServer().equals(header.get("world").getAsString())) return null;
                    if (world == null) world = new ChatContracts.WorldIdentity(header.get("world").getAsString(),
                            header.has("label") ? header.get("label").getAsString() : header.get("world").getAsString());
                    String id = header.get("id").getAsString();
                    if (!(safeName(id) + "-" + digest(id) + ".jsonl").equals(file.getFileName().toString())) return null;
                    return new ChatContracts.SessionSummary(world, id, "Saved chat", "", Instant.ofEpochMilli(modifiedAt(file)));
                }
            }
            ChatContracts.Conversation conversation = readConversation(file, world);
            if (conversation == null) return null;
            value = writeSummary(file, conversation);
        }
        if (world == null) world = new ChatContracts.WorldIdentity(value.get("world").getAsString(),
                value.has("label") ? value.get("label").getAsString()
                        : file.getParent().getFileName().toString().replaceFirst("-[0-9a-f]{16}$", "").replace('_', ' '));
        String completed = value.has("latestCompletedRequestId") ? value.get("latestCompletedRequestId").getAsString() : null;
        CompletionState state = completed == null ? new CompletionState(null, null) : readCompletionState(file);
        return new ChatContracts.SessionSummary(world, value.get("id").getAsString(), value.get("title").getAsString(),
                value.get("lastMessage").getAsString(), Instant.ofEpochMilli(value.get("modifiedAt").getAsLong()), false,
                completed != null && completed.equals(state.latestCompletedRequestId())
                        && !completed.equals(state.acknowledgedRequestId()) ? new ChatContracts.RequestId(completed) : null);
    }

    private JsonObject writeSummary(Path file, ChatContracts.Conversation conversation) throws IOException {
        JsonObject value = new JsonObject();
        value.addProperty("world", conversation.world().worldOrServer());
        value.addProperty("label", conversation.world().readableLabel());
        value.addProperty("id", conversation.id());
        String title = conversation.turns().isEmpty() ? "New chat" : conversation.turns().getFirst().question();
        String preview = conversation.turns().isEmpty() ? "" : conversation.turns().getLast().answer();
        value.addProperty("title", title.substring(0, Math.min(title.length(), 160)));
        value.addProperty("lastMessage", preview.substring(0, Math.min(preview.length(), 240)));
        value.addProperty("size", Files.size(file));
        value.addProperty("modifiedAt", modifiedAt(file));
        conversation.turns().stream().filter(turn -> turn.outcome().status() == ChatContracts.OutcomeStatus.COMPLETED)
                .reduce((first, last) -> last).ifPresent(turn -> value.addProperty("latestCompletedRequestId", turn.requestId().value()));
        Path summaryFile = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.jsonl$", ".summary.json"));
        Path temporary = summaryFile.resolveSibling(summaryFile.getFileName() + "-" + UUID.randomUUID() + ".tmp");
        try {
            if (!safePath(summaryFile)) throw new IOException("conversation summary path is unsafe");
            Files.writeString(temporary, value.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temporary, summaryFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            report(exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException exception) {
                report(exception);
            }
        }
        return value;
    }

    private void refreshSummary(Path file, ChatContracts.WorldIdentity world) {
        try {
            ChatContracts.Conversation conversation = readConversation(file, world);
            if (conversation != null) writeSummary(file, conversation);
        } catch (IOException | RuntimeException exception) {
            report(exception);
        }
    }

    public CompletionStage<Void> deleteConversation(ChatContracts.WorldIdentity world, String conversationId) {
        return enqueue(() -> {
            Path file = findConversation(world, conversationId);
            Path directory = file.getParent();
            Path latest = directory.resolve("latest");
            try {
                if (Files.isSymbolicLink(directory) || !safePath(directory)) throw new IOException("world directory is unsafe");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !safePath(file)
                        || !hasConversationHeader(file, world, conversationId)) {
                    throw new IOException("conversation does not belong to the requested world and id");
                }
                Path stateFile = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.jsonl$", ".state.json"));
                Path summaryFile = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.jsonl$", ".summary.json"));
                if (!safePath(summaryFile) || Files.exists(summaryFile, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(summaryFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("conversation summary path is unsafe");
                }
                if (!safePath(stateFile) || Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("conversation state path is unsafe");
                }
                boolean wasLatest = false;
                if (Files.exists(latest, LinkOption.NOFOLLOW_LINKS)) {
                    if (!safePath(latest) || !Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("latest marker is unsafe");
                    }
                    wasLatest = conversationId.equals(Files.readString(latest, StandardCharsets.UTF_8).trim());
                }
                if (wasLatest) {
                    Path fallback = null;
                    try (var paths = Files.list(directory)) {
                        for (Path candidate : paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")
                                && !path.equals(file) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                && safePath(path)).sorted(Comparator.comparingLong(this::modifiedAt).reversed()).toList()) {
                            if (hasWorldHeader(candidate, world)) {
                                ChatContracts.Conversation conversation = readConversation(candidate, world);
                                if (conversation != null) {
                                    fallback = candidate;
                                    break;
                                }
                            }
                        }
                    }
                    if (fallback == null) {
                        Files.deleteIfExists(latest);
                    } else {
                        ChatContracts.Conversation conversation = readConversation(fallback, world);
                        writeLatest(directory, conversation.id());
                    }
                }
                Files.deleteIfExists(stateFile);
                Files.deleteIfExists(summaryFile);
                Files.delete(file);
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletionStage<ChatContracts.Conversation> newConversation(ChatContracts.WorldIdentity world) {
        String id = UUID.randomUUID().toString();
        ChatContracts.Conversation conversation = emptyConversation(world, id);
        return enqueue(() -> {
            try {
                if (Files.isSymbolicLink(worldDirectory(world))) throw new IOException("world directory is a symbolic link");
                Files.createDirectories(worldDirectory(world));
                if (!safePath(worldDirectory(world))) throw new IOException("world directory escapes conversation root");
                Files.writeString(conversationPath(world, id), conversationJson(conversation) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                writeSummary(conversationPath(world, id), conversation);
                writeLatest(world, id);
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        }).thenApply(ignored -> conversation);
    }

    public CompletionStage<Void> appendTurn(String conversationId, ChatContracts.Turn turn) {
        throw new IllegalArgumentException("world identity is required when appending a turn");
    }

    public CompletionStage<Void> appendTurn(ChatContracts.WorldIdentity world, Evidence.Scope expectedScope,
                                            String conversationId, ChatContracts.Turn turn) {
        return enqueue(() -> {
            Path file = findConversation(world, conversationId);
            try {
                if (!expectedScope.equals(turn.context().scope())
                        || turn.sources().stream().anyMatch(source -> !expectedScope.equals(source.scope()))) {
                    throw new IOException("turn evidence scope does not match the submitted request scope");
                }
                if (!hasConversationHeader(file, world, conversationId)) {
                    throw new IOException("conversation header does not match append target");
                }
                if (Files.size(file) > 0) {
                    byte[] existing = Files.readAllBytes(file);
                    if (existing[existing.length - 1] != '\n') appendBytes(file, new byte[]{'\n'});
                }
                appendBytes(file, (turnJson(world, turn) + "\n").getBytes(StandardCharsets.UTF_8));
                refreshSummary(file, world);
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletionStage<Void> replaceLatestTurn(ChatContracts.WorldIdentity world, Evidence.Scope expectedScope,
                                                    String conversationId, ChatContracts.RequestId expectedLatest,
                                                    ChatContracts.Turn replacement) {
        return enqueue(() -> {
            Path file = findConversation(world, conversationId);
            try {
                if (!expectedScope.equals(replacement.context().scope())
                        || replacement.sources().stream().anyMatch(source -> !expectedScope.equals(source.scope()))) {
                    throw new IOException("turn evidence scope does not match the submitted request scope");
                }
                if (!hasConversationHeader(file, world, conversationId)) throw new IOException("conversation header does not match append target");
                ChatContracts.Conversation conversation = readConversation(file, world);
                if (conversation == null || conversation.turns().isEmpty()
                        || !conversation.turns().getLast().requestId().equals(expectedLatest)) {
                    throw new IOException("latest conversation turn changed before replacement");
                }
                Path temporary = file.resolveSibling(file.getFileName() + ".replace-" + UUID.randomUUID() + ".tmp");
                try {
                    StringBuilder contents = new StringBuilder(conversationJson(conversation)).append('\n');
                    for (int index = 0; index < conversation.turns().size() - 1; index++) {
                        contents.append(turnJson(world, conversation.turns().get(index))).append('\n');
                    }
                    contents.append(turnJson(world, replacement)).append('\n');
                    Files.writeString(temporary, contents, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                refreshSummary(file, world);
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletionStage<Void> recordCompletion(ChatContracts.WorldIdentity world, String conversationId,
                                                   ChatContracts.RequestId requestId) {
        Objects.requireNonNull(requestId);
        return enqueue(() -> {
            Path file = findConversation(world, conversationId);
            ChatContracts.Conversation conversation = readConversation(file, world);
            ChatContracts.Turn latestCompleted = conversation == null ? null : conversation.turns().stream()
                    .filter(turn -> turn.outcome().status() == ChatContracts.OutcomeStatus.COMPLETED)
                    .reduce((firstTurn, lastTurn) -> lastTurn).orElse(null);
            if (latestCompleted == null || !latestCompleted.requestId().equals(requestId)) return;
            CompletionState state = readCompletionState(file);
            if (requestId.value().equals(state.latestCompletedRequestId())) return;
            try {
                writeCompletionState(file, new CompletionState(requestId.value(), state.acknowledgedRequestId()));
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletionStage<Void> markRead(ChatContracts.WorldIdentity world, String conversationId,
                                          ChatContracts.RequestId expectedCompletion) {
        Objects.requireNonNull(expectedCompletion);
        return enqueue(() -> {
            Path file = findConversation(world, conversationId);
            ChatContracts.Conversation conversation = readConversation(file, world);
            ChatContracts.Turn latestCompleted = conversation == null ? null : conversation.turns().stream()
                    .filter(turn -> turn.outcome().status() == ChatContracts.OutcomeStatus.COMPLETED)
                    .reduce((firstTurn, lastTurn) -> lastTurn).orElse(null);
            if (latestCompleted == null || !latestCompleted.requestId().equals(expectedCompletion)) return;
            CompletionState state = readCompletionState(file);
            if (!expectedCompletion.value().equals(state.latestCompletedRequestId())
                    || expectedCompletion.value().equals(state.acknowledgedRequestId())) return;
            try {
                writeCompletionState(file, new CompletionState(state.latestCompletedRequestId(), expectedCompletion.value()));
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletionStage<Void> appendMedia(String conversationId, ChatContracts.Screenshot screenshot, byte[] bytes) {
        throw new IllegalArgumentException("world identity is required when appending media");
    }

    public CompletionStage<Void> appendMedia(ChatContracts.WorldIdentity world, String conversationId,
                                             ChatContracts.Screenshot screenshot, byte[] bytes) {
        return enqueue(() -> {
            Path file = findConversation(world, conversationId);
            try {
                Path media = file.getParent().resolve("media").resolve(assetName(screenshot.assetId()));
                if (!safePath(media)) throw new IOException("media path escapes conversation root");
                if (screenshot.bytes() != bytes.length) throw new IOException("media byte count does not match screenshot metadata");
                if (!hasConversationHeader(file, world, conversationId)) {
                    throw new IOException("conversation header does not match media target");
                }
                Files.createDirectories(media.getParent());
                try (FileChannel channel = FileChannel.open(media, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    channel.write(ByteBuffer.wrap(bytes));
                }
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        });
    }

    public Path conversationPath(ChatContracts.WorldIdentity world, String conversationId) {
        return worldDirectory(world).resolve(safeName(conversationId) + "-" + digest(conversationId) + ".jsonl");
    }

    public CompletionStage<byte[]> readMedia(ChatContracts.WorldIdentity world, String conversationId,
                                             ChatContracts.Screenshot screenshot) {
        return enqueueRead(() -> {
            Path file = findConversation(world, conversationId);
            Path media = file.getParent().resolve("media").resolve(assetName(screenshot.assetId()));
            try {
                if (!safePath(media)) throw new IOException("media path escapes conversation root");
                byte[] bytes = Files.readAllBytes(media);
                if (bytes.length != screenshot.bytes()) throw new IOException("media byte count does not match screenshot metadata");
                return bytes;
            } catch (IOException exception) {
                report(exception);
                throw new RuntimeException(exception);
            }
        });
    }

    public List<String> errors() {
        synchronized (errors) {
            return List.copyOf(errors);
        }
    }

    private CompletableFuture<Void> enqueue(Runnable operation) {
        synchronized (writeLock) {
            writes = writes.handle((ignored, failure) -> null).thenRunAsync(operation, ioExecutor);
            return writes;
        }
    }

    private <T> CompletableFuture<T> enqueueRead(Supplier<T> operation) {
        synchronized (writeLock) {
            CompletableFuture<T> result = writes.handle((ignored, failure) -> null).thenApplyAsync(ignored -> operation.get(), ioExecutor);
            writes = result.handle((ignored, failure) -> null);
            return result;
        }
    }

    private <T> CompletableFuture<T> enqueueCatalogRead(Supplier<T> operation) {
        synchronized (writeLock) {
            // Catalog reads observe prior saves, but never become a dependency of a selected-chat read or save.
            return writes.handle((ignored, failure) -> null).thenApplyAsync(ignored -> operation.get(), catalogExecutor);
        }
    }

    private Path findConversation(ChatContracts.WorldIdentity world, String conversationId) {
        try {
            Path file = conversationPath(world, conversationId);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && safePath(file)
                    && hasConversationHeader(file, world, conversationId)) return file;
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !safePath(root)) {
                throw new IOException("conversation does not exist: " + conversationId);
            }
            String fileName = file.getFileName().toString();
            try (var paths = Files.walk(root, 2)) {
                for (Path candidate : paths.filter(path -> path.getFileName().toString().equals(fileName)
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && safePath(path)).toList()) {
                    if (hasConversationHeader(candidate, world, conversationId)) return candidate;
                }
            }
            throw new IOException("conversation does not exist: " + conversationId);
        } catch (IOException exception) {
            report(exception);
            throw new RuntimeException(exception);
        }
    }

    private ChatContracts.Conversation readConversation(Path file, ChatContracts.WorldIdentity world) {
        List<ChatContracts.Turn> turns = new ArrayList<>();
        String id = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
        try {
            if (!hasWorldHeader(file, world)) return null;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).trim();
                if (line.isEmpty()) continue;
                try {
                    JsonObject value = JsonParser.parseString(line).getAsJsonObject();
                    if ("conversation".equals(value.get("type").getAsString())) {
                        if (!world.worldOrServer().equals(value.get("world").getAsString())) return null;
                        id = value.get("id").getAsString();
                        if (!(safeName(id) + "-" + digest(id) + ".jsonl").equals(file.getFileName().toString())) return null;
                    }
                    if ("turn".equals(value.get("type").getAsString())) {
                        if (value.has("world") && !world.worldOrServer().equals(value.get("world").getAsString())) return null;
                        ChatContracts.Turn turn = turnFromJson(value);
                        if (turn.sources().stream().anyMatch(source -> !turn.context().scope().equals(source.scope()))
                                || turn.navigationTargets().stream().anyMatch(target -> !turn.context().scope().equals(target.scope()))) {
                            report(new IOException("turn evidence scope mismatch in " + file));
                            continue;
                        }
                        if (value.has("toolExchanges") && !value.getAsJsonArray("toolExchanges").isEmpty()) {
                            report(new IOException("legacy tool history is not replayable in " + file));
                        }
                        turns.add(turn);
                    }
                } catch (RuntimeException exception) {
                    if (index == lines.size() - 1) {
                        report(new IOException("truncated final conversation record in " + file, exception));
                        break;
                    }
                    report(new IOException("invalid conversation record in " + file, exception));
                }
            }
            ChatContracts.Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
            return new ChatContracts.Conversation(id, world, turns,
                    last == null ? new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null) : last.outcome());
        } catch (IOException exception) {
            report(exception);
            return null;
        }
    }

    private static ChatContracts.Conversation emptyConversation(ChatContracts.WorldIdentity world, String id) {
        return new ChatContracts.Conversation(id, world, List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null));
    }

    private static String conversationJson(ChatContracts.Conversation conversation) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "conversation");
        value.addProperty("id", conversation.id());
        value.addProperty("world", conversation.world().worldOrServer());
        value.addProperty("label", conversation.world().readableLabel());
        value.addProperty("updatedAt", Instant.now().toString());
        return value.toString();
    }

    private static String turnJson(ChatContracts.WorldIdentity world, ChatContracts.Turn turn) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "turn");
        value.addProperty("world", world.worldOrServer());
        value.addProperty("requestId", turn.requestId().value());
        value.addProperty("question", turn.question());
        value.addProperty("answer", turn.answer());
        value.addProperty("tokenCount", turn.tokenCount());
        if (turn.sentAt() != null) value.addProperty("sentAt", turn.sentAt().toString());
        if (turn.durationMillis() != null) value.addProperty("durationMillis", turn.durationMillis());
        value.addProperty("outcome", turn.outcome().status().name());
        if (turn.outcome().detail() != null) value.addProperty("outcomeDetail", turn.outcome().detail());
        JsonArray replay = new JsonArray();
        turn.replayItems().forEach(item -> replay.add(replayJson(item)));
        value.add("replayItems", replay);
        value.add("context", contextJson(turn.context()));
        JsonArray sources = new JsonArray();
        turn.sources().forEach(source -> sources.add(sourceJson(source)));
        value.add("sources", sources);
        JsonArray targets = new JsonArray();
        turn.navigationTargets().forEach(target -> targets.add(navigationTargetJson(target)));
        value.add("navigationTargets", targets);
        JsonArray activities = new JsonArray();
        turn.activities().forEach(step -> {
            JsonObject item = new JsonObject();
            item.addProperty("id", step.id());
            item.addProperty("title", step.title());
            item.addProperty("detail", step.detail());
            item.addProperty("status", step.status().name());
            if (step.serializedArguments() != null) item.addProperty("serializedArguments", step.serializedArguments());
            if (step.toolName() != null) item.addProperty("toolName", step.toolName());
            if (step.resultSummary() != null) item.addProperty("resultSummary", step.resultSummary());
            if (step.durationMillis() != null) item.addProperty("durationMillis", step.durationMillis());
            if (step.resultDetail() != null) item.addProperty("resultDetail", step.resultDetail());
            activities.add(item);
        });
        value.add("activities", activities);
        JsonArray steeringMessages = new JsonArray();
        turn.steeringMessages().forEach(message -> {
            JsonObject item = new JsonObject();
            item.addProperty("text", message.text());
            item.addProperty("answerOffset", message.answerOffset());
            steeringMessages.add(item);
        });
        value.add("steeringMessages", steeringMessages);
        return value.toString();
    }

    private static JsonObject navigationTargetJson(Evidence.NavigationTarget target) {
        JsonObject value = new JsonObject();
        value.add("scope", scopeJson(target.scope()));
        value.addProperty("providerId", target.providerId());
        value.addProperty("sourceHandle", target.sourceHandle());
        value.addProperty("targetId", target.targetId());
        return value;
    }

    private static JsonObject replayJson(dev.packsage.model.ModelBackend.InputItem item) {
        JsonObject value = new JsonObject();
        if (item instanceof dev.packsage.model.ModelBackend.Message message) {
            value.addProperty("type", "message");
            value.addProperty("role", message.role().name());
            JsonArray content = new JsonArray();
            for (dev.packsage.model.ModelBackend.Content part : message.content()) {
                JsonObject contentValue = new JsonObject();
                if (part instanceof dev.packsage.model.ModelBackend.TextContent text) {
                    contentValue.addProperty("type", "text");
                    contentValue.addProperty("text", text.text());
                } else if (part instanceof dev.packsage.model.ModelBackend.ImageContent image) {
                    contentValue.addProperty("type", "image");
                    contentValue.addProperty("dataUrl", image.dataUrl());
                }
                content.add(contentValue);
            }
            value.add("content", content);
        } else if (item instanceof dev.packsage.model.ModelBackend.EncryptedReasoning reasoning) {
            value.addProperty("type", "reasoning");
            value.addProperty("id", reasoning.id());
            value.addProperty("encryptedContent", reasoning.encryptedContent());
            value.addProperty("serializedSummary", reasoning.serializedSummary());
        } else if (item instanceof dev.packsage.model.ModelBackend.ToolCall call) {
            value.addProperty("type", "toolCall");
            value.addProperty("callId", call.callId());
            if (call.namespace() != null) value.addProperty("namespace", call.namespace());
            value.addProperty("name", call.name());
            value.addProperty("serializedArguments", call.serializedArguments());
        } else if (item instanceof dev.packsage.model.ModelBackend.ToolResult result) {
            value.addProperty("type", "toolResult");
            value.addProperty("callId", result.callId());
            value.addProperty("serializedResult", result.serializedResult());
            value.addProperty("error", result.error());
        } else if (item instanceof dev.packsage.model.ModelBackend.AssistantOutput output) {
            value.addProperty("type", "assistantOutput");
            value.addProperty("id", output.id());
            value.addProperty("status", output.status().name());
            if (output.phase() != null) value.addProperty("phase", output.phase().name());
            JsonArray content = new JsonArray();
            for (dev.packsage.model.ModelBackend.OutputContent part : output.content()) {
                JsonObject contentValue = new JsonObject();
                if (part instanceof dev.packsage.model.ModelBackend.OutputText text) {
                    contentValue.addProperty("type", "outputText");
                    contentValue.addProperty("text", text.text());
                    JsonArray citations = new JsonArray();
                    for (dev.packsage.model.ModelBackend.UrlCitation citation : text.citations()) {
                        JsonObject citationValue = new JsonObject();
                        citationValue.addProperty("startIndex", citation.startIndex());
                        citationValue.addProperty("endIndex", citation.endIndex());
                        citationValue.addProperty("title", citation.title());
                        citationValue.addProperty("url", citation.url());
                        citations.add(citationValue);
                    }
                    contentValue.add("citations", citations);
                } else if (part instanceof dev.packsage.model.ModelBackend.Refusal refusal) {
                    contentValue.addProperty("type", "refusal");
                    contentValue.addProperty("refusal", refusal.refusal());
                }
                content.add(contentValue);
            }
            value.add("content", content);
        } else if (item instanceof dev.packsage.model.ModelBackend.WebSearchCall search) {
            value.addProperty("type", "webSearchCall");
            value.addProperty("id", search.id());
            value.addProperty("status", search.status().name());
            JsonObject action = new JsonObject();
            if (search.action() instanceof dev.packsage.model.ModelBackend.SearchAction searchAction) {
                action.addProperty("type", "search");
                if (searchAction.query() != null) action.addProperty("query", searchAction.query());
                JsonArray queries = new JsonArray();
                searchAction.queries().forEach(queries::add);
                action.add("queries", queries);
                JsonArray sources = new JsonArray();
                searchAction.sources().forEach(source -> sources.add(source.url()));
                action.add("sources", sources);
            } else if (search.action() instanceof dev.packsage.model.ModelBackend.OpenPageAction open) {
                action.addProperty("type", "openPage");
                if (open.url() != null) action.addProperty("url", open.url());
            } else if (search.action() instanceof dev.packsage.model.ModelBackend.FindInPageAction find) {
                action.addProperty("type", "findInPage");
                action.addProperty("url", find.url());
                action.addProperty("pattern", find.pattern());
            }
            if (search.action() != null) value.add("action", action);
        }
        return value;
    }

    private static JsonObject contextJson(ChatContracts.Context context) {
        JsonObject value = new JsonObject();
        value.addProperty("serializedGameplayContext", context.serializedGameplayContext());
        value.add("scope", scopeJson(context.scope()));
        if (context.screenshot() == null) value.add("screenshot", com.google.gson.JsonNull.INSTANCE);
        else {
            JsonObject screenshot = new JsonObject();
            screenshot.addProperty("assetId", context.screenshot().assetId());
            screenshot.addProperty("mediaType", context.screenshot().mediaType());
            screenshot.addProperty("bytes", context.screenshot().bytes());
            screenshot.addProperty("capturedAt", context.screenshot().capturedAt().toString());
            value.add("screenshot", screenshot);
        }
        return value;
    }

    private static JsonObject scopeJson(Evidence.Scope scope) {
        JsonObject value = new JsonObject();
        value.addProperty("worldSession", scope.worldSession());
        value.addProperty("resourcesLocale", scope.resourcesLocale());
        value.addProperty("playerTeam", scope.playerTeam());
        value.addProperty("providerRuntime", scope.providerRuntime());
        value.addProperty("requestGeneration", scope.requestGeneration());
        return value;
    }

    private static JsonObject sourceJson(Evidence.Source source) {
        JsonObject value = new JsonObject();
        value.add("scope", scopeJson(source.scope()));
        value.addProperty("providerId", source.providerId());
        value.addProperty("providerVersion", source.providerVersion());
        value.addProperty("sourceHandle", source.sourceHandle());
        value.addProperty("observedGeneration", source.observedGeneration());
        value.addProperty("observedAt", source.observedAt().toString());
        value.addProperty("activity", source.activity().name());
        value.addProperty("coverage", source.coverage().name());
        value.addProperty("authority", source.authority().name());
        JsonArray provenance = new JsonArray();
        source.provenance().forEach(provenance::add);
        value.add("provenance", provenance);
        return value;
    }

    private static ChatContracts.Turn turnFromJson(JsonObject value) {
        JsonObject contextValue = value.getAsJsonObject("context");
        Evidence.Scope scope = scopeFromJson(contextValue.getAsJsonObject("scope"));
        JsonElement screenshotElement = contextValue.get("screenshot");
        ChatContracts.Screenshot screenshot = null;
        if (screenshotElement != null && !screenshotElement.isJsonNull()) {
            JsonObject screenshotValue = screenshotElement.getAsJsonObject();
            screenshot = new ChatContracts.Screenshot(screenshotValue.get("assetId").getAsString(),
                    screenshotValue.get("mediaType").getAsString(), screenshotValue.get("bytes").getAsLong(),
                    Instant.parse(screenshotValue.get("capturedAt").getAsString()));
        }
        List<dev.packsage.model.ModelBackend.InputItem> replay = new ArrayList<>();
        if (value.has("replayItems")) value.getAsJsonArray("replayItems")
                .forEach(item -> replay.add(replayFromJson(item.getAsJsonObject())));
        List<Evidence.Source> sources = new ArrayList<>();
        if (value.has("sources")) value.getAsJsonArray("sources").forEach(source -> sources.add(sourceFromJson(source.getAsJsonObject())));
        List<Evidence.NavigationTarget> targets = new ArrayList<>();
        if (value.has("navigationTargets")) value.getAsJsonArray("navigationTargets").forEach(target -> {
            JsonObject item = target.getAsJsonObject();
            targets.add(new Evidence.NavigationTarget(scopeFromJson(item.getAsJsonObject("scope")),
                    item.get("providerId").getAsString(), item.get("sourceHandle").getAsString(), item.get("targetId").getAsString()));
        });
        List<ChatContracts.ActivityStep> activities = new ArrayList<>();
        if (value.has("activities")) value.getAsJsonArray("activities").forEach(activity -> {
            JsonObject item = activity.getAsJsonObject();
            activities.add(new ChatContracts.ActivityStep(item.get("id").getAsString(), item.get("title").getAsString(),
                    item.has("detail") ? item.get("detail").getAsString() : "",
                    ChatContracts.ActivityStatus.valueOf(item.get("status").getAsString()),
                    item.has("serializedArguments") && !item.get("serializedArguments").isJsonNull()
                            ? item.get("serializedArguments").getAsString() : null,
                    item.has("toolName") ? item.get("toolName").getAsString() : null,
                    item.has("resultSummary") ? item.get("resultSummary").getAsString() : null,
                    item.has("durationMillis") ? item.get("durationMillis").getAsLong() : null,
                    item.has("resultDetail") && !item.get("resultDetail").isJsonNull() ? item.get("resultDetail").getAsString() : null));
        });
        List<ChatContracts.SteeringMessage> steeringMessages = new ArrayList<>();
        if (value.has("steeringMessages")) value.getAsJsonArray("steeringMessages").forEach(message -> {
            JsonObject item = message.getAsJsonObject();
            steeringMessages.add(new ChatContracts.SteeringMessage(item.get("text").getAsString(),
                    item.get("answerOffset").getAsInt()));
        });
        for (int i = 0; i < activities.size(); i++) {
            ChatContracts.ActivityStep activity = activities.get(i);
            if (activity.status() != ChatContracts.ActivityStatus.FAILED
                    || !(activity.detail().isBlank() || activity.detail().equals("Could not complete"))
                    || !activity.id().startsWith("tool:")) continue;
            String callId = activity.id().substring("tool:".length());
            for (dev.packsage.model.ModelBackend.InputItem replayItem : replay) {
                if (replayItem instanceof dev.packsage.model.ModelBackend.ToolResult result
                        && result.error() && result.callId().equals(callId)) {
                    activities.set(i, new ChatContracts.ActivityStep(activity.id(), activity.title(),
                            ActivityLabels.toolError(result.serializedResult()), activity.status(), activity.serializedArguments(),
                            activity.toolName(), activity.resultSummary(), activity.durationMillis(), activity.resultDetail()));
                    break;
                }
            }
        }
        String detail = value.has("outcomeDetail") ? value.get("outcomeDetail").getAsString() : null;
        return new ChatContracts.Turn(new ChatContracts.RequestId(value.get("requestId").getAsString()),
                value.get("question").getAsString(), value.get("answer").getAsString(), replay,
                new ChatContracts.Context(contextValue.get("serializedGameplayContext").getAsString(), screenshot, scope),
                sources, targets, new ChatContracts.Outcome(ChatContracts.OutcomeStatus.valueOf(value.get("outcome").getAsString()), detail),
                value.get("tokenCount").getAsInt(), value.has("sentAt") && !value.get("sentAt").isJsonNull()
                ? Instant.parse(value.get("sentAt").getAsString()) : null, activities, steeringMessages,
                value.has("durationMillis") ? value.get("durationMillis").getAsLong() : null);
    }

    private static dev.packsage.model.ModelBackend.InputItem replayFromJson(JsonObject value) {
        String type = value.get("type").getAsString();
        if ("message".equals(type)) {
            List<dev.packsage.model.ModelBackend.Content> content = new ArrayList<>();
            value.getAsJsonArray("content").forEach(item -> {
                JsonObject part = item.getAsJsonObject();
                if ("text".equals(part.get("type").getAsString())) {
                    content.add(new dev.packsage.model.ModelBackend.TextContent(part.get("text").getAsString()));
                } else if ("image".equals(part.get("type").getAsString())) {
                    content.add(new dev.packsage.model.ModelBackend.ImageContent(part.get("dataUrl").getAsString()));
                } else {
                    throw new IllegalArgumentException("unknown replay content type");
                }
            });
            return new dev.packsage.model.ModelBackend.Message(
                    dev.packsage.model.ModelBackend.Role.valueOf(value.get("role").getAsString()), content);
        }
        if ("reasoning".equals(type)) return new dev.packsage.model.ModelBackend.EncryptedReasoning(
                value.get("id").getAsString(), value.get("encryptedContent").getAsString(),
                value.get("serializedSummary").getAsString());
        if ("toolCall".equals(type)) return new dev.packsage.model.ModelBackend.ToolCall(value.get("callId").getAsString(),
                value.has("namespace") ? value.get("namespace").getAsString() : null, value.get("name").getAsString(),
                value.get("serializedArguments").getAsString());
        if ("toolResult".equals(type)) return new dev.packsage.model.ModelBackend.ToolResult(
                value.get("callId").getAsString(), value.get("serializedResult").getAsString(),
                value.get("error").getAsBoolean());
        if ("assistantOutput".equals(type)) {
            List<dev.packsage.model.ModelBackend.OutputContent> content = new ArrayList<>();
            value.getAsJsonArray("content").forEach(item -> {
                JsonObject part = item.getAsJsonObject();
                if ("outputText".equals(part.get("type").getAsString())) {
                    List<dev.packsage.model.ModelBackend.UrlCitation> citations = new ArrayList<>();
                    part.getAsJsonArray("citations").forEach(citation -> {
                        JsonObject citationValue = citation.getAsJsonObject();
                        citations.add(new dev.packsage.model.ModelBackend.UrlCitation(
                                citationValue.get("startIndex").getAsInt(), citationValue.get("endIndex").getAsInt(),
                                citationValue.get("title").getAsString(), citationValue.get("url").getAsString()));
                    });
                    content.add(new dev.packsage.model.ModelBackend.OutputText(part.get("text").getAsString(), citations));
                } else if ("refusal".equals(part.get("type").getAsString())) {
                    content.add(new dev.packsage.model.ModelBackend.Refusal(part.get("refusal").getAsString()));
                } else {
                    throw new IllegalArgumentException("unknown assistant output content type");
                }
            });
            return new dev.packsage.model.ModelBackend.AssistantOutput(value.get("id").getAsString(),
                    dev.packsage.model.ModelBackend.OutputStatus.valueOf(value.get("status").getAsString()),
                    value.has("phase") ? dev.packsage.model.ModelBackend.OutputPhase.valueOf(value.get("phase").getAsString()) : null,
                    content);
        }
        if ("webSearchCall".equals(type)) {
            if (!value.has("action") || value.get("action").isJsonNull()) {
                return new dev.packsage.model.ModelBackend.WebSearchCall(value.get("id").getAsString(),
                        dev.packsage.model.ModelBackend.WebSearchStatus.valueOf(value.get("status").getAsString()), null);
            }
            JsonObject action = value.getAsJsonObject("action");
            dev.packsage.model.ModelBackend.WebSearchAction searchAction;
            if ("search".equals(action.get("type").getAsString())) {
                List<String> queries = new ArrayList<>();
                action.getAsJsonArray("queries").forEach(query -> queries.add(query.getAsString()));
                List<dev.packsage.model.ModelBackend.WebSearchSource> sources = new ArrayList<>();
                action.getAsJsonArray("sources").forEach(source -> sources.add(
                        new dev.packsage.model.ModelBackend.WebSearchSource(source.getAsString())));
                searchAction = new dev.packsage.model.ModelBackend.SearchAction(
                        action.has("query") ? action.get("query").getAsString() : null, queries, sources);
            } else if ("openPage".equals(action.get("type").getAsString())) {
                searchAction = new dev.packsage.model.ModelBackend.OpenPageAction(
                        action.has("url") ? action.get("url").getAsString() : null);
            } else if ("findInPage".equals(action.get("type").getAsString())) {
                searchAction = new dev.packsage.model.ModelBackend.FindInPageAction(action.get("url").getAsString(),
                        action.get("pattern").getAsString());
            } else {
                throw new IllegalArgumentException("unknown web search action type");
            }
            return new dev.packsage.model.ModelBackend.WebSearchCall(value.get("id").getAsString(),
                    dev.packsage.model.ModelBackend.WebSearchStatus.valueOf(value.get("status").getAsString()), searchAction);
        }
        throw new IllegalArgumentException("unknown replay item type");
    }

    private static Evidence.Scope scopeFromJson(JsonObject value) {
        return new Evidence.Scope(value.get("worldSession").getAsString(), value.get("resourcesLocale").getAsString(),
                value.get("playerTeam").getAsString(), value.get("providerRuntime").getAsString(),
                value.get("requestGeneration").getAsLong());
    }

    private static Evidence.Source sourceFromJson(JsonObject value) {
        List<String> provenance = new ArrayList<>();
        value.getAsJsonArray("provenance").forEach(item -> provenance.add(item.getAsString()));
        return new Evidence.Source(scopeFromJson(value.getAsJsonObject("scope")), value.get("providerId").getAsString(),
                value.get("providerVersion").getAsString(), value.get("sourceHandle").getAsString(),
                value.get("observedGeneration").getAsLong(), Instant.parse(value.get("observedAt").getAsString()),
                Evidence.Activity.valueOf(value.get("activity").getAsString()), Evidence.Coverage.valueOf(value.get("coverage").getAsString()),
                Evidence.Authority.valueOf(value.get("authority").getAsString()), provenance);
    }

    private Path worldDirectory(ChatContracts.WorldIdentity world) {
        return root.resolve(safeName(world.readableLabel()) + "-" + digest(world.worldOrServer()));
    }

    private Path latestPath(ChatContracts.WorldIdentity world) {
        return worldDirectory(world).resolve("latest");
    }

    private Path latestPath(Path directory) {
        return directory.resolve("latest");
    }

    private CompletionState readCompletionState(Path conversationFile) {
        Path stateFile = conversationFile.resolveSibling(conversationFile.getFileName().toString()
                .replaceFirst("\\.jsonl$", ".state.json"));
        try {
            if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return new CompletionState(null, null);
            if (!safePath(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("conversation state file is unsafe");
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("conversation state is malformed");
            JsonObject value = parsed.getAsJsonObject();
            if (!value.has("latestCompletedRequestId") || !value.has("acknowledgedRequestId")) {
                throw new IOException("conversation state is malformed");
            }
            JsonElement latest = value.get("latestCompletedRequestId");
            JsonElement acknowledged = value.get("acknowledgedRequestId");
            if (!latest.isJsonNull() && (!latest.isJsonPrimitive() || !latest.getAsJsonPrimitive().isString())
                    || !acknowledged.isJsonNull() && (!acknowledged.isJsonPrimitive()
                    || !acknowledged.getAsJsonPrimitive().isString())) {
                throw new IOException("conversation state is malformed");
            }
            return new CompletionState(latest.isJsonNull() ? null : latest.getAsString(),
                    acknowledged.isJsonNull() ? null : acknowledged.getAsString());
        } catch (IOException | RuntimeException exception) {
            report(exception instanceof IOException ioException ? ioException
                    : new IOException("conversation state is malformed", exception));
            return new CompletionState(null, null);
        }
    }

    private void writeCompletionState(Path conversationFile, CompletionState state) throws IOException {
        Path stateFile = conversationFile.resolveSibling(conversationFile.getFileName().toString()
                .replaceFirst("\\.jsonl$", ".state.json"));
        if (!safePath(stateFile)) throw new IOException("conversation state path escapes conversation root");
        JsonObject value = new JsonObject();
        value.addProperty("latestCompletedRequestId", state.latestCompletedRequestId());
        value.addProperty("acknowledgedRequestId", state.acknowledgedRequestId());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + "-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, value.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temporary, stateFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeLatest(ChatContracts.WorldIdentity world, String conversationId) throws IOException {
        writeLatest(worldDirectory(world), conversationId);
    }

    private void writeLatest(Path directory, String conversationId) throws IOException {
        Path latest = latestPath(directory);
        if (!safePath(latest)) throw new IOException("latest marker escapes conversation root");
        Path temporary = latest.resolveSibling("latest-" + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, conversationId, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, latest, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void appendBytes(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            channel.write(ByteBuffer.wrap(bytes));
        }
    }

    private boolean hasWorldHeader(Path file, ChatContracts.WorldIdentity world) throws IOException {
        return hasConversationHeader(file, world, null);
    }

    private boolean hasConversationHeader(Path file, ChatContracts.WorldIdentity world,
                                          String conversationId) throws IOException {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).findFirst().map(line -> {
                try {
                    JsonObject value = JsonParser.parseString(line).getAsJsonObject();
                    return "conversation".equals(value.get("type").getAsString())
                            && world.worldOrServer().equals(value.get("world").getAsString())
                            && (conversationId == null || conversationId.equals(value.get("id").getAsString()));
                } catch (RuntimeException exception) {
                    return false;
                }
            }).orElse(false);
        }
    }

    private boolean safePath(Path path) {
        try {
            Path absoluteRoot = root.toAbsolutePath().normalize();
            Path absolutePath = path.toAbsolutePath().normalize();
            if (!absolutePath.startsWith(absoluteRoot)) return false;
            Path existingRoot = absoluteRoot;
            while (existingRoot != null && !Files.exists(existingRoot, LinkOption.NOFOLLOW_LINKS)) {
                existingRoot = existingRoot.getParent();
            }
            if (existingRoot == null) return false;
            BasicFileAttributes rootAttributes = Files.readAttributes(existingRoot, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (rootAttributes.isSymbolicLink() || rootAttributes.isOther()) return false;
            Path current = absoluteRoot;
            for (Path part : absoluteRoot.relativize(absolutePath)) {
                current = current.resolve(part);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther()) return false;
                    if (!current.toRealPath().startsWith(existingRoot.toRealPath())) return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            report(exception);
            return Long.MIN_VALUE;
        }
    }

    private void report(Exception exception) {
        synchronized (errors) {
            errors.add(exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage());
        }
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "conversation" : safe;
    }

    private static String assetName(String value) {
        String safe = safeName(value);
        int extension = safe.lastIndexOf('.');
        return extension > 0 ? safe.substring(0, extension) + "-" + digest(value) + safe.substring(extension)
                : safe + "-" + digest(value);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
