package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Tek eksen kilidi: dovuste bir eksen tick basina 3-35 derece donerken diger eksen ust uste 20 tick
 * hic kipirdamiyor. Fare sensoru bu kadar uzun bir surukleme boyunca dik eksende sifir nokta uretmez;
 * yalnizca yaw'i (ya da yalnizca pitch'i) duzelten aim assist uretir. (Medusa AimAssist A'dan uyarlandi.)
 */
@CheckData(name = "AimAxis", stableKey = "hapi.aim.axis", description = "Tek eksende kilitli nisan (aim assist)", decay = 0.02)
public final class AimAxis extends Check {
    private static final int RUN_TO_FLAG = 20;
    private int run;

    public AimAxis(@NotNull GrimPlayer player) {
        super(player);
    }

    void reset() {
        run = 0;
    }

    void onTick(float dYaw, float dPitch) {
        final float yaw = Math.abs(dYaw);
        final float pitch = Math.abs(dPitch);
        // pitch +-90'da kirpilir, orada dPitch=0 mesrudur
        final boolean yawOnly = pitch < 0.007f && yaw > 3f && yaw < 35f && Math.abs(player.pitch) < 85f;
        final boolean pitchOnly = yaw < 0.007f && pitch > 3f && pitch < 35f;
        if (yawOnly || pitchOnly) {
            if (++run > RUN_TO_FLAG) {
                flag((yawOnly ? "yalniz-yaw" : "yalniz-pitch") + " dYaw=" + dYaw + " dPitch=" + dPitch + " x" + run);
                run = RUN_TO_FLAG / 2;
            }
        } else {
            run = 0;
        }
    }
}
