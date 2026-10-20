package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

/**
 * A stasis tower. Its height (segments stacked on the base) sets the field radius r = 8 (segments + 2); its draw
 * is P = k r^1.5 W and its store holds c r^2 J (Baughn: bigger bases get more time to fix: store/draw grows as
 * sqrt(r); r = 24 runs 10 minutes on a full store). States:
 * <ul>
 * <li>OFF: no field. Starts once the store is at least half full.</li>
 * <li>RUNNING: the field is up and its chunks stay loaded; the draw comes out of the store. While less comes in than
 * goes out, the shield shows it (failing = how far the store has run down).</li>
 * <li>COLLAPSING: the store ran out; the field stops in patches, outer blocks first, over 15 s, then OFF.</li>
 * </ul>
 * The base block entity ticks even in stopped land (LevelChunkMixin), or it could never start.
 */
public class TowerBlockEntity extends BlockEntity {
    public enum State { OFF, RUNNING, COLLAPSING }

    /** FE per J (Electrical Age's exporter ratio, 4/3). */
    static final double FE_PER_J = 4.0 / 3.0;
    static final int COLLAPSE_TICKS = 15 * 20;

    State state = State.OFF;
    int fieldId;
    int segments;
    int collapseTicks;
    /** Net power (in - draw, J per tick), averaged over about 3 s; draining with hysteresis (Baughn: no flicker). */
    double netAvg;
    /** Share of the field in a drifting deep ("weather": up to +50% draw, more lightning); updated every second. */
    float weather;
    /** Share of the field in a static deep's outer zone: up to x3 draw. */
    float staticWeather;
    /** Tests: keep the weather set by testWeather. */
    boolean weatherPinned;

    /** Storm shares (stage 3): mist (with drifting deeps) and rift, while a storm runs; else 0. */
    float stormMist, stormRift;
    boolean storming;

    /** Baughn: in a storm mist x2 and rift x3 (as disjoint shares); in normal weather a drifting deep x1.5. */
    double weatherFactor() {
        double w = storming ? stormMist + 2 * stormRift : 0.5 * weather;
        return 1 + w + 2 * staticWeather + faunaPressure;
    }

    /** Rift fauna pressing on the shield (λ-Nero: they raise the draw, like a storm, and can make it fail): +0.5 each, at most +2. */
    double faunaPressure;

    public static double faunaPressureForTests(ServerLevel level, Field f) {
        return faunaPressure(level, f);
    }

    /** Fauna within 4 blocks outside the field's surface. Every 20 ticks with the weather (weatherFactor is read every tick). */
    static double faunaPressure(ServerLevel level, Field f) {
        double reach = f.radius() + 4;
        var box = new net.minecraft.world.phys.AABB(f.x() - reach, f.y() - reach, f.z() - reach, f.x() + reach, f.y() + reach, f.z() + reach);
        int n = 0;
        for (RiftFauna e : level.getEntitiesOfClass(RiftFauna.class, box)) {
            double d = Math.sqrt(e.distanceToSqr(f.x(), f.y(), f.z()));
            if (d <= reach && !f.containsPoint(e.getX(), e.getY(), e.getZ())) n++;
        }
        return Math.min(4, n) * 0.5;
    }

