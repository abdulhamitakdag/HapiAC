package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.hapi.HapiSezgi;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Sezgi: dovus olcutlerini tek tek esige vurmak yerine oyuncunun <b>kendi gecmisine</b> ve
 * <b>sunucudaki diger oyunculara</b> gore degerlendirir. Her biten dovus bir ornektir.
 * <ul>
 * <li><b>aykiri</b>: olcut nufusa gore surekli hile yonunde (birikimli toplam, CUSUM).</li>
 * <li><b>degisim</b>: oyuncunun kendi tabanina gore ani ve kalici kayma — hileyi actigi an.</li>
 * <li><b>zamanlama</b>: vuruslarin neredeyse hepsi bekleme suresinin doldugu ayni tickte
 * (nufus verisi gerektirmez; incelenen butun hile istemcilerinin ortak izi).</li>
 * </ul>
 * Olcutler {@link CombatTracker} tarafindan uretilir: kenar (bekleme dolar dolmaz vurus orani),
 * ani (nisan hedefe girdigi tickte vurus orani), merkez (vurus aninda nisanin hitbox merkezine
 * uzakligi), iz (hedefin yon degisimine gecikmesiz karsilik).
 */
@CheckData(name = "Sezgi", stableKey = "hapi.combat.sezgi", description = "Dovus davranisi kendi gecmisine ve diger oyunculara gore hile yonunde", decay = 0.01)
public final class Sezgi extends Check {
    private static final int P = HapiSezgi.PER_FEATURE;
    private static final int BASE = 0, BASE_N = 1, POP = 2, SELF = 3;

    // onReload atar (super() icinde)
    private int minPopulation, minBaseline, timingStreak;
    private double popSlack, popLimit, selfSlack, selfLimit, timingShare;

    private double[] profile;
    private int timingRun;

    public Sezgi(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        minPopulation = config.getIntElse("hapi.combat.sezgi.min-population", 100);
        minBaseline = config.getIntElse("hapi.combat.sezgi.min-baseline-fights", 10);
        popSlack = config.getDoubleElse("hapi.combat.sezgi.outlier-slack", 2.5);
        popLimit = config.getDoubleElse("hapi.combat.sezgi.outlier-limit", 8);
        selfSlack = config.getDoubleElse("hapi.combat.sezgi.change-slack", 1.0);
        selfLimit = config.getDoubleElse("hapi.combat.sezgi.change-limit", 8);
        timingShare = config.getDoubleElse("hapi.combat.sezgi.timing-share", 0.9);
        timingStreak = config.getIntElse("hapi.combat.sezgi.timing-streak", 3);
    }

    @Override
    protected double defaultAlertFrom() {
        return 1;
    }

    /**
     * @param x       olcut degerleri ({@link HapiSezgi#NAMES} sirasiyla); olculemeyen NaN
     * @param edgeN   bekleme suresi dolduktan sonra atilan vurus sayisi
     * @param attacks dovusteki toplam oyuncu vurusu
     */
    void onFight(double[] x, int edgeN, int attacks) {
        // zamanlama: ust uste dovus dilimlerinde vuruslarin neredeyse tamami ayni bekleme tickinde
        if (!Double.isNaN(x[0]) && edgeN >= 10) {
            if (x[0] >= timingShare) {
                if (++timingRun >= timingStreak) {
                    final double share = x[0];
                    flag("zamanlama kenar=" + fmt(share) + " vurus=" + attacks + " ust-uste=" + timingRun);
                    timingRun = 0;
                }
            } else {
                timingRun = 0;
            }
        }

        if (profile == null) profile = HapiSezgi.profile(player.uuid);
        int outliers = 0, changes = 0;
        double maxPop = 0, maxSelf = 0;
        final double[] zs = new double[HapiSezgi.FEATURES];
        for (int f = 0; f < HapiSezgi.FEATURES; f++) {
            zs[f] = Double.NaN;
            if (Double.isNaN(x[f])) continue;
            double z = HapiSezgi.scoreAndAdd(f, x[f], minPopulation);
            if (Double.isNaN(z)) continue;
            z = Math.max(-3, Math.min(6, z));
            zs[f] = z;
            final int o = f * P;

            profile[o + POP] = Math.max(0, profile[o + POP] + z - popSlack);
            if (profile[o + POP] >= popLimit) outliers++;
            if (profile[o + POP] > maxPop) maxPop = profile[o + POP];

            final double delta = z - profile[o + BASE];
            if (profile[o + BASE_N] >= minBaseline) {
                profile[o + SELF] = Math.max(0, profile[o + SELF] + delta - selfSlack);
                if (profile[o + SELF] >= selfLimit) changes++;
                if (profile[o + SELF] > maxSelf) maxSelf = profile[o + SELF];
            }
            // Taban, hile yonundeki sicramayi icine almaz (yoksa hile "olagan" olur)
            if (delta < 2) {
                final double n = Math.min(profile[o + BASE_N] + 1, 40);
                profile[o + BASE] += delta / n;
                profile[o + BASE_N]++;
            }
        }

        boolean flagged = false;
        if (changes >= 2 || maxSelf >= 2 * selfLimit) {
            final String detail = "degisim " + detail(x, zs, SELF);
            flag(detail);
            for (int f = 0; f < HapiSezgi.FEATURES; f++) profile[f * P + SELF] *= 0.5;
            flagged = true;
        }
        if (outliers >= 2 || maxPop >= 2 * popLimit) {
            final String detail = "aykiri " + detail(x, zs, POP);
            flag(detail);
            for (int f = 0; f < HapiSezgi.FEATURES; f++) profile[f * P + POP] *= 0.5;
            flagged = true;
        }
        if (!flagged) reward();
    }

    private String detail(double[] x, double[] zs, int slot) {
        final StringBuilder sb = new StringBuilder(96);
        for (int f = 0; f < HapiSezgi.FEATURES; f++) {
            if (Double.isNaN(zs[f])) continue;
            sb.append(HapiSezgi.NAMES[f]).append('=').append(fmt(x[f]))
                    .append("(z").append(fmt(zs[f])).append(",t").append(fmt(profile[f * P + slot])).append(") ");
        }
        return sb.append("taban-dovus=").append((int) profile[BASE_N]).toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
