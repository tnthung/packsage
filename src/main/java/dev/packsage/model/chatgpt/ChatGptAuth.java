package dev.packsage.model.chatgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.packsage.model.ModelBackend;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class ChatGptAuth {
    public static final String ISSUER = "https://auth.openai.com";
    public static final String AUTHORIZE_ENDPOINT = ISSUER + "/api/accounts/authorize";
    public static final String TOKEN_ENDPOINT = ISSUER + "/api/accounts/oauth/token";
    public static final String REQUIRED_SCOPE = "chatgpt.tokens.use.direct";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CALLBACK_TIMEOUT = Duration.ofMinutes(5);
    private static final int REVOCATION_ATTEMPTS = 3;
    private final Path credentialFile;
    private final Path registrationFile;
    private final Path hostIdFile;
    private final SignInFlow signInFlow;
    private final HttpClient httpClient;
    private final Object lock = new Object();
    private Credential credential;
    private Registration registration;
    private CompletableFuture<ModelBackend.AuthState> pendingSignIn;
    private CompletableFuture<Credential> refreshInFlight;
    private long sessionEpoch;
    private volatile String lastDetail;

    public ChatGptAuth(Path credentialFile, SignInFlow signInFlow) {
        this.credentialFile = credentialFile.toAbsolutePath().normalize();
        this.registrationFile = this.credentialFile.resolveSibling(this.credentialFile.getFileName() + ".registration.json");
        this.hostIdFile = this.credentialFile.resolveSibling(this.credentialFile.getFileName() + ".host-id");
        this.signInFlow = signInFlow;
        this.httpClient = null;
        load();
    }

    public ChatGptAuth(Path credentialFile) {
        this(credentialFile, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), uri -> {
            try {
                if (!Desktop.isDesktopSupported()) throw new IllegalStateException("system browser is unavailable");
                Desktop.getDesktop().browse(uri);
            } catch (IOException error) {
                throw new IllegalStateException("cannot open system browser", error);
            }
        });
    }

    public ChatGptAuth(Path credentialFile, HttpClient client, Consumer<URI> browserOpener) {
        this.credentialFile = credentialFile.toAbsolutePath().normalize();
        this.registrationFile = this.credentialFile.resolveSibling(this.credentialFile.getFileName() + ".registration.json");
        this.hostIdFile = this.credentialFile.resolveSibling(this.credentialFile.getFileName() + ".host-id");
        this.httpClient = client;
        this.signInFlow = new NetworkFlow(client, browserOpener);
        load();
    }

    public static Path defaultCredentialFile() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(localAppData);
        return root.resolve("PackSage").resolve("auth").resolve("chatgpt-credentials.json");
    }

    public CompletionStage<ModelBackend.AuthState> signIn() {
        CompletableFuture<ModelBackend.AuthState> result;
        synchronized (lock) {
            if (pendingSignIn != null) {
                return CompletableFuture.completedFuture(new ModelBackend.AuthState(ModelBackend.AuthStatus.FAILED, null, "a sign-in attempt is already active"));
            }
            result = new CompletableFuture<>();
            pendingSignIn = result;
            lastDetail = "Waiting for browser sign-in";
        }
        final CompletionStage<ModelBackend.AuthState> started;
        try {
            started = signInFlow.start(this, result);
        } catch (RuntimeException error) {
            synchronized (lock) { if (pendingSignIn == result) pendingSignIn = null; }
            result.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.FAILED, null, error.getMessage()));
            return result;
        }
        started.whenComplete((state, failure) -> {
            synchronized (lock) {
                if (pendingSignIn != result) return;
                pendingSignIn = null;
            }
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                lastDetail = cause.getMessage();
                result.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.FAILED, null, cause.getMessage()));
            } else {
                lastDetail = state.detail();
                result.complete(state);
            }
        });
        return result;
    }

    public void cancelSignIn() {
        CompletableFuture<ModelBackend.AuthState> result;
        synchronized (lock) {
            result = pendingSignIn;
            pendingSignIn = null;
        }
        if (result != null) {
            signInFlow.cancel();
            lastDetail = "Sign-in cancelled";
            result.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_OUT, null, lastDetail));
        }
    }

    public CompletionStage<String> signOut() {
        cancelSignIn();
        Credential current;
        CompletableFuture<Credential> refresh;
        long epoch;
        synchronized (lock) {
            current = credential;
            refresh = refreshInFlight;
            credential = null;
            refreshInFlight = null;
            if (current != null || refresh != null) sessionEpoch++;
            epoch = sessionEpoch;
        }
        if (refresh != null) refresh.completeExceptionally(new CancellationException("signed out during token refresh"));
        if (current == null) return CompletableFuture.completedFuture("Signed out");
        if (httpClient == null) {
            try {
                Files.deleteIfExists(credentialFile);
                lastDetail = "Signed out";
            } catch (IOException error) {
                lastDetail = "Signed out in memory, but local credential deletion failed: " + error.getMessage();
            }
            return CompletableFuture.completedFuture(lastDetail);
        }
        lastDetail = "Signed out in memory; remote revocation is pending";
        return fetchOpenIdConfigurationForSignOut(1).thenCompose(configuration -> {
            if (!configuration.has("revocation_endpoint")) return CompletableFuture.failedFuture(new IllegalStateException("revocation endpoint is unavailable"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(configuration.get("revocation_endpoint").getAsString())).timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token", current.refreshToken(), "token_type_hint", "refresh_token", "client_id", current.clientId())))).build();
            return revoke(request, 1);
        }).handle((response, failure) -> {
            synchronized (lock) {
                if (sessionEpoch != epoch) {
                    return "Sign-out outcome belongs to an inactive session";
                }
                String deletionFailure = null;
                try {
                    Files.deleteIfExists(credentialFile);
                } catch (IOException error) {
                    deletionFailure = error.getMessage();
                }
                if (failure != null || response.statusCode() / 100 != 2) {
                    lastDetail = deletionFailure == null ? "Signed out locally; remote revocation was not confirmed"
                            : "Remote revocation was not confirmed and local credential deletion failed: " + deletionFailure;
                } else {
                    lastDetail = deletionFailure == null ? "Signed out and remote revocation was confirmed"
                            : "Remote revocation was confirmed, but local credential deletion failed: " + deletionFailure;
                }
                return lastDetail;
            }
        });
    }

    private CompletableFuture<HttpResponse<String>> revoke(HttpRequest request, int attempt) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handle((response, failure) -> {
            if ((failure != null || response.statusCode() / 100 == 5) && attempt < REVOCATION_ATTEMPTS) {
                CompletableFuture<HttpResponse<String>> retry = new CompletableFuture<>();
                CompletableFuture.delayedExecutor(200L << (attempt - 1), TimeUnit.MILLISECONDS).execute(() ->
                        revoke(request, attempt + 1).whenComplete((retried, retryFailure) -> {
                            if (retryFailure == null) retry.complete(retried);
                            else retry.completeExceptionally(retryFailure);
                        }));
                return retry;
            }
            if (failure != null) return CompletableFuture.<HttpResponse<String>>failedFuture(failure);
            return CompletableFuture.completedFuture(response);
        }).thenCompose(value -> value);
    }

    private CompletableFuture<JsonObject> fetchOpenIdConfigurationForSignOut(int attempt) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ISSUER + "/.well-known/openid-configuration"))
                .timeout(REQUEST_TIMEOUT).GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handle((response, failure) -> {
            if ((failure != null || response.statusCode() / 100 == 5) && attempt < REVOCATION_ATTEMPTS) {
                CompletableFuture<JsonObject> retry = new CompletableFuture<>();
                CompletableFuture.delayedExecutor(200L << (attempt - 1), TimeUnit.MILLISECONDS).execute(() ->
                        fetchOpenIdConfigurationForSignOut(attempt + 1).whenComplete((retried, retryFailure) -> {
                            if (retryFailure == null) retry.complete(retried);
                            else retry.completeExceptionally(retryFailure);
                        }));
                return retry;
            }
            if (failure != null) return CompletableFuture.<JsonObject>failedFuture(failure);
            if (response.statusCode() / 100 != 2) {
                return CompletableFuture.<JsonObject>failedFuture(new IllegalStateException("OpenID configuration failed: HTTP " + response.statusCode()));
            }
            return CompletableFuture.completedFuture(JsonParser.parseString(response.body()).getAsJsonObject());
        }).thenCompose(value -> value);
    }

    public CompletionStage<String> accessToken() {
        Credential current;
        synchronized (lock) { current = credential; }
        if (current == null || current.accessToken() == null) return CompletableFuture.failedFuture(new IllegalStateException("ChatGPT is signed out"));
        if (current.expiresAtEpochSecond() > System.currentTimeMillis() / 1000 + 30) return CompletableFuture.completedFuture(current.accessToken());
        return refreshNow().thenApply(Credential::accessToken);
    }

    public CompletionStage<Credential> refreshNow() {
        Credential current;
        long epoch;
        CompletableFuture<Credential> result;
        synchronized (lock) {
            if (credential == null) return CompletableFuture.failedFuture(new IllegalStateException("ChatGPT is signed out"));
            if (refreshInFlight != null) return refreshInFlight;
            current = credential;
            epoch = sessionEpoch;
            result = new CompletableFuture<>();
            refreshInFlight = result;
        }
        final CompletionStage<Credential> refresh;
        try {
            refresh = signInFlow.refresh(current);
        } catch (RuntimeException error) {
            synchronized (lock) { if (refreshInFlight == result) refreshInFlight = null; }
            result.completeExceptionally(error);
            return result;
        }
        refresh.whenComplete((refreshed, failure) -> {
            synchronized (lock) {
                if (refreshInFlight != result || sessionEpoch != epoch || credential != current) {
                    result.completeExceptionally(new CancellationException("token refresh belongs to an inactive session"));
                    return;
                }
                refreshInFlight = null;
                if (failure != null) {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                    if (cause instanceof OAuthFailure oauth && "invalid_grant".equals(oauth.code)) {
                        credential = null;
                        sessionEpoch++;
                        try {
                            Files.deleteIfExists(credentialFile);
                            lastDetail = "Refresh token was rejected; sign in again";
                        } catch (IOException error) {
                            lastDetail = "Refresh token was rejected and local credential deletion failed: " + error.getMessage();
                        }
                    } else {
                        lastDetail = "Token refresh failed: " + cause.getMessage();
                    }
                    result.completeExceptionally(cause);
                    return;
                }
                try {
                    requireCredential(refreshed);
                    if (!current.issuer().equals(refreshed.issuer()) || !current.subject().equals(refreshed.subject())
                            || !current.clientId().equals(refreshed.clientId()) || !current.hostId().equals(refreshed.hostId())) {
                        throw new IllegalArgumentException("refreshed credential identity changed");
                    }
                    writeCredential(credentialFile, refreshed);
                    credential = refreshed;
                    lastDetail = "Access token refreshed";
                    result.complete(refreshed);
                } catch (RuntimeException error) {
                    lastDetail = "Token refresh could not be saved: " + error.getMessage();
                    result.completeExceptionally(error);
                }
            }
        });
        return result;
    }

    public void saveCredential(Credential value) {
        requireCredential(value);
        synchronized (lock) {
            Registration saved = new Registration(value.clientId(), value.subject(), value.hostId());
            writeProtected(hostIdFile, value.hostId() + System.lineSeparator());
            writeRegistration(registrationFile, saved);
            writeCredential(credentialFile, value);
            registration = saved;
            credential = value;
            sessionEpoch++;
            lastDetail = "Credential saved";
        }
    }

    private void saveCredential(CompletableFuture<ModelBackend.AuthState> attempt, Credential value) {
        requireCredential(value);
        synchronized (lock) {
            if (pendingSignIn != attempt || attempt.isDone()) throw new CancellationException("sign-in attempt is no longer active");
            if (registration != null && (!registration.clientId().equals(value.clientId()) || !registration.subject().equals(value.subject()))) {
                throw new IllegalArgumentException("returning sign-in identity does not match the saved registration");
            }
            Registration saved = new Registration(value.clientId(), value.subject(), value.hostId());
            writeProtected(hostIdFile, value.hostId() + System.lineSeparator());
            writeRegistration(registrationFile, saved);
            writeCredential(credentialFile, value);
            registration = saved;
            credential = value;
            sessionEpoch++;
        }
    }

    public Credential credential() {
        synchronized (lock) { return credential; }
    }

    public String lastDetail() { return lastDetail; }

    public static String randomState() { return randomToken(32); }
    public static String randomNonce() { return randomToken(32); }
    public static String codeVerifier() { return randomToken(48); }

    public static String codeChallenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    public static URI authorizeUri(String clientId, String agentName, String hostId, String redirectUri, String state, String nonce, String challenge) {
        String query = "client_id=" + encode(clientId) + (agentName == null ? "" : "&agent_name_hint=" + encode(agentName)) + "&ext_agent_host_id=" + encode(hostId)
                + "&response_type=code&redirect_uri=" + encode(redirectUri) + "&scope=" + encode("openid profile email offline_access resource.invoke " + REQUIRED_SCOPE)
                + "&resource=" + encode("https://api.openai.com/v1") + "&state=" + encode(state) + "&nonce=" + encode(nonce)
                + "&code_challenge_method=S256&code_challenge=" + encode(challenge);
        return URI.create(AUTHORIZE_ENDPOINT + "?" + query);
    }

    public static boolean hasDirectScope(List<String> scopes) {
        return scopes != null && scopes.contains(REQUIRED_SCOPE);
    }

    public static String validateIdToken(String token, String jwks, String expectedClientId, String expectedNonce, Instant now) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) throw new IllegalArgumentException("malformed ID token");
            JsonObject header = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!header.has("alg") || !"RS256".equals(header.get("alg").getAsString())) throw new IllegalArgumentException("unsupported ID token algorithm");
            if (!header.has("kid") || header.get("kid").getAsString().isBlank()) throw new IllegalArgumentException("ID token key id is missing");
            String kid = header.get("kid").getAsString();
            JsonObject keySet = JsonParser.parseString(jwks).getAsJsonObject();
            if (!keySet.has("keys") || !keySet.get("keys").isJsonArray()) throw new IllegalArgumentException("JWKS keys are unavailable");
            JsonObject key = null;
            for (JsonElement candidate : keySet.getAsJsonArray("keys")) {
                if (!candidate.isJsonObject() || !candidate.getAsJsonObject().has("kid")
                        || !kid.equals(candidate.getAsJsonObject().get("kid").getAsString())) continue;
                if (key != null) throw new IllegalArgumentException("ID token key id is ambiguous");
                key = candidate.getAsJsonObject();
            }
            if (key == null) throw new IllegalArgumentException("ID token key is unavailable");
            if (!key.has("kty") || !"RSA".equals(key.get("kty").getAsString())
                    || !key.has("use") || !"sig".equals(key.get("use").getAsString())
                    || !key.has("alg") || !"RS256".equals(key.get("alg").getAsString())
                    || !key.has("n") || !key.has("e")) {
                throw new IllegalArgumentException("ID token key is not an RS256 signing key");
            }
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new BigInteger(1, Base64.getUrlDecoder().decode(key.get("n").getAsString())),
                    new BigInteger(1, Base64.getUrlDecoder().decode(key.get("e").getAsString()))));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) throw new IllegalArgumentException("invalid ID token signature");
            JsonObject claims = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!claims.has("iss") || !ISSUER.equals(claims.get("iss").getAsString())) throw new IllegalArgumentException("ID token issuer does not match");
            boolean audienceMatches = false;
            if (claims.has("aud") && claims.get("aud").isJsonPrimitive() && claims.getAsJsonPrimitive("aud").isString()) {
                audienceMatches = expectedClientId.equals(claims.get("aud").getAsString());
            } else if (claims.has("aud") && claims.get("aud").isJsonArray()) {
                for (JsonElement audience : claims.getAsJsonArray("aud")) {
                    if (audience.isJsonPrimitive() && audience.getAsJsonPrimitive().isString() && expectedClientId.equals(audience.getAsString())) audienceMatches = true;
                }
            }
            if (!audienceMatches) throw new IllegalArgumentException("ID token audience does not match");
            if (!claims.has("exp") || !claims.get("exp").isJsonPrimitive() || !claims.getAsJsonPrimitive("exp").isNumber()
                    || claims.get("exp").getAsLong() <= now.getEpochSecond()) throw new IllegalArgumentException("ID token is expired");
            if (!claims.has("nonce") || !expectedNonce.equals(claims.get("nonce").getAsString())) throw new IllegalArgumentException("ID token nonce does not match");
            if (!claims.has("sub") || claims.get("sub").getAsString().isBlank()) throw new IllegalArgumentException("ID token subject is missing");
            return claims.get("sub").getAsString();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid ID token", error);
        }
    }

    private void load() {
        RuntimeException credentialFailure = null;
        try {
            credential = readCredential(credentialFile);
        } catch (RuntimeException error) {
            credential = null;
            credentialFailure = error;
        }
        try {
            registration = readRegistration(registrationFile);
            if (registration == null && credential != null) {
                registration = new Registration(credential.clientId(), credential.subject(), credential.hostId());
                writeRegistration(registrationFile, registration);
            }
        } catch (RuntimeException error) {
            registration = null;
            lastDetail = "Saved ChatGPT registration is unavailable: " + error.getMessage();
        }
        if (credentialFailure != null) lastDetail = "Saved ChatGPT credentials are unavailable: " + credentialFailure.getMessage();
        if (registration != null && !Files.exists(hostIdFile, LinkOption.NOFOLLOW_LINKS)) {
            writeProtected(hostIdFile, registration.hostId() + System.lineSeparator());
        }
    }

    private String hostId() {
        try {
            if (Files.exists(hostIdFile, LinkOption.NOFOLLOW_LINKS)) {
                verifyProtected(hostIdFile, false);
                String value = Files.readString(hostIdFile, StandardCharsets.UTF_8).trim();
                if (!value.startsWith("urn:uuid:") || value.length() != 45) throw new IllegalStateException("saved host id is invalid");
                return value;
            }
            String value = "urn:uuid:" + UUID.randomUUID();
            writeProtected(hostIdFile, value + System.lineSeparator());
            return value;
        } catch (IOException error) {
            throw new IllegalStateException("host id could not be persisted", error);
        }
    }

    private static Credential readCredential(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            verifyProtected(path, false);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Credential value;
            if (content.stripLeading().startsWith("{")) {
                JsonObject record = JsonParser.parseString(content).getAsJsonObject();
                if (!record.has("version") || record.get("version").getAsInt() != 1) throw new IllegalArgumentException("unsupported credential version");
                JsonArray scopeValues = record.getAsJsonArray("granted_scopes");
                List<String> scopes = new ArrayList<>();
                for (JsonElement scope : scopeValues) scopes.add(scope.getAsString());
                value = new Credential(record.get("issuer").getAsString(), record.get("subject").getAsString(), record.get("client_id").getAsString(),
                        record.get("host_id").getAsString(), record.get("id_token").getAsString(), record.get("access_token").getAsString(),
                        record.get("refresh_token").getAsString(), record.get("token_type").getAsString(), record.get("expires_at").getAsLong(), scopes);
            } else {
                List<String> lines = content.lines().toList();
                if (lines.size() != 9) throw new IllegalArgumentException("legacy credential record is incomplete");
                value = new Credential(lines.get(0), lines.get(1), lines.get(2), lines.get(3), lines.get(4), lines.get(5), lines.get(6),
                        "Bearer", Long.parseLong(lines.get(7)), List.of(lines.get(8).split(" ")));
            }
            requireCredential(value);
            return value;
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("credential record could not be read", error);
        }
    }

    private static void writeCredential(Path path, Credential value) {
        JsonObject record = new JsonObject();
        record.addProperty("version", 1);
        record.addProperty("issuer", value.issuer());
        record.addProperty("subject", value.subject());
        record.addProperty("client_id", value.clientId());
        record.addProperty("host_id", value.hostId());
        record.addProperty("id_token", value.idToken());
        record.addProperty("access_token", value.accessToken());
        record.addProperty("refresh_token", value.refreshToken());
        record.addProperty("token_type", value.tokenType());
        record.addProperty("expires_at", value.expiresAtEpochSecond());
        JsonArray scopes = new JsonArray();
        value.grantedScopes().forEach(scopes::add);
        record.add("granted_scopes", scopes);
        record.addProperty("saved_at", Instant.now().toString());
        writeProtected(path, record.toString());
    }

    private static Registration readRegistration(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            verifyProtected(path, false);
            JsonObject record = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!record.has("version") || record.get("version").getAsInt() != 1) throw new IllegalArgumentException("unsupported registration version");
            return new Registration(record.get("client_id").getAsString(), record.get("subject").getAsString(), record.get("host_id").getAsString());
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("registration record could not be read", error);
        }
    }

    private static void writeRegistration(Path path, Registration value) {
        JsonObject record = new JsonObject();
        record.addProperty("version", 1);
        record.addProperty("client_id", value.clientId());
        record.addProperty("subject", value.subject());
        record.addProperty("host_id", value.hostId());
        writeProtected(path, record.toString());
    }

    private static void writeProtected(Path path, String content) {
        Path parent = path.getParent();
        if (parent == null) throw new IllegalStateException("protected storage path has no parent");
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            protect(parent, true);
            Files.deleteIfExists(temporary);
            Files.createFile(temporary);
            protect(temporary, false);
            Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            verifyProtected(temporary, false);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            verifyProtected(path, false);
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("protected storage could not be written", error);
        }
    }

    private static void protect(Path path, boolean directory) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(entry.build()));
            verifyProtected(path, directory);
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix == null) throw new IOException("owner-only file permissions are unsupported");
        Files.setPosixFilePermissions(path, directory
                ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        verifyProtected(path, directory);
    }

    private static void verifyProtected(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("protected storage must not be a symbolic link");
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            for (AclEntry entry : acl.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)) throw new IOException("protected storage ACL grants another principal access");
            }
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix == null) throw new IOException("owner-only file permissions are unsupported");
        Set<PosixFilePermission> expected = directory
                ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if (!posix.readAttributes().permissions().equals(expected)) throw new IOException("protected storage permissions are not owner-only");
    }

    private static void requireCredential(Credential value) {
        if (value == null || !ISSUER.equals(value.issuer()) || value.subject() == null || value.subject().isBlank()
                || value.clientId() == null || value.clientId().isBlank() || "dynamic_agent_client".equals(value.clientId())
                || value.hostId() == null || value.hostId().isBlank() || value.idToken() == null || value.idToken().isBlank()
                || value.accessToken() == null || value.accessToken().isBlank() || value.refreshToken() == null || value.refreshToken().isBlank()
                || value.tokenType() == null || !"Bearer".equalsIgnoreCase(value.tokenType()) || !hasDirectScope(value.grantedScopes())) {
            throw new IllegalArgumentException("credential is incomplete or lacks direct inference scope");
        }
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null) return result;
        for (String pair : value.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String form(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        values.forEach((key, value) -> {
            if (result.length() > 0) result.append('&');
            result.append(encode(key)).append('=').append(encode(value));
        });
        return result.toString();
    }

    private static CompletableFuture<JsonObject> fetchOpenIdConfiguration(HttpClient client) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ISSUER + "/.well-known/openid-configuration")).timeout(REQUEST_TIMEOUT).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("OpenID configuration failed: HTTP " + response.statusCode());
            return JsonParser.parseString(response.body()).getAsJsonObject();
        });
    }

    public record Credential(String issuer, String subject, String clientId, String hostId, String idToken,
                             String accessToken, String refreshToken, String tokenType, long expiresAtEpochSecond,
                             List<String> grantedScopes) {
        public Credential {
            grantedScopes = List.copyOf(grantedScopes);
        }

        public Credential(String issuer, String subject, String clientId, String hostId, String idToken,
                          String accessToken, String refreshToken, long expiresAtEpochSecond, List<String> grantedScopes) {
            this(issuer, subject, clientId, hostId, idToken, accessToken, refreshToken, "Bearer", expiresAtEpochSecond, grantedScopes);
        }
    }

    private record Registration(String clientId, String subject, String hostId) {
        private Registration {
            if (clientId == null || clientId.isBlank() || "dynamic_agent_client".equals(clientId)
                    || subject == null || subject.isBlank() || hostId == null || hostId.isBlank()) {
                throw new IllegalArgumentException("registration is incomplete");
            }
        }
    }

    @FunctionalInterface
    public interface SignInFlow {
        CompletionStage<ModelBackend.AuthState> start(ChatGptAuth auth, CompletableFuture<ModelBackend.AuthState> cancellation);
        default void cancel() { }
        default CompletionStage<Credential> refresh(Credential credential) {
            return CompletableFuture.failedFuture(new IllegalStateException("refresh flow is not configured"));
        }
    }

    private static final class NetworkFlow implements SignInFlow {
        private final HttpClient client;
        private final Consumer<URI> browser;
        private final Object lock = new Object();
        private Attempt active;

        private NetworkFlow(HttpClient client, Consumer<URI> browser) {
            this.client = client;
            this.browser = browser;
        }

        @Override
        public CompletionStage<ModelBackend.AuthState> start(ChatGptAuth auth, CompletableFuture<ModelBackend.AuthState> cancellation) {
            HttpServer server = null;
            Attempt attempt = null;
            try {
                String verifier = codeVerifier();
                String state = randomState();
                String nonce = randomNonce();
                String hostId = auth.hostId();
                Registration saved;
                synchronized (auth.lock) { saved = auth.registration; }
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback";
                CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
                Attempt startedAttempt = new Attempt(server, callback, state, saved, cancellation);
                attempt = startedAttempt;
                server.createContext("/auth/callback", exchange -> receiveCallback(exchange, startedAttempt));
                synchronized (lock) {
                    if (active != null) throw new IllegalStateException("a sign-in listener is already active");
                    active = startedAttempt;
                }
                server.start();
                String clientId = saved == null ? "dynamic_agent_client" : saved.clientId();
                browser.accept(authorizeUri(clientId, saved == null ? "PackSage" : null, hostId, redirect, state, nonce, codeChallenge(verifier)));
                CompletableFuture.delayedExecutor(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).execute(() ->
                        callback.completeExceptionally(new TimeoutException("browser sign-in timed out")));
                return callback.thenCompose(values -> exchangeCode(values, clientId, verifier, redirect, auth, nonce, hostId, cancellation))
                        .whenComplete((ignored, failure) -> stop(startedAttempt));
            } catch (Exception error) {
                if (attempt != null) stop(attempt);
                else if (server != null) server.stop(0);
                return CompletableFuture.failedFuture(error);
            }
        }

        private void receiveCallback(HttpExchange exchange, Attempt attempt) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod()) || !"/auth/callback".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 404, "This is not the active PackSage sign-in callback.");
                return;
            }
            Map<String, String> values = query(exchange.getRequestURI().getRawQuery());
            if (!attempt.state().equals(values.get("state"))) {
                respond(exchange, 400, "The sign-in response did not match this PackSage attempt. You may retry in the original browser tab.");
                return;
            }
            if (values.containsKey("error")) {
                respond(exchange, 200, "Sign-in was not completed. Return to PackSage for details.");
                String detail = values.get("error") + (values.containsKey("error_description") ? ": " + values.get("error_description") : "");
                attempt.callback().completeExceptionally(new IllegalStateException(detail));
                return;
            }
            if (!values.containsKey("code") || values.get("code").isBlank()) {
                respond(exchange, 400, "The authorization response did not include a code.");
                attempt.callback().completeExceptionally(new IllegalArgumentException("authorization code is missing"));
                return;
            }
            respond(exchange, 200, "Sign-in complete. Return to PackSage.");
            attempt.callback().complete(values);
        }

        private static void respond(HttpExchange exchange, int status, String message) throws IOException {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        }

        private CompletionStage<ModelBackend.AuthState> exchangeCode(Map<String, String> values, String pendingClientId, String verifier,
                                                                       String redirect, ChatGptAuth auth, String nonce, String hostId,
                                                                       CompletableFuture<ModelBackend.AuthState> attempt) {
            String callbackClientId = values.get("client_id");
            if (!"dynamic_agent_client".equals(pendingClientId) && callbackClientId != null && !pendingClientId.equals(callbackClientId)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("returning callback supplied a different client id"));
            }
            String issuedClientId = callbackClientId == null || callbackClientId.isBlank() ? pendingClientId : callbackClientId;
            if ("dynamic_agent_client".equals(issuedClientId)) return CompletableFuture.failedFuture(new IllegalStateException("registration did not issue a client id"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT)).timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "authorization_code", "client_id", issuedClientId,
                            "code", values.get("code"), "code_verifier", verifier, "redirect_uri", redirect,
                            "resource", "https://api.openai.com/v1")))).build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenCompose(response -> {
                if (response.statusCode() / 100 != 2) return CompletableFuture.failedFuture(oauthFailure("token exchange", response));
                JsonObject token = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!token.has("scope") || !token.get("scope").isJsonPrimitive()) return CompletableFuture.failedFuture(new IllegalStateException("token response did not include granted scopes"));
                List<String> scopes = List.of(token.get("scope").getAsString().strip().split("\\s+"));
                if (!hasDirectScope(scopes)) return CompletableFuture.failedFuture(new IllegalStateException("direct inference scope was not granted"));
                if (!token.has("id_token") || !token.has("access_token") || !token.has("refresh_token")) {
                    return CompletableFuture.failedFuture(new IllegalStateException("token response is incomplete"));
                }
                return fetchOpenIdConfiguration(client).thenCompose(configuration -> {
                    if (!configuration.has("jwks_uri")) return CompletableFuture.failedFuture(new IllegalStateException("JWKS endpoint is unavailable"));
                    HttpRequest jwksRequest = HttpRequest.newBuilder(URI.create(configuration.get("jwks_uri").getAsString())).timeout(REQUEST_TIMEOUT).GET().build();
                    return client.sendAsync(jwksRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                }).thenApply(jwksResponse -> {
                    if (jwksResponse.statusCode() / 100 != 2) throw new IllegalStateException("JWKS request failed: HTTP " + jwksResponse.statusCode());
                    String subject = validateIdToken(token.get("id_token").getAsString(), jwksResponse.body(), issuedClientId, nonce, Instant.now());
                    Credential saved = new Credential(ISSUER, subject, issuedClientId, hostId, token.get("id_token").getAsString(),
                            token.get("access_token").getAsString(), token.get("refresh_token").getAsString(),
                            token.has("token_type") ? token.get("token_type").getAsString() : "Bearer",
                            Instant.now().getEpochSecond() + (token.has("expires_in") ? token.get("expires_in").getAsLong() : 3600), scopes);
                    auth.saveCredential(attempt, saved);
                    return new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, subject, "Direct ChatGPT inference permission granted");
                });
            });
        }

        @Override
        public void cancel() {
            Attempt attempt;
            synchronized (lock) { attempt = active; }
            if (attempt != null) {
                attempt.callback().completeExceptionally(new CancellationException("sign-in cancelled"));
                stop(attempt);
            }
        }

        @Override
        public CompletionStage<Credential> refresh(Credential credential) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT)).timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "refresh_token", "client_id", credential.clientId(),
                            "refresh_token", credential.refreshToken(), "resource", "https://api.openai.com/v1")))).build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
                if (response.statusCode() / 100 != 2) throw oauthFailure("token refresh", response);
                JsonObject token = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!token.has("access_token") || !token.has("refresh_token")) throw new IllegalStateException("refresh response did not rotate both tokens");
                return new Credential(credential.issuer(), credential.subject(), credential.clientId(), credential.hostId(), credential.idToken(),
                        token.get("access_token").getAsString(), token.get("refresh_token").getAsString(),
                        token.has("token_type") ? token.get("token_type").getAsString() : credential.tokenType(),
                        Instant.now().getEpochSecond() + (token.has("expires_in") ? token.get("expires_in").getAsLong() : 3600), credential.grantedScopes());
            });
        }

        private static OAuthFailure oauthFailure(String operation, HttpResponse<String> response) {
            String detail = operation + " failed: HTTP " + response.statusCode();
            String code = null;
            try {
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                if (root.has("error") && root.get("error").isJsonPrimitive()) {
                    code = root.get("error").getAsString();
                    detail += " " + code;
                }
                if (root.has("error_description")) detail += " - " + root.get("error_description").getAsString();
            } catch (RuntimeException ignored) { }
            return new OAuthFailure(code, detail);
        }

        private void stop(Attempt attempt) {
            synchronized (lock) {
                if (active != attempt) return;
                active = null;
            }
            attempt.server().stop(0);
        }

        private record Attempt(HttpServer server, CompletableFuture<Map<String, String>> callback, String state,
                               Registration registration, CompletableFuture<ModelBackend.AuthState> result) {
        }
    }

    private static final class OAuthFailure extends IllegalStateException {
        private final String code;

        private OAuthFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
