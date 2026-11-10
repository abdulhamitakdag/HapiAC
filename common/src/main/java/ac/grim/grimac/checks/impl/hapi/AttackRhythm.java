package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Vurus ritmi (TriggerBot / AutoClicker / bekleme suresine kilitli KillAura).
 * Araliklar istemci tick'i ile olculur, yani ag gecikmesinden etkilenmez.
 * <ul>
 * <li><b>yavas</b> (aralik &gt;= 5 tick, 1.9+ bekleme sureli dovus): 12 ardisik araligin standart sapmasi
 * yarim tick'in altindaysa vuruslari bir zamanlayici atiyor demektir; insan ritmi 1-3 tick oynar.</li>
 * <li><b>hizli</b> (aralik &lt;= 4 tick): 30 kez ust uste birebir ayni aralik.</li>
 * </ul>
 */
@CheckData(name = "AttackRhythm", stableKey = "hapi.combat.rhythm", description = "Makine duzeninde vurus araliklari (triggerbot/autoclicker)", decay = 0.02)
public final class AttackRhythm extends Check {
    private static final int WINDOW = 12;
    private static final int FAST_RUN = 30;
    private final int[] intervals = new int[WINDOW];
    private double maxStd; // onReload atar (super() icinde)
    private int count;
    private int lastAttackTick = Integer.MIN_VALUE / 2;
    private int lastInterval;
    private int fastRun;

    public AttackRhythm(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        maxStd = config.getDoubleElse("hapi.combat.attack-rhythm.max-interval-stddev", 0.45);
    }

    void onAttack(int tick) {
        final int interval = tick - lastAttackTick;
        lastAttackTick = tick;
        if (interval <= 0) return;
        if (interval > 40) { // dovus koptu
            count = 0;
            fastRun = 0;
            lastInterval = 0;
            return;
        }

        if (interval <= 4 && interval == lastInterval) {
            if (++fastRun >= FAST_RUN) {
                flag("hizli aralik=" + interval + " tick x" + (fastRun + 1));
                fastRun = 0;
            }
        } else {
            fastRun = 0;
        }
        lastInterval = interval;

        intervals[count++] = interval;
        if (count < WINDOW) return;
        count = 0;

        int min = Integer.MAX_VALUE;
        double sum = 0, sumSq = 0;
        for (int value : intervals) {
            if (value < min) min = value;
            sum += value;
            sumSq += (double) value * value;
        }
        if (min < 5) return; // hizli tiklama rejimi tick cozunurlugunde ayirt edilemez

        final double mean = sum / WINDOW;
        final double std = Math.sqrt(Math.max(0, sumSq / WINDOW - mean * mean));
        if (std < maxStd) {
            flag("yavas ort=" + String.format("%.2f", mean) + " std=" + String.format("%.3f", std) + " n=" + WINDOW);
        } else {
            reward();
        }
    }
}
