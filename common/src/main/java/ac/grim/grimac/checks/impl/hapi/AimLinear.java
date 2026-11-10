package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Sabit hizli nisan: dovus sirasinda ardisik ticklerde birebir ayni (sifir olmayan) yaw farki.
 * Fare ile tick basina ayni nokta sayisini 6 kez ust uste tutturmak mumkun degildir; sabit adimli
 * aimbot / "smooth aim" bunu uretir.
 */
@CheckData(name = "AimLinear", stableKey = "hapi.aim.linear", description = "Dovuste sabit hizli donus (aimbot)", decay = 0.02)
public final class AimLinear extends Check {
    private static final int RUN_TO_FLAG = 6;
    private float last;
    private int run;

    public AimLinear(@NotNull GrimPlayer player) {
        super(player);
    }

    void reset() {
        run = 0;
        last = 0;
    }

    void onTick(float dYaw) {
        if (Math.abs(dYaw) >= 2f && Math.abs(dYaw - last) < 1e-4f) {
            if (++run >= RUN_TO_FLAG) {
                flag("dYaw=" + dYaw + " x" + (run + 1));
                run = 0;
            }
        } else {
            run = 0;
        }
        last = dYaw;
    }
}
