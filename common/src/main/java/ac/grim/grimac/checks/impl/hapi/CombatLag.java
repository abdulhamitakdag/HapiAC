package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Dovuse ozel gecikme (Backtrack / LagRange / FakeLag): bu hileler gelen paketleri yalnizca rakip
 * yakindayken geciktirir; Grim'in gecikme telafisi bunu mesru ping sayar ve Reach flaglamaz. Imza,
 * oyuncunun KENDI dovus disi taban gecikmesine gore dovus sirasinda belirgin ve tekrarlayan artistir.
 * <p>
 * Olcum transaction gidis-donus suresidir (keepalive pingi degil). Taban dovus disinda yavas bir
 * ustel ortalamayla tutulur ve dovus baslarken dondurulur; dovus bitince dovus ortalamasi tabanla
 * karsilastirilir. Tek dovus kanit degildir (gercek ag dalgalanmasi): ust uste supheli dovus aranir,
 * temiz her dovus sayaci dusurur.
 */
@CheckData(name = "CombatLag", stableKey = "hapi.net.combat_lag", description = "Yalnizca dovuste artan gecikme (backtrack/fakelag)", decay = 0)
public final class CombatLag extends Check {
    private static final int MIN_BASE_TICKS = 200;
    private static final int MIN_FIGHT_TICKS = 40;
    private int minIncreaseMs; // onReload atar (super() icinde)
    private double minRatio;
    private int fightsToFlag;

    private double base;
    private int baseTicks;
    private boolean inFight;
    private double fightBase;
    private long fightSum;
    private int fightTicks;
    private int suspicious;

    public CombatLag(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        minIncreaseMs = config.getIntElse("hapi.net.combat-lag.min-increase-ms", 120);
        minRatio = config.getDoubleElse("hapi.net.combat-lag.min-ratio", 1.8);
        fightsToFlag = config.getIntElse("hapi.net.combat-lag.fights-to-flag", 3);
    }

    void onTick(boolean combat) {
        final int ping = player.getTransactionPing();

        if (combat) {
            if (!inFight) {
                inFight = true;
                fightBase = base;
                fightSum = 0;
                fightTicks = 0;
            }
            fightSum += ping;
            fightTicks++;
            return;
        }

        if (inFight) {
            inFight = false;
            if (fightTicks >= MIN_FIGHT_TICKS && baseTicks >= MIN_BASE_TICKS) {
                final double avg = (double) fightSum / fightTicks;
                if (avg - fightBase >= minIncreaseMs && avg >= fightBase * minRatio) {
                    if (++suspicious >= fightsToFlag) {
                        flag("baz=" + (int) fightBase + "ms dovus=" + (int) avg + "ms x" + suspicious);
                        suspicious = 0;
                    }
                } else if (suspicious > 0) {
                    suspicious--;
                }
            }
        }

        // taban: dovus disinda ~50 tickte yakinsayan ustel ortalama
        base = baseTicks == 0 ? ping : base + (ping - base) * 0.02;
        if (baseTicks < Integer.MAX_VALUE) baseTicks++;
    }
}
