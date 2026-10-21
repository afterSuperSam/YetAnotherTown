# Yet Another Town

A Fabric mod for organizing your Minecraft town directly on Xaero’s World Map.

Mark building projects, find shared facilities, read usage instructions and report repairs — without leaving the game.

[Download the Beta](https://github.com/afterSuperSam/YetAnotherTown/releases/tag/v1.0.0beta) · [All releases](https://github.com/afterSuperSam/YetAnotherTown/releases) · [Report an issue](https://github.com/afterSuperSam/YetAnotherTown/issues)

## Features

- Select chunks on the map to create a project.
- Hover over projects to see their owner, status and description.
- Search by name or filter by project type.
- Navigate to projects and report or complete repairs.
- Manage town members, permissions and past worlds.
- English and Simplified Chinese, following your game language.

## Install

One JAR supports **Minecraft 26.1.2, 26.2 and 26.3** with Fabric.

Install **Fabric Loader 0.19.5**, **Fabric API**, **Xaero’s World Map** and **Xaero’s Minimap** for your Minecraft version, then put the Yet Another Town JAR in your `mods` folder. **Mod Menu** is optional.

**An external town service is required.** Ask your town administrator for its address and enter it in Client Settings. The Minecraft server itself does not need this mod.

## Get started

1. Press **K** to open the town menu. Join a listed town, enter a town ID, or create your own.
2. The mayor opens **Town map settings** and selects **Use my current map**. Members match automatically when using the same server address and map name.
3. Press **M** to open Xaero’s map. Hover over a project for details, or right-click and drag to select chunks for a new project.
4. Press **X** when inside a project to open its details.

New members start with read-only access. Ask a town administrator for permission to create projects. Keys can be changed in Minecraft’s Controls settings.

## Host a town service

Requires **Node.js 24+**. Download the repository, copy `server/config.example.json` to `server/config.json`, then run:

```sh
npm --prefix server start
```

No npm dependencies are required. The default address is `http://127.0.0.1:37841`. For remote players, put the service behind an HTTPS reverse proxy and share that HTTPS address. Back up `server/data` to preserve town records.

## Good to know

- Designed for trusted communities: player UUIDs are read from the game, not independently authenticated.
- Project areas are markers, not protected claims. The mod does not prevent block changes.
- Terrain stays in each player’s local Xaero maps; the service only shares town and project records.
- Everyone should connect using the same server address. Different IPs or domain names are treated separately.
