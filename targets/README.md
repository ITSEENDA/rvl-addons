# Platform targets

Each directory under `targets` is a standalone loader and Minecraft-version target.
Keep loader APIs, Minecraft classes, mappings, metadata, datagen, and mixins inside the target.

To add another target:

1. Create a directory such as `targets/fabric/1.21.1` or `targets/neoforge/1.21.1`.
2. Add its project path in `settings.gradle`.
3. Add a matching version file under `gradle/versions`.
4. Keep the target's metadata in its own resources directory.
5. Depend on `:common` and include it in the final mod jar.

The `common` project must stay free of Minecraft, loader, and Mixin imports so it can be reused by every target.

## Trace mode

The Fabric targets register `F8` as the trace toggle and `F9` as the RVL Addons feature toggle. Both keys can be remapped from Minecraft's Controls menu.

Trace output is appended to `run/logs/rvl-addons-trace.log` and includes outgoing commands/chat, inbound/outbound packets, and handled-screen slot clicks. Packet fields can contain chat or connection data, so only share the trace file after reviewing it.

Features are active only after a `GameJoinS2CPacket` contains a dimension key matching `minecraft:rvl...`. Other backends are ignored.

## Build to a custom directory

Build one target:

```powershell
.\gradlew.bat buildToPath -Ptarget=fabric_1_20_4 -PdistDir="D:\\MinecraftTest\\mods"
```

Build every registered target:

```powershell
.\gradlew.bat buildToPath -Ptarget=all -PdistDir="D:\\MinecraftTest\\mods"
```

If `distDir` is omitted, artifacts are copied to `dist/` at the repository root.
