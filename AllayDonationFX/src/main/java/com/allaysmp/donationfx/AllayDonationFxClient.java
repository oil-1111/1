package com.allaysmp.donationfx;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
            "(?i)\\bPAY\\s+.+?\\s+paid you\\s+\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)([KMB]?)\\b"
    );
    private static volatile Effect active = Effect.NONE;
    private static volatile boolean has67 = false;
    private static volatile long startedAt = 0L;
    private static volatile String amountLabel = "";

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((Component message, boolean overlay) -> detect(message));
        ClientReceiveMessageEvents.CHAT.register((Component message, PlayerChatMessage playerChatMessage, GameProfile sender, ChatType.Bound boundChatType, java.time.Instant receptionTimestamp) -> detect(message));
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("allaydonationfx", "effects"),
                (graphics, deltaTracker) -> render(graphics)
        );
    }

    private static void detect(Component message) {
        String raw = message.getString();
        Matcher matcher = PAYMENT.matcher(raw);
        if (!matcher.find()) return;

        String numeric = matcher.group(1).replace(",", "");
        String suffix = matcher.group(2).toUpperCase(Locale.ROOT);
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
        String digitsOnly = matcher.group(1).replaceAll("[^0-9]", "");
        has67 = digitsOnly.contains("67");
        if (amount >= 10_000_000d) active = Effect.CINEMATIC;
        else if (amount >= 1_000_000d) active = Effect.BLACK_HOLE;
        else if (amount >= 100_000d) active = Effect.EXPLOSION;
        else if (amount >= 10_000d) active = Effect.SIDE_POPUP;
        else active = Effect.NONE;

        if (active != Effect.NONE || has67) {
            amountLabel = "$" + matcher.group(1) + suffix;
            startedAt = System.currentTimeMillis();
        }
    }

    private static void render(GuiGraphicsExtractor g) {
        Effect effect = active;
        boolean meme = has67;
        if (effect == Effect.NONE && !meme) return;

        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > 8_000L) {
            active = Effect.NONE;
            has67 = false;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        float t = Math.min(1f, elapsed / 8000f);

        // The current starter build uses 2D HUD animation. The 3D cinematic is a planned upgrade.
        if (effect == Effect.SIDE_POPUP) {
            int x = (int) (-210 + Math.min(1f, elapsed / 500f) * 230);
            g.fill(x, height / 3, x + 205, height / 3 + 52, 0xE0202630);
            g.fill(x, height / 3, x + 5, height / 3 + 52, 0xFF36D6A3);
            g.text(mc.font, "PAYMENT RECEIVED", x + 15, height / 3 + 9, 0xFFFFFFFF, true);
            g.text(mc.font, amountLabel, x + 15, height / 3 + 27, 0xFF65FFC5, true);
        } else if (effect == Effect.EXPLOSION) {
            float pulse = (float) Math.sin(elapsed / 90.0) * 0.5f + 0.5f;
            int r = (int) (22 + pulse * 18);
            g.fill(width / 2 - r, height / 2 - r, width / 2 + r, height / 2 + r, 0x66FF8A24);
            g.text(mc.font, "BOOM!  " + amountLabel, width / 2 - 55, height / 2 - 5, 0xFFFFD36A, true);
        } else if (effect == Effect.BLACK_HOLE) {
            int radius = (int) (18 + Math.sin(elapsed / 180.0) * 5);
            g.fill(width / 2 - radius * 2, height / 2 - radius * 2, width / 2 + radius * 2, height / 2 + radius * 2, 0x331B0B38);
            g.fill(width / 2 - radius, height / 2 - radius, width / 2 + radius, height / 2 + radius, 0xFF080512);
            g.fill(width / 2 - radius - 5, height / 2 - 2, width / 2 + radius + 5, height / 2 + 2, 0xBB9D65FF);
            g.text(mc.font, "BLACK HOLE  " + amountLabel, width / 2 - 65, height / 2 + radius + 14, 0xFFDCCBFF, true);
        } else if (effect == Effect.CINEMATIC) {
            int bar = (int) (height * 0.13f * Math.min(1f, elapsed / 900f));
            g.fill(0, 0, width, bar, 0xFF050509);
            g.fill(0, height - bar, width, height, 0xFF050509);
            g.fill(0, 0, width, height, 0x22000000);
            g.text(mc.font, "TEN MILLION!", width / 2 - 45, height / 2 - 18, 0xFFFFD76A, true);
            g.text(mc.font, amountLabel, width / 2 - 24, height / 2 + 2, 0xFFFFFFFF, true);
        }

        // 67 Easter egg: always drawn last so it stacks over the normal donation effect,
        // including the 10M cinematic. The amount test is performed on the payment digits.
        if (meme) {
            float bounce = (float) Math.abs(Math.sin(elapsed / 115.0)) * 16f;
            float pulse = (float) (0.5 + 0.5 * Math.sin(elapsed / 95.0));
            int y = (int) (height * 0.18f - bounce);
            int x = width / 2;
            int glow = 0x5500FF00 | ((int) (55 + pulse * 90) << 24);
            int size = 76 + (int) (pulse * 8);
            // Layered green glow, dark backing plate, and bright centre.
            g.fill(x - size - 9, y - 31, x + size + 9, y + 43, glow);
            g.fill(x - size, y - 25, x + size, y + 37, 0xDD071B0A);
            g.fill(x - size + 3, y - 22, x + size - 3, y + 34, 0xAA123B12);
            g.text(mc.font, "67", x - 17, y - 8, 0xFFFFFFFF, true);
            g.text(mc.font, "67", x - 16, y - 9, 0xFF8CFF36, true);
            // Small pixel-like sparks around the popup.
            int spark = 0xFF9DFF55;
            g.fill(x - size - 13, y - 12, x - size - 7, y - 6, spark);
            g.fill(x + size + 7, y + 8, x + size + 13, y + 14, spark);
            g.fill(x - 42, y + 40, x - 36, y + 46, spark);
            g.fill(x + 36, y - 34, x + 42, y - 28, spark);
        }
    }

    private enum Effect { NONE, SIDE_POPUP, EXPLOSION, BLACK_HOLE, CINEMATIC }
}
