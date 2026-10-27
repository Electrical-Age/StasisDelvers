---
navigation:
  title: The Workbench Ledger
  parent: diary.md
  position: 0
  icon: eln:thermometer
---

# The Workbench Ledger

*Found bolted to the drafting table in Substation 4, High Moor. Bound in vulcanised rubber sheeting over an old switching log.*

If you are reading this, Kid, one of two things has happened. Either the Lance party actually found whatever you all went looking for on the other side of the Bypass and somebody had the bad manners to come back for my tools, or you are a total stranger who pried my door open with a crowbar after I finally walked into a drifting cloud without looking up.

Either way: wipe your boots, don’t touch the knife switch on the north wall—it’s live at two hundred volts and the breaker is sitting on my coasters—and welcome to what’s left of the Regional Grid.

My name is Arthur Vance. For thirty-one years I kept the municipal three-phase running between the quarry towns and the valley. When the Uppsala Alignment started issuing stability compasses and the air down by the rail depot turned the colour of a bruised plum, the Ministry drafted me to wire up the Safeguard Towers around Exclusion Zone STRHLD.

Then the evacuation order came down. Not to the coast—the coast had already gone still three weeks prior, waves standing up like green glass you could walk out on if you had a thirty-minute suit and no sense—but *down*. Deep into the old stonework beneath Exclusion Zone STRHLD, where the surveyors had uncovered the ancient portal frame at the Bypass site.

You wanted me to come. You stood there by the flatbed in your oversized Delver suit, helmet tucked under your arm, telling me that the frame was lit, the portal was open, and if your expedition just went through to the End and killed whatever was holding the knot on the other side, time would snap back like a rubber band. Maybe you were right. Or maybe you’re still over there in the dark, hunting a dragon with a pickaxe and a pocketful of beds. I’m fifty-eight years old, my knees ache when the barometer drops, and I have never trusted a problem whose proposed solution is *climb into an ancient stone ring and hit the sky with a sword*. I told you somebody had to stay behind on the surface and keep the heaters on in case you came back cold.

You left your Ministry pamphlet on my lathe, though. I’ve glued it into the inside cover of this ledger so the damp doesn’t get it. Partly as a keepsake. Mostly because it’s the finest piece of bureaucratic comedy written since the tax code:

> *1. Follow your compass. The Uppsala Alignment has yet to observe stasis in areas above 80% stability.*
>
> *2. Do not be afraid of the rift next door! Temporal stability can vary wildly, meter by meter, and living next to a temporal sinkhole is not inherently dangerous.*

"Not inherently dangerous." I would love to take the junior minister who drafted Page 3 by the collar and walk him down to the sinkhole behind the sawmill. There is an Eye at the bottom of that pit that looks like a camera aperture made of wet anthracite. A crow flew into it on Tuesday. It didn’t fall. It’s still there, four feet above the gravel, wings spread, solid as cast iron. Touch the Eye and no suit on earth will save you; you’re a garden ornament until the sun burns out.

And Page 6: *"If your child wanders into a Deep and is frozen, then you can extract them with a leash."* A leash! Like a prized heifer! Never mind that if the Dread gets thick enough around the edges of the wall, the statues start *walking around on their own*, or that the Static comes hopping out of the violet mist sounding like a Geiger counter in a pitchblende mine, sucking the charge straight out of your suit capacitors. And don’t get me started on the tall, wrong-jointed bastards—or the winged things overhead that come circling the moment you step onto stopped ground. The Ministry boys swore up and down those creatures would freeze like the sheep did. Well, they don't. Whatever clock we're wound to, they aren't on it.

Still. The pamphlet gets one thing right, even if it lies about the rescue party: **electricity works.**

Out there in the grey—where the grass doesn't bend in the wind and the creek hangs over the rocks like poured resin—nothing moves. A clock doesn't tick, a fire doesn't burn, and a dynamo doesn't turn. But inside the bubble of a powered Safeguard Tower, or even huddled inside the four-pace hum of a bootleg rift-crystal quern (which Page 7 "disavows," naturally, because God forbid a citizen start a generator without a permit), the laws of thermodynamics are still on the books. Watts are still volts times amps. Steam still expands. Copper still gets hot when you ask too much of it.

You always had a good head for the wrenches, Kid, even if you never finished your second-year theory before the world stopped. And if you *aren't* the Kid—if you’re just some poor drifter who found a dead man’s workshop and needs to keep the tower field from collapsing before the grey reaches the highlands—then you need this book even more than they did.

## How I’ve Kept This Book

I have divided every subject in this ledger into two halves:

1. **The Diary:** What I actually built, why I built it, and what caught fire before I got it right. Read this first if you don't know a volt from a volume of water. I’ve written down the physics the way I used to explain it to the summer apprentices—plain English, no calculus, and honest about where the analogies break down.
2. **The Reference Notes:** Terse, clean numbers at the back of each section. When the tower alarm is whooping at two in the morning and a pipe is whistling in your ear, don't read my diary. Flip to the [Reference pages](../reference.md), check your instrument display, and turn the right valve.

## Four Rules Before You Touch a Wire

* **Watch your instrument readout.** Whether you're using a hand-held **[MultiMeter](../reference/electricity.md#circuit-protection)**, a **Thermometer**, or the heads-up diagnostic lens on your visor (what the old survey crews called the *Jade* display), read the numbers. Remember to **crouch (sneak)** when looking at a device if you want the full diagnostic readout instead of just the first three lines.
* **Current does not shock you; heat and pressure will kill you.** By some miracle of the local physics, you can grab a bare fifty-volt or two-hundred-volt terminal with your naked hands and feel nothing at all—until you short a three-thousand-volt high-tension line and the **arc flash** cooks you in your boots, or an overworked cable sets the floorboards ablaze. And respect **steam**: a blowing [safety valve](../reference/steam.md#pipe-safety-valve) or a burst iron pipe throws a jet of scalding vapour four paces long that goes straight through armour like it isn't there.
* **Thirty percent is mercy; after that, it’s smoke.** Almost every electrical device I’ve built is rated for a nominal voltage—usually **50 V** or **200 V**. Push it past **1.3 times** that rating (65 V on a 50 V line, 260 V on a 200 V line) for more than a couple of seconds, and the internal watchdog gives up. The device will burn out in place. (See [the voltage tiers](../reference/electricity.md#voltage-tiers-and-cables).)
* **Don't throw away a burnt machine.** Unless it's a run of laid voxel cable (which chars to useless slag and has to be ripped out and re-laid), a burnt-out block stays right where it is. Walk up to it, click it with an empty hand, and it will tell you why it died and what single component—a **Cheap Chip**, a **Machine Block**, a **Ferromagnetic Core**, an **Electrical Motor**—you need to press into it to bring it back to life.

Now put the kettle on. We have a lot of copper to string before the grey reaches the hill.

---

Previous: [Contents](../index.md) · Next: [First Light: Bleeding the Trees](01-first-light.md)

[Back to the contents](../index.md)
