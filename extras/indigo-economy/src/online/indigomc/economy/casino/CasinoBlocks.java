package online.indigomc.economy.casino;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import online.indigomc.economy.IndigoEconomy;
import online.indigomc.economy.core.CasinoGames.Game;
import online.indigomc.economy.net.WalletPayloads;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Casino block, item and block entity registration (0.5.0). */
public final class CasinoBlocks {
    private CasinoBlocks() {}

    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(WalletPayloads.MODID);
    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WalletPayloads.MODID);
    static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, WalletPayloads.MODID);

    private static BlockBehaviour.Properties props(MapColor color, SoundType sound) {
        return BlockBehaviour.Properties.of().mapColor(color).strength(2.5f, 6f).sound(sound).noOcclusion();
    }

    public static final DeferredBlock<CasinoBlock> SLOT_MACHINE = BLOCKS.register("slot_machine",
        () -> new CasinoBlock(Game.SLOTS, props(MapColor.COLOR_RED, SoundType.METAL)));
    public static final DeferredBlock<CasinoBlock> BLACKJACK_TABLE = BLOCKS.register("blackjack_table",
        () -> new CasinoBlock(Game.BLACKJACK, props(MapColor.COLOR_GREEN, SoundType.WOOD)));
    public static final DeferredBlock<CasinoBlock> ROULETTE_TABLE = BLOCKS.register("roulette_table",
        () -> new CasinoBlock(Game.ROULETTE, props(MapColor.COLOR_GREEN, SoundType.WOOD)));

    public static final DeferredItem<BlockItem> SLOT_MACHINE_ITEM = ITEMS.registerSimpleBlockItem("slot_machine", SLOT_MACHINE);
    public static final DeferredItem<BlockItem> BLACKJACK_TABLE_ITEM = ITEMS.registerSimpleBlockItem("blackjack_table", BLACKJACK_TABLE);
    public static final DeferredItem<BlockItem> ROULETTE_TABLE_ITEM = ITEMS.registerSimpleBlockItem("roulette_table", ROULETTE_TABLE);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CasinoTableEntity>> TABLE_ENTITY = ENTITIES.register("casino_table",
        () -> entityType(SLOT_MACHINE.get(), BLACKJACK_TABLE.get(), ROULETTE_TABLE.get()));

    /**
     * BlockEntityType.Builder.of(CasinoTableEntity::new, blocks).build(null). Its supplier interface is package-private in
     * the plain Minecraft jar this mod compiles against (NeoForge's access transformer makes it public at runtime), so the
     * call is made reflectively; at runtime it is the ordinary builder.
     */
    @SuppressWarnings("unchecked")
    private static BlockEntityType<CasinoTableEntity> entityType(Block... blocks) {
        try {
            Class<?> sup = Class.forName("net.minecraft.world.level.block.entity.BlockEntityType$BlockEntitySupplier");
            Object supplier = Proxy.newProxyInstance(sup.getClassLoader(), new Class<?>[]{sup}, (proxy, m, args) -> switch (m.getName()) {
                case "create" -> new CasinoTableEntity((BlockPos) args[0], (BlockState) args[1]);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "indigo_economy casino table supplier";
                default -> throw new UnsupportedOperationException(m.getName());
            });
            Method of = BlockEntityType.Builder.class.getMethod("of", sup, Block[].class);
            of.setAccessible(true);
            BlockEntityType.Builder<?> b = (BlockEntityType.Builder<?>) of.invoke(null, supplier, blocks);
            return (BlockEntityType<CasinoTableEntity>) b.build(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not register the casino block entity", e);
        }
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey().location().equals(ResourceLocation.withDefaultNamespace("functional_blocks"))) {
                e.accept(SLOT_MACHINE_ITEM.get());
                e.accept(BLACKJACK_TABLE_ITEM.get());
                e.accept(ROULETTE_TABLE_ITEM.get());
            }
        });
        // only the casino's owner (or staff) can take a table or machine away; claims protect them as well
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (!(e.getState().getBlock() instanceof CasinoBlock)) return;
            if (!(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel().getBlockEntity(e.getPos()) instanceof CasinoTableEntity t)) return;
            if (CasinoService.mayRemove(p, t)) return;
            e.setCanceled(true);
            p.displayClientMessage(Component.literal("Only the casino's owner can take this away."), true);
            IndigoEconomy.LOG.info("INDIGO_CASINO_BREAK_REFUSED {} at {}", p.getGameProfile().getName(), e.getPos());
        });
    }
}
