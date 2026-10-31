---
name: battleaudio-workshop-publisher
description: Validate and stage a BattleAudio music-pack export for the Slay the Spire Steam Workshop, then optionally run the game's bundled mod uploader after the user confirms the exact publication.
metadata:
  short-description: Prepare or publish BattleAudio music packs
---

# BattleAudio Workshop publishing

Use this skill when the user wants help publishing or updating a music pack they created with BattleAudio. The game export is the source of truth; do not edit the user's live `BetterCustomAudioTriggers` settings or source files. If the selected package is subscribed, ask the user to choose **Copy as new music pack** first.

## Workflow

1. Find the selected pack's export folder. The game's **Export for Workshop** action writes a new, non-overwriting snapshot under the game directory's `BetterCustomAudioTriggers\workshop-exports\<contentId>\...` and opens that folder. A valid snapshot has `music-pack.json` plus only package-owned media listed in its `files` array. If the user has not exported yet, ask them to use that game action; do not reconstruct or rewrite their settings.
2. Locate the Slay the Spire install containing `mod-uploader.jar`; pass `-GameDir` if automatic Steam library discovery fails. The helper searches registered Steam libraries.
3. Run `scripts/prepare-workshop.ps1` in prepare-only mode first. It checks paths, file sizes and SHA-256 values, confirms every rule media reference is listed, then creates or refreshes an isolated uploader workspace. It never uploads unless `-Upload` is explicitly supplied.
4. Explain the proposed action, Workshop ID (or that a new item will be created), title, visibility, package version, file count/size, and generated description/link. New items default to **Private**. Preserve an existing item's visibility unless the user asks to change it. This is an independent music-pack Workshop item, not a Steam Collection.
5. Before any upload or update, obtain explicit confirmation for that exact target and visibility in the current conversation. Then run the helper with `-Upload`. Do not silently make an item public. If a failed new-item attempt wrote `steamPublishedID` into `config.json`, reuse it rather than creating a duplicate.
   The game uploader creates a default `image.jpg`. Tell the user whether that default or a chosen cover will be used. For a custom cover, copy the user-selected JPEG to `image.jpg` in the printed workspace, then visually inspect it before asking final confirmation; the next prepare run leaves this preview image untouched.
6. Steam must be running and signed into the correct account. The user may need to accept the Workshop License Agreement in a browser and complete Steam Guard. Never ask for Steam credentials. If the result is ambiguous, preserve the workspace, verify the Workshop page, and retry against the same saved ID.

## Safety

The helper refuses unsafe paths, links, manifest/hash mismatches, unexpected files, package identity mismatches, and unowned workspaces. Before replacing managed workspace content it preserves a dated copy; it never deletes old exports or backups. The uploader workspace is separate from the in-game package, and updates replace its managed content without merging. Report only what uploader output and the Workshop page verify. If local game files are unavailable, finish validation and explain that upload must be done locally. Packaging validation does not establish rights to publish third-party audio.
