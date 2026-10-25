# Changelog

## v1.0.0d

Changes since v1.0.0beta.

### Projects and navigation

- Save favorites on this device; search projects and filter by type, favorites, ownership or maintenance needs.
- Resize project areas with conflict details while preserving project information and repair history.
- Read expanded hover descriptions and distinguish neighboring areas with hover outlines.
- Navigate from the map's project menu. Tap the project key (X by default) to view the navigation target or the project beneath you; hold it to stop navigation.
- Use compact centered directions and colored project status on entry. Removing the temporary Xaero waypoint also stops navigation.
- Report repairs in a compact dialog and complete a project's outstanding repairs together.

### Towns and interface

- A compact sidebar, responsive project preview, resource-pack-aware buttons and clearer loading feedback.
- Requests preserve input on failure; pages open without automatically focusing a control.
- New installations default to the public town service at `https://server.moesam.com`; saved custom addresses are preserved.
- Copy town invitations, configure the current map and pin up to three common facilities per world with town-management permissions.
- Remember the selected town for each Minecraft server; towns and sessions are scoped to the server address.
- Each player UUID can create one town per service and join other towns. Mayors can delete inactive worlds and their project and repair records.
- Updated English and Simplified Chinese translations.

### Requirements and compatibility

- One Fabric client JAR for Minecraft 26.1.2, 26.2 and 26.3; Java 25+ is required.
- Fabric Loader 0.19.3+ is declared; 0.19.5 is the automated check baseline. Fabric API, Xaero's World Map and Xaero's Minimap are required; Mod Menu is optional.
- Expanded checks to 6,224 dependency candidates: 2,214 satisfy dependency metadata and 4,010 are excluded by declared conflicts. Interface coverage includes 153 compatible Xaero pairs and all 38 Minecraft-specific Fabric API builds.
- World Map 1.47.1 and Minimap 26.6.1 must be updated together. See the README for the exact checked versions and pairing restrictions.

### Town service availability

- The `server/` directory is temporarily excluded from this version's repository and release. Self-hosting files for v1.0.0d are currently unavailable; earlier source remains in Git history.
- Use the public town service or a compatible service supplied by your administrator. Older Beta services do not support every new feature; existing hosts should preserve their configuration and data.
- See the [Server Deployment wiki](https://github.com/afterSuperSam/YetAnotherTown/wiki/Server-Deployment) for hosting status.

Automated checks do not establish in-game behavior or production load performance.
