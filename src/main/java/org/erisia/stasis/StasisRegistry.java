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

    // Delver suit: charged at 400 W, 10 W per piece in stopped land; its store lasts StasisConfig.delverSuitMinutes (30: 18 kJ)
    static net.neoforged.neoforge.registries.DeferredItem<DelverSuitItem> delver(String name, net.minecraft.world.item.equipment.ArmorType type) {
        return ITEMS.registerItem(name, p -> new DelverSuitItem(p, 400, 10), p -> p.humanoidArmor(DELVER, type).stacksTo(1));
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
    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>, net.minecraft.world.level.block.entity.BlockEntityType<TowerCellBlockEntity>> TOWER_CELL_ENTITY =
        BLOCK_ENTITIES.register("stasis_tower_cell", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(TowerCellBlockEntity::new, TOWER.get()));

    // ---- the strata around rifts (Strata, DeepFeature): riftstone, its brighter veins, and rift crystals (budding
    // walls and four growth stages, like amethyst; Baughn: violet with dark spots, script/gen_crystals.py)
    public static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> RIFTSTONE = BLOCKS.registerSimpleBlock("riftstone",
        p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_PURPLE).strength(2.5f, 9f).requiresCorrectToolForDrops()
            .sound(net.minecraft.world.level.block.SoundType.DEEPSLATE));
    public static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> RIFTSTONE_VEIN = BLOCKS.registerSimpleBlock("riftstone_vein",
        p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_MAGENTA).strength(2.5f, 9f).requiresCorrectToolForDrops()
            .sound(net.minecraft.world.level.block.SoundType.DEEPSLATE).lightLevel(st -> 2));
    public static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> BUDDING_RIFT_CRYSTAL = BLOCKS.registerSimpleBlock("budding_rift_crystal",
        p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_PURPLE).strength(2.5f, 9f).requiresCorrectToolForDrops()
            .sound(net.minecraft.world.level.block.SoundType.AMETHYST));
    public static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.AmethystClusterBlock> RIFT_CRYSTAL_SMALL = crystal("rift_crystal_small", 5, 8, 1);
    public static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.AmethystClusterBlock> RIFT_CRYSTAL_MEDIUM = crystal("rift_crystal_medium", 7, 10, 2);
    public static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.AmethystClusterBlock> RIFT_CRYSTAL_LARGE = crystal("rift_crystal_large", 10, 12, 4);
    public static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.AmethystClusterBlock> RIFT_CRYSTAL_CLUSTER = crystal("rift_crystal_cluster", 14, 12, 5);

    static net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.AmethystClusterBlock> crystal(String name, float height, float width, int light) {
        return BLOCKS.registerBlock(name, p -> new net.minecraft.world.level.block.AmethystClusterBlock(height, width, p),
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_PURPLE).forceSolidOn().noOcclusion().strength(1.5f)
                .sound(net.minecraft.world.level.block.SoundType.AMETHYST_CLUSTER).lightLevel(st -> light)
                .pushReaction(net.minecraft.world.level.material.PushReaction.POPPED));
    }

    static {
        for (var b : java.util.List.of(RIFTSTONE, RIFTSTONE_VEIN, BUDDING_RIFT_CRYSTAL, RIFT_CRYSTAL_SMALL, RIFT_CRYSTAL_MEDIUM, RIFT_CRYSTAL_LARGE, RIFT_CRYSTAL_CLUSTER))
            ITEMS.registerSimpleBlockItem(b);
    }

    /** Crystal uses: the rift compass (to the nearest rift; spins in storms) and the rift barometer (storm warning). */
    public static final net.neoforged.neoforge.registries.DeferredItem<RiftCompassItem> RIFT_COMPASS = ITEMS.registerItem("rift_compass", RiftCompassItem::new,
        p -> p.stacksTo(1));
    public static final net.neoforged.neoforge.registries.DeferredBlock<RiftBarometerBlock> RIFT_BAROMETER_BLOCK = BLOCKS.registerBlock("rift_barometer",
        RiftBarometerBlock::new, p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_PURPLE).strength(2.5f, 9f)
            .requiresCorrectToolForDrops().sound(net.minecraft.world.level.block.SoundType.AMETHYST).lightLevel(st -> st.getValue(RiftBarometerBlock.POWER) / 3));
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> RIFT_BAROMETER = ITEMS.registerSimpleBlockItem(RIFT_BAROMETER_BLOCK);
    /** The surveyor's desk (spawn cottage): maps to ruined cottages (MapDeskBlock). */
    public static final net.neoforged.neoforge.registries.DeferredBlock<MapDeskBlock> MAP_DESK_BLOCK = BLOCKS.registerBlock("surveyors_desk",
        MapDeskBlock::new, p -> p.mapColor(net.minecraft.world.level.material.MapColor.WOOD).strength(2.5f).sound(net.minecraft.world.level.block.SoundType.WOOD));
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> MAP_DESK = ITEMS.registerSimpleBlockItem(MAP_DESK_BLOCK);

    /** Stilled liquids (StilledLiquids): taken with a shovel from stopped water and lava; they thaw where time runs. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<StilledLiquids.StilledBlock> STILLED_WATER_BLOCK = BLOCKS.registerBlock("stilled_water",
        p -> new StilledLiquids.StilledBlock(p, false), p -> p.mapColor(net.minecraft.world.level.material.MapColor.WATER).strength(0.5f)
            .sound(net.minecraft.world.level.block.SoundType.GLASS).noOcclusion());
    public static final net.neoforged.neoforge.registries.DeferredBlock<StilledLiquids.StilledBlock> STILLED_LAVA_BLOCK = BLOCKS.registerBlock("stilled_lava",
        p -> new StilledLiquids.StilledBlock(p, true), p -> p.mapColor(net.minecraft.world.level.material.MapColor.FIRE).strength(0.5f)
            .sound(net.minecraft.world.level.block.SoundType.GLASS).lightLevel(st -> 15));
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> STILLED_WATER = ITEMS.registerSimpleBlockItem(STILLED_WATER_BLOCK);
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> STILLED_LAVA = ITEMS.registerSimpleBlockItem("stilled_lava", STILLED_LAVA_BLOCK, p -> p.stacksTo(4));

    /** A rift crystal shard (placeholder for the dust / shard / charged grades). */
    /** What a ruined tower core breaks into (Baughn 2026-10-17: they never drop as cores); smelts into an iron nugget. */
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.Item> SCRAP = ITEMS.registerSimpleItem("scrap");
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.Item> RIFT_SHARD = ITEMS.registerSimpleItem("rift_shard");
    /** The crystal grades (Crystals): dust from small and medium buds, charged shards from a cluster broken in violet. */
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.Item> RIFT_DUST = ITEMS.registerSimpleItem("rift_dust");
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.Item> CHARGED_RIFT_SHARD = ITEMS.registerSimpleItem("charged_rift_shard",
        p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    /** Rift capacitors (Capacitors): take a stopped world's harm, vented with EA power. */
    public static final net.neoforged.neoforge.registries.DeferredItem<CapacitorItem> RIFT_CAPACITOR = ITEMS.registerItem("rift_capacitor",
        p -> new CapacitorItem(p, Capacitors.CAPACITOR_SECONDS), p -> p.stacksTo(1));
    public static final net.neoforged.neoforge.registries.DeferredItem<CapacitorItem> CHARGED_RIFT_CAPACITOR = ITEMS.registerItem("charged_rift_capacitor",
        p -> new CapacitorItem(p, Capacitors.CHARGED_SECONDS), p -> p.stacksTo(1).rarity(net.minecraft.world.item.Rarity.UNCOMMON));

    static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, StasisDelvers.MODID);
    static {
        RECIPE_SERIALIZERS.register("suit_upgrade", () -> SuitUpgradeRecipe.SERIALIZER);
    }

    static final DeferredRegister<com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.storage.loot.predicates.LootItemCondition>> LOOT_CONDITIONS =
        DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, StasisDelvers.MODID);
    static {
        LOOT_CONDITIONS.register("pocket_violet", () -> Crystals.PocketViolet.CODEC);
    }

    static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, StasisDelvers.MODID);
    /** Music for stopped land: a melancholic/eldritch subset of the vanilla tracks (assets/stasisdelvers/sounds.json). */
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> MUSIC_STOPPED = SOUNDS.register("music.stopped",
        () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "music.stopped")));
    /** A strata pocket about to turn rift hums (client/RiftHum; sounds/rift_hum.ogg, made with sox). */
    /** A storm's wind (client/RiftHum.StormWind; sounds/storm_wind.ogg, made with sox). */
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> STORM_WIND = SOUNDS.register("storm.wind",
        () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "storm.wind")));
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> RIFT_HUM = SOUNDS.register("rift.hum",
        () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "rift.hum")));

    /** Worldgen around static deeps (DeepFeature; data/stasisdelvers/worldgen, neoforge/biome_modifier). */
    static final DeferredRegister<com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.levelgen.feature.Feature>> FEATURE_TYPES =
        DeferredRegister.create(Registries.FEATURE_TYPE, StasisDelvers.MODID);
    static {
        FEATURE_TYPES.register("deep_dislocations", () -> DeepFeature.CODEC);
    }

    static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, StasisDelvers.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<Statue>> STATUE = ENTITY_TYPES.register("statue",
        () -> EntityType.Builder.<Statue>of(Statue::new, MobCategory.MISC).sized(0.6f, 1.8f).clientTrackingRange(10)
            .updateInterval(20).fireImmune().noSummon()
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "statue"))));

    /** Frozen in time (data/stasisdelvers/damage_type/frozen_in_time.json); bypasses armour, totems and resistance. */
    public static final ResourceKey<DamageType> FROZEN_IN_TIME =
        ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "frozen_in_time"));

    /**
     * Entities held still in stopped land, as the server sees it, sent to the client (Baughn: frozen items juddered and
     * ender pearls became stuck streaks: the client guessed differently). The client holds them exactly still.
     */
    static final DeferredRegister<net.neoforged.neoforge.attachment.AttachmentType<?>> ATTACHMENTS =
        DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.ATTACHMENT_TYPES, StasisDelvers.MODID);
    public static final java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<Boolean>> FROZEN = ATTACHMENTS.register("frozen",
        () -> net.neoforged.neoforge.attachment.AttachmentType.builder(() -> false).sync(net.minecraft.network.codec.ByteBufCodecs.BOOL).build());

    static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        ENTITY_TYPES.register(modBus);
        FEATURE_TYPES.register(modBus);
        SOUNDS.register(modBus);
        LOOT_CONDITIONS.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
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
