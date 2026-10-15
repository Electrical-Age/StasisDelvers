package org.erisia.stasis.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("level")
    ClientLevel stasis$level();

    @Accessor("x")
    double stasis$x();

    @Accessor("y")
    double stasis$y();

    @Accessor("z")
    double stasis$z();

    @Accessor("xo")
    void stasis$setXo(double v);

    @Accessor("yo")
    void stasis$setYo(double v);

    @Accessor("zo")
    void stasis$setZo(double v);

    @Accessor("age")
    int stasis$age();

    @Accessor("age")
    void stasis$setAge(int v);

    @Accessor("lifetime")
    int stasis$lifetime();
}
