package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * End kristalini istemcide gorundugu anda, tek ve bosa gitmeyen bir tikla kirma (CrystalAura / AutoCrystal).
 * <p>
 * Hizli kristal oynayan insan sol tusa surekli basar: kristal gorunur gorunmez kirilabilir ama arada bosa
 * giden tiklamalar (havaya / obsidyene kol sallama) olur. Hile ise kristal basina tam bir vurus yollar.
 * Ust uste "sample-size" kristalin hepsi en cok "max-ticks" tick icinde ve arada hic bos tiklama olmadan
 * kirilirsa flag.
 */
@CheckData(name = "CrystalSpeed", stableKey = "hapi.crystal.speed", description = "Kristali gorunur gorunmez, bos tiklamasiz kirma (CrystalAura)", decay = 0.02)
public final class CrystalSpeed extends Check {
    private static final int MAX_GAP_TICKS = 60;

    private int sampleSize; // onReload atar (super() icinde)
    private int maxTicks;

    private int streak;
    private int lastBreakTick;

    public CrystalSpeed(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        sampleSize = config.getIntElse("hapi.crystal.speed.sample-size", 12);
        maxTicks = config.getIntElse("hapi.crystal.speed.max-ticks", 1);
    }

    void onBreak(int tick, int reactionTicks, int wastedSwings) {
        if (tick - lastBreakTick > MAX_GAP_TICKS) streak = 0;
        lastBreakTick = tick;

        if (reactionTicks > maxTicks || wastedSwings > 0) {
            if (streak >= sampleSize / 2) reward();
            streak = 0;
            return;
        }
        if (++streak >= sampleSize) {
            flag("tepki<=" + maxTicks + " tick, bos tiklama yok x" + streak);
            streak = 0;
        }
    }
}
