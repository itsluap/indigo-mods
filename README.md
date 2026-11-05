# Indigo mods (source)

Source code for the two small mods that the [Indigo modpack on Modrinth](https://modrinth.com/modpack/indigomc)
bundles in its `overrides/mods` folder. Both are written by the Indigo team for the Indigo community server
(Minecraft Java 1.21.1, NeoForge 21.1.248) and are MIT licensed.

| Mod | Version in the pack | What it does |
|---|---|---|
| `indigo_client` | 0.4.3 | Client-only, one class. On an instance's first launch it switches on the bundled Faithful 32x texture packs, writes a marker file in `config/`, and never touches resource packs again. No networking. |
| `indigo_economy` | 0.5.4 | Account-based Indigo balance keyed to the Minecraft UUID, an in-game wallet screen, and Indigo Credits pricing for Lightman's Currency shops. Its only network listener is an optional HMAC-signed bridge for Indigo's Discord bot. The bridge binds 127.0.0.1 and is off unless the server enables it. Casino tables are present but switched off in 0.5.4. On the client it only draws screens and talks to the server the player joined. |

## Matches the jars in Indigo 0.6.0

This tree is the exact source snapshot used to build the jars in the Modrinth pack version **Indigo 0.6.0**.
Rebuilding it gives the same files:

| Jar in the pack | sha256 of the shipped jar | Rebuild from this source |
|---|---|---|
| `indigo_client-0.4.3.jar` | `76729e03e792c82f2dc0c6da73abe230c68093ebea163b4c7027b072b733ff24` | Byte-identical jar |
| `indigo_economy-0.5.4.jar` | `2daf0f739995d4521d4f32ef01cb95e083702b7b70ef7335b1aac4d2a8452434` | All 111 class and asset entries identical. `META-INF/neoforge.mods.toml` differs only in line endings (CRLF in the shipped jar). |

Build commands (Python 3 and JDK 21):

```
python scripts/indigo-client-build.py build
python scripts/economy-build.py build
```

The economy build compiles against NeoForge 21.1.248, Gson 2.10.1 and Lightman's Currency
`lightmanscurrency-1.21-2.3.0.5.jar`. It looks for them in a Prism Launcher library folder and in a local
`.work/` runtime, as in the Indigo workspace it comes from.

## Made with AI help

Most of this code was written with AI coding assistants. The Indigo team directed and reviewed the work and tested it
on a disposable test server before release.

## License

MIT. See `extras/indigo-client/LICENSE` and `extras/indigo-economy/LICENSE`.
