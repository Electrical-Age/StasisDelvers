package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.TimeScars;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Temporal aftermath (Baughn): crops in structures (village farms) were caught at random moments when the world
 * crashed, so each stands at its own growth stage (TimeScars.cropAge), in stopping dimensions.
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin {
    @Inject(method = "processBlockInfos(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Ljava/util/List;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;)Ljava/util/List;",
        at = @At("RETURN"), cancellable = true)
    private static void stasis$cropAftermath(ServerLevelAccessor level, BlockPos position, BlockPos referencePos, StructurePlaceSettings settings,
                                             List<StructureTemplate.StructureBlockInfo> in, StructureTemplate template,
                                             CallbackInfoReturnable<List<StructureTemplate.StructureBlockInfo>> cir) {
        List<StructureTemplate.StructureBlockInfo> l = cir.getReturnValue();
        if (l == null || l.isEmpty() || !Stasis.stops(level.getLevel())) return;
        List<StructureTemplate.StructureBlockInfo> out = null;
        for (int i = 0; i < l.size(); i++) {
            var b = l.get(i);
            if (!(b.state().getBlock() instanceof CropBlock crop)) continue;
            if (out == null) out = new ArrayList<>(l);
            out.set(i, new StructureTemplate.StructureBlockInfo(b.pos(), TimeScars.cropAge(crop, b.state(), b.pos()), b.nbt()));
        }
        if (out != null) cir.setReturnValue(out);
    }
}
