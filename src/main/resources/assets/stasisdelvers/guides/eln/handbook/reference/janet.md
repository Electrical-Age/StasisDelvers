---
navigation:
  title: Janet on the Computer
  parent: reference.md
  position: 8
  icon: eln:janet_rom_chip
item_ids:
  - eln:janet_rom_chip
---

# Janet on the Computer

The Janet ROM is [Janet 1.41](https://janet-lang.org), a small Lisp, with this library on top. Anything that works in
Janet works here. It has no network, threads or operating system calls, but files on the floppies work.

## Tasks

A program is a set of tasks taking turns. While every task waits, the computer sleeps.

| call | does |
|---|---|
| `(spawn f & args)` | runs `(f ;args)` as a task of its own |
| `(sleep s)` | waits `s` seconds of the computer's time (other tasks run meanwhile) |
| `(now)`, `(ticks)` | the computer's clock, in seconds or game ticks (20 a second, only while it runs) |
| `(run-file path)` | runs a file's forms in this task, as at start-up |
| `(alarm & msg)` | prints `ALARM: …` in red |

Each form typed at the REPL runs as a task too, so `(sleep 10)` doesn't stop the others. Ctrl+C stops it.

## Processes

A process is a program with its own tab, its own definitions, and its own tasks. `startup.janet` runs as one.

| call | does |
|---|---|
| `(proc/start name f & args)` | starts `(f ;args)` as a new process, in a tab of its own where what it prints goes; returns the tab |
| `(proc/list)` | the processes: `[tab name tasks]` each |
| `(proc/stop t)` | stops every task of the process in tab `t` (as Ctrl+C on its tab) |
| `(proc/close t)` | ends it and removes its tab (as right-clicking the tab) |
| `(proc/tab)` | the tab of the process this task belongs to |
| `(proc/env t)` | its environment: `((proc/env t) 'x)` is its `x` |
| `(proc/new-tab name)` | an empty tab with a process of its own, for a program that draws its own screen |
| `(spawn-in env f & args)` | a task in another environment |
| `(edit path)`, `(files &opt dir)`, `(inspect)` | the editor, file list and inspector tabs |

## Drawing your own screen

A process can fill its tab with text, boxes, buttons, lists, lines to type in, and areas that take the keyboard.
Positions and sizes are in character cells: the screen is 52 by 17. `t` is the tab, `id` a number you choose for
each element (draw it again with the same `id` to change it), and colours are dye names such as `:lime`.

| call | does |
|---|---|
| `(ui/text t id x y colour s &opt bg)` | a line of text |
| `(ui/box t id x y w h colour)` | a filled rectangle |
| `(ui/log t id x y w h)`, `(ui/append t id colour s)` | a scrolling log, and text added to it |
| `(ui/output env t id)` | what tasks in `env` print goes into log `id` |
| `(ui/button t id x y w label f)` | a button: a click runs `(f)` as a task |
| `(ui/list t id x y w h items f)` | rows of text: a click runs `(f index)` |
| `(ui/line t id x y w prompt f)` | a line the player types in: Enter runs `(f text seq)` |
| `(ui/raw t id x y w h on-key &opt on-click)` | an area that takes the keyboard once clicked: `(on-key key mods)` for each key, `(on-click x y)` |
| `(ui/remove t id)`, `(ui/clear t)` | removes one element, or all of the tab's |
| `(ui/tab t title)`, `(ui/focus t)` | renames a tab, or brings it forward |

Elements are drawn in the order they were first made. A raw area's keys are the characters typed, or `"enter"`,
`"backspace"`, `"tab"`, `"delete"`, `"left"`, `"right"`, `"up"`, `"down"`, `"home"`, `"end"`, `"pageup"`,
`"pagedown"`, `"insert"` and `"f1"` to `"f12"`, or a letter with Ctrl or Alt held. `mods` adds 1 for Shift, 2 for
Ctrl and 4 for Alt.

```clojure
# a counter
(var n 0)
(defn show [t]
  (ui/text t 2 2 1
   :yellow (string
    "count: " n)))
```

```clojure
(defn up [t]
  (++ n) (show t))
(defn down [t]
  (-- n) (show t))
```

```clojure
(defn counter []
  (def t (proc/tab))
  (ui/clear t)
  (ui/button t 3 2 3
   5 "+1" |(up t))
  (ui/button t 4 8 3
   5 "-1" |(down t))
  (show t)
  (forever
   (sleep 60)))
```

```clojure
(proc/start "counter"
  counter)
```

Type each form at the REPL, or save them in a file and Run it.

## The bus

Addresses: 0 everyone, 17 to 255 a device. Colour channels are `:white` … `:black`, or 0 to 15.

| call | does |
|---|---|
| `(bus/send target type index value)` | sends from this computer's address. `type` is `:signal`, `:press`, `:power`, `:write`, `:read`, `:reply`, `:fault`, `:arrive` or a number |
| `(bus/raw-send target source type index value)` | sends with any source address (it can pretend to be another device) |
| `(bus/level ch)` | a colour channel's level, 0 to 1 |
| `(bus/set ch level)` | sets a colour channel. It holds until someone else writes it |
| `(bus/wait &opt pred timeout)` | waits for a packet that `pred` accepts. `nil` after `timeout` seconds |
| `(bus/on type f)` | runs `(f packet)` as a task for every packet of that type (or that a function accepts) |
| `(bus/on-press address f)` | runs `(f index)` whenever the button at that address is pressed |
| `(bus/handlers-clear)` | forgets every `bus/on` |
| `(bus/address)` | this computer's address, and whether it is on a bus |

A packet is a struct: `{:target :source :type :index :value}`. At most 16 packets a tick go out from one computer.

## Devices

| call | does |
|---|---|
| `(light/set address level)` | a light's (or a button lamp's) brightness, 0 to 1 |
| `(device/write address reg value)` | sets a register |
| `(device/read address reg)` | reads a register. An error if no reply comes within a second |
| `(drive/move address height &opt timeout)` | sends a Linear Servo Drive's car to `height` and waits until it is there. An error on a fault, or after `timeout` seconds (default 60) |
| `(drive/position address)`, `(drive/state address)` | where its car is, and `:idle`, `:in-position`, `:moving` or `:fault` |

