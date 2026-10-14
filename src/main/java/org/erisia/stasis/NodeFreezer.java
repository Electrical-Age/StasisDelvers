package org.erisia.stasis;

import mods.eln.node.NodeBase;
import mods.eln.node.NodeManager;
import mods.eln.node.six.SixNode;
import mods.eln.node.six.SixNodeElement;
import mods.eln.node.transparent.TransparentNode;
import mods.eln.simplenode.energyconverter.EnergyConverterElnToOtherNode;
import mods.eln.sixnode.electricalcable.ElectricalCableElement;
import mods.eln.transparentnode.battery.BatteryElement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Electrical Age simulates its whole network every tick, wherever its blocks are, even in unloaded chunks. In stopped
 * land that must stop too: every half second, EA nodes whose block is stopped are taken out of the simulation (as
 * EA does when a node goes away: disconnect) and put back when time runs there again (connect). A cable crossing into
 * stopped land is cut there, so a field needs its power from inside it.
 * <p>
 * Within 6 blocks of a tower core, cables, batteries and exporters keep running even with the field down (Baughn: no machinery,
 * not even a heat turbine), so a battery can start the tower.
 */
public final class NodeFreezer {
    private NodeFreezer() {
    }

    private static final Set<NodeBase> FROZEN = Collections.newSetFromMap(new IdentityHashMap<>());

    static void tick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 10 != 0 || NodeManager.instance == null) return;
        pass();
    }

    /** One pass over all EA nodes (public for tests). */
    public static void pass() {
        ArrayList<NodeBase> nodes = new ArrayList<>(NodeManager.instance.getNodes());
        Set<NodeBase> present = Collections.newSetFromMap(new IdentityHashMap<>());
        for (NodeBase n : nodes) {
            present.add(n);
            if (n.isDestructing() || !(n.coordonate.world() instanceof ServerLevel)) continue;
            // work from EA's own state (isAdded), not a list of our own: EA reconnects nodes from many places
            boolean runs = runs(n);
            if (runs) FROZEN.remove(n); else FROZEN.add(n);
            if (!runs && n.isAdded()) {
                n.disconnect();
                n.visualConnect();
            } else if (runs && !n.isAdded()) {
                n.connect();
            } else if (!runs) {
                // frozen cables still show their connections (to frozen neighbours too); republished only on change
                n.visualConnect();
            }
        }
        FROZEN.retainAll(present);   // destroyed nodes
    }

    /** Does this node belong in the simulation now? (Also EA's veto: NodeBase.simulationVeto, set in install.) */
    static boolean runs(NodeBase n) {
        if (!(n.coordonate.world() instanceof ServerLevel level)) return true;
        BlockPos pos = n.coordonate.pos();
        return Stasis.isRunning(level, pos) || (Stasis.nearTower(level, pos) && infrastructure(n));
    }

    /** EA asks before it puts a node into the simulation, so a frozen node never slips back in (Baughn). */
    static void install() {
        NodeBase.simulationVeto = n -> !runs(n);
    }

    /** Is the node only cables, a battery or an EA-to-FE exporter? */
    static boolean infrastructure(NodeBase n) {
        if (n instanceof EnergyConverterElnToOtherNode) return true;
        if (n instanceof TransparentNode t) return t.element instanceof BatteryElement;
        if (n instanceof SixNode s) {
            boolean any = false;
            for (SixNodeElement el : s.sideElementList) {
                if (el == null) continue;
                // wiring: cables, and the passive line parts in them (switches, relays, fuses, diodes, ground)
                if (!(el instanceof ElectricalCableElement || el instanceof mods.eln.sixnode.electricalswitch.ElectricalSwitchElement
                    || el instanceof mods.eln.sixnode.electricalrelay.ElectricalRelayElement || el instanceof mods.eln.sixnode.ElectricalFuseHolderElement
                    || el instanceof mods.eln.sixnode.diode.DiodeElement || el instanceof mods.eln.sixnode.groundcable.GroundCableElement)) return false;
                any = true;
            }
            return any;
        }
        return false;
    }

    /** Is this node held out of the simulation by stasis? */
    public static boolean isFrozen(NodeBase n) {
        return FROZEN.contains(n);
    }
}
