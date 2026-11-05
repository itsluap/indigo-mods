package online.indigomc.economy.lc;

import io.github.lightman314.lightmanscurrency.api.events.TradeEvent;
import io.github.lightman314.lightmanscurrency.api.misc.player.PlayerReference;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyStorage;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.traders.TraderData;
import io.github.lightman314.lightmanscurrency.api.traders.trade.TradeData;
import io.github.lightman314.lightmanscurrency.common.traders.gacha.GachaTrader;
import io.github.lightman314.lightmanscurrency.common.traders.item.ItemTraderData;
import io.github.lightman314.lightmanscurrency.common.traders.slot_machine.SlotMachineTraderData;
import io.github.lightman314.lightmanscurrency.common.traders.item.tradedata.ItemTradeData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import online.indigomc.economy.EconomyService;
import online.indigomc.economy.IndigoEconomy;
import online.indigomc.economy.core.Json;
import online.indigomc.economy.core.Ledger;
import online.indigomc.economy.net.WalletPayloads;

import java.util.UUID;

/**
 * Settles Lightman's Currency trades priced in Indigo Credits through the Indigo ledger: item shops (sales), and
 * from 0.4.4 player-run slot machines and gacha machines (a paid spin or pull; the prize is an item the owner stocked).
 *
 * <p>LC runs a sale as: pre-trade event, customer pays (our handler), goods move, price is added to the
 * trader's own storage, post-trade event, all synchronously on the server thread. We map that onto the
 * ledger as one hold per trade:
 * <pre>
 *   buyer --hold--> system:shop-escrow --settle--> owner      (or --burn--> treasury for admin shops)
 *                                     --refund--> buyer        (trade failed after payment)
 * </pre>
 * The price LC puts into the trader's storage is removed again in the same call, so no LC storage or
 * bank ever holds Indigo value. Every other balance change (Discord included) runs on this same thread,
 * so LC's "check, then take" payment cannot be raced. Anything left open (an exception mid-trade) is
 * refunded at the end of the tick; anything open after a crash is refunded on the next start.
 */
public final class LcShops {
    /** The Indigo-priced trade executing right now on the server thread, or null. */
    private static Pending current;

    private static final class Pending {
        final String holdId = UUID.randomUUID().toString();
        final TraderData trader;
        final UUID buyer;
        final long price;
        final String what;
        final Kind kind;
        boolean held;

        Pending(TraderData trader, UUID buyer, long price, String what, Kind kind) {
            this.trader = trader;
            this.buyer = buyer;
            this.price = price;
            this.what = what;
            this.kind = kind;
        }
    }

    /** What the customer paid for: a shop sale, or a spin/pull of a player-run chance machine. */
    enum Kind {
        SHOP("Shop", "Shop sale"), SLOT("Slot machine", "Slot machine spin"), GACHA("Gacha", "Gacha pull");
        final String hold, settle;
        Kind(String hold, String settle) { this.hold = hold; this.settle = settle; }
    }

