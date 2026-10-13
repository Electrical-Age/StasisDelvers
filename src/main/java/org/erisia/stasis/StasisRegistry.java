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

    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(StasisDelvers.MODID);
    static final DeferredRegister<net.minecraft.world.item.CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, StasisDelvers.MODID);

    static ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset> asset(String name) {
        return ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, name));
    }

    static java.util.Map<net.minecraft.world.item.equipment.ArmorType, Integer> defense(int boots, int legs, int chest, int helmet) {
        var m = new java.util.EnumMap<net.minecraft.world.item.equipment.ArmorType, Integer>(net.minecraft.world.item.equipment.ArmorType.class);
        m.put(net.minecraft.world.item.equipment.ArmorType.BOOTS, boots);
        m.put(net.minecraft.world.item.equipment.ArmorType.LEGGINGS, legs);
        m.put(net.minecraft.world.item.equipment.ArmorType.CHESTPLATE, chest);
        m.put(net.minecraft.world.item.equipment.ArmorType.HELMET, helmet);
        return m;
    }

    /** Delver suit: copper coils on leather armour, so leather's protection (Baughn 2026-10-13); repaired with copper
     *  coils (tag repairs_delver_suit). */
    static final net.minecraft.world.item.equipment.ArmorMaterial DELVER = new net.minecraft.world.item.equipment.ArmorMaterial(
        12, defense(1, 2, 3, 1), 1, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER, 0f, 0f,
        net.minecraft.tags.TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "repairs_delver_suit")), asset("delver"));
    /** The combined suit: E-Coal (energy instead of armour points) plus time protection. */
    static final net.minecraft.world.item.equipment.ArmorMaterial STASIS_ARMOR = new net.minecraft.world.item.equipment.ArmorMaterial(
        10, defense(0, 0, 0, 0), 1, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_GENERIC, 0f, 0f,
        net.minecraft.tags.TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "repairs_stasis_armor")), asset("stasis_armor"));

    // Delver suit: 40 kJ per piece, charged at 400 W, 10 W per piece in stopped land (a full charge: about 67 minutes)
    static net.neoforged.neoforge.registries.DeferredItem<DelverSuitItem> delver(String name, net.minecraft.world.item.equipment.ArmorType type) {
        return ITEMS.registerItem(name, p -> new DelverSuitItem(p, 40000, 400, 10), p -> p.humanoidArmor(DELVER, type).stacksTo(1));
    }

    // Stasis armour: E-Coal's damage absorption (1.7.10 points {2, 6, 5, 2} as ratios) on a 60 kJ store per piece
    static net.neoforged.neoforge.registries.DeferredItem<StasisArmorItem> stasisArmor(String name, net.minecraft.world.item.equipment.ArmorType type, int armor) {
        return ITEMS.registerItem(name, p -> new StasisArmorItem(p, 60000, 1000, armor / 20.0, armor * 500.0, 500, 10),
            p -> p.durability(type.getDurability(STASIS_ARMOR.durability())).attributes(STASIS_ARMOR.createAttributes(type))
                .component(net.minecraft.core.component.DataComponents.EQUIPPABLE, net.minecraft.world.item.equipment.Equippable
                    .builder(type.getSlot()).setEquipSound(STASIS_ARMOR.equipSound()).setAsset(STASIS_ARMOR.assetId()).build()));
    }

    public static final net.neoforged.neoforge.registries.DeferredItem<DelverSuitItem> DELVER_HELMET = delver("delver_helmet", net.minecraft.world.item.equipment.ArmorType.HELMET);
    public static final net.neoforged.neoforge.registries.DeferredItem<DelverSuitItem> DELVER_CHEST = delver("delver_chestplate", net.minecraft.world.item.equipment.ArmorType.CHESTPLATE);
    public static final net.neoforged.neoforge.registries.DeferredItem<DelverSuitItem> DELVER_LEGS = delver("delver_leggings", net.minecraft.world.item.equipment.ArmorType.LEGGINGS);
    public static final net.neoforged.neoforge.registries.DeferredItem<DelverSuitItem> DELVER_BOOTS = delver("delver_boots", net.minecraft.world.item.equipment.ArmorType.BOOTS);
    public static final net.neoforged.neoforge.registries.DeferredItem<StasisArmorItem> STASIS_HELMET = stasisArmor("stasis_helmet", net.minecraft.world.item.equipment.ArmorType.HELMET, 2);
    public static final net.neoforged.neoforge.registries.DeferredItem<StasisArmorItem> STASIS_CHEST = stasisArmor("stasis_chestplate", net.minecraft.world.item.equipment.ArmorType.CHESTPLATE, 6);
    public static final net.neoforged.neoforge.registries.DeferredItem<StasisArmorItem> STASIS_LEGS = stasisArmor("stasis_leggings", net.minecraft.world.item.equipment.ArmorType.LEGGINGS, 5);
    public static final net.neoforged.neoforge.registries.DeferredItem<StasisArmorItem> STASIS_BOOTS = stasisArmor("stasis_boots", net.minecraft.world.item.equipment.ArmorType.BOOTS, 2);

    static {
        TABS.register("main", () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(net.minecraft.network.chat.Component.translatable("itemGroup.stasisdelvers"))
            .icon(() -> new net.minecraft.world.item.ItemStack(DELVER_HELMET.get()))
            .displayItems((params, out) -> ITEMS.getEntries().forEach(i -> out.accept(i.get())))
            .build());
    }

    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(StasisDelvers.MODID);
    static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, StasisDelvers.MODID);

    public static final net.neoforged.neoforge.registries.DeferredBlock<TowerBlock> TOWER = BLOCKS.registerBlock("stasis_tower",
        TowerBlock::new, p -> p.mapColor(net.minecraft.world.level.material.MapColor.METAL).strength(5f, 6f)
            .requiresCorrectToolForDrops().sound(net.minecraft.world.level.block.SoundType.METAL));
    /** The survey pole (mast and booms). Its id is still the V1 segment's, so worlds keep their masts. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<TowerPoleBlock> TOWER_SEGMENT =
        BLOCKS.registerBlock("stasis_tower_segment", TowerPoleBlock::new, p -> p.mapColor(net.minecraft.world.level.material.MapColor.METAL)
            .strength(1f, 6f).noOcclusion().sound(net.minecraft.world.level.block.SoundType.METAL));
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> TOWER_ITEM = ITEMS.registerSimpleBlockItem(TOWER);
    public static final net.neoforged.neoforge.registries.DeferredItem<PoleItem> TOWER_SEGMENT_ITEM =
        ITEMS.registerItem("stasis_tower_segment", p -> new PoleItem(TOWER_SEGMENT.get(), p), p -> p.useBlockDescriptionPrefix());

    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>, net.minecraft.world.level.block.entity.BlockEntityType<TowerBlockEntity>> TOWER_ENTITY =
        BLOCK_ENTITIES.register("stasis_tower", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(TowerBlockEntity::new, TOWER.get()));

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
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class, e ->
            e.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.Energy.BLOCK, (level, pos, state, be, side) ->
                level.getBlockEntity(TowerBlock.master(pos, state)) instanceof TowerBlockEntity t ? t.energy() : null, TOWER.get()));
        modBus.addListener(Tickets::register);
        TABS.register(modBus);
    }
}