## Floppies

`slurp`, `spit`, `file/open` and `import` work on paths like `a:/main.janet` and `b:/lib/util.janet`. The disks are
FAT12, 1.44 MB.

| call | does |
|---|---|
| `(disk/format drive &opt label)` | formats the disk in `:a` or `:b`, erasing it |
| `(disk/list &opt path)` | the names in a directory (default `a:/`); directories end in `/` |
| `(disk/label drive)`, `(disk/free drive)` | its label, and the bytes free |
| `(disk/delete path)`, `(disk/rename from to)`, `(disk/mkdir path)`, `(disk/exists? path)` | the rest |

## A small program

```clojure
# a:/startup.janet:
# the button at 30
# toggles the lamp
# at 31
(var on false)
```

```clojure
(defn toggle [_]
  (set on (not on))
  (light/set 31
    (if on 1 0)))
(bus/on-press 30
  toggle)
```

```clojure
# keep its task alive
(forever (sleep 60))
```

## Your own system

With the **Floppy Boot** ROM in slot 1, the computer loads drive A's sector 0 (512 bytes) to address 0x100000 (slot
2's RAM) and jumps there, with `a0` = 0 and `a1` = 0x10000000. From there it's yours. The CPU is RV32IMAFDC in machine
mode, with the timer, external and software (Break) interrupts.

| address | what |
|---|---|
| 0x000000 to 0x0003FF | unmapped (catches NULL) |
| slot *n* (1 to 8) | from (*n* − 1) × 0x100000, its chip |
| 0x10000000 | info: `+0x10` + 4·k slot kind (0 none, 1 RAM, 2 ROM), `+0x30` + 4·k size, `+0x50` ticks |
| 0x10001000 | timer: a 64-bit compare at `+0`, interrupt while ticks ≥ compare |
| 0x10002000 | console: `+0` send a byte, `+4` read one (−1: none), `+8` status (1 input, 2 room), `+0xC` interrupt enables |
| 0x10003000 | bus: packet registers; `+0x14` send, `+0x24` take the next received; channel levels from `+0x80` |
| 0x10004000 | floppies: drive, sector, RAM address, count, command (1 read, 2 write), status, interrupt, error |
| 0x10005000 | power: `+0` write 1 to halt, 2 to reset; `+4` Break pending |
| 0x10007000 | the screen's channel ([The Screen Protocol](screen-protocol.md)): registers as the console's |

All device registers are 32-bit words.

[The Computer](computer.md) · [The Screen Protocol](screen-protocol.md) · [Back to the reference](../reference.md)
