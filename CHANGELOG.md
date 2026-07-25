# Changelog

## 1.0.0

Initial release.

- Sidebar panel showing the team's board: score, rank, tile counts, and a
  points-descending list of tiles still needed, with per-item detail for
  complete-set tiles.
- Optional compact board overlay.
- Automatic reporting of on-board `NPC` and `EVENT` drops (`PICKPOCKET`
  opt-in) as `PENDING` submissions, with JPEG proof screenshots.
- Retry queue with backoff for network failures and rate limits.
- Fail-closed: nothing is reported unless a board has loaded.
