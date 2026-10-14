package dev.packsage.model.chatgpt;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatGptProbeScreenTest {
    @Test
    void citationLinksAcceptOnlyHttpUrlsWithoutUserInformation() {
        assertEquals(URI.create("https://example.test/source"), ChatGptProbeScreen.citationUri("https://example.test/source"));
        assertEquals(URI.create("http://example.test/source"), ChatGptProbeScreen.citationUri("http://example.test/source"));
        assertNull(ChatGptProbeScreen.citationUri("ftp://example.test/source"));
        assertNull(ChatGptProbeScreen.citationUri("https://user@example.test/source"));
        assertNull(ChatGptProbeScreen.citationUri("/relative/source"));
        assertNull(ChatGptProbeScreen.citationUri("not a URI"));
    }
}
