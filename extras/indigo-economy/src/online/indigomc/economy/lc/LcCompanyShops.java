package online.indigomc.economy.lc;

import io.github.lightman314.lightmanscurrency.api.misc.player.PlayerReference;
import io.github.lightman314.lightmanscurrency.api.traders.TraderData;
import io.github.lightman314.lightmanscurrency.api.traders.blockentity.TraderBlockEntity;
import io.github.lightman314.lightmanscurrency.api.traders.blocks.ITraderBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import online.indigomc.economy.EconomyService;
import online.indigomc.economy.core.CompanyConsole;

import java.util.Optional;
import java.util.UUID;

/**
 * Which Lightman's Currency trader a player is looking at, for {@code /company shop ...}. Everything is read on the
 * server from the world and LC's own trader data (the trader id is LC's persistent counter id, and the owner is LC's
 * valid owner), so a client can't name someone else's trader or claim to own one.
 */
public final class LcCompanyShops {
    private LcCompanyShops() {}

    public static Optional<CompanyConsole.ShopRef> lookedAt(UUID player) {
        EconomyService s = EconomyService.get();
        if (s == null) return Optional.empty();
        ServerPlayer p = s.server.getPlayerList().getPlayer(player);
        if (p == null) return Optional.empty();
        HitResult hit = p.pick(5.0, 0f, false);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) return Optional.empty();
        BlockPos pos = bh.getBlockPos();
        BlockState state = p.level().getBlockState(pos);
        if (!(state.getBlock() instanceof ITraderBlock tb)) return Optional.empty();
        BlockEntity be = tb.getBlockEntity(state, p.level(), pos);   // multi-block traders resolve to their core block
        if (!(be instanceof TraderBlockEntity<?> tbe)) return Optional.empty();
        TraderData t = tbe.getRawTraderData();
        if (t == null || t.getID() < 0) return Optional.empty();
        PlayerReference owner = t.getOwner().getPlayerForContext();
        // the same test LcShops uses to burn a sale instead of paying an owner: such a trader has no owner to route for
        boolean admin = !t.canStoreMoney() || owner == null;
        return Optional.of(new CompanyConsole.ShopRef(String.valueOf(t.getID()), t.getName().getString(), admin ? null : owner.id));
    }
}
