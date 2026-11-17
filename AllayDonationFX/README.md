# Allay Donation FX (Fabric 26.2)

Client-side Fabric mod prototype for AllaySMP payment notifications.

## Detection

Watches server chat/system messages for the pattern shown by the user:

`PAY <player> paid you $<amount>`

The mod does not send commands, interact with the server economy, or require installation on the server.

## Effect tiers

- `$10,000+`: side popup
- `$100,000+`: small explosion-style HUD effect
- `$1,000,000+`: black-hole-style HUD effect
- `$10,000,000+`: cinematic letterbox/title HUD effect
- Any amount containing the digits `67`: a bouncing green `67` overlay is stacked over the tier effect (e.g. `$67K`, `$6.7M`)

Only the highest matching tier is selected.

## Important prototype limitations

This is a starter source project, not a tested release JAR. The current rendering is 2D HUD animation; the requested true 3D black hole and 3D cutscene are not implemented yet. AllaySMP's notification format was matched from the screenshot, but the parser must be tested in-game against actual payment messages.

## Build requirements

- Java 25 JDK
- Gradle 9.5.1
- Fabric Loom 1.17
- Minecraft 26.2 / Fabric Loader 0.19.x / Fabric API 0.158.0+26.2

Build from this directory with:

```bash
gradle build
```

The output JAR, if the project compiles successfully with the matching toolchain, will be in `build/libs/`.
