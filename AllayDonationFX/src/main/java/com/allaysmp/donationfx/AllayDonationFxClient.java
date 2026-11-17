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
import java.util.List;
import java.util.ArrayList;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.world.item.component.ResolvableProfile;
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
    private static volatile boolean showDonationPopup = false;
    private static volatile long startedAt = 0L;
    private static volatile String amountLabel = "";
    private static volatile String donorName = "";
    private static volatile boolean fakePayment = false;
    private static volatile String fakeColour = "";
    private static final List<DonationPopup> donationPopups = new ArrayList<>();
    private static final int MAX_VISIBLE_POPUPS = 5;

    private static final class DonationPopup {
        final String donor;
        final String amount;
        final Effect effect;
        final long createdAt;
        final int donorColor;
        final boolean fake;
        DonationPopup(String donor, String amount, Effect effect, long createdAt, int donorColor, boolean fake) {
            this.donor = donor;
            this.amount = amount;
            this.effect = effect;
            this.createdAt = createdAt;
            this.donorColor = donorColor;
            this.fake = fake;
        }
    }

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
        showDonationPopup = true;
        if (amount >= 100_000_000d) active = Effect.DRAGON;
        else if (amount >= 10_000_000d) active = Effect.CINEMATIC;
        else if (amount >= 1_000_000d) active = Effect.BLACK_HOLE;
        else if (amount >= 100_000d) active = Effect.EXPLOSION;
        else if (amount >= 10_000d) active = Effect.SIDE_POPUP;
        else active = Effect.NONE;

        if (showDonationPopup || active != Effect.NONE || has67) {
            amountLabel = "$" + matcher.group(2) + suffix;
            fakePayment = false;
            fakeColour = "";
            startedAt = System.currentTimeMillis();
            addDonationPopup(donorName, amountLabel, active, startedAt, donorColor(active, 0L), false);
            playDonationSound(active);
        }
    }

    private static void render(GuiGraphicsExtractor g) {
        Effect effect = active;
        boolean meme = has67;
        if (effect == Effect.NONE && !meme && !showDonationPopup) return;

        long elapsed = System.currentTimeMillis() - startedAt;
        long effectDuration = effect == Effect.NONE ? 6_000L
                : (effect == Effect.DRAGON ? 8_000L
                : (effect == Effect.SIDE_POPUP ? 6_000L
                : (effect == Effect.CINEMATIC ? 5_500L : 4_000L)));
        synchronized (donationPopups) {
            donationPopups.removeIf(popup -> System.currentTimeMillis() - popup.createdAt > 6_000L);
        }
        if (elapsed > effectDuration) {
            active = Effect.NONE;
            has67 = false;
            showDonationPopup = false;
            fakePayment = false;
            fakeColour = "";
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        float t = Math.min(1f, elapsed / 4000f);
        int donorColor = donorColor(effect, elapsed);

        // Every payment gets the same compact side notification; tier effects layer over it.
        drawDonationPopups(g, mc, width, height);

        if (effect == Effect.SIDE_POPUP) {
            // The side notification above is the complete $10K-tier animation.
        } else if (effect == Effect.EXPLOSION) {
            drawNuke(g, mc, width, height, elapsed, donorColor);
        } else if (effect == Effect.BLACK_HOLE) {
            drawBlackHole(g, mc, width, height, elapsed, donorColor);
        } else if (effect == Effect.DRAGON) {
            // Keep the donation details in the compact side popup.
            // The dragon flyby is the tier animation layered over that popup.
            float progress = Math.min(1f, elapsed / 4_800f);
            int dragonX = (int) (-190 + progress * (width + 380));
            int dragonY = (int) (height * 0.24f + Math.sin(elapsed / 150.0) * 13);
            drawRedDragon(g, dragonX, dragonY, elapsed);
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

    private static void addDonationPopup(String donor, String amount, Effect effect, long createdAt, int color, boolean fake) {
        synchronized (donationPopups) {
            donationPopups.add(0, new DonationPopup(donor, amount, effect, createdAt, color, fake));
            while (donationPopups.size() > 12) donationPopups.remove(donationPopups.size() - 1);
        }
    }

    private static void drawDonationPopups(GuiGraphicsExtractor g, Minecraft mc, int width, int height) {
        synchronized (donationPopups) {
            long now = System.currentTimeMillis();
            donationPopups.removeIf(popup -> now - popup.createdAt > 6_000L);
            int shown = Math.min(MAX_VISIBLE_POPUPS, donationPopups.size());
            for (int i = 0; i < shown; i++) {
                DonationPopup popup = donationPopups.get(i);
                drawDonationPopup(g, mc, width, height, now - popup.createdAt, i, popup);
            }
        }
    }

    private static void drawDonationPopup(GuiGraphicsExtractor g, Minecraft mc, int width, int height,
                                           long elapsed, int row, DonationPopup popup) {
        Effect effect = popup.effect;
        int donorColor = popup.donorColor;
        // One compact side card is used for every donation tier.
        // Tier-specific animations are rendered separately on top of this notification.
        int panelWidth = Math.min(166, width - 16);
        int panelHeight = 44;
        float slide = Math.min(1f, elapsed / 420f);
        int left = (int) (-panelWidth - 8 + slide * (panelWidth + 20));
        int top = height / 3 + row * 50;
        int tier = switch (effect) {
            case SIDE_POPUP, NONE -> 1;
            case EXPLOSION -> 2;
            case BLACK_HOLE -> 3;
            case CINEMATIC -> 4;
            case DRAGON -> 5;
        };
        int accent = switch (effect) {
            case SIDE_POPUP, NONE -> popup.fake ? donorColor : 0xFF35D9B1;
            case EXPLOSION -> 0xFFFF8B35;
            case BLACK_HOLE -> 0xFFAD72FF;
            case CINEMATIC -> 0xFFFFD15C;
            case DRAGON -> 0xFFFF4545;
        };

        if (tier == 5) {
            drawRainbowRim(g, left + panelWidth / 2, top + panelHeight / 2, panelWidth, panelHeight, elapsed);
            roundedFill(g, left + 5, top + 5, left + panelWidth - 5, top + panelHeight - 5, 8, 0xF020101A);
        } else {
            drawTierRim(g, left + panelWidth / 2, top + panelHeight / 2, panelWidth, panelHeight,
                    Math.min(tier, 3), accent);
            if (tier == 4) {
                roundedFill(g, left + 5, top + 5, left + panelWidth - 5, top + 7, 2, 0xFFFFE8A0);
                roundedFill(g, left + 5, top + panelHeight - 7, left + panelWidth - 5, top + panelHeight - 5, 2, 0xFFFFE8A0);
            }
        }

        roundedFill(g, left + 4, top + 4, left + panelWidth - 4, top + panelHeight - 4, 7, 0xF0191D29);
        int avatarX = left + 10;
        int avatarY = top + 10;
        roundedFill(g, avatarX, avatarY, avatarX + 32, avatarY + 32, 7, withAlpha(accent, 100));
        roundedFill(g, avatarX + 2, avatarY + 2, avatarX + 30, avatarY + 30, 6, 0xFF202637);
        // Draw the donor's actual Minecraft player head instead of a letter placeholder.
        try {
            PlayerFaceExtractor.extractRenderState(g, ResolvableProfile.createUnresolved(popup.donor), avatarX, avatarY, 32);
        } catch (RuntimeException ignored) {
            String initial = popup.donor == null || popup.donor.isBlank() ? "?" : popup.donor.substring(0, 1).toUpperCase(Locale.ROOT);
            g.text(mc.font, initial, avatarX + 16 - mc.font.width(initial) / 2, avatarY + 10, 0xFFFFFFFF, true);
        }

        int textX = left + 49;
        g.text(mc.font, "DONATION", textX, top + 7, 0xFFDDE7F5, true);
        int amountColor = effect == Effect.NONE || effect == Effect.SIDE_POPUP ? 0xFF8CFFD9 : accent;
        g.text(mc.font, popup.amount, textX, top + 19, amountColor, true);
        String name = popup.donor == null ? "" : popup.donor;
        if (mc.font.width(name) > panelWidth - 57) {
            while (!name.isEmpty() && mc.font.width(name + "...") > panelWidth - 57) {
                name = name.substring(0, name.length() - 1);
            }
            name += "...";
        }
        g.text(mc.font, name, textX, top + 34, donorColor, true);
    }

    private static void drawNuke(GuiGraphicsExtractor g, Minecraft mc, int width, int height,
                                 long elapsed, int donorColor) {
        float t = Math.min(1f, elapsed / 3900f);
        int cx = width / 2;
        int ground = (int) (height * 0.70f);
        float flash = Math.max(0f, 1f - elapsed / 260f);

        // A brief white-hot flash, then a rapidly expanding ground shockwave.
        if (flash > 0f) {
            g.fill(0, 0, width, height, withAlpha(0xFFFFE9B5, (int) (flash * 205)));
        }
        int shockRadius = (int) (t * Math.max(width, height) * 0.78f);
        int shockY = ground + 8;
        drawEllipseBand(g, cx, shockY, shockRadius, Math.max(5, shockRadius / 7),
                Math.max(2, 8 - (int) (t * 5)), withAlpha(0xFFFFB34D, (int) ((1f - t) * 190)));

        // Rising stem and the expanding mushroom-shaped fireball.
        float rise = Math.min(1f, t * 1.65f);
        int stemTop = ground - (int) (rise * height * 0.43f);
        int stemWidth = (int) (10 + 25 * Math.sin(Math.min(1f, t * 2.2f) * Math.PI));
        drawEllipse(g, cx, (ground + stemTop) / 2, stemWidth, Math.max(10, (ground - stemTop) / 2),
                0xD9E94722);
        drawEllipse(g, cx, (ground + stemTop) / 2, Math.max(5, stemWidth / 2), Math.max(8, (ground - stemTop) / 2 - 5),
                0xEFFFF078);

        int cloudY = ground - (int) (height * (0.24f + 0.20f * rise));
        int cloudRx = (int) (18 + t * Math.min(width * 0.23f, 125));
        int cloudRy = (int) (12 + t * 42);
        // Dark smoke cap behind the glowing fireball.
        drawEllipse(g, cx, cloudY - 8, cloudRx + 17, cloudRy + 14, 0xD92A2530);
        drawEllipse(g, cx - cloudRx / 2, cloudY + 3, cloudRx / 2 + 12, cloudRy, 0xD94A3030);
        drawEllipse(g, cx + cloudRx / 2, cloudY + 1, cloudRx / 2 + 12, cloudRy, 0xD94A3030);
        drawEllipse(g, cx, cloudY + 7, cloudRx, cloudRy, 0xE6A63825);
        drawEllipse(g, cx, cloudY + 5, Math.max(8, cloudRx - 13), Math.max(7, cloudRy - 8), 0xF0F36A32);
        drawEllipse(g, cx, cloudY + 8, Math.max(5, cloudRx - 27), Math.max(5, cloudRy - 15), 0xFFFFC45A);

        // Rolling smoke puffs and drifting embers.
        for (int i = 0; i < 7; i++) {
            double a = i * 0.897;
            int px = cx + (int) (Math.cos(a) * (cloudRx * 0.72));
            int py = cloudY - 7 + (int) (Math.sin(a) * cloudRy * 0.55);
            int puff = 8 + (int) ((i % 3) * 4 + t * 5);
            drawEllipse(g, px, py, puff, puff / 2 + 3, 0xB52D2932);
        }
        for (int i = 0; i < 12; i++) {
            double a = i * 2.399 + elapsed / 190.0;
            int distance = 24 + (int) ((i * 13 + elapsed / 24) % 105);
            int px = cx + (int) (Math.cos(a) * distance);
            int py = cloudY + (int) (Math.sin(a) * distance * 0.55);
            int size = 2 + (i % 3);
            g.fill(px - size, py - size, px + size, py + size, (i % 2 == 0) ? 0xFFFFD35A : 0xFFFF6735);
        }

        // Compact caption; the donor and amount remain visible in the side notification too.
        int cardY = Math.min(height - 48, ground + 18);
        drawTierRim(g, cx, cardY + 12, 218, 34, 2, 0xFFFF873A);
        g.text(mc.font, "NUCLEAR BLAST", cx - mc.font.width("NUCLEAR BLAST") / 2, cardY + 2, 0xFFFFD78A, true);
    }

    private static void drawBlackHole(GuiGraphicsExtractor g, Minecraft mc, int width, int height,
                                      long elapsed, int donorColor) {
        int cx = width / 2;
        int cy = height / 2 - 24;
        float pulse = (float) (0.5 + 0.5 * Math.sin(elapsed / 260.0));
        float rotation = elapsed / 520.0f;
        int core = 28 + (int) (pulse * 3);

        // Subtle gravitational vignette and distant stars.
        g.fill(cx - 150, cy - 115, cx + 150, cy + 115, 0x180A0618);
        for (int i = 0; i < 24; i++) {
            double a = i * 2.399;
            int sx = cx + (int) (Math.cos(a) * (72 + (i % 4) * 25));
            int sy = cy + (int) (Math.sin(a) * (42 + (i % 5) * 17));
            int twinkle = (int) (90 + 100 * (0.5 + 0.5 * Math.sin(elapsed / 180.0 + i)));
            g.fill(sx, sy, sx + (i % 3 == 0 ? 3 : 2), sy + (i % 3 == 0 ? 3 : 2), withAlpha(0xFFD9D2FF, twinkle));
        }

        // Accretion disk: several flattened, glowing rings behind the event horizon.
        drawEllipseBand(g, cx, cy + 2, 104, 37, 12, 0x553F24A8);
        drawEllipseBand(g, cx, cy + 1, 94, 30, 9, 0xBB8B4BFF);
        drawEllipseBand(g, cx, cy, 82, 23, 7, 0xFFFFB65E);
        drawEllipseBand(g, cx, cy - 1, 70, 17, 5, 0xFFFFE6A0);
        drawEllipseBand(g, cx, cy - 2, 58, 12, 3, 0xFFFFFFFF);

        // Streaks in the disk suggest fast orbital motion.
        for (int i = 0; i < 18; i++) {
            double a = i * 0.71 + rotation;
            int rx = 48 + (i % 5) * 10;
            int ry = 12 + (i % 4) * 4;
            int px = cx + (int) (Math.cos(a) * rx);
            int py = cy + (int) (Math.sin(a) * ry);
            g.fill(px - 5, py - 1, px + 5, py + 2, (i % 3 == 0) ? 0xFFFFE7B1 : 0xCCB78BFF);
        }

        // Bright gravitational-lensing halo, then the perfectly dark event horizon.
        drawEllipseBand(g, cx, cy, core + 8, core + 8, 3, 0xFFFFDCA0);
        drawEllipseBand(g, cx, cy, core + 4, core + 4, 2, 0xFFFFFFFF);
        drawEllipse(g, cx, cy, core, core, 0xFF020106);
        drawEllipse(g, cx - 5, cy - 5, core / 2, core / 2, 0xFF000000);

        // A small warped light arc on the upper edge of the black sphere.
        drawEllipseBand(g, cx, cy - 1, core + 5, core + 5, 2, 0xD9E6C5FF);

        int cardY = cy + 75;
        drawTierRim(g, cx, cardY + 12, 226, 34, 3, 0xFFB77BFF);
        g.text(mc.font, "BLACK HOLE  " + amountLabel,
                cx - mc.font.width("BLACK HOLE  " + amountLabel) / 2, cardY + 2, 0xFFE9D7FF, true);
        g.text(mc.font, donorName, cx - mc.font.width(donorName) / 2, cardY + 15, donorColor, true);
    }

    private static void drawEllipse(GuiGraphicsExtractor g, int cx, int cy, int rx, int ry, int color) {
        if (rx <= 0 || ry <= 0) return;
        for (int dy = -ry; dy <= ry; dy++) {
            double normalized = 1.0 - (double) (dy * dy) / (ry * ry);
            int half = (int) (rx * Math.sqrt(Math.max(0.0, normalized)));
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    private static void drawEllipseBand(GuiGraphicsExtractor g, int cx, int cy, int rx, int ry,
                                        int thickness, int color) {
        if (rx <= 0 || ry <= 0) return;
        int innerRx = Math.max(0, rx - thickness);
        int innerRy = Math.max(0, ry - thickness);
        for (int dy = -ry; dy <= ry; dy++) {
            double outerN = 1.0 - (double) (dy * dy) / (ry * ry);
            int outerHalf = (int) (rx * Math.sqrt(Math.max(0.0, outerN)));
            int innerHalf = 0;
            if (innerRy > 0 && Math.abs(dy) < innerRy) {
                double innerN = 1.0 - (double) (dy * dy) / (innerRy * innerRy);
                innerHalf = (int) (innerRx * Math.sqrt(Math.max(0.0, innerN)));
            }
            if (innerHalf <= 0) {
                g.fill(cx - outerHalf, cy + dy, cx + outerHalf + 1, cy + dy + 1, color);
            } else {
                g.fill(cx - outerHalf, cy + dy, cx - innerHalf, cy + dy + 1, color);
                g.fill(cx + innerHalf, cy + dy, cx + outerHalf + 1, cy + dy + 1, color);
            }
        }
    }

    private static void drawRedDragon(GuiGraphicsExtractor g, int x, int y, long elapsed) {
        // Detailed, pixel-art Ender Dragon-inspired HUD creature. The layered
        // highlights, scale rows, wing bones and moving embers create a pseudo-3D look.
        int flap = (int) (Math.sin(elapsed / 145.0) * 19);
        int red = 0xFFE52D35;
        int bright = 0xFFFF6658;
        int mid = 0xFFC51E32;
        int dark = 0xFF751326;
        int deep = 0xFF3B1024;
        int bone = 0xFFFFA18B;
        int wing = 0xFF9F203C;
        int wingLight = 0xFFD33A4B;

        // Tail: tapered, segmented, ridged and tipped with a sharp barb.
        g.fill(x - 145, y - 3, x - 126, y + 3, deep);
        g.fill(x - 130, y - 5, x - 106, y + 5, dark);
        g.fill(x - 111, y - 2, x - 82, y + 8, mid);
        g.fill(x - 88, y + 1, x - 57, y + 12, red);
        g.fill(x - 61, y + 4, x - 38, y + 14, dark);
        g.fill(x - 146, y - 3, x - 128, y - 1, bright);
        for (int i = 0; i < 5; i++) {
            int sx = x - 119 + i * 14;
            g.fill(sx, y - 7 + i, sx + 5, y - 1 + i, dark);
            g.fill(sx + 1, y - 7 + i, sx + 3, y - 4 + i, bright);
        }
        g.fill(x - 47, y + 5, x - 34, y + 10, deep);
        g.fill(x - 39, y + 7, x - 28, y + 11, bright);

        // Far wing, behind the body: scalloped membrane and visible wing fingers.
        int farY = y - 8 - flap / 2;
        g.fill(x - 46, farY - 37, x - 35, farY + 5, deep);
        g.fill(x - 43, farY - 34, x - 8, farY - 26, dark);
        g.fill(x - 36, farY - 27, x + 4, farY - 17, wing);
        g.fill(x - 27, farY - 18, x + 13, farY - 8, wingLight);
        g.fill(x - 17, farY - 9, x + 17, farY - 1, dark);
        g.fill(x - 40, farY - 31, x - 35, farY - 5, bright);
        g.fill(x - 11, farY - 24, x - 6, farY - 2, deep);
        g.fill(x + 2, farY - 15, x + 7, farY + 1, deep);
        g.fill(x + 11, farY - 8, x + 16, farY + 3, deep);

        // Near wing: broad upper spar, long separated fingers and shaded membranes.
        int wingY = y - 12 - flap;
        g.fill(x - 29, wingY - 49, x - 14, wingY + 7, deep);
        g.fill(x - 24, wingY - 45, x + 2, wingY - 36, dark);
        g.fill(x - 19, wingY - 37, x + 17, wingY - 27, wing);
        g.fill(x - 12, wingY - 28, x + 30, wingY - 18, wingLight);
        g.fill(x - 3, wingY - 19, x + 39, wingY - 9, wing);
        g.fill(x + 9, wingY - 10, x + 46, wingY - 2, dark);
        // Wing finger bones and membrane panels.
        g.fill(x - 20, wingY - 43, x - 14, wingY - 4, bright);
        g.fill(x - 2, wingY - 33, x + 3, wingY - 3, red);
        g.fill(x + 16, wingY - 24, x + 21, wingY - 2, red);
        g.fill(x + 33, wingY - 14, x + 38, wingY - 1, mid);
        g.fill(x + 43, wingY - 5, x + 49, wingY + 2, deep);
        g.fill(x - 17, wingY - 39, x - 5, wingY - 36, bright);
        g.fill(x + 2, wingY - 29, x + 13, wingY - 26, 0xFFE84A54);
        g.fill(x + 20, wingY - 20, x + 31, wingY - 17, 0xFFE84A54);
        g.fill(x + 37, wingY - 10, x + 44, wingY - 7, 0xFFB92A43);

        // Body silhouette and overlapping armor-like muscle plates.
        g.fill(x - 58, y - 7, x + 35, y + 25, deep);
        g.fill(x - 53, y - 14, x + 30, y + 18, dark);
        g.fill(x - 47, y - 15, x + 26, y + 12, mid);
        g.fill(x - 41, y - 15, x + 20, y + 6, red);
        g.fill(x - 36, y - 15, x + 10, y - 10, bright);
        g.fill(x - 34, y + 7, x + 24, y + 17, deep);
        g.fill(x - 25, y + 10, x + 19, y + 14, 0xFF9F1B32);

        // Rows of small scales on the back and flanks.
        for (int row = 0; row < 2; row++) {
            for (int i = 0; i < 7; i++) {
                int sx = x - 38 + i * 9 + row * 3;
                int sy = y - 7 + row * 8;
                g.fill(sx, sy, sx + 5, sy + 4, row == 0 ? 0xFFFF5960 : 0xFF8D182D);
                g.fill(sx + 1, sy, sx + 4, sy + 1, 0xFFFF8A78);
            }
        }
        // Raised dorsal spikes with bright edges.
        for (int i = 0; i < 7; i++) {
            int sx = x - 38 + i * 11;
            int spikeY = y - 22 + (i % 2) * 3;
            g.fill(sx, spikeY, sx + 7, y - 11, deep);
            g.fill(sx + 1, spikeY + 1, sx + 4, y - 13, bright);
        }
        // Segmented plated belly, with a shaded underside.
        for (int i = 0; i < 5; i++) {
            int sx = x - 24 + i * 10;
            g.fill(sx, y + 14, sx + 8, y + 19, 0xFFFF756C);
            g.fill(sx + 1, y + 19, sx + 7, y + 21, deep);
            g.fill(sx + 2, y + 14, sx + 6, y + 15, 0xFFFFB09A);
        }

        // Neck, shoulder and throat plates leading into the angular head.
        g.fill(x + 13, y - 18, x + 49, y + 7, deep);
        g.fill(x + 17, y - 21, x + 47, y + 3, dark);
        g.fill(x + 21, y - 20, x + 44, y - 3, mid);
        g.fill(x + 24, y - 20, x + 42, y - 13, bright);
        for (int i = 0; i < 3; i++) {
            int sx = x + 27 + i * 7;
            g.fill(sx, y - 4 + i * 2, sx + 5, y + 2 + i * 2, 0xFFFF7669);
        }

        // Head: crown, muzzle, jaw, horns, nostril, glowing eye and tiny teeth.
        g.fill(x + 37, y - 29, x + 72, y - 7, deep);
        g.fill(x + 41, y - 27, x + 70, y - 10, dark);
        g.fill(x + 44, y - 25, x + 68, y - 13, red);
        g.fill(x + 58, y - 18, x + 82, y - 9, mid);
        g.fill(x + 69, y - 15, x + 86, y - 10, deep);
        g.fill(x + 53, y - 8, x + 78, y - 4, dark);
        g.fill(x + 59, y - 6, x + 77, y - 3, bone);
        // Crown ridges and swept-back horns.
        g.fill(x + 43, y - 33, x + 49, y - 23, deep);
        g.fill(x + 45, y - 37, x + 51, y - 30, bone);
        g.fill(x + 60, y - 31, x + 66, y - 21, deep);
        g.fill(x + 64, y - 35, x + 70, y - 28, bone);
        g.fill(x + 48, y - 28, x + 56, y - 24, bright);
        // Eye socket and animated glow.
        int eyeGlow = (int) (150 + 70 * (0.5 + 0.5 * Math.sin(elapsed / 100.0)));
        g.fill(x + 57, y - 21, x + 69, y - 14, withAlpha(0xFFFF2525, eyeGlow));
        g.fill(x + 60, y - 19, x + 66, y - 15, 0xFFFFFF8A);
        g.fill(x + 62, y - 18, x + 65, y - 15, 0xFFFFFFFF);
        g.fill(x + 75, y - 14, x + 79, y - 12, deep); // nostril
        g.fill(x + 68, y - 7, x + 72, y - 4, 0xFFFFE2C0); // tooth

        // Four articulated legs with joints, claws and highlights.
        g.fill(x - 30, y + 12, x - 21, y + 27, deep);
        g.fill(x - 27, y + 14, x - 20, y + 29, mid);
        g.fill(x - 23, y + 25, x - 13, y + 33, dark);
        g.fill(x - 15, y + 31, x - 10, y + 34, bone);
        g.fill(x - 3, y + 13, x + 6, y + 27, deep);
        g.fill(x, y + 14, x + 7, y + 29, red);
        g.fill(x + 5, y + 26, x + 17, y + 32, dark);
        g.fill(x + 14, y + 30, x + 19, y + 33, bone);
        // Far-side legs are darker and slightly offset to add depth.
        g.fill(x - 42, y + 8, x - 35, y + 23, 0xFF4B1428);
        g.fill(x - 40, y + 21, x - 31, y + 26, deep);
        g.fill(x + 18, y + 7, x + 25, y + 22, 0xFF551326);
        g.fill(x + 22, y + 20, x + 31, y + 25, deep);

        // Animated embers, sparks and a short hot exhaust trail.
        int spark = (int) ((elapsed / 55L) % 18L);
        g.fill(x - 166 + spark, y - 8 - spark % 5, x - 160 + spark, y - 4 - spark % 5, 0xFFFFD35A);
        g.fill(x - 153, y + 1, x - 128, y + 7, 0xCCFF542E);
        g.fill(x - 171, y - 4, x - 151, y + 2, 0x99FFD34D);
        g.fill(x - 139, y + 9, x - 121, y + 13, 0x99FF392B);
        g.fill(x - 117 + spark / 2, y - 9, x - 113 + spark / 2, y - 5, 0xFFFF8A35);
        g.fill(x - 98 + spark / 3, y + 13, x - 94 + spark / 3, y + 17, 0xFFFFC34A);
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
        showDonationPopup = true;
        setEffect(amount);
        startedAt = System.currentTimeMillis();
        addDonationPopup(donorName, amountLabel, active, startedAt, donorColor(active, 0L), true);
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
