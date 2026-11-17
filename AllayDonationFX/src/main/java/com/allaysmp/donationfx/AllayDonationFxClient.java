package com.allaysmp.donationfx;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.ChatType;
import com.mojang.authlib.GameProfile;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allay Donation FX - client-side prototype.
 * Detects AllaySMP's server-side "PAY <name> paid you $<amount>" messages.
 * It never sends commands or changes server data.
 */
public final class AllayDonationFxClient implements ClientModInitializer {
    private static final Pattern PAYMENT = Pattern.compile(
            "(?is)\\bPAY\\s+(.+?)\\s+paid you\\s+\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)([KMB]?)\\b"
    );
    private static volatile Effect active = Effect.NONE;
    private static volatile boolean has67 = false;
    private static volatile long startedAt = 0L;
    private static volatile String amountLabel = "";
    private static volatile String donorName = "";
    private static volatile boolean fakePayment = false;
    private static volatile String fakeColour = "";

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((Component message, boolean overlay) -> detect(message));
        ClientReceiveMessageEvents.CHAT.register((Component message, PlayerChatMessage playerChatMessage, GameProfile sender, ChatType.Bound boundChatType, java.time.Instant receptionTimestamp) -> detect(message));
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("allaydonationfx", "effects"),
                (graphics, deltaTracker) -> render(graphics)
        );
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("fakepay")
                        .then(ClientCommands.argument("amount", StringArgumentType.word())
                                .executes(context -> runFakePay(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "amount"),
                                        "gold"
                                ))
                                .then(ClientCommands.argument("colour", StringArgumentType.word())
                                        .executes(context -> runFakePay(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "amount"),
                                                StringArgumentType.getString(context, "colour")
                                        ))
                                )
                        )
                )
        );
    }

    private static void detect(Component message) {
        String raw = message.getString();
        Matcher matcher = PAYMENT.matcher(raw);
        if (!matcher.find()) return;

        donorName = matcher.group(1).trim().replaceAll("\\s+", " ");
        String numeric = matcher.group(2).replace(",", "");
        String suffix = matcher.group(3).toUpperCase(Locale.ROOT);
        double amount;
        try {
            amount = Double.parseDouble(numeric);
            amount *= switch (suffix) {
                case "K" -> 1_000d;
                case "M" -> 1_000_000d;
                case "B" -> 1_000_000_000d;
                default -> 1d;
            };
        } catch (NumberFormatException ignored) {
            return;
        }

        // Keep punctuation out of the Easter-egg test: $6.7M becomes "67".
        String digitsOnly = matcher.group(2).replaceAll("[^0-9]", "");
        has67 = digitsOnly.contains("67");
        if (amount >= 100_000_000d) active = Effect.DRAGON;
        else if (amount >= 10_000_000d) active = Effect.CINEMATIC;
        else if (amount >= 1_000_000d) active = Effect.BLACK_HOLE;
        else if (amount >= 100_000d) active = Effect.EXPLOSION;
        else if (amount >= 10_000d) active = Effect.SIDE_POPUP;
        else active = Effect.NONE;

        if (active != Effect.NONE || has67) {
            amountLabel = "$" + matcher.group(2) + suffix;
            fakePayment = false;
            fakeColour = "";
            startedAt = System.currentTimeMillis();
            playDonationSound(active);
        }
    }

    private static void render(GuiGraphicsExtractor g) {
        Effect effect = active;
        boolean meme = has67;
        if (effect == Effect.NONE && !meme) return;

        long elapsed = System.currentTimeMillis() - startedAt;
        long effectDuration = effect == Effect.NONE ? 2_500L
                : (effect == Effect.DRAGON ? 8_000L
                : (effect == Effect.SIDE_POPUP ? 6_000L
                : (effect == Effect.CINEMATIC ? 5_500L : 4_000L)));
        if (elapsed > effectDuration) {
            active = Effect.NONE;
            has67 = false;
            fakePayment = false;
            fakeColour = "";
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        float t = Math.min(1f, elapsed / 4000f);
        int donorColor = donorColor(effect, elapsed);

        // The current starter build uses 2D HUD animation. The 3D cinematic is a planned upgrade.
        if (effect == Effect.SIDE_POPUP) {
            int x = (int) (-210 + Math.min(1f, elapsed / 500f) * 230);
            drawTierRim(g, x + 102, height / 3 + 26, 213, 60, 1, fakePayment ? donorColor : 0xFF36D6A3);
            roundedFill(g, x + 4, height / 3 + 4, x + 201, height / 3 + 48, 7, 0xF0202630);
            roundedFill(g, x + 7, height / 3 + 7, x + 9, height / 3 + 45, 1, fakePayment ? donorColor : 0xFF36D6A3);
            g.text(mc.font, "PAYMENT RECEIVED", x + 15, height / 3 + 7, 0xFFFFFFFF, true);
            g.text(mc.font, amountLabel, x + 15, height / 3 + 20, 0xFF65FFC5, true);
            g.text(mc.font, donorName, x + 15, height / 3 + 35, donorColor, true);
        } else if (effect == Effect.EXPLOSION) {
            float pulse = (float) Math.sin(elapsed / 90.0) * 0.5f + 0.5f;
            int r = (int) (22 + pulse * 18);
            drawTierRim(g, width / 2, height / 2 + 1, 190, 62, 2, fakePayment ? donorColor : 0xFFFF9638);
            roundedFill(g, width / 2 - 91, height / 2 - 29, width / 2 + 91, height / 2 + 29, 9, 0xE51D100B);
            g.fill(width / 2 - r, height / 2 - r, width / 2 + r, height / 2 + r, fakePayment ? withAlpha(donorColor, 0x66) : 0x66FF8A24);
            g.text(mc.font, "BOOM!  " + amountLabel, width / 2 - 55, height / 2 - 12, 0xFFFFD36A, true);
            g.text(mc.font, donorName, width / 2 - mc.font.width(donorName) / 2, height / 2 + 5, donorColor, true);
        } else if (effect == Effect.BLACK_HOLE) {
            int radius = (int) (18 + Math.sin(elapsed / 180.0) * 5);
            drawTierRim(g, width / 2, height / 2 + radius + 22, 218, 54, 3, fakePayment ? donorColor : 0xFFB477FF);
            roundedFill(g, width / 2 - 105, height / 2 + radius - 3, width / 2 + 105, height / 2 + radius + 48, 10, 0xE5100B1D);
            g.fill(width / 2 - radius * 2, height / 2 - radius * 2, width / 2 + radius * 2, height / 2 + radius * 2, 0x331B0B38);
            g.fill(width / 2 - radius, height / 2 - radius, width / 2 + radius, height / 2 + radius, 0xFF080512);
            g.fill(width / 2 - radius - 5, height / 2 - 2, width / 2 + radius + 5, height / 2 + 2, fakePayment ? withAlpha(donorColor, 0xBB) : 0xBB9D65FF);
            g.text(mc.font, "BLACK HOLE  " + amountLabel, width / 2 - 65, height / 2 + radius + 14, 0xFFDCCBFF, true);
            g.text(mc.font, donorName, width / 2 - mc.font.width(donorName) / 2, height / 2 + radius + 27, donorColor, true);
        } else if (effect == Effect.DRAGON) {
            // A red dragon sweeps across the HUD, then drops a rainbow-rimmed payment card.
            float progress = Math.min(1f, elapsed / 4_800f);
            int dragonX = (int) (-190 + progress * (width + 380));
            int dragonY = (int) (height * 0.24f + Math.sin(elapsed / 150.0) * 13);
            drawRedDragon(g, dragonX, dragonY, elapsed);
            if (elapsed > 900L) {
                float drop = Math.min(1f, (elapsed - 900L) / 650f);
                int cardY = (int) (-76 + drop * (height * 0.64f + 76));
                float pulse = (float) (0.5 + 0.5 * Math.sin(elapsed / 190.0));
                int cardWidth = Math.min(360, width - 32);
                int cardHeight = 88;
                drawRainbowRim(g, width / 2, cardY + cardHeight / 2, cardWidth, cardHeight, elapsed);
                roundedFill(g, width / 2 - cardWidth / 2 + 5, cardY + 5,
                        width / 2 + cardWidth / 2 - 5, cardY + cardHeight - 5, 12, 0xF20B101B);
                String title = "DRAGON DROP!";
                g.text(mc.font, title, width / 2 - mc.font.width(title) / 2, cardY + 14, 0xFFFF6A55, true);
                g.text(mc.font, amountLabel, width / 2 - mc.font.width(amountLabel) / 2,
                        cardY + 34, 0xFFFFFFFF, true);
                g.text(mc.font, donorName, width / 2 - mc.font.width(donorName) / 2,
                        cardY + 55, 0xFFFFC5B8, true);
                int shineX = width / 2 - cardWidth / 2 + (int) ((elapsed * 0.55f) % cardWidth);
                g.fill(shineX, cardY + 7, shineX + 2, cardY + cardHeight - 7,
                        withAlpha(0xFFFFFFFF, (int) (30 + pulse * 35)));
            }
        } else if (effect == Effect.CINEMATIC) {
            // A dramatic, layered 3D-style HUD cinematic: expanding perspective frames,
            // an extruded amount title, sweeping light streaks and cinematic letterboxing.
            float intro = Math.min(1f, elapsed / 850f);
            float pulse = (float) (0.5 + 0.5 * Math.sin(elapsed / 170.0));
            int bar = (int) (height * 0.15f * intro);
            int accent = fakePayment ? donorColor : 0xFFFFD76A;
            g.fill(0, 0, width, bar, 0xFF050509);
            g.fill(0, height - bar, width, height, 0xFF050509);
            g.fill(0, 0, width, height, 0x22000000);

            // Nested frames expand toward the viewer to create a tunnel/depth effect.
            for (int i = 0; i < 5; i++) {
                int inset = 18 + i * 17 + (int) (pulse * 5);
                int frameColor = withAlpha(accent, 95 - i * 13);
                int thickness = 2;
                g.fill(inset, height / 2 - 48 - i * 10, width - inset, height / 2 - 48 - i * 10 + thickness, frameColor);
                g.fill(inset, height / 2 + 48 + i * 10, width - inset, height / 2 + 48 + i * 10 + thickness, frameColor);
                g.fill(inset, height / 2 - 48 - i * 10, inset + thickness, height / 2 + 48 + i * 10, frameColor);
                g.fill(width - inset - thickness, height / 2 - 48 - i * 10, width - inset, height / 2 + 48 + i * 10, frameColor);
            }
            // Light streaks sweep in from the edges.
            int sweep = (int) ((elapsed * 1.4f) % Math.max(1, width));
            g.fill(sweep - 90, height / 2 - 2, sweep + 90, height / 2 + 2, withAlpha(accent, 110));
            g.fill(width - sweep - 90, height / 2 + 34, width - sweep + 90, height / 2 + 37, withAlpha(accent, 75));

            String headline = "DONATION RECEIVED";
            g.text(mc.font, headline, width / 2 - mc.font.width(headline) / 2, height / 2 - 58, 0xFFFFFFFF, true);

            // Offset copies create an extruded, 3D-style amount title.
            float titleScale = 1.8f + pulse * 0.12f;
            g.pose().pushMatrix();
            g.pose().translate(width / 2f, height / 2f - 15f);
            g.pose().scale(titleScale, titleScale);
            g.pose().translate(-width / 2f, -(height / 2f - 15f));
            int amountX = width / 2 - mc.font.width(amountLabel) / 2;
            for (int depth = 7; depth >= 1; depth--) {
                g.text(mc.font, amountLabel, amountX + depth, height / 2 - 15 + depth, 0xFF6B3D12, false);
            }
            g.text(mc.font, amountLabel, amountX, height / 2 - 15, accent, true);
            g.pose().popMatrix();

            g.text(mc.font, donorName, width / 2 - mc.font.width(donorName) / 2, height / 2 + 35, donorColor, true);
            String subline = "ALLAYSMP • LEGENDARY PAYMENT";
            g.text(mc.font, subline, width / 2 - mc.font.width(subline) / 2, height / 2 + 49, 0xFFD8D8E8, true);
        }

        if (fakePayment) {
            String fakeLabel = "(Fake payment - local preview only)";
            g.text(mc.font, fakeLabel, width / 2 - mc.font.width(fakeLabel) / 2, (int) (height * 0.78f), 0xFFFF7777, true);
        }

        // 67 Easter egg: small, calm badge tucked into the bottom-right corner.
        if (meme) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(elapsed / 420.0));
            boolean solo67 = effect == Effect.NONE;
            int x = width - 76;
            int y = height - (solo67 ? 72 : 48);
            int glow = fakePayment ? withAlpha(donorColor, (int) (38 + pulse * 24))
                    : (solo67 ? 0x553B9DFF : 0x554DCE65);
            int panel = solo67 ? 0xE5102948 : 0xE5122B17;
            int edge = solo67 ? 0xFF65B9FF : 0xFF91E76A;
            roundedFill(g, x - 37, y - 22, x + 37, y + 22, 10, glow);
            roundedFill(g, x - 34, y - 19, x + 34, y + 19, 8, panel);
            roundedFill(g, x - 30, y - 16, x + 30, y + 16, 6, withAlpha(edge, 45));
            String small67 = "67";
            g.text(mc.font, small67, x - mc.font.width(small67) / 2, y - 8,
                    fakePayment ? donorColor : edge, true);
            if (solo67) {
                g.text(mc.font, amountLabel, x - mc.font.width(amountLabel) / 2, y + 5, 0xFFFFFFFF, true);
            }
        }
    }

    private static void drawRedDragon(GuiGraphicsExtractor g, int x, int y, long elapsed) {
        // Stylised red Ender-Dragon-inspired silhouette with layered shading and articulated wings.
        int flap = (int) (Math.sin(elapsed / 145.0) * 18);
        int highlight = 0xFFFF6658;
        int red = 0xFFE52D35;
        int mid = 0xFFC51E32;
        int dark = 0xFF751326;
        int deepest = 0xFF3B1024;
        int membrane = 0xFF9F203C;
        int membraneLight = 0xFFD33A4B;

        // Long tapering tail, with a shaded ridge and pointed-looking stepped tip.
        g.fill(x - 126, y - 4, x - 103, y + 3, deepest);
        g.fill(x - 111, y - 1, x - 82, y + 7, dark);
        g.fill(x - 91, y + 2, x - 57, y + 11, mid);
        g.fill(x - 72, y + 5, x - 39, y + 14, red);
        g.fill(x - 104, y - 3, x - 80, y + 1, highlight);

        // Rear wing: broad swept shape, animated up and down, with dark outer edge.
        int rearY = y - 8 - flap / 2;
        g.fill(x - 47, rearY - 31, x - 36, rearY + 5, deepest);
        g.fill(x - 45, rearY - 29, x - 8, rearY - 20, dark);
        g.fill(x - 37, rearY - 21, x + 1, rearY - 10, membrane);
        g.fill(x - 28, rearY - 12, x + 12, rearY - 2, membraneLight);
        g.fill(x - 42, rearY - 29, x - 35, rearY - 4, red);
        g.fill(x - 12, rearY - 19, x - 5, rearY - 1, dark);
        g.fill(x + 2, rearY - 9, x + 8, rearY + 2, dark);

        // Main wing: high arched shoulder, long finger-like spars and red membrane.
        int wingY = y - 12 - flap;
        g.fill(x - 26, wingY - 42, x - 13, wingY + 4, deepest);
        g.fill(x - 22, wingY - 39, x + 5, wingY - 31, dark);
        g.fill(x - 15, wingY - 31, x + 23, wingY - 22, membrane);
        g.fill(x - 8, wingY - 22, x + 36, wingY - 13, membraneLight);
        g.fill(x + 2, wingY - 13, x + 42, wingY - 5, dark);
        g.fill(x - 19, wingY - 37, x - 13, wingY - 7, red);
        g.fill(x + 3, wingY - 29, x + 9, wingY - 3, red);
        g.fill(x + 25, wingY - 19, x + 31, wingY - 2, red);
        g.fill(x + 37, wingY - 11, x + 43, wingY - 1, deepest);
        g.fill(x - 19, wingY - 40, x - 10, wingY - 35, highlight);

        // Torso is built from overlapping shaded plates to give it volume.
        g.fill(x - 55, y - 5, x + 31, y + 23, deepest);
        g.fill(x - 51, y - 12, x + 26, y + 15, dark);
        g.fill(x - 45, y - 13, x + 23, y + 10, mid);
        g.fill(x - 37, y - 12, x + 14, y + 3, red);
        g.fill(x - 34, y - 13, x + 7, y - 8, highlight);
        g.fill(x - 27, y + 9, x + 23, y + 17, deepest);
        // Raised back spines.
        for (int i = 0; i < 5; i++) {
            int sx = x - 31 + i * 12;
            g.fill(sx, y - 20 + (i % 2) * 3, sx + 6, y - 10, dark);
            g.fill(sx + 1, y - 19 + (i % 2) * 3, sx + 4, y - 13, highlight);
        }
        // Belly plates.
        for (int i = 0; i < 4; i++) {
            int sx = x - 21 + i * 12;
            g.fill(sx, y + 12, sx + 9, y + 17, 0xFFED5960);
            g.fill(sx + 1, y + 17, sx + 8, y + 19, dark);
        }

        // Long neck rises into a dragon-shaped angular head.
        g.fill(x + 16, y - 15, x + 48, y + 5, deepest);
        g.fill(x + 19, y - 20, x + 47, y + 1, mid);
        g.fill(x + 22, y - 20, x + 43, y - 5, red);
        g.fill(x + 38, y - 24, x + 69, y - 8, dark);
        g.fill(x + 42, y - 22, x + 72, y - 11, red);
        g.fill(x + 57, y - 17, x + 79, y - 9, mid);
        g.fill(x + 68, y - 15, x + 81, y - 10, deepest);
        // Brow, horns, glowing eye and jaw highlight.
        g.fill(x + 47, y - 29, x + 54, y - 18, deepest);
        g.fill(x + 61, y - 27, x + 68, y - 17, deepest);
        g.fill(x + 49, y - 22, x + 55, y - 18, highlight);
        g.fill(x + 57, y - 18, x + 62, y - 14, 0xFFFFF0A0);
        g.fill(x + 62, y - 17, x + 66, y - 14, 0xFFFFFFFF);
        g.fill(x + 54, y - 8, x + 75, y - 5, 0xFFFF8B79);

        // Far legs and near legs, shaded to imply depth.
        g.fill(x - 26, y + 14, x - 18, y + 34, deepest);
        g.fill(x - 23, y + 14, x - 15, y + 31, mid);
        g.fill(x + 8, y + 12, x + 17, y + 31, deepest);
        g.fill(x + 10, y + 12, x + 18, y + 28, red);
        g.fill(x - 33, y + 30, x - 12, y + 35, dark);
        g.fill(x + 4, y + 27, x + 23, y + 32, dark);
        g.fill(x - 31, y + 30, x - 24, y + 32, highlight);
        g.fill(x + 7, y + 27, x + 13, y + 29, highlight);

        // Ember trail behind the dragon.
        g.fill(x - 154, y + 1, x - 127, y + 8, 0xCCFF542E);
        g.fill(x - 170, y - 4, x - 148, y + 3, 0x99FFD34D);
        g.fill(x - 137, y + 10, x - 121, y + 14, 0x99FF392B);
    }

    private static void drawRainbowRim(GuiGraphicsExtractor g, int centerX, int centerY, int width, int height, long elapsed) {
        int left = centerX - width / 2;
        int top = centerY - height / 2;
        int right = centerX + width / 2;
        int bottom = centerY + height / 2;
        int[] rainbow = {0xFFFF4545, 0xFFFFA52F, 0xFFFFFF45, 0xFF4DFF83, 0xFF43C9FF, 0xFF8A62FF, 0xFFFF5BD6};
        int shift = (int) (elapsed / 95L);
        int thickness = 5;
        for (int i = 0; i < rainbow.length; i++) {
            int color = rainbow[(i + shift) % rainbow.length];
            int segmentLeft = left + (width * i / rainbow.length);
            int segmentRight = left + (width * (i + 1) / rainbow.length);
            g.fill(segmentLeft, top, segmentRight, top + thickness, color);
            g.fill(segmentLeft, bottom - thickness, segmentRight, bottom, color);
            int segmentTop = top + (height * i / rainbow.length);
            int segmentBottom = top + (height * (i + 1) / rainbow.length);
            g.fill(left, segmentTop, left + thickness, segmentBottom, color);
            g.fill(right - thickness, segmentTop, right, segmentBottom, color);
        }
        roundedFill(g, left + thickness, top + thickness, right - thickness, bottom - thickness,
                10, 0xF20B101B);
    }

    private static void drawTierRim(GuiGraphicsExtractor g, int centerX, int centerY, int width, int height, int tier, int accent) {
        int halfW = width / 2;
        int halfH = height / 2;
        int radius = tier == 1 ? 8 : (tier == 2 ? 11 : 14);
        int thickness = tier == 1 ? 2 : (tier == 2 ? 3 : 4);
        roundedFill(g, centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH, radius, withAlpha(accent, tier == 1 ? 210 : 235));
        roundedFill(g, centerX - halfW + thickness, centerY - halfH + thickness,
                centerX + halfW - thickness, centerY + halfH - thickness,
                Math.max(2, radius - thickness), 0xEE10131D);
        // Higher tiers get extra inset highlights to make the frame feel more premium.
        if (tier >= 2) {
            roundedFill(g, centerX - halfW + 7, centerY - halfH + 5,
                    centerX + halfW - 7, centerY - halfH + 7, 3, withAlpha(accent, 175));
            roundedFill(g, centerX - halfW + 7, centerY + halfH - 7,
                    centerX + halfW - 7, centerY + halfH - 5, 3, withAlpha(accent, 135));
        }
        if (tier >= 3) {
            roundedFill(g, centerX - halfW + 5, centerY - halfH + 12,
                    centerX - halfW + 8, centerY + halfH - 12, 2, withAlpha(accent, 180));
            roundedFill(g, centerX + halfW - 8, centerY - halfH + 12,
                    centerX + halfW - 5, centerY + halfH - 12, 2, withAlpha(accent, 180));
        }
    }

    private static void roundedFill(GuiGraphicsExtractor g, int left, int top, int right, int bottom, int radius, int color) {
        int r = Math.max(1, Math.min(radius, Math.min((right - left) / 2, (bottom - top) / 2)));
        for (int y = top; y < bottom; y++) {
            int dy = y < top + r ? top + r - y : (y >= bottom - r ? y - (bottom - r - 1) : 0);
            int inset = 0;
            if (dy > 0) {
                double inside = (double) r * r - (double) (r - dy) * (r - dy);
                inset = r - (int) Math.sqrt(Math.max(0, inside));
            }
            g.fill(left + inset, y, right - inset, y + 1, color);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static int donorColor(Effect effect, long elapsed) {
        if (fakePayment) {
            return switch (fakeColour) {
                case "red" -> 0xFFFF5555;
                case "blue" -> 0xFF55AAFF;
                case "green" -> 0xFF55FF77;
                case "crimson" -> 0xFFDC143C;
                case "rainbow" -> new int[] {0xFFFF5555, 0xFFFFAA33, 0xFFFFFF55, 0xFF55FF77, 0xFF55AAFF, 0xFFCC66FF}[(int) ((elapsed / 140L) % 6)];
                case "galaxy" -> new int[] {0xFF8B5CFF, 0xFF477BFF, 0xFFD36BFF, 0xFF54C7FF}[(int) ((elapsed / 180L) % 4)];
                default -> 0xFFFFD34D;
            };
        }
        return switch (effect) {
            case SIDE_POPUP -> 0xFF65CFFF;
            case EXPLOSION -> 0xFFFFA34D;
            case BLACK_HOLE -> 0xFFBE8CFF;
            case CINEMATIC -> 0xFFFFD76A;
            case DRAGON -> new int[] {0xFFFF5555, 0xFFFFAA33, 0xFFFFFF55, 0xFF55FF77, 0xFF55AAFF, 0xFFCC66FF}[(int) ((elapsed / 130L) % 6)];
            default -> 0xFFFFFFFF;
        };
    }

    private static int runFakePay(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String amountInput, String colourInput) {
        String colour = colourInput.toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("red", "blue", "green", "gold", "crimson", "rainbow", "galaxy").contains(colour)) {
            source.sendFeedback(Component.literal("Colour must be: red, blue, green, gold, crimson, rainbow or galaxy."));
            return 0;
        }

        Matcher matcher = Pattern.compile("(?i)\\$?([0-9][0-9,]*(?:\\.[0-9]+)?)([KMB]?)").matcher(amountInput);
        if (!matcher.matches()) {
            source.sendFeedback(Component.literal("Usage: /fakepay <amount> [red|blue|green|gold|crimson|rainbow|galaxy]"));
            return 0;
        }

        String number = matcher.group(1).replace(",", "");
        String suffix = matcher.group(2).toUpperCase(Locale.ROOT);
        double amount;
        try {
            amount = Double.parseDouble(number) * switch (suffix) {
                case "K" -> 1_000d;
                case "M" -> 1_000_000d;
                case "B" -> 1_000_000_000d;
                default -> 1d;
            };
        } catch (NumberFormatException exception) {
            source.sendFeedback(Component.literal("That amount isn't valid."));
            return 0;
        }

        donorName = "FakeDonor";
        amountLabel = "$" + matcher.group(1) + suffix;
        fakePayment = true;
        fakeColour = colour;
        has67 = matcher.group(1).replaceAll("[^0-9]", "").contains("67");
        setEffect(amount);
        startedAt = System.currentTimeMillis();
        playDonationSound(active);
        source.sendFeedback(Component.literal("Playing local fake donation preview: " + amountLabel + " (" + colour + "). No payment is sent."));
        return 1;
    }

    private static void setEffect(double amount) {
        if (amount >= 100_000_000d) active = Effect.DRAGON;
        else if (amount >= 10_000_000d) active = Effect.CINEMATIC;
        else if (amount >= 1_000_000d) active = Effect.BLACK_HOLE;
        else if (amount >= 100_000d) active = Effect.EXPLOSION;
        else if (amount >= 10_000d) active = Effect.SIDE_POPUP;
        else active = Effect.NONE;
    }

    private static void playDonationSound(Effect effect) {
        Minecraft mc = Minecraft.getInstance();
        switch (effect) {
            case SIDE_POPUP -> {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1.0F));
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ANVIL_LAND, 0.65F));
            }
            case EXPLOSION -> {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE, 1.0F));
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.DRAGON_FIREBALL_EXPLODE, 0.9F));
            }
            case BLACK_HOLE -> {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.END_PORTAL_SPAWN, 1.0F));
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ENDER_DRAGON_GROWL, 0.85F));
            }
            case CINEMATIC -> {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ENDER_DRAGON_DEATH, 0.9F));
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F));
            }
            case DRAGON -> {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ENDER_DRAGON_GROWL, 1.0F));
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ENDER_DRAGON_FLAP, 1.0F));
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ENDER_DRAGON_DEATH, 0.8F));
            }
            default -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.8F));
        }
    }

    private enum Effect { NONE, SIDE_POPUP, EXPLOSION, BLACK_HOLE, CINEMATIC, DRAGON }
}
