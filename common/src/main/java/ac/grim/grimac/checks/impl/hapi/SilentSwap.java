package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Tek istemci tickinde: eldeki slotu degistir -> esya kullan / vur / blok koy / kazmaya basla -> eski slota don.
 * Vanilla istemci bir tickte once tum slot tuslarini, sonra eylem tuslarini isler; bu sira vanilla'da olusamaz.
 * AutoPot, sessiz slot degisimli scaffold / kristal, topuz ve nitelik takasi (attribute swap) bu imzayi birakir.
 */
@CheckData(name = "SilentSwap", stableKey = "hapi.inventory.silent_swap", description = "Tek tickte slot degistir-kullan-geri don (sessiz takas)", decay = 0.02)
public final class SilentSwap extends Check {
    private int hits;
    private int firstHitTick;

    public SilentSwap(@NotNull GrimPlayer player) {
        super(player);
    }

    void onSilentSwap(int tick, int from, int via) {
        if (hits == 0 || tick - firstHitTick > 200) {
            hits = 0;
            firstHitTick = tick;
        }
        if (++hits >= 3) {
            flag("slot " + from + ">" + via + ">" + from + " x" + hits);
            hits = 0;
        }
    }
}
