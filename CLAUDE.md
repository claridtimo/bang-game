# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The source code and media for Bang! Howdy, a western-themed multiplayer tactics MMO created
by Three Rings Design in 2004. It is a partially-completed port to libGDX; most rendering still
goes through a forked jMonkeyEngine/LWJGL layer. Code is BSD-licensed; media (everything under
`assets/rsrc/`) is CC Attribution-NonCommercial.

## Build and run

Built with Gradle 8.14 (via the checked-in wrapper) on a JDK 21 toolchain. Maven `pom.xml`
files also exist per module but they are stale; Gradle is the build.

```sh
./gradlew deploy                  # build everything into build/client, build/server, build/assets
./gradlew test                    # run unit tests
./gradlew client:shared:test --tests '*RatingUnitTest'   # run a single test class
./gradlew server:createTestUser   # create user `test` / password `yeehaw` in the user DB
```

Local setup before running:
1. Copy each `etc/*.dist` file to `etc/test/<name-without-.dist>` (e.g. `etc/deployment.properties.dist`
   → `etc/test/deployment.properties`). The `processResources` tasks bake `etc/${deployment}/` files
   into the built jars; `deployment` defaults to `test` (set in the root `build.gradle`).
2. The server needs MySQL: create `bang` and `ooouser` databases and set
   `db.default.*` / `db.userdb.url` / `db.sitedb.url` in `etc/test/server.properties`.

Run via the shell scripts in `bin/`:
- `./bin/bangclient` — dev client (flags: `-test=<board>`, `-autoplay`, `-tutorial=<name>`, `-go=<where>`)
- `./bin/bangserver` — server (creates DB tables on first run, listens on port 47624)
- `./bin/bangeditor` — board editor

## Module structure

Gradle modules (see `settings.gradle`), in dependency order:

- `jme` — forked jMonkeyEngine core over LWJGL 2.9, with libGDX pulled in for the in-progress port
- `app` — `com.threerings.jme.*`: scenegraph/sprite/model/camera framework on top of `jme`
- `bui` — BUI, the in-game UI toolkit (depends on `jme`)
- `client/shared` — the bulk of all game code (`com.threerings.bang.*`), client AND server logic
- `client/desktop` — thin LWJGL/libGDX launchers: `BangDesktop` (client), `EditorDesktop`
- `client/getdown` — Getdown auto-updater installer stub
- `server` — `BangServer` entry point plus persistence (`server/persist`, Depot ORM, MySQL)
- `assets` — all media under `rsrc/`, plus heavyweight resource-processing tasks (model
  compilation, avatar component bundles, XML config compilation) run as part of `processResources`
- `tools` — build-time ant tasks

## Architecture

The game is built on Three Rings' Narya "Presents" distributed-object framework (deps:
`com.threerings:narya`, `nenya`, `vilya`, `com.samskivert:samskivert`, `com.samskivert:depot`).
Understanding the Presents pattern is essential to navigating this code:

- Each game area ("place") lives in a package under `com.threerings.bang.*` (e.g. `saloon`,
  `store`, `ranch`, `gang`, `bounty`, `station`, `avatar`, and `game` for the actual board game),
  each split into `client/`, `data/`, and `server/` subpackages.
- `data/` holds `*Object` distributed objects (shared client/server state, auto-synced),
  `*Service` interfaces (client→server RPC), `*Marshaller`/`*Codes` classes.
- `server/` holds `*Manager` (place lifecycle) and `*Provider` (service implementations).
- `*Marshaller`, `*Dispatcher`, and `*Provider` stubs are **generated** from the `*Service`
  interfaces by the `client/shared` `genService` task — don't edit generated classes by hand;
  re-run `./gradlew client:shared:genService` after changing a `*Service` interface.

Note that most server-side game logic lives in `client/shared` (in the `*/server` subpackages),
not in the `server` module — the `server` module mainly adds persistence and the
`BangServer` bootstrap. Core gameplay (board game rules, effects, AI) is in
`com.threerings.bang.game.{client,data,server}` within `client/shared`.

The game world is divided into "towns" (`frontier_town`, `indian_post` — listed in the root
`build.gradle` `towns` ext property). Asset jars and avatar bundles are built per-town, and the
server runs in cluster mode as one node per town.

Resource pipeline: `assets/rsrc/` contains XML definitions (tutorials, avatar articles/aspects,
color definitions) that get compiled to serialized binary form, 3D models compiled from
`model.properties` files, and avatar component PNGs bundled into jars — all wired into
`assets:processResources`. Asset changes generally require re-running `./gradlew assets:deploy`.
