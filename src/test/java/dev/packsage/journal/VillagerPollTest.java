package dev.packsage.journal;

import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.Visibility;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VillagerPollTest {
    @Test
    void eachTickVisitsABoundedSliceAndACycleMeetsEveryEntity() throws Exception {
        var unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        var storage = new EntitySectionStorage<>(Entity.class, key -> Visibility.TICKING);
        var all = new HashSet<UUID>();
        for (int i = 0; i < 300; i++) {
            // Entities only need an id and a section here; no level is required.
            var entity = (ItemEntity) ((sun.misc.Unsafe) unsafe.get(null)).allocateInstance(ItemEntity.class);
            var id = UUID.randomUUID();
            var uuid = Entity.class.getDeclaredField("uuid");
            uuid.setAccessible(true);
            uuid.set(entity, id);
            all.add(id);
            storage.getOrCreateSection(SectionPos.asLong(i % 7, 4, i / 7)).add(entity);
        }
        var poll = new VillagerPoll();
        var seen = new HashSet<UUID>();
        int ticks = 0;
        do {
            var before = seen.size();
            poll.step("test", storage, ItemEntity.class, entity -> seen.add(entity.getUUID()));
            assertTrue(seen.size() - before <= VillagerPoll.VISITS_PER_TICK);
            ticks++;
        } while (seen.size() < all.size() && ticks < 100);
        assertEquals(all, seen);
        assertTrue(ticks > 300 / VillagerPoll.VISITS_PER_TICK);
    }
}