    private LcShops() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(LcShops::preTrade);
        NeoForge.EVENT_BUS.addListener(LcShops::postTrade);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> sweep("tick end"));
    }

    private static boolean indigo(MoneyValue v) { return v instanceof IndigoCreditValue; }

    private static void preTrade(TradeEvent.PreTradeEvent e) {
        TraderData trader = e.getTrader();
        if (trader == null || trader.isClient()) return;
        TradeData trade = e.getTrade();
        MoneyValue price = trade.getCost(e.getContext());
        if (!indigo(price)) {
            // Indigo Credits are the only money: coins, emeralds, ancient coins or any other LC currency can't pay
            if (!price.isFree() && !price.isEmpty())
                e.addDenial(Component.literal("This shop is priced in retired coins. Ask the owner to set an Indigo Credits price."));
            return;
        }
        final Kind kind;
        final String what;
        if (trader instanceof ItemTraderData && trade instanceof ItemTradeData itd) {
            if (itd.isPurchase()) {
                e.addDenial(Component.literal("Shops can't buy items for Indigo Credits yet. Sell to players instead."));
                return;
            }
            if (!itd.isSale()) return;   // barter: no money involved
            ItemStack sold = itd.getSellItem(0);
            kind = Kind.SHOP;
            what = sold.isEmpty() ? "items" : sold.getCount() + "x " + sold.getHoverName().getString();
        } else if (trader instanceof SlotMachineTraderData) {
            kind = Kind.SLOT;
            what = "a spin at " + trader.getName().getString();
        } else if (trader instanceof GachaTrader) {
            kind = Kind.GACHA;
            what = "a pull at " + trader.getName().getString();
        } else {
            e.addDenial(Component.literal("Indigo Credits work at item shops, slot machines and gacha machines."));
            return;
        }
        if (trader.isLinkedToBank()) {
            e.addDenial(Component.literal("This shop is linked to an LC bank account. Unlink it: Indigo Credits go straight to the owner's wallet."));
            return;
        }
        if (!trader.getApplicableTaxes(e.getContext().getTaxContext()).isEmpty()) {
            e.addDenial(Component.literal("Taxes don't apply to Indigo Credits. Ask staff to remove the tax collector."));
            return;
        }
        EconomyService s = EconomyService.get();
        if (s == null || !s.ok()) {
            e.addDenial(Component.literal("The Indigo economy is unavailable right now."));
            return;
        }
        if (!e.hasPlayerReference()) return;
        sweep("new trade");   // never let one trade inherit another's state
        current = new Pending(trader, e.getPlayerReference().id, ((IndigoCreditValue) price).minor(), what, kind);
    }

    /** Customer payment (LC calls this twice: simulate, then for real). All or nothing. */
    static boolean pay(Player player, long minor, boolean simulate) {
        EconomyService s = EconomyService.get();
        Pending p = current;
        if (s == null || !s.ok() || p == null || !p.buyer.equals(player.getUUID()) || p.price != minor || p.held) return false;
        if (simulate) return s.ledger.balance(player.getUUID()) >= minor;
        try {
            s.ledger.hold(p.holdId, player.getUUID(), minor, p.kind.hold + ": " + p.what,
                Json.obj("trader", String.valueOf(p.trader.getID()), "shop", p.trader.getName().getString(), "item", p.what,
                    "kind", p.kind.name().toLowerCase()));
            p.held = true;
            return true;
        } catch (Ledger.LedgerError err) {
            IndigoEconomy.LOG.error("INDIGO_LC_PAYMENT_FAILED_AFTER_CHECK trade={} code={} (trade may complete unpaid; staff review)", p.holdId, err.code);
            return false;
        }
    }

    /** LC returning a payment after a failed trade (no room for the goods, etc.). */
    static boolean refundToBuyer(Player player, long minor, boolean simulate) {
        EconomyService s = EconomyService.get();
        Pending p = current;
        if (s == null || p == null || !p.held || !p.buyer.equals(player.getUUID()) || p.price != minor) return false;
        if (simulate) return true;
        try {
            s.ledger.refund(p.holdId, "Shop trade failed: " + p.what);
            p.held = false;
            return true;
        } catch (Ledger.LedgerError err) {
            IndigoEconomy.LOG.error("INDIGO_LC_REFUND_FAILED trade={} code={}", p.holdId, err.code);
            return false;
        }
    }

    private static void postTrade(TradeEvent.PostTradeEvent e) {
        Pending p = current;
        if (p == null || e.getTrader() != p.trader) return;
        current = null;
        if (!p.held) return;
        EconomyService s = EconomyService.get();
        MoneyValue paid = e.getPricePaid();
        TraderData trader = p.trader;
        // LC just stored the price in the trader; the ledger holds the real money, so take it back out
        MoneyStorage storage = trader.getInternalStoredMoney();
        if (indigo(paid) && storage.containsValue(paid)) storage.removeValue(paid);
        String won = p.kind == Kind.SHOP ? "" : prize(e);
        try {
            Ledger.Tx tx;
            PlayerReference owner = trader.getOwner().getPlayerForContext();
            if (!trader.canStoreMoney() || owner == null) {
                tx = s.ledger.burn(p.holdId, "Admin " + p.kind.settle.toLowerCase() + ": " + p.what).tx();
            } else {
                // open the owner's account if it has none, but never rename it from here: for an LC team-owned trader the
                // context name is the TEAM's name (TeamOwner.asPlayerReference), which would re-point /pay <name> lookups (0.5.1)
                if (s.ledger.account(owner.id).isEmpty()) s.ledger.ensureAccount(owner.id, owner.getName(false), "shop");
                // Indigo companies: a trader assigned to a company pays its treasury (the registry decides, under the ledger
                // lock, in the same journal line); unassigned traders settle to the owner exactly as before
                tx = s.ledger.settleShop(p.holdId, String.valueOf(trader.getID()), owner.id,
                    p.kind.settle + ": " + p.what + (won.isEmpty() ? "" : " (won " + won + ")")).tx();
            }
            String company = Json.str(tx.meta(), "company_route_skipped") == null ? Json.str(tx.meta(), "company") : null;
            IndigoEconomy.LOG.info("INDIGO_LC_SALE tx={} hold={} kind={} buyer={} owner={} company={} price={} item={} won={}", tx.txid(), p.holdId,
                p.kind, p.buyer, owner == null ? "treasury" : owner.getName(false), company == null ? "-" : company, p.price, p.what, won);
            notify(s, p, owner, won, company == null ? null : s.ledger.displayName(company));
        } catch (Ledger.LedgerError err) {
            IndigoEconomy.LOG.error("INDIGO_LC_SETTLE_FAILED hold={} code={} (hold stays open and is refunded on next start)", p.holdId, err.code);
        }
    }

    /** What a spin or pull paid out, from LC's post-trade product list (items; a gacha ball holds its prize). */
    private static String prize(TradeEvent.PostTradeEvent e) {
        StringBuilder b = new StringBuilder();
        for (Object o : e.getProduct())
            if (o instanceof ItemStack st && !st.isEmpty())
                b.append(b.length() == 0 ? "" : ", ").append(st.getCount()).append("x ").append(st.getHoverName().getString());
        return b.length() == 0 ? "nothing" : b.toString();
    }

    private static void notify(EconomyService s, Pending p, PlayerReference owner, String won, String company) {
        String price = IndigoCreditValue.plain(p.price) + " Indigo Credits";
        ServerPlayer buyer = s.server.getPlayerList().getPlayer(p.buyer);
        String toBuyer = switch (p.kind) {
            case SHOP -> "Bought " + p.what + " for " + price + ".";
            case SLOT -> "Spun for " + price + ": won " + won + ".";
            case GACHA -> "Pulled for " + price + ": got " + won + ".";
        };
        if (buyer != null) s.push(buyer, false, WalletPayloads.State.OK, toBuyer, "", 0);
        if (owner != null && !owner.id.equals(p.buyer)) {
            ServerPlayer o = s.server.getPlayerList().getPlayer(owner.id);
            String toOwner = switch (p.kind) {
                case SHOP -> "Your shop sold " + p.what + " for " + price + ".";
                case SLOT -> "Your slot machine took " + price + " for a spin.";
                case GACHA -> "Your gacha machine took " + price + " for a pull.";
            };
            if (company != null) toOwner = toOwner.substring(0, toOwner.length() - 1) + ", paid to the " + company + " treasury.";
            if (o != null) s.push(o, false, WalletPayloads.State.INFO, toOwner, "", 0);
        }
    }

    /** A trade that started but never finished (exception, or LC stopped after payment): give the money back. */
    private static void sweep(String why) {
        Pending p = current;
        if (p == null) return;
        current = null;
        if (!p.held) return;
        EconomyService s = EconomyService.get();
        MoneyValue v = IndigoCreditValue.of(p.price);
        MoneyStorage storage = p.trader.getInternalStoredMoney();
        if (storage.containsValue(v)) storage.removeValue(v);
        try {
            s.ledger.refund(p.holdId, "Shop trade did not finish (" + why + "): " + p.what);
            IndigoEconomy.LOG.warn("INDIGO_LC_REFUNDED_UNFINISHED hold={} buyer={} reason={}", p.holdId, p.buyer, why);
        } catch (Ledger.LedgerError err) {
            IndigoEconomy.LOG.error("INDIGO_LC_SWEEP_FAILED hold={} code={}", p.holdId, err.code);
        }
    }
}
