# OSRS Bingo — RuneLite Plugin

Shows your clan's **OSRS Bingo** board inside the game and automatically logs your
drops to it. Paste your team's board code once, then play — your board is on the
sidebar, and on-board drops get reported to your event for an admin to approve.
No screenshots to chase, no spreadsheets.

## What it does

- **Shows your board in-game.** A sidebar panel lists what your team still needs —
  highest-value tiles first, with the items each tile accepts and, for set tiles,
  which pieces you're still missing — plus your score, rank, and pending count.
- **Optional board overlay.** A "Board overlay: On/Off" toggle in the panel draws
  a grid of your tiles over the game screen, coloured by state — green (done),
  yellow (pending), grey (still needed) — with a small info box showing your team
  name and the current time.
- **Logs your drops automatically.** When you get a drop that's on your board, it's
  reported — optionally with a screenshot — and appears for your admin to approve.
  Only items that are on your board are sent; everything else is ignored, and a
  human approves every submission.

## Getting started

1. Your event admin gives your team a **board code** from the bingo site (one code
   per team — it's your team's, not the whole event's).
2. Install the plugin from the RuneLite **Plugin Hub**, open its settings, and paste
   the board code.
3. Play normally. Your board shows up on the sidebar, and on-board drops are
   reported for an admin to approve.

## Settings

- **Board code** — your team's code from the bingo site (masked; treat it like a
  password). This is the only thing you need to enter.
- **What to report** — boss/monster kills and raid/chest rewards are on by default;
  pickpocketing is off by default (turn it on only for thieving tiles). There is no
  PvP option, by design.
- **Attach screenshots** — send a proof screenshot with each logged drop (on by
  default).
- **Chat messages** — a short chat line when a drop is logged or something needs
  your attention.

The board overlay toggle ("Board overlay: On/Off") lives in the sidebar panel next
to your board, not in this settings list.

## Your data

The plugin only ever contacts the OSRS Bingo service — no analytics, no other
destinations — and it sends nothing at all until you enter a board code. After
that, your board code and your on-board drops (and, if you leave screenshots on, a
proof screenshot per drop) are sent there so your board stays in sync. The plugin
never reads your inventory or bank. See [SECURITY.md](SECURITY.md) for specifics.

## Install

Available via the RuneLite **Plugin Hub** (search "OSRS Bingo") once published.

## License

[BSD 2-Clause](LICENSE). Old School RuneScape is a trademark of Jagex Ltd; this
project is unaffiliated.

---

> This repository is the **published, buildable source** of the plugin. Day-to-day
> development happens in a separate repository; releases are published here.
