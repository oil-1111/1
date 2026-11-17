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
            int bar = (int) (height * 0.13f * Math.min(1f, elapsed / 900f));
            g.fill(0, 0, width, bar, 0xFF050509);
            g.fill(0, height - bar, width, height, 0xFF050509);
            g.fill(0, 0, width, height, 0x22000000);
            g.text(mc.font, "TEN MILLION!", width / 2 - 45, height / 2 - 18, fakePayment ? donorColor : 0xFFFFD76A, true);
            g.text(mc.font, amountLabel, width / 2 - mc.font.width(amountLabel) / 2, height / 2 + 2, 0xFFFFFFFF, true);
            g.text(mc.font, donorName, width / 2 - mc.font.width(donorName) / 2, height / 2 + 18, donorColor, true);
        }

        if (fakePayment) {
            String fakeLabel = "(Fake payment - local preview only)";
            g.text(mc.font, fakeLabel, width / 2 - mc.font.width(fakeLabel) / 2, (int) (height * 0.78f), 0xFFFF7777, true);
        }

        // 67 Easter egg: always drawn last so it stacks over the normal donation effect,
        // including the 10M cinematic. The amount test is performed on the payment digits.
        if (meme) {
            float bounce = (float) Math.abs(Math.sin(elapsed / 115.0)) * 16f;
            float pulse = (float) (0.5 + 0.5 * Math.sin(elapsed / 95.0));
            boolean solo67 = effect == Effect.NONE;
            int y = solo67 ? (int) (height / 2f - 23 - bounce) : (int) (height * 0.18f - bounce);
            int x = width / 2;
            int glow = fakePayment ? withAlpha(donorColor, (int) (75 + pulse * 100))
                    : (solo67 ? (0x550000FF | ((int) (55 + pulse * 90) << 24))
                              : (0x5500FF00 | ((int) (55 + pulse * 90) << 24)));
            int size = solo67 ? 88 + (int) (pulse * 8) : 76 + (int) (pulse * 8);
            // A solo $67 gets a blue popup; stacked 67s use the signature green glow.
            g.fill(x - size - 9, y - 31, x + size + 9, y + 43, glow);
            g.fill(x - size, y - 25, x + size, y + 37, solo67 ? 0xDD07152B : 0xDD071B0A);
            g.fill(x - size + 3, y - 22, x + size - 3, y + 34, solo67 ? 0xAA123B70 : 0xAA123B12);
            g.text(mc.font, "67", x - 17, y - 8, 0xFFFFFFFF, true);
            g.text(mc.font, "67", x - 16, y - 9, fakePayment ? donorColor : (solo67 ? 0xFF55B8FF : 0xFF8CFF36), true);
            if (solo67) {
                g.text(mc.font, amountLabel, x - mc.font.width(amountLabel) / 2, y + 15, 0xFFFFFFFF, true);
                g.text(mc.font, donorName, x - mc.font.width(donorName) / 2, y + 28, 0xFF9CD9FF, true);
            }
            // Small pixel-like sparks around the popup.
            int spark = fakePayment ? donorColor : (solo67 ? 0xFF80CFFF : 0xFF9DFF55);
            g.fill(x - size - 13, y - 12, x - size - 7, y - 6, spark);
            g.fill(x + size + 7, y + 8, x + size + 13, y + 14, spark);
            g.fill(x - 42, y + 40, x - 36, y + 46, spark);
            g.fill(x + 36, y - 34, x + 42, y - 28, spark);
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
            case SIDE_POPUP -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 0.9F));
            case EXPLOSION -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE, 0.85F));
            case BLACK_HOLE -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.END_PORTAL_SPAWN, 1.0F));
            case CINEMATIC -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F));
            default -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.8F));
        }
    }

    private enum Effect { NONE, SIDE_POPUP, EXPLOSION, BLACK_HOLE, CINEMATIC }
}
