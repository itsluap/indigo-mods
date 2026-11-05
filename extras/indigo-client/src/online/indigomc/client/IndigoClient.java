package online.indigomc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Indigo's client defaults. Client-only; the server never sees it.
 *
 * <p>On the first launch of an instance that contains Indigo's texture packs (the Indigo and Indigo Far packs ship
 * Faithful 32x and the 32x packs for Create, Mekanism and Supplementaries), it switches them on in the right order,
 * then records that in {@code config/indigo_client-defaults.txt} and never touches resource packs again. A player who
 * later turns Faithful off, or switches to Faithful 64x, keeps that choice across pack updates: the marker lives in
 * {@code config/}, which a pack update does not replace. The pack cannot ship {@code options.txt} for this, because an
 * update would then overwrite every player's settings. Instances without those packs (Indigo Performance) are left alone.
 */
@Mod(value = "indigo_client", dist = Dist.CLIENT)
public final class IndigoClient {
    static final Logger LOG = LoggerFactory.getLogger("indigo_client");
    static final String MARKER = "config/indigo_client-defaults.txt";
    /** Lowest priority first (later entries override earlier ones): the vanilla base, then mod-specific packs. */
    static final String[] DEFAULT_PACK_PREFIXES = {
        "file/Faithful 32x",
        "file/[1.21.1_ver_0.06]_Improved_Create_32x", "file/Improved_Create_32x", "file/Improved Create 32x",
        "file/Upscaled Mekanism 32x",
        "file/Upscaled Supplementaries 32x",
    };
    private boolean checked = false;

    public IndigoClient() {
        NeoForge.EVENT_BUS.addListener(this::onScreen);
    }

    private void onScreen(ScreenEvent.Init.Post e) {
        if (checked || !(e.getScreen() instanceof TitleScreen)) return;
        checked = true;
        try {
            applyDefaultsOnce(Minecraft.getInstance());
        } catch (Exception ex) {
            LOG.warn("INDIGO_CLIENT could not apply the default resource packs; the game is unaffected", ex);
        }
    }

    static void applyDefaultsOnce(Minecraft mc) throws Exception {
        Path marker = mc.gameDirectory.toPath().resolve(MARKER);
        if (Files.exists(marker)) {
            LOG.info("INDIGO_CLIENT defaults already applied once ({}); leaving resource packs to the player", MARKER);
            return;
        }
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        List<String> wanted = new ArrayList<>();
        for (String prefix : DEFAULT_PACK_PREFIXES)
            for (String id : repo.getAvailableIds())
                if (id.startsWith(prefix) && !wanted.contains(id)) wanted.add(id);
        if (wanted.isEmpty()) {
            LOG.info("INDIGO_CLIENT no Indigo texture packs in this instance (Indigo Performance?); nothing to do");
            return;
        }
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        List<String> added = new ArrayList<>();
        for (String id : wanted)
            if (!selected.contains(id)) { selected.add(id); added.add(id); }
        repo.setSelected(selected);
        mc.options.updateResourcePacks(repo);   // saves options.txt and reloads resources when the list changed
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "Indigo default texture packs were switched on once: " + String.join(", ", added)
            + "\nDelete this file to have them switched on again at the next launch.\n");
        List<String> now = new ArrayList<>();
        for (Pack p : repo.getSelectedPacks()) now.add(p.getId());
        LOG.info("INDIGO_CLIENT_DEFAULTS_APPLIED added={} selected={}", added, now);
    }
}