    static float staticOverlap(ServerLevel level, Field f) {
        int in = 0, n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    double[] p = f.toward(dx, dy, dz, 0.7);
                    n++;
                    if (StaticDeeps.zone(level, (int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])) > 0) in++;
                }
        return in / (float) n;
    }
    /** Game time the field started (for the lock-on sound; not saved). */
    long startedAt = -1;
    boolean draining;

    public double failing() {
        return failing;
    }

    public boolean draining() {
        return draining;
    }
    double failing;

    /** The store, in FE (insert-only from outside). */
    final Store store = new Store();

    final class Store extends SimpleEnergyHandler {
        Store() {
            super(1, Integer.MAX_VALUE, 0);
        }

        void setCapacity(int c) {
            capacity = Math.max(1, c);
            if (energy > capacity) energy = capacity;
        }

        int amount() {
            return energy;
        }

        int capacity() {
            return capacity;
        }

        /** Testing (/stasis tower charge): set the store to a share of its capacity. */
        void setShare(double share) {
            energy = (int) Math.max(0, Math.min(capacity, share * capacity));
        }

        void take(int fe) {
            energy = Math.max(0, energy - fe);
        }

        /** Size the store before taking energy (the first insert can come before onLoad or the first tick). */
        @Override
        public int insert(int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            if (level != null && !level.isClientSide()) ensureSized();
            return super.insert(amount, transaction);
        }

        @Override
        protected void onEnergyChanged(int previousAmount) {
            if (energy > previousAmount) received += energy - previousAmount;
            setChanged();
        }
    }

    private int received;   // FE inserted this tick
    private boolean sized;

    /** The generated tower over this core (P3d). */
    public final TowerStructure structure = new TowerStructure();

    public TowerBlockEntity(BlockPos pos, BlockState state) {
        super(StasisRegistry.TOWER_ENTITY.get(), pos, state);
        structure.seed = (int) (pos.asLong() ^ (pos.asLong() >>> 32));
    }

    /** Sends the tower's layout and status to clients (they build and draw the mesh). */
    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    /** Count the segments and size the store as soon as the tower exists (before its first tick). */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) TowerClientHooks.updated(this);
        if (level != null && !level.isClientSide()) resize();
    }

    /** Count the segments and size the store for the design radius. */
    private void resize() {
        segments = countSegments();
        store.setCapacity(storeCapacity());
    }

    /** Size the store once, before the first energy moves (an insert or a feed can come before onLoad or the first tick). */
    private void ensureSized() {
        if (sized) return;
        sized = true;
        resize();
    }

    /** The store's capacity in FE, for the design radius. */
    private int storeCapacity() {
        return (int) (storeFor(designRadius()) * FE_PER_J);
    }

    public State state() {
        return state;
    }

    public int segments() {
        return segments;
    }

    /**
     * The field's radius now (the effective radius; console, safety and steps applied). Before the tower runs: what
     * it would start at.
     */
    public double radius() {
        return effRadius > 0 ? effRadius : goalRadius();
    }

    // ---- range (tower console, CONSOLE-PLAN.md A; Baughn's refinements)

    /** The preferred range: from the tower's size (mast segments), as before. */
    public double designRadius() {
        return radiusFor(segments);
    }

    /** The least the field shrinks to: just the tower itself (the mast and the core, plus a margin). */
    public double minRadius() {
        return Math.min(designRadius(), Math.max(6, segments + 4));
    }

    /** The most it expands to: slightly past the preferred range, at ruinous cost (drawPenalty). */
    public double maxRadius() {
        return designRadius() * MAX_EXPAND;
    }

    static final double MAX_EXPAND = 1.25, STEP = 2;

    /** The console's (or the signal port's) target; NaN: the preferred range. */
    double targetRadius = Double.NaN;
    /** OVERRIDE: no safety retreat (it runs at full size until it collapses). */
    boolean override;
    /** The radius the field has now; 0 while off. */
    double effRadius;

    public double target() {
        return Math.max(minRadius(), Math.min(maxRadius(), Double.isNaN(targetRadius) ? designRadius() : targetRadius));
    }

    public boolean override() {
        return override;
    }

    /**
     * Block-entity flags (Baughn 2026-10-17; set with /data, in templates, or by the ruined-cottage placement): locked =
     * the console shows but can't change the radius or OVERRIDE (the spawn tower); coreDrop = the chance this core block
     * drops itself when broken (else scrap: ruined cottages 0); ruined = a ruin's core: its tower isn't read or drawn
     * (the bare poles show the damage) until it first runs (Baughn). Kept on every core block.
     */
    public boolean locked, ruined;
    public double coreDrop = 1;

    /** Console: feeding the store from a player's batteries and suit (Baughn: a jump-start), 2 kW while it lasts. */
    java.util.@org.jspecify.annotations.Nullable UUID feeder;
    double fed;
    /** The feed started on a dead tower: it stops as the tower starts (AdventurAgent: it emptied the suit regardless). */
    boolean feedingDead;
    static final double FEED_W = 2000;
    /** Feeding a running tower leaves a quarter in each suit piece (enough to walk home). */
    static final double FEED_RESERVE = 0.25;

    public void toggleFeed(ServerPlayer p) {
        if (p.getUUID().equals(feeder)) feeder = null;
        else {
            // a dead tower you can't start would only take your suit (AdventurAgent: 42 kJ into a spawn tower needing 353)
            if (state == State.OFF) {
                ensureSized();
                double need = 0.5 * storeMaxJ() - storedJ(), have = Suits.available(p);
                if (have < need) {
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format(
                        "This tower needs %.0f kJ more to start, and you carry %.0f kJ. Feeding it would only empty your suit.",
                        need / 1000, have / 1000)).withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }
            }
            feeder = p.getUUID();
            fed = 0;
            feedingDead = state == State.OFF;
            StasisDelvers.LOG.info("{} feeds the tower at {} from their suit", p.getName().getString(), worldPosition.toShortString());
        }
    }

    /** Once a second: move up to FEED_W from the feeder (carried batteries first, then the suit pieces evenly). */
    void feed(ServerLevel sl) {
        if (feeder == null) return;
        if (!(sl.getPlayerByUUID(feeder) instanceof ServerPlayer p) || p.isSpectator()
            || p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(worldPosition)) > 8 * 8) {
            feeder = null;
            return;
        }
        ensureSized();
        if (feedingDead && state != State.OFF) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format(
                "The tower is up. You fed it %.1f kJ and kept the rest.", fed / 1000)));
            feeder = null;
            return;
        }
        double room = (store.capacity() - store.amount()) / FE_PER_J;
        if (feedingDead) room = Math.min(room, Math.max(0, 0.5 * storeMaxJ() - storedJ()) + 1);   // (just enough to start it)
        double got = Suits.drain(p, Math.min(FEED_W, room), feedingDead ? 0 : FEED_RESERVE);
        if (got > 0) {
            store.set(Math.min(store.capacity(), store.amount() + (int) (got * FE_PER_J)));
            fed += got;
            setChanged();
        }
        if (got < 1 || room - got < 1) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format("Fed the tower %.1f kJ%s.", fed / 1000,
                room - got < 1 ? (feedingDead ? "; it starts now" : "; its store is full")
                    : feedingDead ? "; you have nothing left to give" : "; your suit keeps a quarter to walk home on")));
            feeder = null;
        }
    }

    /** Console: set the target radius (NaN: the preferred range) and OVERRIDE (ignored on a locked tower). */
    public void setControls(double target, boolean override) {
        if (locked) return;
        this.targetRadius = Double.isNaN(target) ? Double.NaN : Math.max(minRadius(), Math.min(maxRadius(), target));
        this.override = override;
        setChanged();
    }

    /**
     * The failure cycle (Baughn 2026-10-17, notes/timestop/FAILURE-PLAN.md A): failure shows first, at full size
     * (failing, the strain); once it's well under way the safety retreats, only as far as the input can carry (with
     * no input it holds on, and retreats to the minimum as a last ditch below 15%); at 80% it grows back to the target,
     * overdraws, and fails again, until someone steers it. OVERRIDE: no retreat.
     */
    boolean retreating;
    double retreatRadius;

    public boolean retreating() {
        return retreating;
    }

    /** Where the field is heading: the target, or the retreat radius. */
    public double goalRadius() {
        return retreating && !override ? Math.max(minRadius(), Math.min(target(), retreatRadius)) : target();
    }

    /** The largest radius (up to the target) whose draw the input carries with a margin, or -1 if none. */
    public double carryRadius() {
        double w = weatherFactor();
        for (double r = Math.floor(target()); r >= minRadius(); r -= 1)
            if (drawAt(r) * w <= 0.9 * inAvgW) return r;
        return -1;
    }

    /** Once a second: retreat or regrow. */
    void safety() {
        double share = storedJ() / Math.max(1, storeMaxJ());
        if (override) {
            retreating = false;
            return;
        }
        if (!retreating && failing > 0.5) {
            double carry = carryRadius();
            if (carry > 0 && carry < effRadius - 2) {
                retreating = true;
                retreatRadius = carry;
            } else if (share < 0.15) {   // nothing carries it: the last ditch
                retreating = true;
                retreatRadius = minRadius();
            }
        } else if (retreating && share >= 0.8) retreating = false;   // grow back (and fail again)
    }

    /** Above the preferred range the draw climbs steeply: (R / R_design)^6 (x3.8 at +25%). */
    public double drawPenalty(double r) {
        double d = designRadius();
        return r > d ? Math.pow(r / d, 6) : 1;
    }

    public static double radiusFor(int segments) {
        return 8.0 * (segments + 2);   // Baughn 2026-10-14: twice the old 4 (segments + 2)
    }

    /**
     * The field's reach each way along the ground (Field.Stretch): from the built tower's booms (Baughn 2026-10-18).
     * Kept while the tower rebuilds or a pole is invalid; saved.
     */
    public Field.Stretch stretch = Field.Stretch.ONE;

    /** A side reaches 1 + k * (its longest boom's share of its allowed length, H - y). */
    public static Field.Stretch stretchFor(org.erisia.stasis.towergen.Layout layout) {
        double[] f = new double[4];
        for (var b : layout.booms()) {
            int room = layout.H() - b.y();
            if (room > 0) f[b.dir()] = Math.max(f[b.dir()], Math.min(1.0, b.length() / (double) room));
        }
        double k = StasisConfig.towerBoomStretch;
        return new Field.Stretch(1 + k * f[0], 1 + k * f[1], 1 + k * f[2], 1 + k * f[3]);
    }

    /** The radius of the sphere with the stretched field's volume, per unit radius: cbrt(mean of the four ground
     *  quarters' sx * sz). The draw follows volume (Baughn: yes); the store stays on the base radius. */
    public double volumeScale() {
        var t = stretch;
        return Math.cbrt((t.e() * t.s() + t.s() * t.w() + t.w() * t.n() + t.n() * t.e()) / 4);
    }

    /** Draw in W at radius r, with this tower's stretch and the penalty above the preferred range. */
    public double drawAt(double r) {
        return drawFor(r * volumeScale()) * drawPenalty(r);
    }

    void updateStretch(ServerLevel sl) {
        if (ruined || structure.status != TowerStructure.Status.OK || structure.layout == null) return;
        Field.Stretch want = stretchFor(structure.layout);
        if (want.equals(stretch)) return;
        stretch = want;
        if (fieldId != 0) FieldData.get(sl).update(sl, fieldId, f -> f.withStretch(want));
        Tickets.update(sl, this);
        setChanged();
    }

    /** Draw in W. */
    public static double drawFor(double r) {
        return StasisConfig.towerDrawK * Math.pow(r, 1.5);
    }

    /** Tests: multiply this tower's draw (to empty it quickly). */
    public double testDrawFactor = 1;

    /** Store in J. */
    public static double storeFor(double r) {
        return StasisConfig.towerStoreC * r * r;
    }

    public double storedJ() {
        return store.amount() / FE_PER_J;
    }

    /** The store is sized by the preferred range, so changing the target never clips energy. */
    public double storeMaxJ() {
        return storeFor(designRadius());
    }

    int countSegments() {
        int n = 0;
        BlockPos p = TowerStructure.pole(worldPosition, 1);
        while (n < StasisConfig.towerMaxSegments && TowerPoleBlock.isMast(level.getBlockState(p))) {
            n++;
            p = p.above();
        }
        return n;
    }

    public int ticks;

    void serverTick() {
        if (!(level instanceof ServerLevel sl)) return;
        ticks++;
        if (!ruined && structure.tick(sl, worldPosition, ticks)) {
            setChanged();
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        if (ticks % 20 == 7) updateStretch(sl);
        if (level.getGameTime() % 20 == 0) feed(sl);
        if (level.getGameTime() % 20 == 0 || segments == 0) {
            int s = countSegments();
            if (s != segments) {
                segments = s;
                Tickets.update(sl, this);
            }
        }
        // the field's radius follows its goal, a block a second (shrinking gives grace to land left stopped)
        if (state == State.RUNNING && level.getGameTime() % 20 == 10) {
            if (effRadius <= 0)   // (a save from before the console: take the field as it is)
                effRadius = FieldData.get(sl).get(fieldId).map(Field::radius).orElse(designRadius());
            safety();
            double g = goalRadius();
            // coarse steps on the server (each one re-meshes the shell's chunks): a retreat in two jumps, growth
            // a block or two a second; clients ease the shield between them (StasisPost)
            double diff = g - effRadius;
            double stepSize = diff < 0 ? Math.max(STEP, Math.ceil(-diff / 2)) : Math.max(1, Math.ceil(diff / 8));
            double next = diff < 0 ? Math.max(g, effRadius - stepSize) : Math.min(g, effRadius + stepSize);
            if (next != effRadius) {
                effRadius = next;
                FieldData.get(sl).update(sl, fieldId, f -> f.withRadius(effRadius));
                Tickets.update(sl, this);
            }
        }
        double r = radius();
        store.setCapacity(storeCapacity());
        if (level.getGameTime() % 10 == 3) signals(sl);
        double in = received / FE_PER_J;
        received = 0;
        inAvgW += (in * 20 - inAvgW) / 40.0;
        if (weatherPinned) {
            // (test)
        } else if (state == State.RUNNING && level.getGameTime() % 20 == 0) {
            weather = FieldData.get(sl).get(fieldId).map(f -> Deeps.overlap(sl, f)).orElse(0f);
            staticWeather = FieldData.get(sl).get(fieldId).map(f -> staticOverlap(sl, f)).orElse(0f);
            storming = Storms.intensity(sl) > 0;
            float[] st = storming ? FieldData.get(sl).get(fieldId).map(f -> Storms.overlap(sl, f)).orElse(new float[2]) : new float[2];
            stormMist = st[0];
            stormRift = st[1];
            faunaPressure = FieldData.get(sl).get(fieldId).map(f -> faunaPressure(sl, f)).orElse(0.0);
        } else if (state != State.RUNNING) {
            weather = staticWeather = stormMist = stormRift = 0;
            faunaPressure = 0;
            storming = false;
        }
        double perTick = drawAt(r) * testDrawFactor * weatherFactor() / 20.0;
        switch (state) {
            case OFF -> {
                if (storedJ() >= 0.5 * storeMaxJ()) start(sl);
            }
            case RUNNING -> {
                if (FieldData.get(sl).get(fieldId).isEmpty()) {     // removed by a command: start over
                    stop(sl);
                    break;
                }
                if (startedAt >= 0 && sl.getGameTime() - startedAt == 60) {
                    sl.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.CONDUIT_ACTIVATE, net.minecraft.sounds.SoundSource.BLOCKS, 2.5F, 0.7F);
                    bolt(sl);   // lock-on
                    startedAt = -1;
                }
                // a shield losing power sparks now and then, by how fast it drains (Baughn: stable at 5% throws
                // nothing), about every 25 s at a full drain
                double drain = Math.max(0, Math.min(1, -netAvg / Math.max(1e-9, perTick)));
                if (drain > 0.1 && sl.getRandom().nextDouble() < 0.002 * drain * drain) bolt(sl);
                if (weather > 0 && sl.getRandom().nextDouble() < 0.003 * weather) bolt(sl);   // a deep crossing the field
                // overcharged (pushed past its preferred range, Baughn): lightning lashes outwards from the mast, more
                // the further it's pushed (about every 2 s at +25%)
                double over = effRadius / designRadius() - 1;
                if (over > 0.01 && sl.getRandom().nextDouble() < 0.1 * over / (MAX_EXPAND - 1)) boltOut(sl);
                store.take((int) Math.ceil(perTick * FE_PER_J));
                netAvg += (in - perTick - netAvg) / 60.0;
                if (netAvg < -0.02 * perTick) draining = true;
                else if (netAvg > 0.02 * perTick) draining = false;
                // the strain (Baughn: failure shows first, while there's charge): rising with the drain and as the charge
                // falls; a retreated shield keeps a floor of it (the rim stays amber while it recharges)
                double share = storedJ() / storeMaxJ();
                double target = draining ? Math.min(1, (0.25 + 0.75 * (1 - share)) * Math.min(1, drain * 3)) : 0;
                if (retreating) target = Math.max(target, 0.25);
                failing += (target - failing) / 40.0;
                // the shield's hexes (Baughn 2026-10-17, as shares of all hexes): draining, 5-15% orange by the drain;
                // under half charge they start dropping out, 20% by 20% charge (80% always stay); orange spreads
                // smoothly to the whole shield by 10%
                double a0 = draining ? 0.05 + 0.10 * Math.min(1, drain) : 0;
                double ta = Math.max(0, Math.min(1, (0.5 - share) / 0.4)), td = Math.max(0, Math.min(1, (0.5 - share) / 0.3));
                amber += (a0 + (1 - a0) * ta * ta * (3 - 2 * ta) - amber) / 40.0;
                dropout += (0.2 * td * td * (3 - 2 * td) - dropout) / 40.0;
                if (level.getGameTime() % 5 == 0) FieldData.get(sl).update(sl, fieldId, f -> f.withFailing(failing).withShield(amber, dropout, 0));
                if (store.amount() <= 0) {
                    state = State.COLLAPSING;
                    collapseTicks = 0;
                    collapseProgress = 0;
                    // (Baughn 2026-10-17) a collapse loses hexes until half of the shield is gone, leaning to one side
                    // (the storm's, else random), and time stops right then: lost = dropout + collapse (stasis.fsh)
                    freezeAt = Math.max(0.05, 0.5 - dropout);
                    collapseSpeed = freezeAt / (10 + 15 * sl.getRandom().nextDouble());   // 10-25 s
                    double storm = storming ? FieldData.get(sl).get(fieldId).map(f -> Storms.side(sl, f)).orElse(Double.NaN) : Double.NaN;
                    side = Double.isNaN(storm) ? sl.getRandom().nextDouble() * 2 * Math.PI : storm;
                    double d0 = dropout, sd = side;
                    boltBurst = 0;
                    int seed = sl.getRandom().nextInt() & 0xffffff;   // (24 bits: exact in the shader's floats)
                    collapseSeed = seed;
                    FieldData.get(sl).update(sl, fieldId, f -> f.withSeed(seed).withShield(1, d0, sd));
                    StasisDelvers.LOG.info("Stasis tower at {} ran out of power: field {} collapses", worldPosition.toShortString(), fieldId);
                }
            }
            case COLLAPSING -> {
                // (Baughn: too predictable) each collapse runs at its own pace, 10-25 s, and time stutters through
                // it: the front pauses, lurches, now and then slips back a little, with lightning in bursts
                var rnd = sl.getRandom();
                collapseTicks++;
                double roll = rnd.nextDouble();
                double step = collapseSpeed * (roll < 0.12 ? 0 : roll < 0.2 ? -1.5 : roll < 0.3 ? 3 : 1);
                collapseProgress = Math.max(0, Math.min(freezeAt, collapseProgress + step / 20.0));
                if (rnd.nextInt(boltBurst > 0 ? 3 : 25) == 0) bolt(sl);
                if (boltBurst > 0) boltBurst--;
                else if (rnd.nextInt(60) == 0) boltBurst = 10 + rnd.nextInt(20);
                double c = collapseTicks >= 25 * 20 ? freezeAt : collapseProgress;   // (never longer than 25 s)
                if (collapseTicks % 4 == 0 || c >= freezeAt) FieldData.get(sl).update(sl, fieldId, f -> f.withCollapse(c).withFailing(1));
                if (c >= freezeAt) freeze(sl, c);
            }
        }
        setChanged();
    }

    /** Testing: /stasis tower charge|bolt. */
    public void testCharge(double share) {
        store.setShare(share);
        setChanged();
    }

    public void testWeather(float drifting, float statics) {
        weather = drifting;
        staticWeather = statics;
        weatherPinned = true;
    }

    /** Tests: pin a storm's shares (mist, rift) and read the weather factor. */
    public double testStorm(float mist, float rift) {
        storming = true;
        stormMist = mist;
        stormRift = rift;
        weatherPinned = true;
        return weatherFactor();
    }

    /** Tests: set the input average (W) and the strain, and run the safety once. */
    public void testSafety(double inW, double strain) {
        inAvgW = inW;
        failing = strain;
        if (effRadius <= 0) effRadius = target();
        safety();
    }

    public void testBolt() {
        if (level instanceof ServerLevel sl) bolt(sl);
    }

    void bolt(ServerLevel sl) {
        StasisNet.sendBolt(sl, makeBolt(sl));
    }

    StasisNet.Bolt makeBolt(ServerLevel sl) {
        var rnd = sl.getRandom();
        double r = radius();
        double ux = rnd.nextGaussian(), uy = Math.abs(rnd.nextGaussian()) + 0.3, uz = rnd.nextGaussian();
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        double cx = worldPosition.getX() + 0.5, cy = worldPosition.getY() + 0.5, cz = worldPosition.getZ() + 0.5;
        double top = worldPosition.getY() + TowerBlock.HEIGHT + segments + 0.5;
        // no sound: the client's anti-sound cuts every other sound for the crack (client.AntiSound)
        double sx = ux >= 0 ? stretch.e() : stretch.w(), sz = uz >= 0 ? stretch.s() : stretch.n();
        return new StasisNet.Bolt(cx + ux / ul * r * sx * 0.97, cy + uy / ul * r * 0.97, cz + uz / ul * r * sz * 0.97, cx, top, cz, rnd.nextInt());
    }

    /** A bolt from the mast top outwards, through the shield to the land beyond it (an overcharged tower). */
    void boltOut(ServerLevel sl) {
        StasisNet.sendBolt(sl, makeBoltOut(sl));
    }

    StasisNet.Bolt makeBoltOut(ServerLevel sl) {
        var rnd = sl.getRandom();
        double r = radius() * (1.05 + 0.3 * rnd.nextDouble());
        double a = rnd.nextDouble() * 2 * Math.PI;
        double cx = worldPosition.getX() + 0.5, cz = worldPosition.getZ() + 0.5;
        double top = worldPosition.getY() + TowerBlock.HEIGHT + segments + 0.5;
        double sx = Math.cos(a) >= 0 ? stretch.e() : stretch.w(), sz = Math.sin(a) >= 0 ? stretch.s() : stretch.n();
        int tx = (int) Math.floor(cx + Math.cos(a) * r * sx), tz = (int) Math.floor(cz + Math.sin(a) * r * sz);
        double ty = sl.hasChunkAt(new BlockPos(tx, 0, tz))
            ? sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, tx, tz) : worldPosition.getY();
        return new StasisNet.Bolt(cx, top, cz, tx + 0.5, ty, tz + 0.5, rnd.nextInt());
    }

    /** The end of a collapse: the field goes, and its last moment (a few bolts mid-strike) stays as the remnant. */
    void freeze(ServerLevel sl, double c) {
        var rnd = sl.getRandom();
        java.util.List<StasisNet.Bolt> last = new java.util.ArrayList<>();
        // (three: the shader takes 150 bolt points, about 36 per held bolt)
        last.add(makeBolt(sl));
        last.add(makeBolt(sl));
        last.add(rnd.nextBoolean() ? makeBoltOut(sl) : makeBolt(sl));
        last.forEach(b -> StasisNet.sendBolt(sl, b));
        Remnant rem = new Remnant(radius(), collapseSeed, c, dropout, side, sl.getGameTime(), java.util.List.copyOf(last), stretch);
        stop(sl);
        remnant = rem;
        setChanged();
        sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        StasisDelvers.LOG.info("Stasis tower at {}: the collapse froze at {}", worldPosition.toShortString(), String.format("%.2f", c));
    }

    void start(ServerLevel sl) {
        ruined = false;   // (a ruin that runs is a tower again: read and drawn from the next tick)
        if (remnant != null) {
            remnant = null;
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        effRadius = 0;
        effRadius = goalRadius();
        Field f = FieldData.get(sl).add(sl, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, radius(), false,
            StasisConfig.towerFieldRate);
        fieldId = f.id();
        updateStretch(sl);
        if (!stretch.isOne()) FieldData.get(sl).update(sl, fieldId, x -> x.withStretch(stretch));
        // start-up: visual only (Baughn); clients animate from formedAt, about 3 s, with a hum and a lock-on sound
        long now = sl.getGameTime();
        FieldData.get(sl).update(sl, fieldId, x -> x.withFormedAt(now));
        startedAt = now;
        sl.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.BLOCKS, 2.0F, 0.6F);
        state = State.RUNNING;
        failing = amber = dropout = 0;
        netAvg = 0;
        draining = false;
        Tickets.update(sl, this);
        StasisDelvers.LOG.info("Stasis tower at {} started: field {} r {}", worldPosition.toShortString(), fieldId, radius());
    }

    void stop(ServerLevel sl) {
        if (fieldId != 0) FieldData.get(sl).remove(sl, fieldId);
        fieldId = 0;
        state = State.OFF;
        effRadius = 0;
        retreating = false;
        collapseTicks = 0;
        failing = amber = dropout = 0;
        Tickets.update(sl, this);
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel sl && state != State.OFF && !removedByUnload) stop(sl);
        if (!removedByUnload && node != null) {
            try {
                node.onBreakBlock();
            } catch (LinkageError e) {
                // (no EA)
            }
            node = null;
        }
        if (level != null && !level.isClientSide() && !removedByUnload) structure.release(level);
        if (level != null && level.isClientSide()) TowerClientHooks.removed(this);
        super.setRemoved();
    }

    private boolean removedByUnload;

    @Override
    public void onChunkUnloaded() {
        removedByUnload = true;
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putString("state", state.name());
        out.putInt("field", fieldId);
        out.putInt("segments", segments);
        out.putInt("collapse", collapseTicks);
        out.putInt("energy", store.amount());
        out.putDouble("failing", failing);
        if (locked) out.putBoolean("locked", true);
        if (ruined) out.putBoolean("ruined", true);
        if (coreDrop < 1) out.putDouble("coreDrop", coreDrop);
        out.putDouble("amber", amber);
        out.putDouble("dropout", dropout);
        out.putDouble("side", side);
        out.putDouble("collapseProgress", collapseProgress);
        out.putDouble("freezeAt", freezeAt);
        out.putDouble("collapseSpeed", collapseSpeed);
        if (!Double.isNaN(targetRadius)) out.putDouble("target", targetRadius);
        out.putBoolean("override", override);
        out.putDouble("radius", effRadius);
        out.putDouble("stretchE", stretch.e());
        out.putDouble("stretchS", stretch.s());
        out.putDouble("stretchW", stretch.w());
        out.putDouble("stretchN", stretch.n());
        out.putBoolean("retreating", retreating);
        out.putDouble("retreatRadius", retreatRadius);
        out.putString("facing", facing.getSerializedName());
        if (remnant != null) {
            out.putDouble("remnantRadius", remnant.radius());
            out.putInt("remnantSeed", remnant.seed());
            out.putDouble("remnantCollapse", remnant.collapse());
            out.putDouble("remnantDropout", remnant.dropout());
            out.putDouble("remnantSide", remnant.side());
            out.putLong("remnantFrozeAt", remnant.frozeAt());
            var list = out.childrenList("remnantBolts");
            for (StasisNet.Bolt b : remnant.bolts()) {
                var o = list.addChild();
                o.putDouble("fx", b.fx());
                o.putDouble("fy", b.fy());
                o.putDouble("fz", b.fz());
                o.putDouble("tx", b.tx());
                o.putDouble("ty", b.ty());
                o.putDouble("tz", b.tz());
                o.putInt("seed", b.seed());
            }
        }
        structure.save(out);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        try {
            state = State.valueOf(in.getStringOr("state", "OFF"));
        } catch (IllegalArgumentException e) {
            state = State.OFF;
        }
        fieldId = in.getIntOr("field", 0);
        segments = in.getIntOr("segments", 0);
        collapseTicks = in.getIntOr("collapse", 0);
        store.setCapacity(Integer.MAX_VALUE);
        store.set(in.getIntOr("energy", 0));
        failing = in.getDoubleOr("failing", 0);
        locked = in.getBooleanOr("locked", false);
        ruined = in.getBooleanOr("ruined", false);
        coreDrop = in.getDoubleOr("coreDrop", 1);
        amber = in.getDoubleOr("amber", 0);
        dropout = in.getDoubleOr("dropout", 0);
        side = in.getDoubleOr("side", 0);
        collapseProgress = in.getDoubleOr("collapseProgress", 0);
        freezeAt = in.getDoubleOr("freezeAt", 0.5);
        collapseSpeed = in.getDoubleOr("collapseSpeed", freezeAt / 15);
        targetRadius = in.getDoubleOr("target", Double.NaN);
        override = in.getBooleanOr("override", false);
        effRadius = in.getDoubleOr("radius", 0);
        stretch = new Field.Stretch(in.getDoubleOr("stretchE", 1), in.getDoubleOr("stretchS", 1), in.getDoubleOr("stretchW", 1),
            in.getDoubleOr("stretchN", 1));
        var fd = net.minecraft.core.Direction.byName(in.getStringOr("facing", "south"));
        facing = fd != null && fd.getAxis().isHorizontal() ? fd : net.minecraft.core.Direction.SOUTH;
        retreating = in.getBooleanOr("retreating", false);
        retreatRadius = in.getDoubleOr("retreatRadius", 0);
        double rr = in.getDoubleOr("remnantRadius", 0);
        remnant = rr <= 0 ? null : new Remnant(rr, in.getIntOr("remnantSeed", 0), in.getDoubleOr("remnantCollapse", 0.5),
            in.getDoubleOr("remnantDropout", 0), in.getDoubleOr("remnantSide", 0), in.getLongOr("remnantFrozeAt", 0L),
            in.childrenListOrEmpty("remnantBolts").stream().map(o -> new StasisNet.Bolt(o.getDoubleOr("fx", 0), o.getDoubleOr("fy", 0),
                o.getDoubleOr("fz", 0), o.getDoubleOr("tx", 0), o.getDoubleOr("ty", 0), o.getDoubleOr("tz", 0), o.getIntOr("seed", 0))).toList(),
            stretch);   // (the tower's: a ruin isn't rebuilt, so it is the remnant's)
        structure.load(in);
        if (level != null && level.isClientSide()) TowerClientHooks.updated(this);
    }

    /** The collapse's own pace and progress (0..1), and a lightning burst in progress (ticks). */
    double collapseProgress, collapseSpeed = 1.0 / 15;
    int boltBurst, collapseSeed;
    double freezeAt = 0.5;
    /** The shield's look (Field.amber, dropout, side), eased; dropout and side held through a collapse. */
    double amber, dropout, side;

    /**
     * What's left of a collapsed field (Baughn: the tower never sees zero charge; no moment passes where it would):
     * its last moment, stopped for good. Drawn by the client (Remnants) until the tower starts again or is broken.
     */
    public record Remnant(double radius, int seed, double collapse, double dropout, double side, long frozeAt, java.util.List<StasisNet.Bolt> bolts,
                          Field.Stretch stretch) {
    }

    Remnant remnant;

    public Remnant remnant() {
        return remnant;
    }

    /** Power coming in, W (an average over a few seconds), for the console. */
    double inAvgW;

    /** What each value of consoleState's v is (bug reports name them with this; keep it in step with consoleState). */
    public static final java.util.List<String> CONSOLE_NAMES = java.util.List.of("state", "stored_J", "max_J", "in_W", "draw_W",
        "weather_factor", "preferred_radius", "min_radius", "max_radius", "target_radius", "radius_now", "goal_radius", "override",
        "target_by_signal", "retreating", "carry_radius", "failing", "locked", "feeding_from_asker", "kJ_fed");

    /** The console's readout (StasisNet.ConsoleState). */
    public StasisNet.ConsoleState consoleState() {
        return consoleState(null);
    }

    /** ... plus, for the player asking: locked (17), feeding from them (18), kJ fed so far (19). */
    public StasisNet.ConsoleState consoleState(@org.jspecify.annotations.Nullable ServerPlayer asker) {
        double r = radius();
        java.util.List<Double> v = java.util.List.of((double) state.ordinal(), storedJ(), storeMaxJ(), inAvgW,
            drawAt(r) * weatherFactor(), weatherFactor(), designRadius(), minRadius(), maxRadius(), target(),
            state == State.OFF ? 0 : r, goalRadius(), override ? 1.0 : 0.0, signalSet ? 1.0 : 0.0, retreating ? 1.0 : 0.0, carryRadius(),
            failing, locked ? 1.0 : 0.0, asker != null && asker.getUUID().equals(feeder) ? 1.0 : 0.0, fed / 1000);
        return new StasisNet.ConsoleState(worldPosition, v, structureLine());
    }

    /** Set while a signal cable feeds the input side (the console's target buttons yield to it). */
    boolean signalSet;
    /** The console's side (Baughn: input on its left, output on its right); set when the core forms. */
    net.minecraft.core.Direction facing = net.minecraft.core.Direction.SOUTH;
    TowerSignalNode node;

    public int fieldId() {
        return fieldId;
    }

    public net.minecraft.core.Direction facing() {
        return facing;
    }

    /** Twice a second: the EA signal sides (TowerSignalNode). */
    void signals(ServerLevel sl) {
        try {
            if (node == null || node.isDestructing()) node = TowerSignalNode.attach(sl, worldPosition, facing);
            if (node == null) return;
            double share = storedJ() / Math.max(1, storeMaxJ());
            node.outProc.setOutputNormalizedSafe(Math.max(0, Math.min(1, share)));
            boolean fed = node.inputAttached(sl, worldPosition, facing);
            if (fed) {
                double v = node.in.getNormalized();
                targetRadius = minRadius() + (maxRadius() - minRadius()) * v;
            }
            if (fed != signalSet) {
                signalSet = fed;
                setChanged();
            }
        } catch (LinkageError e) {
            // (no EA: no signal sides)
        }
    }

    /** What the tower will draw at its target radius (Baughn: known before it's powered), e.g. "4.3 kW". */
    String drawText() {
        double t = target(), w = drawAt(t) * weatherFactor();
        return w >= 1000 ? String.format("%.1f kW", w / 1000) : String.format("%.0f W", w);
    }

    String structureLine() {
        TowerStructure s = structure;
        return switch (s.status) {
            case NONE -> "No mast yet: stack Stasis Tower Poles on the core's top (each widens the field); poles off the mast "
                + "make booms. Feed power into any face.";
            case WAITING, BUILDING -> "Building the tower...";
            case OK -> "Tower: " + (s.layout == null ? "" : "height " + s.layout.H() + ", " + s.layout.booms().size() + " boom(s)")
                + (s.ladderTop > 0 ? ", ladder to y+" + s.ladderTop : "") + ". Draw: " + drawText() + ".";
            case INVALID -> "Tower layout not allowed: " + s.message;
            case REFUSED -> "Tower refused: " + s.message;
            case FAILED -> "Tower could not be built (see the server log).";
        };
    }

    /** What right-clicking the core says: the field, the store, the structure, and what to do next. */
    public net.minecraft.network.chat.Component statusText() {
        double r = radius(), stored = storedJ(), max = storeMaxJ();
        StringBuilder sb = new StringBuilder("Stasis tower: ");
        sb.append(switch (state) {
            case OFF -> stored >= 0.5 * max ? "starting" : "off, needs half a store to start";
            case RUNNING -> "field up, radius " + (int) r;
            case COLLAPSING -> "COLLAPSING";
        });
        sb.append(String.format(". Store %.0f%% (%.0f of %.0f kJ), draw %.0f W", 100 * stored / Math.max(1, max), stored / 1000,
            max / 1000, drawFor(r * volumeScale()) * weatherFactor()));
        // (the draw shows what a deep adds, unexplained: Baughn wants no messages about deeps)
        sb.append(". ");
        TowerStructure s = structure;
        sb.append(switch (s.status) {
            case NONE -> "No mast yet: stack Stasis Tower Poles on the core's top (each widens the field); poles off the mast "
                + "make booms. Feed power into any face.";
            case WAITING, BUILDING -> "Building the tower...";
            case OK -> "Tower: " + (s.layout == null ? "" : "height " + s.layout.H() + ", " + s.layout.booms().size() + " boom(s)")
                + (s.ladderTop > 0 ? ", ladder to y+" + s.ladderTop : "") + ". Draw: " + drawText() + ".";
            case INVALID -> "Tower layout not allowed: " + s.message;
            case REFUSED -> "Tower refused: " + s.message;
            case FAILED -> "Tower could not be built (see the server log).";
        });
        boolean bad = s.status == TowerStructure.Status.INVALID || s.status == TowerStructure.Status.REFUSED || s.status == TowerStructure.Status.FAILED;
        return net.minecraft.network.chat.Component.literal(sb.toString()).withStyle(bad ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.WHITE);
    }

    /** Is this the tower (the bottom centre of a formed core), not just a core block? */
    public boolean isMaster() {
        var s = getBlockState();
        return s.is(StasisRegistry.TOWER.get()) && s.getValue(TowerBlock.CELL) == TowerBlock.MASTER;
    }

    /** The core fell apart: no field, the poles back to survey poles, no tower (the store is kept). */
    void unform() {
        if (!(level instanceof ServerLevel sl)) return;
        if (state != State.OFF) stop(sl);
        structure.release(level);
        structure.reset();
        setChanged();
        sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    /** Fill the store (the spawn cottage's tower starts running at once). */
    public void fillStore() {
        resize();
        store.set(storeCapacity());
        sized = true;
        setChanged();
    }

    /** For tests and the tower's own chunk loading. */
    public SimpleEnergyHandler energy() {
        return store;
    }
}
