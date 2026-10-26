package org.erisia.stasis;

import mods.eln.misc.Coordonate;
import mods.eln.misc.Direction;
import mods.eln.misc.LRDU;
import mods.eln.node.NodeBase;
import mods.eln.node.NodeManager;
import mods.eln.Eln;
import mods.eln.sim.ElectricalLoad;
import mods.eln.sim.ThermalLoad;
import mods.eln.sim.nbt.NbtElectricalGateInput;
import mods.eln.sim.nbt.NbtElectricalGateOutput;
import mods.eln.sim.nbt.NbtElectricalGateOutputProcess;
import mods.eln.sim.mna.component.Component;
import mods.eln.sim.mna.state.State;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The tower core's EA signal sides (CONSOLE-PLAN.md D; Baughn: no separate block, the core itself; facing the
 * console, the input is on your left and the output on your right). An EA node of our own at the core's master:
 * signal cables beside those faces connect to it like to any EA machine. Input 0-50 V sets the target radius
 * across the tower's range; the output gives the charge as 0-50 V. Not saved: the tower entity makes it. A bare
 * NodeBase (EA's SimpleNode, which it used to extend, is gone): its two gate loads and the output's driver go into
 * the simulation while it is connected.
 */
public final class TowerSignalNode extends NodeBase {
    final NbtElectricalGateInput in = new NbtElectricalGateInput("in");
    final NbtElectricalGateOutput out = new NbtElectricalGateOutput("out");
    final NbtElectricalGateOutputProcess outProc = new NbtElectricalGateOutputProcess("outP", out);
    Direction inFace, outFace;

    final List<State> electricalLoadList = List.of(in, out);
    final List<Component> electricalComponentList = List.of(outProc);

    @Override
    public void initializeFromThat(Direction front, LivingEntity entityLiving, ItemStack itemStack) {
        connect();
    }

    @Override
    public void initializeFromNBT() {
        connect();   // (never saved, so never loaded)
    }

    @Override
    public void connectJob() {
        super.connectJob();
        Eln.simulator.addAllElectricalComponent(electricalComponentList);
        for (State load : electricalLoadList) Eln.simulator.addElectricalLoad(load);
    }

    @Override
    public void disconnectJob() {
        super.disconnectJob();
        Eln.simulator.removeAllElectricalComponent(electricalComponentList);
        for (State load : electricalLoadList) Eln.simulator.removeElectricalLoad(load);
    }

    @Override
    public int getSideConnectionMask(Direction d, LRDU l) {
        return d == inFace || d == outFace ? maskElectricalGate : 0;
    }

    @Override
    public ElectricalLoad getElectricalLoad(Direction d, LRDU l) {
        return d == inFace ? in : d == outFace ? out : null;
    }

    @Override
    public ThermalLoad getThermalLoad(Direction d, LRDU l) {
        return null;
    }

    @Override
    public String getNodeUuid() {
        return "StasisTowerSignal";
    }

    @Override
    public boolean mustBeSaved() {
        return false;
    }

    @Override
    public void setNeedPublish(boolean b) {
        // (no EA client entity at the core: publishing would only log "No node found")
    }

    /** The tower's node at master (made, or the one already there), facing: the console's side. */
    static TowerSignalNode attach(ServerLevel level, BlockPos master, net.minecraft.core.Direction facing) {
        if (NodeManager.instance == null) return null;
        Coordonate c = new Coordonate(master, level);
        NodeBase old = NodeManager.instance.getNodeFromCoordonate(c);
        Direction inF = Direction.fromEnumFacing(facing.getClockWise()), outF = Direction.fromEnumFacing(facing.getCounterClockWise());
        if (old instanceof TowerSignalNode t && t.inFace == inF && t.outFace == outF) return t;
        if (old != null) old.onBreakBlock();
        TowerSignalNode n = new TowerSignalNode();
        n.inFace = inF;
        n.outFace = outF;
        n.onBlockPlacedBy(c, Direction.fromEnumFacing(facing), null, ItemStack.EMPTY);
        return n;
    }

    /**
     * Does something beside the input face connect to it as a signal (so the input means something; an undriven net reads
     * 0 V)? By EA's own rule for neighbours (NodeBase.connect: the masks must match). Any EA node there used to count, so
     * an energy exporter powering the tower from that side set the radius from a signal of 0 V (a player's report).
     */
    boolean inputAttached(ServerLevel level, BlockPos master, net.minecraft.core.Direction facing) {
        if (NodeManager.instance == null) return false;
        NodeBase other = NodeManager.instance.getNodeFromCoordonate(new Coordonate(master.relative(facing.getClockWise()), level));
        if (other == null) return false;
        for (LRDU l : LRDU.values())
            if (compareConnectionMask(getSideConnectionMask(inFace, l), other.getSideConnectionMask(inFace.getInverse(), l.inverseIfLR())))
                return true;
        return false;
    }
}
