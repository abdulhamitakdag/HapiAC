package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * KillAura donus imzalari:
 * <ul>
 * <li><b>geri-don</b>: vurus tickinde hedefe buyuk bir donus, hemen ardindan neredeyse ayni acida geri donus
 * ("silent rotation" / hedefe bakip eski bakisa donme). Insan elinde iki ardisik 50 ms'lik hareketin
 * birbirini %4 hassasiyetle goturmesi pratikte olmaz.</li>
 * <li><b>ani-dur</b>: oncesi ve sonrasi neredeyse hareketsizken tek tickte buyuk donus + vurus.
 * Insan "flick"i hizlanip yavaslar ve genelde 2-3 tick'e yayilir; bu yuzden tekrar sarti aranir.</li>
 * </ul>
 */
@CheckData(name = "AimSnap", stableKey = "hapi.aim.snap", description = "Vurus aninda hedefe ani donus (KillAura)", decay = 0.02)
public final class AimSnap extends Check {
    private float minSnapback; // onReload atar (super() icinde)
    private float minFlick;
    private double backBuffer;
    private double flickBuffer;

    public AimSnap(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        minSnapback = (float) config.getDoubleElse("hapi.combat.aim-snap.min-snapback-angle", 20);
        minFlick = (float) config.getDoubleElse("hapi.combat.aim-snap.min-flick-angle", 45);
    }

    /** @param prev vurustan onceki tickin yaw farki, cur vurus ticki, next sonraki tick */
    void onAttackWindow(float prev, float cur, float next) {
        final float a = Math.abs(cur);

        if (isSnapback(cur, next) || isSnapback(prev, cur)) {
            if (++backBuffer >= 2) {
                flag("geri-don prev=" + fmt(prev) + " cur=" + fmt(cur) + " next=" + fmt(next));
                backBuffer = 1;
            }
            return;
        }

        if (a >= minFlick && Math.abs(prev) <= 1f && Math.abs(next) <= 1f) {
            if (++flickBuffer >= 4) {
                flag("ani-dur prev=" + fmt(prev) + " cur=" + fmt(cur) + " next=" + fmt(next));
                flickBuffer = 2;
            }
            return;
        }

        backBuffer = Math.max(0, backBuffer - 0.05);
        flickBuffer = Math.max(0, flickBuffer - 0.2);
        reward();
    }

    private boolean isSnapback(float first, float second) {
        final float a = Math.abs(first);
        return a >= minSnapback && Math.abs(second) >= minSnapback
                && Math.abs(first + second) <= Math.max(1.5f, a * 0.04f);
    }

    private static String fmt(float v) {
        return String.format("%.2f", v);
    }
}
