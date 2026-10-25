# Yet Another Town

A Fabric mod for organizing your Minecraft town directly on Xaero’s World Map.

Mark building projects, find shared facilities, read usage instructions and report repairs — without leaving the game.

[Download v1.0.0d](https://github.com/afterSuperSam/YetAnotherTown/releases/tag/v1.0.0d) · [Changelog](CHANGELOG.md) · [Wiki](https://github.com/afterSuperSam/YetAnotherTown/wiki) · [Report an issue](https://github.com/afterSuperSam/YetAnotherTown/issues)

Current version: **v1.0.0d**, a prerelease for Minecraft 26.1.2, 26.2 and 26.3.

## Features

- Select chunks on the map to create a project.
- Hover over projects to see their owner, status and description.
- Save favorites locally and filter by type, favorites, your projects or repairs.
- Toggle favorites on the project icon and edit project status directly in its editor.
- Show or hide project areas and distinguish neighboring projects with hover outlines.
- Resize existing project areas while keeping their details and repair records.
- Follow compact on-screen directions to projects, report repairs in a small dialog, or complete all repairs in one action.
- Manage town members, permissions and past worlds.
- A compact home page and sidebar, with inline project actions on wider screens.
- English and Simplified Chinese, following your game language.

## Install

One **v1.0.0d** JAR supports **Minecraft 26.1.2, 26.2 and 26.3** with Fabric.

Install **Fabric Loader 0.19.5**, **Fabric API**, **Xaero’s World Map** and **Xaero’s Minimap** for your Minecraft version, then put the Yet Another Town JAR in your `mods` folder. **Mod Menu** is optional.

**Java 25+** is required. The mod metadata accepts Loader 0.19.3+, with automated checks performed on 0.19.5. The oldest checked builds for each Minecraft version are:

| Minecraft | Fabric API | World Map | Minimap |
| --- | --- | --- | --- |
| 26.3 | 0.160.5+26.3 | 1.46.2 | 26.5.1 |
| 26.2 | 0.152.1+26.2 | 1.41.0 | 26.1.0 |
| 26.1.2 | 0.145.4+26.1.2 | 1.40.14 | 25.3.10 |

These are per-mod version baselines; choose a compatible Xaero pair from the checked releases below rather than treating each row as a tested combination.

**World Map 1.47.1 and Minimap 26.6.1 must be updated together.** Older versions must also satisfy Xaero's own pairing requirements.

<details>
<summary>Exact versions and automated compatibility coverage</summary>

The release check passed for these exact published Fabric builds; Fabric API needs the matching `+Minecraft` suffix:

| Minecraft | Fabric API | World Map | Minimap |
| --- | --- | --- | --- |
| 26.3 | 0.160.5, 0.160.6, 0.160.7, 0.161.0, 0.162.0, 0.163.0, 0.164.0, 0.165.0 | 1.46.2, 1.46.3, 1.46.4, 1.47.0, 1.47.1 | 26.5.1, 26.5.2, 26.5.3, 26.6.0, 26.6.1 |
| 26.2 | 0.152.1, 0.152.2, 0.153.0, 0.154.0, 0.154.1, 0.154.2, 0.155.0, 0.155.2, 0.156.0, 0.157.0, 0.158.0, 0.159.0, 0.160.0, 0.161.0 | 1.41.0, 1.41.1, 1.41.2, 1.41.3, 1.42.0, 1.43.0, 1.44.0, 1.44.2, 1.45.0, 1.46.0, 1.46.1, 1.47.0, 1.47.1 | 26.1.0, 26.1.1, 26.1.2, 26.1.3, 26.2.0, 26.3.0, 26.4.0, 26.4.2, 26.5.0, 26.5.1, 26.6.0, 26.6.1 |
| 26.1.2 | 0.145.4, 0.146.0, 0.146.1, 0.147.0, 0.148.2, 0.149.0, 0.149.1, 0.150.0, 0.151.0, 0.152.1, 0.153.0, 0.154.0, 0.154.2, 0.155.0, 0.155.2, 0.155.3 | 1.40.14, 1.40.16, 1.40.17, 1.40.18, 1.41.0, 1.41.1, 1.41.2, 1.41.3, 1.42.0, 1.43.0, 1.44.0, 1.44.2, 1.45.0, 1.46.0, 1.47.0, 1.47.1 | 25.3.10, 25.3.11, 25.3.12, 25.3.14, 26.1.0, 26.1.3, 26.1.4, 26.1.5, 26.2.0, 26.3.0, 26.4.0, 26.4.2, 26.5.0, 26.6.0, 26.6.1 |

Map versions must also satisfy Xaero's own dependency rules. **World Map 1.47.1 and Minimap 26.6.1 must be updated together**; older releases have additional pairing restrictions. The release check evaluated 6,224 combinations on Loader 0.19.5: 2,214 satisfy dependency metadata and 4,010 are excluded by declared conflicts. The final single JAR passed all 188 interface checks, covering 153 compatible Xaero pairs and all 38 Minecraft-specific Fabric API builds. These are static checks; gameplay and every Cartesian combination’s bytecode are not tested.

Only the versions listed here are within the current automated check scope; unlisted versions are not guaranteed compatible.

</details>

**A town data service is required.** New installations connect directly to `https://server.moesam.com`. Existing saved addresses are kept; change the address in Client Settings to use your own service. The public-service guide is [Yet Another Town](https://blog.moesam.com/articles/4). The Minecraft server itself does not need this mod.

## Get started

1. Press **K**, then join a listed town or enter the town ID from an invitation.
2. Open a common facility on the home page, or press **M** and hover over a project. Right-click its area to navigate.
3. Tap the project key (**X** by default) for details; hold it to stop navigation. Change the binding in Minecraft Controls.
4. Click a project's star to save it on this device. With member permissions, right-click and drag chunks on the map to register a project.

New members can browse immediately. Ask the mayor for member permissions before registering projects. First-use hints explain map selection, navigation and favorites where you use them.

For a password-protected town, enter the password beside its details or return to the directory to choose another. A loading overlay covers pending requests; failed requests keep your input. Turning off animations keeps its indicator still. Buttons follow your active Minecraft resource pack. Pages open without a preselected control; use the mouse or Tab to focus one.

## Mayor: set up once

Create a town while connected to your Minecraft server, then choose **Set up current map**. This shares the world/map name so members can match their own Xaero maps automatically. You can also do this later under **Manage → Worlds and seasons**.

Give members suitable roles, and pin up to **three common facilities per world** from project details. Mayors and town managers can change pins; favorites stay personal. Use **Copy invite** to share the town ID, Minecraft server and town service together. Password-protected towns still require their password.

<details>
<summary>More project and town options</summary>

- Search by project name, owner or description; filter favorites, types, your projects or repairs. Toggle the map layer without changing records.
- Edit a project's status and instructions, report repairs, or complete all outstanding repairs together. To resize, choose **Resize area**, drag the new full area and confirm; conflicting projects are named.
- Deleting the mod's temporary Xaero waypoint also stops navigation. Directions use small centered text; entering a project briefly shows its status first.
- Client Settings shows all options together, with your UUID below the service address. The project-key hold threshold is `projectKeyHoldMillis` in `config/yetanothertown.json` (600 ms by default, 200–3000 ms).
- Use **Switch town** below **Client Settings** on the home page. Each player UUID can create one town per service and join others. Town names allow 12 units: ASCII counts as one, other characters as two.
- Towns match the Minecraft server address; different IPs and domain names remain separate. Returning to a server restores its last selected town.
- Manage historical worlds under **Manage**. Mayors can delete inactive worlds and their records after choosing another current world; Minecraft terrain is unaffected.

</details>

## Host a town service

The `server/` directory is temporarily excluded from the current repository and release. Self-hosting files for v1.0.0d are currently unavailable. Use the public service at `https://server.moesam.com`, or a compatible service supplied by your administrator.

Client and service changes must be kept in sync for server-specific towns, project status editing and pinned facilities. The older Beta service does not provide all v1.0.0d features. Existing hosts should retain their configuration and data backups.

See [Server Deployment](https://github.com/afterSuperSam/YetAnotherTown/wiki/Server-Deployment) for the current hosting status. Earlier commits may still contain service source; removing this directory does not erase repository history.

## Good to know

- Designed for trusted communities: player UUIDs are read from the game, not independently authenticated.
- Project areas are markers, not protected claims. The mod does not prevent block changes.
- Terrain stays in each player’s local Xaero maps; the service only shares town and project records.
- Everyone should connect using the same server address. Different IPs or domain names are treated separately.
