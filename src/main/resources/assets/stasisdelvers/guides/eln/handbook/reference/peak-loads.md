---
navigation:
  title: Peak Loads and the Electrical Anvil
  parent: reference.md
  position: 5
  icon: eln:electrical_anvil
item_ids:
  - eln:electrical_anvil
  - eln:capacitor_bank
  - eln:capacitor_cell
  - eln:power_capacitor
  - eln:power_inductor
  - eln:dielectric
---

# Peak Loads, Capacitors and the Electrical Anvil

*Reference notes for [Peak Loads: The Desk That Eats a Plant](../diary/12-peak-loads.md).*

## The electrical anvil

A workshop desk that repairs tools for **no experience**.

* **Repair only.** It mends a damaged tool with its usual repair material, or with a plain (unenchanted) copy of the same tool. The material slot refuses enchanted books and enchanted items. It won't mend items whose bar shows charge (EA tools, batteries, suits).
* **A quarter per unit**, as on a vanilla anvil. The desk stores fractions of a unit, so topping up a nearly whole tool wastes nothing. The rest stays in the desk as **credit** for that material, and the next tool uses it. Breaking the desk loses the credit.
* **No ratchet.** Each repair lowers the tool's anvil penalty a notch instead of raising it. It never drops below what a fresh tool with that many enchantments would have.
* **Engraving:** type a name and press Enter for a short 2 kJ pulse. A blank name removes it.
* **Power:** one **400 V** connection, on the plate low on the back. While it welds it draws **5 kW**. A unit of material takes **40 kJ**, in **two bursts of 20 kJ** (4 s each on a stiff line).
* **Between bursts** it rests at least 3 s, and until it may start again, so a bank can refill.
* **Start and stop:** it starts a burst when the line reaches **380 V**, or at **372 V** or more once the line has stopped rising for 2 s. If the line falls below **360 V** it pauses with *Supply too weak*, keeps its progress, and waits at least 1 s.
* **Too much voltage** burns it, as with any 400 V machine.

## Why a peak load is hard

Energy is power × time. A 1 kW plant makes 40 kJ in 40 s, which is plenty for one repair a minute. But the desk asks for 20 kJ in 4 s. A generator pushed past its rating sags, so the line falls below 360 V the moment the arc strikes. Size the generator for the average, and add a **store** for the peak.

| Store | Good for | Bad for |
| :--- | :--- | :--- |
| Lead-acid battery | bulk energy, keeps for days | fast pulls (they wear); at most 230 V |
| **Capacitor Bank** | one heavy burst at 400 V | keeping: it leaks |
| Power Capacitor | small bursts, any current | holds little; it leaks |
| Power Inductor | smoothing changes in current | storing energy at all |

## The Capacitor Bank

The workshop answer: one bank carries one 20 kJ burst.

* **1.44 F**, rated **500 V**; it pops above 650 V. At 400 V it holds 115 kJ, of which **21.9 kJ** lies between 400 V and 360 V, where the desk can use it.
* **Polar.** **+** is low on the left flank of its front (the HV-coloured band), **−** on the right (black band). Put + on the 400 V line and − to a **Ground Cable**.
* **Inrush:** an empty bank on a stiff 400 V line pulls a huge current and **burns the cable**. It takes and gives any current; nothing inside limits it. See [charging the bank](peak-loads.md#charging-the-bank).
* **Leaks** through about 1.25 kΩ: about 128 W at 400 V, and only 2% left after an hour. It's for bursts, not for keeping a night's power. Keep the plant connected.
* Jade shows its voltage, energy and capacitance.
* **Recipe:** six **Capacitor Cells** (each: 6 Dielectrics, 2 copper plates, a redstone block) over 2 iron plates and a High Voltage Cable. The compressor makes **4 Dielectrics** from a sand.

## Charging the bank

Put a **Large Rheostat** between the plant and the bank. Start it high, and turn it down as the bank fills.

* **From empty: about 82 Ω.** It peaks at about 4.9 A (under 2 kW), inside the rheostat's 4 kW cooling. The bank is at about 250 V after 2 minutes and 360 V after 6.
* **Past about 300 V: turn it down to about 25 Ω** (4 A at most from there). Left at 82 Ω, the bank's own 1.25 kΩ leak parks it at about 375 V, barely over the desk's 372 V start. Then it welds a one-second bite every two minutes. At 25 Ω it settles near 392 V and welds a bite every 20 s or so, even on a weak plant.
* **Setting it:** Coal Dust sets the rheostat's top value and its control signal scales it linearly. **23 Coal Dust** gives about 82 Ω at a full 5 V signal and about 25 Ω at 1.5 V. Turn it down by lowering the signal, not by taking dust out.
* **Wire the signal first.** With no signal the rheostat sits at 1% of its value (about 0.8 Ω with 23 Coal Dust). That's a stiff line, and it burns the cable on an empty bank.
* **Automate it:** a voltage probe on the bank drives the rheostat's control signal, high resistance when the bank is low and low when it's high.
* The rheostat runs hot while it fills an empty bank (it reached 281 °C in testing and survived). Charging any capacitor through a resistor heats the resistor by about as much energy as the capacitor ends up holding: some 115 kJ for an empty bank.
* **Never 25 Ω from empty:** that peaks at 16 A. **Never 250 Ω to run on:** the leak then holds the bank at about 333 V, below the desk's 360 V, so it never welds.
* An inductor in series with the rheostat smooths the plant's draw while the bank refills after a weld.

## The Power Capacitor

The small, older wall capacitor.

* **Redstone** (1-13, left slot) sets the capacity. Each **Dielectric** adds 48 V of rating, but the capacity is divided by the square of their count. So the energy at full rating is the same whatever the rating: at most **11.5 kJ** (13 redstone).
* For a 400 V line use **9 Dielectrics**: rated 432 V, it pops only above 562 V (1.3 × its rating). With fewer it holds a little more at 400 V, but pops sooner. With more it holds less.
* The desk can use only `1 − 0.9² = 19%` of it: about **1.9 kJ**, so a burst needs **eleven** of them.
* Its terminals are the **two ends of the can**: one end to the line, the other to a Ground Cable.
* It leaks like the bank (about 87% of its voltage gone in an hour).

## Sizing

* **One Capacitor Bank per desk** carries a burst. How fast it refills depends on the plant and the rheostat (see [charging the bank](peak-loads.md#charging-the-bank)).
* **A weak plant** (a few hundred watts) still works: the desk welds in short bites as the line recovers. It takes minutes per unit, but it gets there. The plant must give more than the bank's leak.
* **No bank:** a plant has to hold 360 V or more under the full 5 kW.

Energy stored: `E = ½ × C × U²`. The desk's draw falls as the voltage sags (`P = U² ÷ 32 Ω`), and cables lose some on the way, so expect the pause to show a little above 360 V.

## The Power Inductor

* **Copper Cable** (1-19) sets the inductance (0.1-100 H). It needs a **Ferromagnetic Core**; without one it is an open circuit. It can't burn.
* An inductor stores almost nothing; it resists *changes* in current. In series with the charging rheostat, between the plant and the bank, it lets the bank take the shock of each weld while the plant sees a steady draw as the bank refills.
* It can't tame an empty bank on its own: even the largest one (100 H) lets the current swing up to some 48 A, and the voltage rings up past the bank's 650 V limit. Use resistance as well.
* An inductor alone, with no capacitor, makes a pulse load *worse*: it holds the current back just when the desk wants more.

[Reference index](../reference.md) · [Back to the contents](../index.md)
