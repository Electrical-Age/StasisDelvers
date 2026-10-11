package org.erisia.stasis;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class StasisRegistry {
    private StasisRegistry() {
    }

    static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, StasisDelvers.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<Statue>> STATUE = ENTITY_TYPES.register("statue",
        () -> EntityType.Builder.<Statue>of(Statue::new, MobCategory.MISC).sized(0.6f, 1.8f).clientTrackingRange(10)
            .updateInterval(20).fireImmune().noSummon()
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "statue"))));

    /** Frozen in time (data/stasisdelvers/damage_type/frozen_in_time.json); bypasses armour, totems and resistance. */
    public static final ResourceKey<DamageType> FROZEN_IN_TIME =
        ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "frozen_in_time"));

    static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }
}
