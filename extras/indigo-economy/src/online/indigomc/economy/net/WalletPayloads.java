package online.indigomc.economy.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Wallet network messages. The client only ever sends INTENTS (open, send X to Y, make a link code);
 * the server validates and answers with its own authoritative state. No payload carries a balance
 * from client to server.
 */
public final class WalletPayloads {
    public static final String MODID = "indigo_economy";

    private WalletPayloads() {}

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MODID, path); }

    /** C2S: 0 open, 1 refresh, 2 new link code, 3 unlink. */
    public record Request(int action) implements CustomPacketPayload {
        public static final int OPEN = 0, REFRESH = 1, LINK_CODE = 2, UNLINK = 3;
        public static final Type<Request> TYPE = new Type<>(id("wallet_request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC =
            StreamCodec.of((b, v) -> b.writeVarInt(v.action), b -> new Request(b.readVarInt()));
        @Override public Type<Request> type() { return TYPE; }
    }

    /** C2S: send money. requestId is fixed when the player reviews, so a double-confirm moves money once. */
    public record Transfer(String to, String amount, String note, String requestId) implements CustomPacketPayload {
        public static final Type<Transfer> TYPE = new Type<>(id("wallet_transfer"));
        public static final StreamCodec<FriendlyByteBuf, Transfer> CODEC = StreamCodec.of(
            (b, v) -> { b.writeUtf(v.to, 32); b.writeUtf(v.amount, 32); b.writeUtf(v.note, 80); b.writeUtf(v.requestId, 40); },
            b -> new Transfer(b.readUtf(32), b.readUtf(32), b.readUtf(80), b.readUtf(40)));
        @Override public Type<Transfer> type() { return TYPE; }
    }

    public record Row(String when, String label, String amount, boolean incoming, String balanceAfter, String note) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(when, 32); b.writeUtf(label, 64); b.writeUtf(amount, 32); b.writeBoolean(incoming);
            b.writeUtf(balanceAfter, 48); b.writeUtf(note, 96);
        }
        static Row read(FriendlyByteBuf b) {
            return new Row(b.readUtf(32), b.readUtf(64), b.readUtf(32), b.readBoolean(), b.readUtf(48), b.readUtf(96));
        }
    }

    /** S2C: the server's view of this player's wallet. open=false only refreshes a screen that is already open. */
    public record State(boolean open, long balanceMinor, String balance, String currency, boolean linked, String linkCode, int linkSeconds,
                        int resultKind, String result, List<Row> rows, List<String> recipients) implements CustomPacketPayload {
        public static final int NONE = 0, OK = 1, ERROR = 2, INFO = 3;
        public static final Type<State> TYPE = new Type<>(id("wallet_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of(
            (b, v) -> {
                b.writeBoolean(v.open); b.writeVarLong(v.balanceMinor); b.writeUtf(v.balance, 64); b.writeUtf(v.currency, 32); b.writeBoolean(v.linked);
                b.writeUtf(v.linkCode, 16); b.writeVarInt(v.linkSeconds); b.writeVarInt(v.resultKind); b.writeUtf(v.result, 200);
                b.writeVarInt(v.rows.size());
                for (Row r : v.rows) r.write(b);
                b.writeVarInt(v.recipients.size());
                for (String s : v.recipients) b.writeUtf(s, 16);
            },
            b -> {
                boolean open = b.readBoolean(); long minor = b.readVarLong(); String bal = b.readUtf(64), cur = b.readUtf(32); boolean linked = b.readBoolean();
                String code = b.readUtf(16); int secs = b.readVarInt(), kind = b.readVarInt(); String res = b.readUtf(200);
                int n = Math.min(b.readVarInt(), 50);
                List<Row> rows = new ArrayList<>();
                for (int i = 0; i < n; i++) rows.add(Row.read(b));
                int m = Math.min(b.readVarInt(), 500);
                List<String> rec = new ArrayList<>();
                for (int i = 0; i < m; i++) rec.add(b.readUtf(16));
                return new State(open, minor, bal, cur, linked, code, secs, kind, res, rows, rec);
            });
        @Override public Type<State> type() { return TYPE; }
    }
}
