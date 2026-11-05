# indigo_economy 0.5.4 (Indigo Credits)

This mod is an account-based Indigo balance keyed to the Minecraft UUID. It has an in-game wallet screen and a narrow,
HMAC-signed loopback bridge that the official Indigo Discord bot uses. From 0.4.0 it also prices
Lightman's Currency shops in Indigo Credits (`lc/`).

0.5.4 (Indigo 0.6, D97): with `casino_enabled=false` a casino owner can still use `/casino`, `/casino withdraw` and `/casino
close` so a house bank is never stuck; opening, depositing and every table stay off ("Casinos have moved to the Indigo
roleplay server").

It is pinned in `manifest/stack.json` `customMods` and bundled in the Indigo pack. 0.4.0 went live on 2026-09-28
(evidence); 0.4.1 changes only the licence (see below).

**Licence: [MIT](LICENSE)** (owner decision, 2026-09-28). The licence text is packed into the jar as `LICENSE`, and
`neoforge.mods.toml` says `license="MIT"`. 0.4.0 carried a lab-era "All Rights Reserved … not for distribution" string.

- Design and contract: `docs/INDIGO_ECONOMY_CONTRACT.md`.
- Evidence: `docs/validation/2026-09-27-account-economy/`.
- Decision: D42.

| Directory | Contents |
|---|---|
| `src/online/indigomc/economy/core/` | Ledger, journal, money, links, bridge: plain Java plus Gson, no game classes |
| `src/online/indigomc/economy/` | NeoForge glue, commands and payloads (server + common) |
| `src/online/indigomc/economy/client/` | Wallet screen and keybind (client only) |
| `resources/` | `neoforge.mods.toml`, lang |
| `src/online/indigomc/economy/lc/` | Indigo Credits as an LC currency; shop settlement through ledger holds |
| (moved) Discord module | now `tools/discord/src/bot/economy.ts`, harness `tools/discord/test/economy-lab-harness.ts` |
| `test/CoreTest.java` | Headless tests |
| `src/online/indigomc/economy/core/Compan*.java` | Indigo companies (branch `claude/indigo-companies`, off by default): state, rules, `/company` text console |
| `test/CompanyTest.java`, `LegacyJournalGen.java`, `CompanyBridgeLab.java` | Company tests, the pre-company journal generator and the real-bridge lab for the bot e2e |

Build and test with `python scripts/economy-build.py core-test`, then `python scripts/economy-build.py build`.
Companies: `python scripts/economy-build.py company-test [--discord]` (design).
Outside Windows/Prism, pass Gson 2.10.1 with `--gson <jar>` or `INDIGO_GSON_JAR`. The jars go
to `.work/economy/build/`, which is ignored by Git.
