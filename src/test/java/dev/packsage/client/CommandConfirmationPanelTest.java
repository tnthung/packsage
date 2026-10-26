package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.world.CommandRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandConfirmationPanelTest {
    @Test
    void exactCommandWrapsAndRunCancelOnlyRespondInsideTheVisibleChat() {
        class Actions implements ChatActions {
            String decision;
            public ChatContracts.View view() { return null; }
            public List<CommandRunner.Confirmation> commandConfirmations() {
                return List.of(new CommandRunner.Confirmation("id", "request", "/give @s minecraft:diamond 1"));
            }
            public void confirmCommand(String id, boolean run) { decision = id + ":" + run; }
            public void signIn() { }
            public void cancelSignIn() { }
            public void signOut() { }
            public void send(String text, boolean screenshot, boolean web) { }
            public void stop() { }
            public void newChat() { }
            public void setPaused(boolean paused) { }
            public void navigate(Evidence.NavigationTarget target) { }
        }
        var actions = new Actions();
        var panel = new CommandConfirmationPanel(actions);
        var font = new net.minecraft.client.gui.Font(ignored -> null, false) {
            @Override public List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.FormattedText text, int width) {
                return net.minecraft.locale.Language.getInstance().getVisualOrder(new net.minecraft.client.StringSplitter((code, style) -> 5)
                        .splitLines(text, width, net.minecraft.network.chat.Style.EMPTY));
            }
        };
        var rows = panel.layout(font, 10, 20, 180, 0, 500);
        assertEquals("/give @s minecraft:diamond 1", rows.getFirst().confirmation().command());
        assertFalse(rows.getFirst().lines().isEmpty());
        assertTrue(panel.mouseClicked(20, rows.getFirst().buttonsY() + 5, 0));
        assertEquals("id:true", actions.decision);
        assertTrue(panel.mouseClicked(90, rows.getFirst().buttonsY() + 5, 0));
        assertEquals("id:false", actions.decision);
        actions.decision = null;
        panel.layout(font, 10, 20, 180, 0, 21);
        assertFalse(panel.mouseClicked(20, rows.getFirst().buttonsY() + 5, 0));
        assertNull(actions.decision);
    }
}
