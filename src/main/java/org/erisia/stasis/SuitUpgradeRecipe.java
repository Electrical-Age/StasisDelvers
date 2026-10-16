package org.erisia.stasis;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

/**
 * A shaped recipe that turns a Delver suit piece into a stasis-suit piece and keeps its charge (AdventurAgent
 * 2026-10-13: crafting lost it). The charge moves in assemble(), so it shows in the result slot already and survives
 * every way of taking it (a crafting event missed shift-clicks). Recipe type stasisdelvers:suit_upgrade, otherwise the
 * vanilla shaped format.
 */
public class SuitUpgradeRecipe extends ShapedRecipe {
    final Recipe.CommonInfo ci;
    final CraftingRecipe.CraftingBookInfo bi;
    final ShapedRecipePattern pat;
    final ItemStackTemplate res;

    public SuitUpgradeRecipe(Recipe.CommonInfo ci, CraftingRecipe.CraftingBookInfo bi, ShapedRecipePattern pat, ItemStackTemplate res) {
        super(ci, bi, pat, res);
        this.ci = ci;
        this.bi = bi;
        this.pat = pat;
        this.res = res;
    }

    public static final MapCodec<SuitUpgradeRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.ci),
        CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bi),
        ShapedRecipePattern.MAP_CODEC.forGetter(o -> o.pat),
        ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> o.res)
    ).apply(i, SuitUpgradeRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, SuitUpgradeRecipe> STREAM_CODEC = StreamCodec.composite(
        Recipe.CommonInfo.STREAM_CODEC, o -> o.ci, CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> o.bi,
        ShapedRecipePattern.STREAM_CODEC, o -> o.pat, ItemStackTemplate.STREAM_CODEC, o -> o.res, SuitUpgradeRecipe::new);

    public static final RecipeSerializer<SuitUpgradeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RecipeSerializer<ShapedRecipe> getSerializer() {
        return (RecipeSerializer) SERIALIZER;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack out = super.assemble(input);
        if (!(out.getItem() instanceof StasisArmorItem armor)) return out;
        for (ItemStack in : input.items())
            if (in.getItem() instanceof DelverSuitItem d) {
                armor.setEnergy(out, Math.min(armor.getEnergyMax(out), d.getEnergy(in)));
                break;
            }
        return out;
    }
}
