package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
        return 1 + w + 2 * staticWeather;
    }

    static float staticOverlap(ServerLevel level, Field f) {
        int in = 0, n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double k = l == 0 ? 0 : 0.7 * f.radius() / l;
                    n++;
                    if (StaticDeeps.zone(level, (int) Math.floor(f.x() + dx * k), (int) Math.floor(f.y() + dy * k), (int) Math.floor(f.z() + dz * k)) > 0) in++;
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
            if (!sized && level != null && !level.isClientSide()) {
                sized = true;
                segments = countSegments();
                setCapacity((int) (storeFor(designRadius()) * FE_PER_J));
            }
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
        if (level != null && !level.isClientSide()) {
            segments = countSegments();
            store.setCapacity((int) (storeFor(designRadius()) * FE_PER_J));
        }
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

    static final double MAX_EXPAND = 1.25, SAFETY_SHARE = 0.6, STEP = 2;

    /** The console's (or the signal port's) target; NaN: the preferred range. */
    double targetRadius = Double.NaN;
    /** OVERRIDE: no automatic shrinking below SAFETY_SHARE charge (it runs at full size until it collapses). */
    boolean override;
    /** The radius the field has now; 0 while off. */
    double effRadius;
    /** The stepped goal (hysteresis: it moves only by whole steps). */
    double stepGoal;

    public double target() {
        return Math.max(minRadius(), Math.min(maxRadius(), Double.isNaN(targetRadius) ? designRadius() : targetRadius));
    }

    public boolean override() {
        return override;
    }

    /** Console: set the target radius (NaN: the preferred range) and OVERRIDE. */
    public void setControls(double target, boolean override) {
        this.targetRadius = Double.isNaN(target) ? Double.NaN : Math.max(minRadius(), Math.min(maxRadius(), target));
        this.override = override;
        setChanged();
    }

    /**
     * Where the field is heading: the target, or (safety on, below 60% charge) shrunk toward the minimum in proportion
     * to charge, in whole steps (Gemini: continuous shrinking thrashes chunk re-meshing).
     */
    public double goalRadius() {
        double t = target();
        double share = storedJ() / Math.max(1, storeMaxJ());
        if (override || share >= SAFETY_SHARE) return t;
        double min = minRadius();
        double g = min + (t - min) * Math.max(0, share) / SAFETY_SHARE;
        return Math.max(min, Math.floor(g / STEP) * STEP);
    }

    /** Above the preferred range the draw climbs steeply: (R / R_design)^6 (x3.8 at +25%). */
    public double drawPenalty(double r) {
        double d = designRadius();
        return r > d ? Math.pow(r / d, 6) : 1;
    }

    public static double radiusFor(int segments) {
        return 8.0 * (segments + 2);   // Baughn 2026-10-14: twice the old 4 (segments + 2)
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
        if (structure.tick(sl, worldPosition, ticks)) {
            setChanged();
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
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
                effRadius = stepGoal = FieldData.get(sl).get(fieldId).map(Field::radius).orElse(designRadius());
            double g = goalRadius();
            if (stepGoal == 0 || Math.abs(g - stepGoal) >= STEP || g == target() || g == minRadius()) stepGoal = g;
            double next = effRadius < stepGoal ? Math.min(stepGoal, effRadius + 1) : Math.max(stepGoal, effRadius - 1);
            if (next != effRadius) {
                effRadius = next;
                FieldData.get(sl).update(sl, fieldId, f -> f.withRadius(effRadius));
                Tickets.update(sl, this);
            }
        }
        double r = radius();
        store.setCapacity((int) (storeFor(designRadius()) * FE_PER_J));
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
        } else if (state != State.RUNNING) {
            weather = staticWeather = stormMist = stormRift = 0;
            storming = false;
        }
        double perTick = drawFor(r) * drawPenalty(r) * testDrawFactor * weatherFactor() / 20.0;
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
                // the shield shows the charge only while the store drains (Baughn), eased over about 2 s
                double target = draining ? 1 - storedJ() / storeMaxJ() : 0;
                failing += (target - failing) / 40.0;
                if (level.getGameTime() % 5 == 0) FieldData.get(sl).update(sl, fieldId, f -> f.withFailing(failing));
                if (store.amount() <= 0) {
                    state = State.COLLAPSING;
                    collapseTicks = 0;
                    collapseProgress = 0;
                    collapseSpeed = 1.0 / (10 + 15 * sl.getRandom().nextDouble());   // 10-25 s
                    boltBurst = 0;
                    int seed = sl.getRandom().nextInt();
                    FieldData.get(sl).update(sl, fieldId, f -> f.withSeed(seed));
                    StasisDelvers.LOG.info("Stasis tower at {} ran out of power: field {} collapses", worldPosition.toShortString(), fieldId);
                }
            }
            case COLLAPSING -> {
                // (Baughn: too predictable) each collapse runs at its own pace, 10-25 s, and time stutters through
                // it: the front pauses, lurches, now and then slips back a little, with lightning in bursts
                var rnd = sl.getRandom();
                double step = collapseSpeed * (rnd.nextDouble() < 0.12 ? 0 : rnd.nextDouble() < 0.08 ? -1.5 : rnd.nextDouble() < 0.1 ? 3 : 1);
                collapseProgress = Math.max(0, Math.min(1, collapseProgress + step / 20.0));
                collapseTicks++;
                if (rnd.nextInt(boltBurst > 0 ? 3 : 25) == 0) bolt(sl);
                if (boltBurst > 0) boltBurst--;
                else if (rnd.nextInt(60) == 0) boltBurst = 10 + rnd.nextInt(20);
                double c = collapseTicks >= 25 * 20 ? 1.0 : collapseProgress;   // (never longer than 25 s)
                if (collapseTicks % 5 == 0 || c >= 1) FieldData.get(sl).update(sl, fieldId, f -> f.withCollapse(c).withFailing(1));
                if (c >= 1) stop(sl);
            }
        }
        setChanged();
    }

    /**
     * A lightning bolt (visual only, Baughn: it hits the tower, not players): from a random point of the upper shield
     * to the top of the mast, with a crackle.
     */
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

    public void testBolt() {
        if (level instanceof ServerLevel sl) bolt(sl);
    }

    void bolt(ServerLevel sl) {
        var rnd = sl.getRandom();
        double r = radius();
        double ux = rnd.nextGaussian(), uy = Math.abs(rnd.nextGaussian()) + 0.3, uz = rnd.nextGaussian();
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        double cx = worldPosition.getX() + 0.5, cy = worldPosition.getY() + 0.5, cz = worldPosition.getZ() + 0.5;
        double top = worldPosition.getY() + TowerBlock.HEIGHT + segments + 0.5;
        StasisNet.sendBolt(sl, new StasisNet.Bolt(cx + ux / ul * r * 0.97, cy + uy / ul * r * 0.97, cz + uz / ul * r * 0.97, cx, top, cz, rnd.nextInt()));
        sl.playSound(null, worldPosition.getX(), top, worldPosition.getZ(), net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_IMPACT,
            net.minecraft.sounds.SoundSource.WEATHER, 1.5F, 0.8F + rnd.nextFloat() * 0.4F);
    }

    /** A bolt from the mast top outwards, through the shield to the land beyond it (an overcharged tower). */
    void boltOut(ServerLevel sl) {
        var rnd = sl.getRandom();
        double r = radius() * (1.05 + 0.3 * rnd.nextDouble());
        double a = rnd.nextDouble() * 2 * Math.PI;
        double cx = worldPosition.getX() + 0.5, cz = worldPosition.getZ() + 0.5;
        double top = worldPosition.getY() + TowerBlock.HEIGHT + segments + 0.5;
        int tx = (int) Math.floor(cx + Math.cos(a) * r), tz = (int) Math.floor(cz + Math.sin(a) * r);
        double ty = sl.hasChunkAt(new BlockPos(tx, 0, tz))
            ? sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, tx, tz) : worldPosition.getY();
        StasisNet.sendBolt(sl, new StasisNet.Bolt(cx, top, cz, tx + 0.5, ty, tz + 0.5, rnd.nextInt()));
        sl.playSound(null, tx + 0.5, ty, tz + 0.5, net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER,
            net.minecraft.sounds.SoundSource.WEATHER, 1.2F, 1.2F + rnd.nextFloat() * 0.3F);
    }

    void start(ServerLevel sl) {
        effRadius = 0;
        effRadius = goalRadius();
        stepGoal = effRadius;
        Field f = FieldData.get(sl).add(sl, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, radius(), false,
            StasisConfig.towerFieldRate);
        fieldId = f.id();
        // start-up: visual only (Baughn); clients animate from formedAt, about 3 s, with a hum and a lock-on sound
        long now = sl.getGameTime();
        FieldData.get(sl).update(sl, fieldId, x -> x.withFormedAt(now));
        startedAt = now;
        sl.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.BLOCKS, 2.0F, 0.6F);
        state = State.RUNNING;
        failing = 0;
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
        stepGoal = 0;
        collapseTicks = 0;
        failing = 0;
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
        if (!Double.isNaN(targetRadius)) out.putDouble("target", targetRadius);
        out.putBoolean("override", override);
        out.putDouble("radius", effRadius);
        out.putString("facing", facing.getSerializedName());
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
        targetRadius = in.getDoubleOr("target", Double.NaN);
        override = in.getBooleanOr("override", false);
        effRadius = in.getDoubleOr("radius", 0);
        var fd = net.minecraft.core.Direction.byName(in.getStringOr("facing", "south"));
        facing = fd != null && fd.getAxis().isHorizontal() ? fd : net.minecraft.core.Direction.SOUTH;
        stepGoal = effRadius;
        structure.load(in);
        if (level != null && level.isClientSide()) TowerClientHooks.updated(this);
    }

    /** The collapse's own pace and progress (0..1), and a lightning burst in progress (ticks). */
    double collapseProgress, collapseSpeed = 1.0 / 15;
    int boltBurst;

    /** Power coming in, W (an average over a few seconds), for the console. */
    double inAvgW;

    /** The console's readout (StasisNet.ConsoleState). */
    public StasisNet.ConsoleState consoleState() {
        double r = radius();
        java.util.List<Double> v = java.util.List.of((double) state.ordinal(), storedJ(), storeMaxJ(), inAvgW,
            drawFor(r) * drawPenalty(r) * weatherFactor(), weatherFactor(), designRadius(), minRadius(), maxRadius(), target(),
            state == State.OFF ? 0 : r, goalRadius(), override ? 1.0 : 0.0, signalSet ? 1.0 : 0.0);
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

    String structureLine() {
        TowerStructure s = structure;
        return switch (s.status) {
            case NONE -> "No mast yet: stack Stasis Tower Poles on the core's top centre (each one widens the field); poles off "
                + "the mast's side make booms. Feed power (FE, e.g. EA's exporter) into any face.";
            case WAITING, BUILDING -> "Building the tower...";
            case OK -> "Tower: " + (s.layout == null ? "" : "height " + s.layout.H() + ", " + s.layout.booms().size() + " boom(s)")
                + (s.ladderTop > 0 ? ", ladder to y+" + s.ladderTop : "") + ".";
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
            case OFF -> stored >= 0.5 * max ? "starting" : "off (needs half a store to start)";
            case RUNNING -> "field up, radius " + (int) r;
            case COLLAPSING -> "COLLAPSING";
        });
        sb.append(String.format(". Store %.0f%% (%.0f of %.0f kJ), draw %.0f W", 100 * stored / Math.max(1, max), stored / 1000,
            max / 1000, drawFor(r) * weatherFactor()));
        // (the draw shows what a deep adds, unexplained: Baughn wants no messages about deeps)
        sb.append(". ");
        TowerStructure s = structure;
        sb.append(switch (s.status) {
            case NONE -> "No mast yet: stack Stasis Tower Poles on the core's top centre (each one widens the field); poles off "
                + "the mast's side make booms. Feed power (FE, e.g. EA's exporter) into any face.";
            case WAITING, BUILDING -> "Building the tower...";
            case OK -> "Tower: " + (s.layout == null ? "" : "height " + s.layout.H() + ", " + s.layout.booms().size() + " boom(s)")
                + (s.ladderTop > 0 ? ", ladder to y+" + s.ladderTop : "") + ".";
            case INVALID -> "Tower layout not allowed: " + s.message;
            case REFUSED -> "Tower refused: " + s.message;
            case FAILED -> "Tower could not be built (see the server log).";
        });
        return net.minecraft.network.chat.Component.literal(sb.toString());
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
        segments = countSegments();
        store.setCapacity((int) (storeFor(radiusFor(segments)) * FE_PER_J));
        store.set((int) (storeFor(radiusFor(segments)) * FE_PER_J));
        sized = true;
        setChanged();
    }

    /** For tests and the tower's own chunk loading. */
    public SimpleEnergyHandler energy() {
        return store;
    }
}
