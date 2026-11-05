package online.indigomc.economy.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Casino messages (0.5.0). As with the wallet, the client only sends INTENTS (spin with this stake, hit, stand, set my
 * limits); the server decides every outcome with its own random numbers and answers with the whole view.
 */
public final class CasinoPayloads {
    private CasinoPayloads() {}

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(WalletPayloads.MODID, path); }

    /** C2S. amount/amount2 are typed text (the server parses them); a1/a2 are small integers (bet kind, pick). */
    public record Action(BlockPos pos, int action, String amount, String amount2, int a1, int a2) implements CustomPacketPayload {
        public static final int REFRESH = 0, ACK = 1, SPIN = 2, ROULETTE = 3, DEAL = 4, HIT = 5, STAND = 6, DOUBLE = 7,
            LIMITS = 8, DEPOSIT = 9, WITHDRAW = 10;
        public static final Type<Action> TYPE = new Type<>(id("casino_action"));
        public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.of(
            (b, v) -> { b.writeBlockPos(v.pos); b.writeVarInt(v.action); b.writeUtf(v.amount, 24); b.writeUtf(v.amount2, 24);
                b.writeVarInt(v.a1); b.writeVarInt(v.a2); },
            b -> new Action(b.readBlockPos(), b.readVarInt(), b.readUtf(24), b.readUtf(24), b.readVarInt(), b.readVarInt()));
        @Override public Type<Action> type() { return TYPE; }
    }

    /**
     * S2C: everything a casino screen shows. mode: ACK (first-time notice), SLOTS, ROULETTE, BLACKJACK, MANAGE (owner),
     * CLOSE (close the screen). Money is pre-formatted text; the numbers are for layout only.
     */
    public record View(boolean open, BlockPos pos, int mode, String casino, String owner, String minBet, String maxBet,
                       String coverNote, String balance, int resultKind, String message,
                       int[] reels, int wheel, int[] playerCards, int[] dealerCards, boolean dealerHidden, boolean inHand,
                       boolean canDouble, int playerTotal, int dealerTotal, String stake,
                       String bank, String inPlay, long rounds, String staked, String returned, String houseResult, String game)
            implements CustomPacketPayload {
        public static final int ACK = 0, SLOTS = 1, ROULETTE = 2, BLACKJACK = 3, MANAGE = 4, CLOSE = 5;
        public static final int NONE = 0, WIN = 1, LOSE = 2, INFO = 3, ERROR = 4;
        public static final Type<View> TYPE = new Type<>(id("casino_view"));

        private static void ints(FriendlyByteBuf b, int[] a) { b.writeVarInt(a.length); for (int x : a) b.writeVarInt(x); }
        private static int[] ints(FriendlyByteBuf b) {
            int n = Math.min(b.readVarInt(), 16);
            int[] a = new int[n];
            for (int i = 0; i < n; i++) a[i] = b.readVarInt();
            return a;
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of(
            (b, v) -> {
                b.writeBoolean(v.open); b.writeBlockPos(v.pos); b.writeVarInt(v.mode); b.writeUtf(v.casino, 32); b.writeUtf(v.owner, 32);
                b.writeUtf(v.minBet, 32); b.writeUtf(v.maxBet, 32); b.writeUtf(v.coverNote, 120); b.writeUtf(v.balance, 48);
                b.writeVarInt(v.resultKind); b.writeUtf(v.message, 200);
                ints(b, v.reels); b.writeVarInt(v.wheel + 1); ints(b, v.playerCards); ints(b, v.dealerCards);
                b.writeBoolean(v.dealerHidden); b.writeBoolean(v.inHand); b.writeBoolean(v.canDouble);
                b.writeVarInt(v.playerTotal); b.writeVarInt(v.dealerTotal); b.writeUtf(v.stake, 32);
                b.writeUtf(v.bank, 48); b.writeUtf(v.inPlay, 48); b.writeVarLong(v.rounds); b.writeUtf(v.staked, 48);
                b.writeUtf(v.returned, 48); b.writeUtf(v.houseResult, 48); b.writeUtf(v.game, 32);
            },
            b -> new View(b.readBoolean(), b.readBlockPos(), b.readVarInt(), b.readUtf(32), b.readUtf(32), b.readUtf(32), b.readUtf(32),
                b.readUtf(120), b.readUtf(48), b.readVarInt(), b.readUtf(200), ints(b), b.readVarInt() - 1, ints(b), ints(b),
                b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readUtf(32),
                b.readUtf(48), b.readUtf(48), b.readVarLong(), b.readUtf(48), b.readUtf(48), b.readUtf(48), b.readUtf(32)));
        @Override public Type<View> type() { return TYPE; }
    }
}
