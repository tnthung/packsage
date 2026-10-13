package dev.packsage.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class ConversationStore {
    private final Path root;
    private final Executor ioExecutor;
    private final Object writeLock = new Object();
    private CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);
    private final List<String> errors = new ArrayList<>();

    public ConversationStore(Path root, Executor ioExecutor) {
        this.root = root;
        this.ioExecutor = ioExecutor;
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
        return enqueueRead(() -> {
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
                        ChatContracts.Conversation conversation = readConversation(file, world);
                        if (conversation == null) continue;
                        ChatContracts.Turn first = conversation.turns().isEmpty() ? null : conversation.turns().getFirst();
                        ChatContracts.Turn last = conversation.turns().isEmpty() ? null : conversation.turns().getLast();
                        summaries.add(new ChatContracts.SessionSummary(world, conversation.id(),
                                first == null ? "New chat" : first.question(), last == null ? "" : last.answer(),
                                Instant.ofEpochMilli(modifiedAt(file))));
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
        String detail = value.has("outcomeDetail") ? value.get("outcomeDetail").getAsString() : null;
        return new ChatContracts.Turn(new ChatContracts.RequestId(value.get("requestId").getAsString()),
                value.get("question").getAsString(), value.get("answer").getAsString(), replay,
                new ChatContracts.Context(contextValue.get("serializedGameplayContext").getAsString(), screenshot, scope),
                sources, targets, new ChatContracts.Outcome(ChatContracts.OutcomeStatus.valueOf(value.get("outcome").getAsString()), detail),
                value.get("tokenCount").getAsInt(), value.has("sentAt") && !value.get("sentAt").isJsonNull()
                ? Instant.parse(value.get("sentAt").getAsString()) : null);
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
