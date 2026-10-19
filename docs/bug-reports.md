# Bug reports (F7)

The client side is in `src/main/java/org/erisia/stasis/client/report/` unless a path says otherwise.
The spec is `/var/lib/mclab/agent/notes/bugreport/SPEC.md`.

## What it does
- F7 ("Send bug report", key category `stasisdelvers:keys`) takes a screenshot, collects the client's state,
  asks the server for its view, and writes a redacted zip to `<game>/bugreports/pending/`. Shift+F7 asks for
  a one-line note first (`NoteScreen.java`).
- Uploading is manual: the player posts the zip in Discord, then runs `/bugreport sent [name]` (a client
  command; with no name, all pending reports), which moves it to `sent/`. `/bugreport list` shows what is pending.
- One report at a time, with 5 s between reports (`COOLDOWN_MS`).

## Pipeline (`BugReport.java`)
1. `request` → `start`: a nonce, a screenshot (`screenshot`, downscaled), then sections, each caught on its own
   and capped at `REPORT_SECTION_CAP` = 256 KiB. A failing section lands in `errors` and never breaks the game.
   - `ClientCollector.java`: versions, mods, client, player, world, look, screen, inventory, F3, chat.
   - `src/main/java/org/erisia/stasis/client/StasisReport.java`: fields, deeps, post data texture (decoded
     plus raw), towers, remnants, sprites. `ReportData.java` exposes package-private common state.
   - `ElnReport.java`: EA client state (node entities, the EA block looked at, open EA GUI).
   - `FrameTimes.java`: frame-time percentiles.
2. `StasisNet.ReportQuery(nonce, look, face)` goes to the server. `poll` waits for the screenshot (≤ 6 s), the
   note, and the server reply (≤ 3 s, `SERVER_WAIT_MS`). Then the server status is ok / timeout / no_channel /
   error.
3. `finish`: `redact` runs `Redactor` over every JSON key and string, then builds `report.json`.
4. Off-thread, `Packager.java` does the rest:
   - writes `report.json`, `f3.txt`, `log.txt` (head 200 lines + tail ≤ 3000 lines / 512 KiB, redacted),
     `chat.txt`, `crash.txt` (this session's crash report, if any), and the screenshot
   - zips them in `.tmp/` and publishes to `pending/`
   - over `ZIP_MAX` = 8 MiB, halves the screenshot (at most twice)
5. `done` says so in chat, with a click to open the folder.
- Retention (`Packager.retain`): `sent/` keeps `SENT_KEEP` = 10; `pending/` keeps ≤ `PENDING_MAX` = 20 files
  and ≤ 100 MiB.

## Server side (`src/main/java/org/erisia/stasis/ReportResponder.java`)
- `answer(player, query)` runs on the server thread and returns JSON sections: server, player, statue, world,
  fields, towers, and eln (nodes around the looked-at block).
- Rate limit: one per 10 s per UUID (`LAST`, `MIN_INTERVAL_MS`). The look position is honoured within 8 blocks.
- The answer is capped at `ReportState.REPORT_MAX - 256`; sections are dropped until it fits.

## Redactor (`Redactor.java`)
- Pure Java with no Minecraft classes, so the server GameTest and the unit tests can use it.
- Order of replacement:
  1. the game directory, in every spelling (/ and \, `file:` URLs, longest first) → `<game>`
  2. other absolute paths → `<path>` (a `.jar`'s file name is kept); POSIX roots `/home`, `/Users`, `/root`, `/var`,
     `/tmp`, `/opt`, `/mnt`, `/media`, `/run`, `/nix`, `/srv`, `/data`, `/Volumes`; on POSIX only the folder right after the root may contain spaces
  3. the server address and host → `<server>`
  4. IP literals → `<ip>`
  5. the OS user name (≥ 4 characters, word-bounded) → `<user>`
  6. tokens, passwords, `sessionId=` → `<redacted>`
- `BugReport.redactor(mc)` builds one from `gameDirs` (the absolute and real path), the current server
  address and `user.name`.

## Config
- Client config `stasisdelvers-client.toml` (`ReportConfig.java`, registered in `StasisClient`): `includeChat`,
  `chatSystemOnly`, `includePostData`.

## Invariants and tests
- No private path, IP or user name leaves unredacted. Redacting twice changes nothing.
  - `src/test/java/org/erisia/stasis/client/report/RedactorTest.java`: `noPrivateNameSurvives`,
    `redactingTwiceChangesNothing`, `theUserNameNeverSurvives`, `knownLeaks` (regressions), and
    `gameTestExamples` (the same examples as the GameTest).
  - GameTest `bugreport_redaction` (examples, run on the server).
- The report payloads round-trip, and over the cap they throw: `NetCodecTest.reportPayloadsRoundTrip`,
  `reportStateOverTheCapThrows`.
- A failing collector never breaks the report: each `section(...)` is caught on its own, on both sides.

## Gotchas
- `ReportResponder.LAST` is never pruned (one entry per UUID that ever reported). That is small, but unbounded.
- The client's report payloads are optional channels. Against a server without them the report still
  completes, with `server: no_channel`.
- Anything new written into a report must go through `redact` (JSON) or `Redactor.redact` (text). The log and
  crash text are redacted in `Packager`.
