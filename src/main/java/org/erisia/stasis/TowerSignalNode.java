package org.erisia.stasis;

import mods.eln.misc.Coordonate;
import mods.eln.misc.Direction;
import mods.eln.misc.LRDU;
import mods.eln.node.NodeBase;
import mods.eln.node.NodeManager;
import mods.eln.node.simple.SimpleNode;
import mods.eln.sim.ElectricalLoad;
import mods.eln.sim.ThermalLoad;
import mods.eln.sim.nbt.NbtElectricalGateInput;
import mods.eln.sim.nbt.NbtElectricalGateOutput;
import mods.eln.sim.nbt.NbtElectricalGateOutputProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * The tower core's EA signal sides (CONSOLE-PLAN.md D; Baughn: no separate block, the core itself; facing the
 * console, the input is on your left and the output on your right). An EA node of our own at the core's master:
 * signal cables beside those faces connect to it like to any EA machine. Input 0-50 V sets the target radius
 * across the tower's range; the output gives the charge as 0-50 V. Not saved: the tower entity makes it.
 */
public final class TowerSignalNode extends SimpleNode {
    final NbtElectricalGateInput in = new NbtElectricalGateInput("in");
    final NbtElectricalGateOutput out = new NbtElectricalGateOutput("out");
    final NbtElectricalGateOutputProcess outProc = new NbtElectricalGateOutputProcess("outP", out);
    Direction inFace, outFace;

    @Override
    public void initialize() {
        electricalLoadList.add(in);
        electricalLoadList.add(out);
        electricalComponentList.add(outProc);
        connect();
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

    /** Is something EA beside the input face (so the input means something; an undriven net reads 0 V)? */
    boolean inputAttached(ServerLevel level, BlockPos master, net.minecraft.core.Direction facing) {
        return NodeManager.instance != null
            && NodeManager.instance.getNodeFromCoordonate(new Coordonate(master.relative(facing.getClockWise()), level)) != null;
    }
}
