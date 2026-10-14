package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImmersiveEngineeringGuideReaderTest {
    @Test
    void textContinuationIncludesOnlyTheBoundedSearchOverlap() {
        var text = "0123456789";
        var first = ImmersiveEngineeringGuideReader.readText(new StringReader(text), "immersiveengineering:manual/test",
                0, 6, 2, new Toolbox.DocumentBudget());
        var next = ImmersiveEngineeringGuideReader.readText(new StringReader(text), "immersiveengineering:manual/test",
                6, 6, 2, new Toolbox.DocumentBudget());

        assertEquals("012345", first.text());
        assertEquals(0, first.overlapChars());
        assertEquals("6", first.nextPosition());
        assertEquals("456789", next.text());
        assertEquals(2, next.overlapChars());
        assertNull(next.nextPosition());
        assertEquals(Evidence.Coverage.COMPLETE, next.coverage());
    }

    @Test
    void textContinuationStopsBeforeCopyingPastTheSharedByteBudget() {
        var budget = new Toolbox.DocumentBudget();
        budget.bytes(96 * 1024 - 12);

        var fragment = ImmersiveEngineeringGuideReader.readText(new StringReader("abcdef"), "manual/test", 0,
                6, 2, budget);

        assertEquals("ab", fragment.text());
        assertEquals("2", fragment.nextPosition());
        assertEquals(Evidence.Coverage.PARTIAL, fragment.coverage());
    }
}
