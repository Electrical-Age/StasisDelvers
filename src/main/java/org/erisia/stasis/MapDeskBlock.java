package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The surveyor's desk at spawn (Baughn 2026-10-17): each player gets a map to a ruined cottage nobody else has a map
 * to (vanilla's one-map-per-structure rule), nearest first. One map per player, ever (Baughn). Not craftable: it's part
 * of the spawn cottage.
 */
public class MapDeskBlock extends Block {
    public static final TagKey<Structure> COTTAGES = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "on_cottage_maps"));
    static final String LAST = "stasisdelvers_map_desk";

    public MapDeskBlock(Properties p) {
        super(p);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
        CompoundTag persisted = p.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        if (persisted.getBooleanOr(LAST, false)) {
            p.sendSystemMessage(Component.literal("The surveyor's desk has given you your map: one to each delver."));
            return InteractionResult.SUCCESS_SERVER;
        }
        ItemStack map = map(sl, pos);
        if (map == null) {
            p.sendSystemMessage(Component.literal("The surveyor's notes hold no unclaimed ruins within reach."));
            return InteractionResult.SUCCESS_SERVER;
        }
        persisted.putBoolean(LAST, true);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        p.getInventory().placeItemBackInInventory(map, net.minecraft.util.Prediction.SERVER_ONLY);
        p.sendSystemMessage(Component.literal("A map to a ruined cottage: someone's tower that broke down. Bring power."));
        StasisDelvers.LOG.info("Map desk: {} got a map to a ruined cottage", p.getName().getString());
        return InteractionResult.SUCCESS_SERVER;
    }

    /** A map to the nearest unclaimed cottage (claiming it), framing the desk and the cottage; null if none in reach. */
    public static ItemStack map(ServerLevel level, BlockPos from) {
        BlockPos to = level.findNearestMapStructure(COTTAGES, from, 256, true);
        if (to == null) return null;
        int cx = (from.getX() + to.getX()) / 2, cz = (from.getZ() + to.getZ()) / 2;
        int span = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ())) + 64;
        byte scale = 1;
        while (scale < 4 && (128 << scale) < span) scale++;
        ItemStack map = MapItem.create(level, cx, cz, scale, true, true);
        MapItem.renderBiomePreviewMap(level, map);
        MapItemSavedData.addTargetDecoration(map, to, "+", MapDecorationTypes.RED_X);
        map.set(net.minecraft.core.component.DataComponents.ITEM_NAME, Component.literal("Map to a Ruined Cottage"));
        return map;
    }
}
