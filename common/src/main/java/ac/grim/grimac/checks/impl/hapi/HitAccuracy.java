package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Kusursuz isabet: dovus penceresinde 80 sallamanin tamami oyuncuya isabet etmis ve bunlarin en az
 * yarisi nisan hareket halindeyken atilmis. TriggerBot ve KillAura hic bosa sallamaz; insan,
 * hareketli hedefte arada iskalar. Tek basina kanit degildir (yakin mesafede iyi oyuncu da
 * tutturabilir) — bu yuzden varsayilan alarm esigi 2 penceredir ve guven puani icin bir girdidir.
 */
@CheckData(name = "HitAccuracy", stableKey = "hapi.combat.accuracy", description = "Dovuste hic iskalamama (triggerbot/killaura)", decay = 0.02)
public final class HitAccuracy extends Check {
    private static final int WINDOW = 80;
    private int swings;
    private int hits;
    private int dynamicHits;

    public HitAccuracy(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    protected double defaultAlertFrom() {
        return 2;
    }

    /** Dovus penceresindeki her tick: o tickteki sallama ve oyuncuya vurus sayisi. */
    void onTick(int tickSwings, int tickPlayerHits) {
        if (tickSwings == 0) return;
        swings += tickSwings;
        hits += Math.min(tickPlayerHits, tickSwings);
        if (swings < WINDOW) return;

        if (hits >= swings && dynamicHits >= WINDOW / 2) {
            flag("isabet=" + hits + "/" + swings + " hareketli=" + dynamicHits);
        } else {
            reward();
        }
        swings = hits = dynamicHits = 0;
    }

    /** Vurus cevresindeki donusler: nisan hareket halindeyken atilan vuruslari sayar. */
    void onAttackWindow(float prev, float cur, float next) {
        if (Math.abs(prev) > 2f || Math.abs(cur) > 2f || Math.abs(next) > 2f) dynamicHits++;
    }
}
