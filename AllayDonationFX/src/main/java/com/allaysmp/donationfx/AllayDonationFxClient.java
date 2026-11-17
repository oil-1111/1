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
        if (amount >= 10_000_000d) active = Effect.CINEMATIC;
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
        long effectDuration = effect == Effect.NONE ? 2_500L : (effect == Effect.CINEMATIC ? 5_500L : 4_000L);
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
            g.fill(x, height / 3, x + 205, height / 3 + 52, 0xE0202630);
            g.fill(x, height / 3, x + 5, height / 3 + 52, fakePayment ? donorColor : 0xFF36D6A3);
            g.text(mc.font, "PAYMENT RECEIVED", x + 15, height / 3 + 7, 0xFFFFFFFF, true);
            g.text(mc.font, amountLabel, x + 15, height / 3 + 20, 0xFF65FFC5, true);
            g.text(mc.font, donorName, x + 15, height / 3 + 35, donorColor, true);
        } else if (effect == Effect.EXPLOSION) {
            float pulse = (float) Math.sin(elapsed / 90.0) * 0.5f + 0.5f;
            int r = (int) (22 + pulse * 18);
            g.fill(width / 2 - r, height / 2 - r, width / 2 + r, height / 2 + r, fakePayment ? withAlpha(donorColor, 0x66) : 0x66FF8A24);
            g.text(mc.font, "BOOM!  " + amountLabel, width / 2 - 55, height / 2 - 12, 0xFFFFD36A, true);
            g.text(mc.font, donorName, width / 2 - mc.font.width(donorName) / 2, height / 2 + 5, donorColor, true);
        } else if (effect == Effect.BLACK_HOLE) {
            int radius = (int) (18 + Math.sin(elapsed / 180.0) * 5);
            g.fill(width / 2 - radius * 2, height / 2 - radius * 2, width / 2 + radius * 2, height / 2 + radius * 2, 0x331B0B38);
            g.fill(width / 2 - radius, height / 2 - radius, width / 2 + radius, height / 2 + radius, 0xFF080512);
            g.fill(width / 2 - radius - 5, height / 2 - 2, width / 2 + radius + 5, height / 2 + 2, fakePayment ? withAlpha(donorColor, 0xBB) : 0xBB9D65FF);
            g.text(mc.font, "BLACK HOLE  " + amountLabel, width / 2 - 65, height / 2 + radius + 14, 0xFFDCCBFF, true);
            g.text(mc.font, donorName, width / 2 - mc.font.width(donorName) / 2, height / 2 + radius + 27, donorColor, true);
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
        if (amount >= 10_000_000d) active = Effect.CINEMATIC;
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
            default -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.8F));
        }
    }

    private enum Effect { NONE, SIDE_POPUP, EXPLOSION, BLACK_HOLE, CINEMATIC }
}
