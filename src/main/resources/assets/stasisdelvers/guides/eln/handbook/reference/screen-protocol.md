---
navigation:
  title: The Screen Protocol
  parent: reference.md
  position: 9
  icon: eln:computer
---

# The Screen Protocol

The computer's screen is drawn by each player's own client from short messages, so typing and editing never wait for
the server. The Janet library speaks it for you (see [Drawing your own screen](janet.md)). This page is for
programs that speak it themselves, such as your own system booted from a floppy.

## The channel

The screen's channel is a device at 0x10007000 with the console's registers: `+0` send a byte, `+4` read one (−1:
none), `+8` status (1: input waiting, 2: room to send), `+0xC` interrupt enables (the same bits). Both ways it
carries **one message per line**: a Janet form such as `(text 2 5 0 0 :lime "Hello")`, ended by a newline. Messages
it doesn't understand are ignored. Strings use Janet's escapes (`\n`, `\"`, `\\`). Colours are dye names (`:white`,
`:orange`, `:magenta`, `:light-blue`, `:yellow`, `:lime`, `:pink`, `:gray`, `:light-gray`, `:cyan`, `:purple`,
`:blue`, `:brown`, `:green`, `:red`, `:black`).

The screen is 52 by 17 character cells unless you say otherwise. It has tabs, numbered 1 to 255 (the server keeps tab
0, the Console, for what the machine prints at `0x10002000`). Each tab holds elements, numbered by you; `t` below is
a tab and `id` an element. Sending an element again with the same `id` changes it in place; a different kind replaces
it. Elements are drawn in the order they were first made, so make backgrounds first.

## What the computer sends

| message | does |
|---|---|
| `(screen w h)`, `(reset w h)` | the screen's size; `reset` also removes every tab |
| `(tab t "title")`, `(untab t)` | adds (or renames) a tab, or removes it |
| `(focus t)`, `(clear t)` | brings a tab forward, or empties it |
| `(remove t id)` | removes an element |
| `(box t id x y w h colour)` | a filled rectangle |
| `(text t id x y colour "text" bg)` | a line of text (`bg` is optional) |
| `(log t id x y w h)`, `(append t id colour "text")` | a log keeping its last 1000 lines, and text added to it (it may hold newlines) |
| `(button t id x y w "label")` | a button |
| `(list t id x y w h ["row" ...])` | rows of text the player can scroll and click |
| `(line t id x y w "prompt")` | a line the player types in, on their own client |
| `(busy t id seq true)`, `(busy t id seq false)` | a line's submission `seq` is taken and running, or done |
| `(editor t id x y w h :janet path rev "text")` | a text editor (`:plain` for no colours) holding a file at revision `rev` (any string) |
| `(saved t id seq rev)`, `(conflict t id seq rev "text")` | the answer to a save: the new revision, or a refusal with the file as it is now |
| `(raw t id x y w h)` | an area that takes the keyboard once clicked (or when its tab has no line) |
| `(dict t ["name" ...])` | names Tab may complete in tab `t` (0: every tab) |

## What the player sends

| message | when |
|---|---|
| `(submit t id seq "text")` | Enter on a line. `seq` counts up; the line shows the text grey until a `busy` for it comes |
| `(press t id)`, `(select t id row)` | a button clicked, a list's row clicked (from 0) |
| `(save t id seq rev [[start deleted ["line" ...]] ...])` | Ctrl+S in an editor: at line `start` (from 0) of revision `rev`, `deleted` lines replaced by the new ones. Answer with `saved` or `conflict` |
| `(key t id "key" mods)`, `(click t id x y)` | in a raw area: a character or a key's name, and Shift 1 + Ctrl 2 + Alt 4; a click in cells from its corner |
| `(break t)` | Ctrl+C on tab `t`. The Break interrupt is raised as well |
| `(close t)` | the player right-clicked tab `t` to close it |

## Limits

A message is at most 64 KB. A player's message is at most 256 bytes (a save 40 KB), and at most 20 a tick. A
screen has at most 32 tabs, and a tab 256 elements. The server keeps what the screen shows, so a player who opens it
later sees it all, and it is saved with the world.

[The Computer](computer.md) · [The Janet library](janet.md) · [Back to the reference](../reference.md)
