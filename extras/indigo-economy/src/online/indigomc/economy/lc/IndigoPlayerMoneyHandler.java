package online.indigomc.economy.lc;

import io.github.lightman314.lightmanscurrency.api.money.types.IPlayerMoneyHandler;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyView;
import net.minecraft.world.entity.player.Player;
import online.indigomc.economy.EconomyService;

import javax.annotation.Nonnull;

/**
 * A player's Indigo Credits as Lightman's Currency sees them. It never keeps a balance of its own.
 *
 * <ul>
 * <li>Server: reads and moves the authoritative ledger, but only inside a trade that {@link LcShops} is tracking
 *     (item shop, slot machine, gacha machine). Any other LC money use (paygates, auctions, ATMs...) is refused.</li>
 * <li>Client: shows the balance the server last sent, for LC's "can I afford this" hints only.</li>
 * </ul>
 */
public final class IndigoPlayerMoneyHandler implements IPlayerMoneyHandler {
    private Player player;

    IndigoPlayerMoneyHandler(Player player) { this.player = player; }

    @Override public void updatePlayer(@Nonnull Player player) { this.player = player; }
    @Override public boolean isMoneyTypeValid(@Nonnull MoneyValue value) { return value instanceof IndigoCreditValue; }

    private long balance() {
        if (player.level().isClientSide()) return online.indigomc.economy.ClientBalance.minor;
        EconomyService s = EconomyService.get();
        return s == null || !s.ok() ? 0 : s.ledger.balance(player.getUUID());
    }

    @Nonnull
    @Override
    public MoneyView getStoredMoney() {
        long b = balance();
        return b > 0 ? MoneyView.singleton(IndigoCreditValue.of(b)) : MoneyView.builder().build();
    }

    /** All or nothing: returns the whole amount unpaid unless it can be paid in full. */
    @Nonnull
    @Override
    public MoneyValue extractMoney(@Nonnull MoneyValue amount, boolean simulation) {
        if (!(amount instanceof IndigoCreditValue c)) return amount;
        if (player.level().isClientSide()) return simulation && online.indigomc.economy.ClientBalance.minor >= c.minor() ? MoneyValue.empty() : amount;
        return LcShops.pay(player, c.minor(), simulation) ? MoneyValue.empty() : amount;
    }

    /** Only ever returns a failed shop payment to the buyer; LC can never pay credits out of thin air. */
    @Nonnull
    @Override
    public MoneyValue insertMoney(@Nonnull MoneyValue amount, boolean simulation) {
        if (!(amount instanceof IndigoCreditValue c)) return amount;
        if (player.level().isClientSide()) return amount;
        return LcShops.refundToBuyer(player, c.minor(), simulation) ? MoneyValue.empty() : amount;
    }
}
