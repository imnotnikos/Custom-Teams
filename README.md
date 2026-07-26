<img width="100" height="100" alt="CustomTeamsLogo" src="https://github.com/user-attachments/assets/706d3259-4de1-4fdc-982f-e7d3656b077e" /> 

# Custom Teams - A Paper 1.21.11 Plugin


[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen?style=flat-square&logo=minecraft&logoColor=white)](https://minecraft.net)
[![Paper](https://img.shields.io/badge/Server-Paper-blue?style=flat-square)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net)
[![Version](https://img.shields.io/badge/Version-1.0.0-purple?style=flat-square)]()

***
### Plugin made by imNikos.
 
[![Discord](https://img.shields.io/badge/Discord-Nikos'%20Wasteland-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/9xJgzNqEaJ)
[![Instagram](https://img.shields.io/badge/Instagram-imNikos_-E4405F?style=flat-square&logo=instagram&logoColor=white)](https://instagram.com/imnikos_)
[![YouTube](https://img.shields.io/badge/YouTube-imNikoss-FF0000?style=flat-square&logo=youtube&logoColor=white)](https://youtube.com/@imnikoss)
[![TikTok](https://img.shields.io/badge/TikTok-imNikos_-000000?style=flat-square&logo=tiktok&logoColor=white)](https://tiktok.com/@imnikos_)
[![GitHub](https://img.shields.io/badge/GitHub-imnotnikos-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/imnotnikos)
[![Modrinth](https://img.shields.io/badge/Modrinth-imNikos-1bd96a?style=flat-square&logo=modrinth&logoColor=white)](https://modrinth.com/user/imNikos)
[![CurseForge](https://img.shields.io/badge/CurseForge-imnotnikos-F16436?style=flat-square&logo=curseforge&logoColor=white)](https://www.curseforge.com/members/imnotnikos/projects)

***

A Paper/Bukkit plugin for running admin-controlled PvP events — either **Free-For-All** or **4-Team** battles — complete with GUI-driven setup, custom kits, smart spawn placement, and per-player kit saving.

---

## Features

- **Two event modes**
  - **Free-For-All (FFA):** any number of players, each fighting solo with a white nametag. The winner's own name is announced instead of a team name.
  - **Teams:** 4 fixed teams — **Red, Blue, Green, Yellow** — using light color variants for nametags, tab list, and chat prefixes.
- **Flexible spawn systems**
  - **Teams:** set multiple spawn points per team using an in-world hotbar tool.
  - **FFA:** choose between placing one spawn point per player, or a single center point that automatically spreads players around it in a radius that scales with player count.
  - All auto-generated spawn locations are checked for safe, non-suffocating ground before use.
- **Master event kit editor** — build a shared kit (armor, offhand, potion effects, and 27 inventory slots) that every participant receives at match start.
- **Personal kit saver** — every player can save and load up to 3 of their own kits, independent of the event kit.
- **Match safety windows**
  - 5 seconds of invincibility (and inability to deal damage) when a match starts, so no one gets ambushed while loading in.
  - 10 seconds of invincibility for the winner(s) during the victory celebration.
- **Configurable toggles** — starting gamemode, health-on-kill, and natural regeneration, all switchable from the admin GUI.
- **Automatic spectating** — players who die are moved to spectator mode and can rejoin as a spectator or leave the event entirely.
- **Persistent data** — kits and spawn points are stored in `kits.yml` and survive server restarts.

---

## Commands

| Command | Description | Permission |
|---|---|---|
| `/ct` | Opens the admin control panel (Teams, Spawns, Kit Creator, toggles, Start/End Event). | `customteams.admin` |
| `/ct leave` | Leave the currently running event and restore your pre-event inventory/location. | none |
| `/ct kits` | Open your personal "My Kits" menu (save/load up to 3 personal kits). | none |

Tab-completion suggests `leave` and `kits` after `/ct`.

---

## Permissions

| Permission | Description |
|---|---|
| `customteams.admin` | Access to the admin panel: team/roster management, spawn setup, kit creation, and starting/ending events. |

Any player (with or without the admin permission) can use `/ct leave` and `/ct kits`.

---

## Admin GUI Walkthrough

Run `/ct` to open **Custom Teams Settings**:

- **Teams** → Choose how the next event will be populated:
  - **Free-For-All (FFA)** → opens a paginated player selector. Click players to toggle their selection, then **Confirm & Set FFA Roster** to lock it in.
  - **Manual Teams** → pick a color, then click players to assign/unassign them to that team.
- **Spawns** → Choose which spawn system to configure:
  - **FFA Spawns** → requires an FFA roster to already be set (so the plugin knows how many spawn points are needed). Then pick:
    - **Exact Spawn Points** — you're handed one white wool per participant; place each one where you want a player to spawn.
    - **Radius Spawn Point** — you're handed a single white wool; place it once as the center, and players will be spread evenly around it at a distance that grows with player count.
  - **Team Spawns** → enters an in-world editing mode with this hotbar layout:

    | Slot | Item |
    |---|---|
    | 1 | Red spawn point (place to add a Red spawn) |
    | 2 | Blue spawn point |
    | 3 | Green spawn point |
    | 4 | Yellow spawn point |
    | 5 | Red spawn reset (right-click to clear only Red's spawns) |
    | 6 | Blue spawn reset |
    | 7 | Green spawn reset |
    | 8 | Yellow spawn reset |
    | 9 | Save and Exit |

    In your main inventory you'll also find **Cancel** (discard all changes) and **Reset All** (clear every team's spawns). Nothing is written to disk until you hit **Save and Exit**.

- **Kit Creator** → build the shared master event kit: drag items onto the armor/offhand slots and potion-effect slots, then click the emerald block to save.
- **Gamemode / Natural Regen / Health on Kill toggles** → click to flip each setting; changes are saved to `config.yml` immediately.
- **Start Event / Force End Event** → starts a 5-second countdown (visible as a title to all queued participants) or immediately ends a running event.

---

## Personal Kits (`/ct kits`)

Every player has 3 personal kit slots, independent of the admin's master kit:

- **Left-click** a slot to load that saved kit into your current inventory.
- **Right-click** a slot to save your current inventory into it.

These are stored per-player and persist across sessions.

---

## Configuration (`config.yml`)

```yaml
settings:
  gamemode-on-start: SURVIVAL   # or ADVENTURE
  health-on-kill: true          # fully heal/feed a player on a kill
  natural-regen: true           # vanilla natural health regeneration during events
```

## Data (`kits.yml`)

Stores the master event kit, team spawn lists, FFA spawn configuration, and each player's personal kits. This file is created automatically on first run — you shouldn't need to edit it by hand.

---

## Requirements

- Paper (or a Paper-based fork) — the plugin uses Paper/Adventure APIs (Adventure text components, `AsyncChatEvent`, modern world height methods).
- Java 17+ (matching your server's Minecraft version requirements).

---

## Notes & Limitations

- Winner/team colors are drawn from a fixed, small palette (Minecraft's legacy chat colors) rather than arbitrary RGB, since scoreboard team colors are limited to that palette.
- The natural-regeneration toggle only affects players who are currently in the event — it cancels natural (food-driven) regeneration on a per-player basis rather than touching the world's gamerule, so non-participants and the world itself are unaffected. Other heal sources (potions, kits, health-on-kill) still work normally regardless of this toggle.
- FFA spawn generation validates ground safety automatically; manually placed team/exact-FFA spawn points are checked too, but only produce a warning (the admin's placement is trusted over the automated fallback).
