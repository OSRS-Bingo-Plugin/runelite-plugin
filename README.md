# OSRS Bingo — RuneLite Plugin

Automatically log your Old School RuneScape drops to your clan's **OSRS Bingo**
board. Paste your team's board code once, then play — genuine boss kills and
raid/chest rewards are reported to your event for an admin to approve. No
screenshots to chase, no spreadsheets.

## How it works

1. Your event admin gives you (or your team) a **board code** from the bingo site.
2. Install this plugin and paste the board code into its settings.
3. Play normally. When you get a drop that's on the board, the plugin reports it —
   optionally with a screenshot — and it shows up for your admin to approve.

Only **genuine** drops are reported: detection uses RuneLite's loot events (kills
and raid/chest reward chests), never your inventory — so items pulled from the
bank and dropped can't create fake entries. The plugin never completes a tile
itself; every drop is reviewed by a human.

## Settings

- **Board code** — your team's code from the bingo site (kept secret/masked).
- **Backend URL** — your bingo backend's address.
- **What to report** — NPC (boss) loot, raid/chest rewards, and optionally PvP /
  pickpocket loot.
- **Attach screenshots** — send a proof screenshot with each drop.
- **Minimum value / chat feedback** — noise controls.

## Privacy

The plugin only ever contacts the bingo backend you configure. Your board code is
stored locally (masked) and sent only to that backend. Screenshots, when enabled,
are sent only as drop proof.

## Install

Available via the RuneLite **Plugin Hub** (search "OSRS Bingo") once published.

## License

[BSD 2-Clause](LICENSE). Old School RuneScape is a trademark of Jagex Ltd; this
project is unaffiliated.

---

> This repository is the **published, buildable source** of the plugin. Day-to-day
> development (design docs, tests, history) happens in a separate private repo;
> releases are published here.
