package dev.packsage.client;

import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.jei.PackSageJeiPlugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAssistantTest {
    @Test
    void optionalVersionPinsAcceptOnlyTheTestedVersionWithItsKnownLoaderSuffix() {
        assertTrue(ClientAssistant.versionMatches("1.21.1-92", "1.21.1-92-NEOFORGE"));
        assertTrue(ClientAssistant.versionMatches("1.21.1-92", "1.21.1-92"));
        assertFalse(ClientAssistant.versionMatches("1.21.1-92", "1.21.1-93-NEOFORGE"));
        assertFalse(ClientAssistant.versionMatches("1.21.1-92", "1.21.1-920-NEOFORGE"));
        assertFalse(ClientAssistant.versionMatches("1.21.1-92", "1.21.1-92-FORGE"));
        assertFalse(ClientAssistant.versionMatches("1.21.1-92", "unknown"));
        assertTrue(ClientAssistant.versionMatches("19.27.0.340", "19.27.0.340"));
        assertFalse(ClientAssistant.versionMatches("19.27.0.340", "19.27.0.341"));
        assertTrue(ClientAssistant.versionMatches("2101.1.36", "2101.1.36"));
        assertFalse(ClientAssistant.versionMatches("2101.1.36", "2101.1.37"));
    }

    @Test
    void privateWorldIdentityIsOpaqueAndStableWhileKeepingItsLabel() {
        ClientAssistant.CopiedWorld first = new ClientAssistant.CopiedWorld(
                "private:C:/instances/fixture/saves/Test World", "Test World", true,
                "C:/instances/fixture/saves/Test World/serverconfig");
        ClientAssistant.CopiedWorld same = new ClientAssistant.CopiedWorld(
                "private:C:/instances/fixture/saves/Test World", "Test World", true,
                "C:/instances/fixture/saves/Test World/serverconfig");
        ClientAssistant.CopiedWorld other = new ClientAssistant.CopiedWorld(
                "private:C:/instances/fixture/saves/Other World", "Other World", true,
                "C:/instances/fixture/saves/Other World/serverconfig");

        ChatContracts.WorldIdentity firstIdentity = ClientAssistant.identityFor(first);

        assertEquals(firstIdentity, ClientAssistant.identityFor(same));
        assertNotEquals(firstIdentity.worldOrServer(), ClientAssistant.identityFor(other).worldOrServer());
        assertEquals("Test World", firstIdentity.readableLabel());
        assertFalse(firstIdentity.worldOrServer().contains("C:/instances"));
        assertFalse(firstIdentity.worldOrServer().contains("Test World"));
    }

    @Test
    void scopeSeparatesSessionResourcesPlayerTeamAndProviderRuntimeWithoutRawTeamToken() {
        UUID session = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Evidence.Scope first = ClientAssistant.scopeFor("world-id", session, 4, "en_us",
                "player-id", "team-token", 7);
        Evidence.Scope same = ClientAssistant.scopeFor("world-id", session, 4, "en_us",
                "player-id", "team-token", 7);
        Evidence.Scope changed = ClientAssistant.scopeFor("world-id", session, 5, "fr_fr",
                "other-player", "other-team-token", 8);

        assertEquals(first, same);
        assertNotEquals(first, changed);
        assertTrue(first.worldSession().contains(session.toString()));
        assertTrue(first.resourcesLocale().contains("4"));
        assertTrue(first.resourcesLocale().contains("en_us"));
        assertTrue(first.playerTeam().startsWith("player-id:"));
        assertFalse(first.playerTeam().contains("team-token"));
        assertEquals("runtime-7", first.providerRuntime());
    }

    @Test
    void jeiRuntimeLossAdvancesTheObservedRuntimeGeneration() {
        long before = PackSageJeiPlugin.runtimeGeneration();

        new PackSageJeiPlugin().onRuntimeUnavailable();

        assertTrue(PackSageJeiPlugin.runtimeGeneration() > before);
    }
}
