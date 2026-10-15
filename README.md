# GrapplingHook v1.1.0 (Paper 26.3)

A lightweight grappling hook plugin. Right-click to latch onto a block up to **50 blocks** away and get pulled in smoothly.

> **Status:** written against the Paper 26.x API but **not yet compiled or run** by the author's tooling. Build it once
> (steps below), then run the test checklist before putting it on a live server.

## Dependencies

| What | Version | Why |
|---|---|---|
| Paper server | 26.3 | Runtime. Paper 26.1+ requires Java 25. |
| JDK | 25 | Build and run. |
| paper-api | `io.papermc.paper:paper-api:26.3.build.+` from `https://repo.papermc.io/repository/maven-public/` | Only compile-time dependency (`compileOnly`). |
| Gradle | 9.1.0 or newer (first release with Java 25 support) | Build tool. |

No other plugins required. Nothing is shaded into the jar.

## Get the jar (pick one)

**A. GitHub Actions (no local setup).** Create a GitHub repo, upload this folder's contents, open the **Actions** tab,
run **Build plugin**, and download the `GrapplingHook-jar` artifact. A red build means a compile error; paste the log to Claude.

**B. Locally.** Install Gradle 9.1+ (JDK 25 is auto-downloaded via the Foojay toolchain resolver if missing), then:
```
gradle build
```
The jar is `build/libs/GrapplingHook-1.1.0.jar`. Drop it into the server's `plugins/` folder and restart.

## Crafting recipe (shaped)

```
 Iron Ingot | Redstone  | Iron Ingot
 String     | Dispenser | String
            | String    |
```

## Use

- **Right-click** (hook in main hand): latch onto the block you look at (max 50 blocks).
- **Right-click again** or **Sneak**: let go early.
- Switching items also lets go (configurable). Fall damage is cancelled during the pull and for 2 seconds after.

## What's new in 1.1.0

- Durability: each grapple costs 1 use (default 256 uses); the hook breaks after its last swing, never mid-pull.
- Cooldown feedback: action bar shows the remaining recharge time.
- Restrictions: per-world disable list; blocked while gliding or riding; ignored in spectator mode.
- Impact effect: block-crumble particles where the hook lands.
- Admin permissions: `grapplinghook.bypass.cooldown` and `grapplinghook.bypass.durability`.
- Cleaner internals: one shared code path ends every grapple (release, arrival, timeout, stall).

## Commands and permissions

| Command / action | Permission | Default |
|---|---|---|
| `/grapplinghook give [player]` (alias `/ghook`), `/grapplinghook reload` | `grapplinghook.admin` | op |
| Use the hook | `grapplinghook.use` | everyone |
| Craft the hook | `grapplinghook.craft` | everyone |
| No cooldown / no durability loss | `grapplinghook.bypass.cooldown` / `.durability` | op |

## Resource pack

`GrapplingHook-ResourcePack.zip` holds the custom texture and item model. Host it at a direct-download URL and set
`resource-pack` and `resource-pack-sha1` in `server.properties`. The custom model is **off by default**
(`use-resource-pack-model: false`, hook looks like a renamed fishing rod). Once the pack is live, set it to `true`,
run `/ghook reload`, and give yourself a new hook.
Its `pack.mcmeta` uses format `97` for 26.3, taken from a single third-party source. Confirm it on the Minecraft Wiki.

## Test checklist (5 minutes)

1. Server log shows `GrapplingHook enabled` and `Crafting recipe registered`. No stack traces.
2. `/ghook give <you>`, then craft one at a crafting table using the recipe.
3. Right-click a wall about 30 blocks away: smooth pull, rope particles, you stop near the wall.
4. Right-click open sky: "No anchor point in range." Right-click a block 60+ away: same message.
5. Sneak mid-pull: you let go. Fall from the end of a pull: no fall damage.
6. Spam right-click: action bar shows the recharge time.
7. Open `config.yml`, set `durability.max-uses: 2`, `/ghook reload`, give a new hook, use it 2 times: it breaks after the second swing.
8. Chain 4 or 5 grapples without landing; if you are kicked for flying, set `allow-flight=true`.

## Why it does not lag

One shared repeating task (idle when nobody is swinging), one ray trace per click, no entities spawned, no block scans,
rope drawn with at most about 40 particles every 2 ticks, and all settings cached on load/reload.
