package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Kusursuz kopru: ust uste "sample-size" blok, hic egilmeden, her blok icin tam BIR koyma paketiyle ve
 * hicbir bosa giden tiklama olmadan ayak altina eklenir. Insan kopruculeri ya egilir (hizli kopru) ya da
 * sag tusu basili tutar / hizli tiklar; ikisinde de blogun ust yuzune denk gelen bosa paketler olusur.
 * Bakisi geri dondurmeyen (ScaffoldSnap'e takilmayan) scaffold'lari hedefler.
 */
@CheckData(name = "BridgePerfect", stableKey = "hapi.build.bridge_perfect", description = "Egilmeden, bosa tiklamasiz kusursuz kopru (scaffold)", decay = 0.05)
public final class BridgePerfect extends Check {
    private static final int NEVER = Integer.MIN_VALUE / 2;

    private int sampleSize; // onReload atar (super() icinde)
    private int maxGap;

    private int run;
    private int lastTick = NEVER;
    private int firstTick;
    private int lastX, lastY, lastZ;

    public BridgePerfect(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        sampleSize = config.getIntElse("hapi.build.bridge-perfect.sample-size", 16);
        maxGap = config.getIntElse("hapi.build.bridge-perfect.max-gap-ticks", 8);
    }

    void onTick(int tick, int places, boolean bridge, int x, int y, int z, boolean sneaking) {
        if (sneaking) {
            if (run >= sampleSize / 2) reward();
            run = 0;
            return;
        }
        if (places == 0) {
            if (run > 0 && tick - lastTick > maxGap) run = 0;
            return;
        }
        // Bosa giden tiklama, ayni tickte birden cok paket ya da ayni bloga tekrar: insan davranisi
        if (!bridge || places != 1 || (run > 0 && x == lastX && y == lastY && z == lastZ)) {
            if (run >= sampleSize / 2) reward();
            run = 0;
            return;
        }
        if (run == 0 || tick - lastTick > maxGap) {
            run = 0;
            firstTick = tick;
        }
        run++;
        lastTick = tick;
        lastX = x;
        lastY = y;
        lastZ = z;
        if (run >= sampleSize) {
            flag("kusursuz=" + run + " blok " + (tick - firstTick) + " tick");
            run = 0;
        }
    }
}
