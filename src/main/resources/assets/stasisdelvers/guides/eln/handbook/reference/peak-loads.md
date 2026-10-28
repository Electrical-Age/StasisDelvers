---
navigation:
  title: Peak Loads and the Electrical Anvil
  parent: reference.md
  position: 5
  icon: eln:electrical_anvil
item_ids:
  - eln:electrical_anvil
  - eln:power_capacitor
  - eln:power_inductor
  - eln:dielectric
---

# Peak Loads, Capacitors and the Electrical Anvil

*Reference notes for [Peak Loads: The Desk That Eats a Plant](../diary/12-peak-loads.md).*

## The electrical anvil

A workshop desk that repairs tools for **no experience**.

* **Repair only.** It mends a damaged tool with its usual repair material, or with a plain (unenchanted) copy of the same tool. It won't apply enchanted books or combine enchanted items. It won't mend items whose bar shows charge (EA tools, batteries, suits).
* **A quarter per unit**, as on a vanilla anvil. The desk stores fractions of a unit, so topping up a nearly whole tool wastes nothing. The rest stays in the desk as **credit** for that material, and the next tool uses it. Breaking the desk loses the credit.
* **No ratchet.** Each repair lowers the tool's anvil penalty a notch instead of raising it. It never drops below what a fresh tool with that many enchantments would have.
* **Engraving:** type a name and press Enter for a short 2 kJ pulse. A blank name removes it.
* **Power:** one **800 V** connection, on the plate low on the back. While it welds it draws **5 kW**: **40 kJ** per unit of material, 8 s on a stiff line.
* **Sag rule:** it starts a weld only when the line is at **784 V** (98%). If the line falls below **720 V** (90%) it pauses with *Supply too weak*, keeps its progress, and waits at least 1 s before trying again.
* **Too much voltage** burns it, as with any 800 V machine.

## Why a peak load is hard

Energy is power × time. A 1 kW plant makes 40 kJ in 40 s, which is plenty for one repair a minute. But the desk asks for that 40 kJ in 8 s. A generator pushed past its rating sags, so the line falls below 720 V the moment the arc strikes. Size the generator for the average, and add a **store** for the peak.

| Store | Good for | Bad for |
| :--- | :--- | :--- |
| Battery | steady loads, for hours | fast pulls (they wear); at most 200 V |
| **Power Capacitor** | seconds, at any current | holds little energy |
| Power Inductor | smoothing changes in current | storing energy for later |

## The Power Capacitor

* **Redstone** (1-13) sets the capacity. Each **Dielectric** adds 50 V of rating, but the capacity is divided by the square of their count. So the energy at full rating is the same whatever the rating: at most **12.5 kJ** per capacitor (13 redstone).
* For an 800 V line use exactly **16 Dielectrics**. With fewer it pops, since it pops above 1.3 × its rating. With more it holds less at 800 V.
* Energy stored: `E = ½ × C × U²`. One leg goes on the line, the other to a **Ground Cable**.
* It leaks very slowly, so a charged bank keeps for hours.

## Sizing a bank

The desk takes only the part between 800 V and 720 V: `1 − 0.9² = 19%` of a full capacitor, about **2.4 kJ** per capacitor (13 redstone, 16 Dielectrics).

To mend a whole unit in one go, the bank must cover what the plant can't, about `(5000 W − plant watts) × 8 s`:

| Plant on the 800 V line | Shortfall per unit | Full capacitors for one go |
| :--- | :--- | :--- |
| nothing (bank only) | 40 kJ | 17 |
| 1 kW | 32 kJ | 14 |
| 2.5 kW | 20 kJ | 9 |
| 4 kW | 8 kJ | 4 |

A smaller bank works too, in **bursts**: weld, pause, recharge, weld. The desk keeps its progress. A unit then takes about `40 kJ ÷ plant watts` (40 s on 1 kW), plus the 1 s waits.

These numbers are rough. The desk draws less as the voltage sags (`P = U² ÷ 128 Ω`), and the cables lose some on the way.

## The Power Inductor

* **Copper Cable** (1-19) sets the inductance (0.1-100 H). It needs a **Ferromagnetic Core**; without one it is an open circuit. It can't burn.
* An inductor stores almost nothing; it resists *changes* in current. In series between the generator and the bank, it lets the bank take the shock of each weld while the generator sees a steady draw as the bank refills.
* An inductor alone, with no capacitor, makes a pulse load *worse*: it holds the current back just when the desk wants more.

[Reference index](../reference.md) · [Back to the contents](../index.md)
