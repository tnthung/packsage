package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AssistantScreen extends Screen {
    public record RuntimeState(boolean privateSingleplayer, boolean integratedServer, boolean published) {
    }

    public static final int QUESTION_MAX_LENGTH = 1000;

    private final ChatActions actions;
    private ChatContracts.View view;
    private String input = "";
    private boolean explicitWebSearch;
    private boolean includeScreenshot = true;
    private EditBox inputBox;
    private Button searchButton;
    private Button screenshotButton;
    private Button signInButton;
    private Button cancelSignInButton;
    private Button signOutButton;
    private Button pauseButton;
    private final List<Button> sourceButtons = new ArrayList<>();
    private Runnable settingsOpener = () -> { };
    private RuntimeState runtimeState = new RuntimeState(true, true, false);
    private int scrollOffset;

    public AssistantScreen(ChatActions actions) {
        this(actions, Component.literal("PackSage Assistant"));
    }

    public AssistantScreen(ChatActions actions, Component title) {
        super(title);
        this.actions = Objects.requireNonNull(actions);
        this.view = actions.view();
    }

    @Override
    protected void init() {
        inputBox = new EditBox(font, 20, 30, Math.max(120, width - 40), 20, Component.literal("Ask PackSage"));
        inputBox.setMaxLength(QUESTION_MAX_LENGTH);
        inputBox.setValue(input);
        addRenderableWidget(inputBox);
        addRenderableWidget(Button.builder(Component.literal("Send"), button -> {
            input = inputBox.getValue();
            submit();
        }).bounds(20, 55, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), button -> stop()).bounds(85, 55, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("New chat"), button -> newChat()).bounds(150, 55, 80, 20).build());
        signInButton = Button.builder(Component.literal("Sign in"), button -> signIn()).bounds(235, 55, 70, 20).build();
        addRenderableWidget(signInButton);
        searchButton = Button.builder(Component.literal("Search: " + (explicitWebSearch ? "on" : "off")), button -> {
            explicitWebSearch = !explicitWebSearch;
            button.setMessage(Component.literal("Search: " + (explicitWebSearch ? "on" : "off")));
        }).bounds(310, 55, 100, 20).build();
        addRenderableWidget(searchButton);
        screenshotButton = Button.builder(Component.literal("Screenshot: " + (includeScreenshot ? "on" : "off")), button -> {
            includeScreenshot = !includeScreenshot;
            button.setMessage(Component.literal("Screenshot: " + (includeScreenshot ? "on" : "off")));
        }).bounds(415, 55, 115, 20).build();
        addRenderableWidget(screenshotButton);
        cancelSignInButton = Button.builder(Component.literal("Cancel sign-in"), button -> cancelSignIn())
                .bounds(20, 80, 105, 20).build();
        addRenderableWidget(cancelSignInButton);
        signOutButton = Button.builder(Component.literal("Sign out"), button -> signOut()).bounds(130, 80, 70, 20).build();
        addRenderableWidget(signOutButton);
        pauseButton = Button.builder(Component.literal("Pause: " + (effectivePause(actualRuntimeState()) ? "on" : "off")), button -> {
            setPaused(!effectivePause(actualRuntimeState()));
            updateButtons();
        }).bounds(205, 80, 90, 20).build();
        addRenderableWidget(pauseButton);
        addRenderableWidget(Button.builder(Component.literal("Settings"), button -> settingsOpener.run())
                .bounds(300, 80, 80, 20).build());
        rebuildSourceButtons();
        updateButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        updateButtons();
        int contentTop = 116;
        int contentBottom = Math.max(contentTop + 1, height - 10);
        graphics.drawString(font, Component.literal("Request: " + (view.active() ? "streaming" : "idle")), 20, 102, 0xffffff);
        graphics.drawString(font, Component.literal("Sign in: " + view.signIn().name()), 150, 102, 0xffffff);
        graphics.drawString(font, Component.literal("Pause effective: "
                + (effectivePause(actualRuntimeState()) ? "on" : "off")), 300, 102, 0xffffff);
        graphics.enableScissor(10, contentTop, Math.max(11, width - 10), contentBottom);
        int y = contentTop + 4 - scrollOffset;
        String currentContext = view.context().serializedGameplayContext();
        boolean currentScreenshot = view.context().screenshot() != null
                && !view.context().screenshot().assetId().equals("none");
        for (var line : font.split(Component.literal("Attached context: " + currentContext
                + (currentScreenshot ? " [screenshot attached]" : " [screenshot removed]")), Math.max(1, width - 40))) {
            graphics.drawString(font, line, 20, y, 0xb0b0b0);
            y += 10;
        }
        y += 4;
        if (!view.sources().isEmpty()) {
            graphics.drawString(font, Component.literal("Current sources: " + view.sources().stream()
                    .map(Evidence.Source::providerId).distinct().reduce((first, next) -> first + ", " + next).orElse("")),
                    20, y, 0x90d0ff);
            y += 12;
        }
        for (ChatContracts.Turn turn : view.conversation().turns()) {
            graphics.drawString(font, Component.literal("You: " + turn.question()), 20, y, 0xffffff);
            y += 12;
            graphics.drawString(font, Component.literal("PackSage:"), 20, y, 0x90d0ff);
            y += 12;
            for (var line : font.split(Component.literal(turn.answer()), Math.max(1, width - 40))) {
                graphics.drawString(font, line, 20, y, 0xffffff);
                y += 10;
            }
            graphics.drawString(font, Component.literal("Outcome: " + turn.outcome().status()
                    + (turn.outcome().detail() == null ? "" : " (" + turn.outcome().detail() + ")")), 20, y, 0xb0b0b0);
            y += 12;
            String turnContext = turn.context().serializedGameplayContext();
            boolean turnScreenshot = turn.context().screenshot() != null
                    && !turn.context().screenshot().assetId().equals("none");
            for (var line : font.split(Component.literal("Context: " + turnContext
                    + (turnScreenshot ? " [screenshot attached]" : " [no screenshot]")), Math.max(1, width - 40))) {
                graphics.drawString(font, line, 20, y, 0xb0b0b0);
                y += 10;
            }
            y += 2;
            if (!turn.replayItems().isEmpty()) {
                graphics.drawString(font, Component.literal("Replay items: " + turn.replayItems().size()), 20, y, 0xb0b0b0);
                y += 12;
            }
            if (!turn.sources().isEmpty()) {
                graphics.drawString(font, Component.literal("Sources: " + turn.sources().stream()
                        .map(Evidence.Source::providerId).distinct().reduce((first, next) -> first + ", " + next).orElse("")),
                        20, y, 0x90d0ff);
                y += 12;
            }
            y += 6;
        }
        if (view.active() && !view.streamingAnswer().isEmpty()) {
            graphics.drawString(font, Component.literal("PackSage:"), 20, y, 0x90d0ff);
            y += 12;
            for (var line : font.split(Component.literal(view.streamingAnswer()), Math.max(1, width - 40))) {
                graphics.drawString(font, line, 20, y, 0xffffff);
                y += 10;
            }
        }
        if (!view.errors().isEmpty()) {
            graphics.drawString(font, Component.literal("Error: " + view.errors().get(view.errors().size() - 1)), 20, y, 0xff5555);
        }
        graphics.disableScissor();
        updateSourceButtons(contentTop, contentBottom);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY < 116) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int available = Math.max(1, height - 126);
        int maxScroll = Math.max(0, view.conversation().turns().size() * 400
                + view.navigationTargets().size() * 22 - available);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * 20)));
        updateSourceButtons(116, Math.max(117, height - 10));
        return true;
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        if (inputBox != null) input = inputBox.getValue();
        super.resize(minecraft, width, height);
    }

    public void refresh(ChatContracts.View next) {
        if (inputBox != null) input = inputBox.getValue();
        view = Objects.requireNonNull(next);
        scrollOffset = 0;
        rebuildSourceButtons();
        updateButtons();
    }

    public ChatContracts.View view() { return view; }
    public String input() { return input; }
    public void setInput(String value) {
        input = Objects.requireNonNull(value);
        if (inputBox != null) inputBox.setValue(input);
    }
    public void setExplicitWebSearch(boolean value) { explicitWebSearch = value; updateButtons(); }
    public void setIncludeScreenshot(boolean value) { includeScreenshot = value; updateButtons(); }
    public boolean explicitWebSearch() { return explicitWebSearch; }
    public boolean includeScreenshot() { return includeScreenshot; }
    public void setSettingsOpener(Runnable opener) { settingsOpener = Objects.requireNonNull(opener); }
    public void setRuntimeState(RuntimeState state) { runtimeState = Objects.requireNonNull(state); updateButtons(); }
    public RuntimeState runtimeState() { return actualRuntimeState(); }
    public int scrollOffset() { return scrollOffset; }

    public void submit() {
        if (!input.isBlank()) actions.send(input, includeScreenshot, explicitWebSearch);
    }

    public void close() {
    }

    @Override
    public void onClose() {
        if (minecraft != null) super.onClose();
    }

    public void stop() { actions.stop(); }
    public void newChat() { actions.newChat(); }
    public void signIn() { actions.signIn(); }
    public void cancelSignIn() { actions.cancelSignIn(); }
    public void signOut() { actions.signOut(); }

    public void setPaused(boolean paused) {
        RuntimeState state = actualRuntimeState();
        if (!state.privateSingleplayer() || !state.integratedServer() || state.published()) return;
        actions.setPaused(paused);
    }

    @Override
    public boolean isPauseScreen() {
        return effectivePause(actualRuntimeState());
    }

    public boolean effectivePause(RuntimeState state) {
        return view.paused() && state != null && state.privateSingleplayer() && state.integratedServer()
                && !state.published();
    }

    public void navigate(Evidence.NavigationTarget target) {
        if (target == null) return;
        ChatContracts.View current = actions.view();
        if (current.navigationTargets().stream().noneMatch(registered -> registered.equals(target))
                || current.sources().stream().noneMatch(source -> source.scope().equals(target.scope())
                && source.providerId().equals(target.providerId()) && source.sourceHandle().equals(target.sourceHandle()))) return;
        actions.navigate(target);
    }

    public List<Evidence.Source> sourceReferences() { return view.sources(); }

    public String answer() {
        if (view.active() && !view.streamingAnswer().isEmpty()) return view.streamingAnswer();
        List<ChatContracts.Turn> turns = view.conversation().turns();
        return turns.isEmpty() ? "" : turns.get(turns.size() - 1).answer();
    }

    private RuntimeState actualRuntimeState() {
        if (minecraft == null) return runtimeState;
        var integratedServer = minecraft.getSingleplayerServer();
        return new RuntimeState(minecraft.isSingleplayer() && integratedServer != null,
                integratedServer != null, integratedServer != null && integratedServer.isPublished());
    }

    private void updateButtons() {
        if (searchButton != null) searchButton.setMessage(Component.literal("Search: " + (explicitWebSearch ? "on" : "off")));
        if (screenshotButton != null) screenshotButton.setMessage(Component.literal("Screenshot: "
                + (includeScreenshot ? "on" : "off")));
        if (pauseButton != null) {
            RuntimeState state = actualRuntimeState();
            pauseButton.setMessage(Component.literal("Pause: " + (effectivePause(state) ? "on" : "off")));
            pauseButton.active = state.privateSingleplayer() && state.integratedServer() && !state.published();
        }
        if (signInButton != null) {
            signInButton.active = view.signIn() != ChatContracts.SignInState.SIGNED_IN
                    && view.signIn() != ChatContracts.SignInState.SIGNING_IN;
            signInButton.visible = view.signIn() != ChatContracts.SignInState.SIGNED_IN;
        }
        if (cancelSignInButton != null) {
            cancelSignInButton.visible = view.signIn() == ChatContracts.SignInState.SIGNING_IN;
            cancelSignInButton.active = cancelSignInButton.visible;
        }
        if (signOutButton != null) {
            signOutButton.visible = view.signIn() == ChatContracts.SignInState.SIGNED_IN;
            signOutButton.active = signOutButton.visible;
        }
    }

    private void rebuildSourceButtons() {
        for (Button button : sourceButtons) removeWidget(button);
        sourceButtons.clear();
        for (Evidence.NavigationTarget target : view.navigationTargets()) {
            Button button = Button.builder(Component.literal("Open " + target.providerId() + ":" + target.targetId()),
                    ignored -> navigate(target)).bounds(Math.max(20, width - 180), 116, 160, 20).build();
            sourceButtons.add(addRenderableWidget(button));
        }
    }

    private void updateSourceButtons(int contentTop, int contentBottom) {
        for (int index = 0; index < sourceButtons.size(); index++) {
            Button button = sourceButtons.get(index);
            int y = contentTop + index * 22 - scrollOffset;
            button.setY(y);
            button.setX(Math.max(20, width - 180));
            button.visible = y + 20 > contentTop && y < contentBottom;
            button.active = button.visible;
        }
    }
}
