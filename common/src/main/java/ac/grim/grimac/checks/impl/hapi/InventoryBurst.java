package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Tek istemci tickinde cok sayida FARKLI slota tiklama (aninda sandik bosaltma / envanter duzenleme).
 * Vanilla'da shift basili surukleme tickte birkac slota ulasabilir; varsayilan sinir bunun ustundedir.
 */
@CheckData(name = "InventoryBurst", stableKey = "hapi.inventory.burst", description = "Tek tickte cok sayida slota tiklama (ChestStealer)", decay = 0.02)
public final class InventoryBurst extends Check {
    private int maxPerTick; // onReload atar (super() icinde)

    public InventoryBurst(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        maxPerTick = config.getIntElse("hapi.inventory.burst.max-slots-per-tick", 6);
    }

    void onTick(int distinctClicks) {
        if (distinctClicks > maxPerTick) flag("slot/tick=" + distinctClicks);
    }
}
