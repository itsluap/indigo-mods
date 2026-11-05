package online.indigomc.economy.casino;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * One casino table or machine in the world: which casino it belongs to and the owner's bet limits (minor units).
 * No money is ever stored here; the house bank and every bet live in the ledger.
 */
public final class CasinoTableEntity extends BlockEntity {
    String casinoId = "";
    UUID placer;
    long minBet = 100, maxBet = 1000;

    public CasinoTableEntity(BlockPos pos, BlockState state) {
        super(CasinoBlocks.TABLE_ENTITY.get(), pos, state);
    }

    public String casinoId() { return casinoId; }
    public UUID placer() { return placer; }
    public long minBet() { return minBet; }
    public long maxBet() { return maxBet; }

    void bind(String id) { casinoId = id; setChanged(); }
    void limits(long min, long max) { minBet = min; maxBet = max; setChanged(); }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Casino", casinoId);
        if (placer != null) tag.putUUID("Placer", placer);
        tag.putLong("MinBet", minBet);
        tag.putLong("MaxBet", maxBet);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        casinoId = tag.getString("Casino");
        placer = tag.hasUUID("Placer") ? tag.getUUID("Placer") : null;
        if (tag.contains("MinBet")) minBet = tag.getLong("MinBet");
        if (tag.contains("MaxBet")) maxBet = tag.getLong("MaxBet");
    }
}
