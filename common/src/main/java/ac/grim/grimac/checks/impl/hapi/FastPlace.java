package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Vanilla'da sag tus basili tutulunca bloklar 4 tickte bir konur; FastPlace bu beklemeyi kaldirir ve
 * her tick tam bir blok koyma paketi yollar. Hizli tiklayan insan (surukleme tiklamasi dahil) tick basina
 * 0, 1, 2 paket arasinda dalgalanir; ust uste "consecutive-ticks" tick boyunca tam birer paket yollayamaz.
 */
@CheckData(name = "FastPlace", stableKey = "hapi.build.fast_place", description = "Her tick tam bir blok koyma (FastPlace / autoclicker)", decay = 0.02)
public final class FastPlace extends Check {
    private int needed; // onReload atar (super() icinde)
    private int streak;

    public FastPlace(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        needed = config.getIntElse("hapi.build.fast-place.consecutive-ticks", 20);
    }

    void onTick(int places) {
        if (places != 1) {
            if (streak > 0 && places == 0) reward();
            streak = 0;
            return;
        }
        if (++streak >= needed) {
            flag("ardisik=" + streak + " tick");
            streak = 0;
        }
    }
}
