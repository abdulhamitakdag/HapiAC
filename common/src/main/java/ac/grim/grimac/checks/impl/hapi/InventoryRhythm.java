package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Farkli slotlara ust uste "sample-size" tiklamanin hepsi birebir ayni tick araligiyla geliyorsa:
 * sabit gecikmeli ChestStealer / InventoryManager. Insan tiklamalari 50 ms'lik tick kutularina dagilir
 * (2,3,2,4...). Shift basili surukleme tick basina 0-2 slot verir; bu yuzden 1 tick aralikta esik 1,5 katidir.
 */
@CheckData(name = "InventoryRhythm", stableKey = "hapi.inventory.rhythm", description = "Sabit araliklarla slot tiklama (ChestStealer / InventoryManager)", decay = 0.05)
public final class InventoryRhythm extends Check {
    private static final int NEVER = Integer.MIN_VALUE / 2;

    private int sampleSize; // onReload atar (super() icinde)
    private int lastClickTick = NEVER;
    private int lastGap;
    private int same;

    public InventoryRhythm(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        sampleSize = config.getIntElse("hapi.inventory.rhythm.sample-size", 15);
    }

    void onTick(int tick, int distinctClicks) {
        if (distinctClicks == 0) return;
        final int gap = tick - lastClickTick;
        lastClickTick = tick;
        if (distinctClicks > 1 || gap < 1 || gap > 10) {
            if (same >= sampleSize / 2) reward();
            same = 0;
            lastGap = 0;
            return;
        }
        if (gap == lastGap) {
            same++;
        } else {
            same = 1;
            lastGap = gap;
        }
        // "same" ayni araligin kac kez tekrarlandigi; n aralik = n+1 tiklama
        if (same + 1 >= (gap == 1 ? sampleSize + sampleSize / 2 : sampleSize)) {
            flag("aralik=" + gap + " tick x" + (same + 1));
            same = 0;
            lastGap = 0;
        }
    }
}
