package dev.packsage.model.chatgpt;

import dev.packsage.model.ModelBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Development-only surface for the authenticated route release gate. */
public final class ChatGptProbeScreen extends Screen {
    private static final String SYNTHETIC_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    private final ModelBackend backend;
    private final AtomicLong requestIds = new AtomicLong();
    private ModelBackend.Capabilities capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
    private ModelBackend.Exchange exchange;
    private Button modelButton;
    private String status = "Signed out";
    private String response = "Use Sign in, then load the account model catalog.";
    private long uiEpoch;
    private long requestEpoch;

    public ChatGptProbeScreen(ModelBackend backend) {
        super(Component.literal("PackSage ChatGPT route probe"));
        this.backend = backend;
    }

    @Override
    protected void init() {
        int left = 20;
        addRenderableWidget(Button.builder(Component.literal("Sign in"), button -> signIn()).bounds(left, 30, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel sign-in"), button -> cancelSignIn()).bounds(left + 75, 30, 105, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Sign out"), button -> {
            long epoch = ++uiEpoch;
            requestEpoch++;
            backend.signOut();
            exchange = null;
            status = "Signed out";
            response = "Waiting for remote revocation status.";
            if (backend instanceof ChatGptBackend chatGpt) {
                chatGpt.signOutStatus().whenComplete((detail, failure) -> Minecraft.getInstance().execute(() -> {
                    if (uiEpoch != epoch) return;
                    status = failure == null ? detail : "Failed: " + failure.getMessage();
                }));
            } else response = "Sign-out requested; remote revocation status is unavailable.";
        }).bounds(left + 185, 30, 70, 20).build());
        modelButton = Button.builder(Component.literal(modelLabel()), button -> cycleModel()).bounds(left + 260, 30, 220, 20).build();
        addRenderableWidget(modelButton);
        addRenderableWidget(Button.builder(Component.literal("Text"), button -> startProbe("Reply with the single word ready.", List.of(), ModelBackend.SearchMode.DISABLED)).bounds(left, 60, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Read-only tool"), button -> startProbe("Call the read_fixture tool once, then report its value.",
                List.of(new ModelBackend.ToolDefinition("read_fixture", "Returns a fixed read-only PackSage probe value.", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")),
                ModelBackend.SearchMode.DISABLED)).bounds(left + 75, 60, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Synthetic image"), button -> startImageProbe()).bounds(left + 190, 60, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Search"), button -> startProbe("Search the web for the official Minecraft 1.21.1 release date and cite the source.", List.of(), ModelBackend.SearchMode.EXPLICIT_REQUEST)).bounds(left + 315, 60, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), button -> cancelRequest()).bounds(left + 390, 60, 60, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xffffff);
        graphics.drawString(font, Component.literal("G1 status: " + status), 20, 92, status.startsWith("Failed") ? 0xff5555 : 0xffffff);
        int y = 108;
        for (var line : font.split(Component.literal(response), Math.max(1, width - 40))) {
            graphics.drawString(font, line, 20, y, 0xd0d0d0);
            y += 10;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    public void signIn() {
        long epoch = ++uiEpoch;
        status = "Waiting for browser sign-in";
        backend.signIn().whenComplete((state, failure) -> Minecraft.getInstance().execute(() -> {
            if (uiEpoch != epoch) return;
            if (failure != null) {
                status = "Failed: " + failure.getMessage();
                return;
            }
            status = state.status() + (state.detail() == null ? "" : ": " + state.detail());
            if (state.status() == ModelBackend.AuthStatus.SIGNED_IN) loadCapabilities(epoch);
        }));
    }

    public void cancelSignIn() {
        uiEpoch++;
        backend.cancelSignIn();
        status = "Sign-in cancelled";
    }

    public void sendText(ModelBackend.Request request, Consumer<ModelBackend.Event> events) {
        exchange = backend.start(request, events);
    }

    public void sendClientToolResult(ModelBackend.ToolResult result) {
        if (exchange != null) exchange.submit(result);
    }

    public void cancelRequest() {
        requestEpoch++;
        if (exchange != null) exchange.cancel();
        exchange = null;
        status = "Request stopped";
    }

    public static String syntheticImageInput(String base64Png) {
        return "data:image/png;base64," + base64Png;
    }

    public static ModelBackend.Request textRequest(String modelId, String text) {
        return new ModelBackend.Request(modelId, "Answer using only the supplied PackSage evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(text)))),
                List.of(), ModelBackend.SearchMode.EXPLICIT_ONLY, "probe", 512);
    }

    private void loadCapabilities(long epoch) {
        status = "Loading account model catalog";
        backend.capabilities().whenComplete((found, failure) -> Minecraft.getInstance().execute(() -> {
            if (uiEpoch != epoch) return;
            if (failure != null) {
                status = "Failed to load models: " + failure.getMessage();
                return;
            }
            capabilities = found;
            status = found.selectedModelId() == null ? "No listed model is available" : "Ready with " + modelLabel();
            if (modelButton != null) modelButton.setMessage(Component.literal(modelLabel()));
        }));
    }

    private void cycleModel() {
        if (capabilities.modelCatalog().isEmpty()) {
            status = "No account model catalog is loaded";
            return;
        }
        int selected = 0;
        for (int index = 0; index < capabilities.modelCatalog().size(); index++) {
            if (capabilities.modelCatalog().get(index).id().equals(capabilities.selectedModelId())) selected = (index + 1) % capabilities.modelCatalog().size();
        }
        ModelBackend.ModelInfo model = capabilities.modelCatalog().get(selected);
        if (backend instanceof ChatGptBackend chatGpt && !chatGpt.selectModel(model.id())) {
            status = "Failed to select " + model.displayName();
            return;
        }
        capabilities = new ModelBackend.Capabilities(capabilities.modelCatalog(), model.id(), model.imageInput(), model.hostedSearch(), model.contextTokens(), model.outputTokens());
        modelButton.setMessage(Component.literal(modelLabel()));
        status = "Selected " + model.displayName();
    }

    private String modelLabel() {
        if (capabilities.selectedModelId() == null) return "Model: unavailable";
        return "Model: " + capabilities.modelCatalog().stream().filter(model -> model.id().equals(capabilities.selectedModelId()))
                .findFirst().map(ModelBackend.ModelInfo::displayName).orElse(capabilities.selectedModelId());
    }

    private void startImageProbe() {
        if (capabilities.selectedModelId() == null) {
            status = "Load an account model before sending";
            return;
        }
        response = "";
        status = "Streaming synthetic image probe";
        long epoch = ++requestEpoch;
        exchange = backend.start(new ModelBackend.Request(capabilities.selectedModelId(), "Describe only the supplied synthetic image.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("Describe this image."),
                        new ModelBackend.ImageContent(syntheticImageInput(SYNTHETIC_PNG))))), List.of(), ModelBackend.SearchMode.DISABLED,
                "probe-" + requestIds.incrementAndGet(), 512), event -> receive(epoch, event));
    }

    private void startProbe(String question, List<ModelBackend.ToolDefinition> tools, ModelBackend.SearchMode searchMode) {
        if (capabilities.selectedModelId() == null) {
            status = "Load an account model before sending";
            return;
        }
        response = "";
        status = searchMode == ModelBackend.SearchMode.EXPLICIT_REQUEST ? "Streaming hosted-search probe" : "Streaming probe";
        long epoch = ++requestEpoch;
        exchange = backend.start(new ModelBackend.Request(capabilities.selectedModelId(), "Answer this PackSage route probe exactly as requested.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(question)))), tools, searchMode,
                "probe-" + requestIds.incrementAndGet(), 512), event -> receive(epoch, event));
    }

    private void receive(long epoch, ModelBackend.Event event) {
        Minecraft.getInstance().execute(() -> {
            if (requestEpoch != epoch) return;
            if (event.kind() == ModelBackend.EventKind.TEXT_DELTA) response += event.text();
            else if (event.kind() == ModelBackend.EventKind.CLIENT_TOOL_CALL && event.toolCall() != null) {
                status = "Executing read-only fixture tool";
                sendClientToolResult(new ModelBackend.ToolResult(event.toolCall().callId(), "{\"fixture\":\"packsage-read-only\"}", false));
            } else if (event.kind() == ModelBackend.EventKind.HOSTED_SEARCH) {
                status = "Hosted search returned output";
                for (ModelBackend.ResponseBlock block : event.blocks()) {
                    response += "\n" + (block.sourceUrl() == null ? "Search metadata: " : "Citation " + block.sourceUrl() + ": ")
                            + block.serializedContent();
                }
            } else if (event.kind() == ModelBackend.EventKind.COMPLETED) {
                status = "G1 request completed";
                exchange = null;
            } else if (event.kind() == ModelBackend.EventKind.FAILED) {
                status = "Failed: " + event.detail();
                exchange = null;
            }
        });
    }
}
