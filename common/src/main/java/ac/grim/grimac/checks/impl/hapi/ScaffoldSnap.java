package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * "Sessiz donus" ile blok koyma: tek tickte bloga buyuk bir donus, blok koyma, ardindan en cok
 * "max-hold-ticks" tick icinde yine tek tickte ESKI bakisa (iki eksende de ~1 derece hassasiyetle) geri donus.
 * Scaffold / otomatik insa / kristal yerlestirme modullerinin sunucuya gecerli bakis gostermek icin
 * kullandigi yontem. Insan eli ayni noktaya tek tickte bu hassasiyetle geri donemez; yine de 10 sn'de 3 tekrar aranir.
 */
@CheckData(name = "ScaffoldSnap", stableKey = "hapi.build.scaffold_snap", description = "Blok koyarken ani donup eski bakisa geri donme (scaffold)", decay = 0.02)
public final class ScaffoldSnap extends Check {
    private float minAngle; // onReload atar (super() icinde)
    private int maxHold;

    private float yaw, pitch; // birikimli bakis (yalniz farklar onemli)
    private boolean active;
    private boolean placed;
    private int snapTick;
    private float fromYaw, fromPitch, snapSize;
    private int hits;
    private int firstHitTick;

    public ScaffoldSnap(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        minAngle = (float) config.getDoubleElse("hapi.build.scaffold-snap.min-angle", 25);
        maxHold = config.getIntElse("hapi.build.scaffold-snap.max-hold-ticks", 8);
    }

    void reset() {
        active = false;
    }

    void onTick(int tick, float dYaw, float dPitch, boolean placedThisTick) {
        final float beforeYaw = yaw, beforePitch = pitch;
        yaw += dYaw;
        pitch += dPitch;
        final float size = (float) Math.hypot(dYaw, dPitch);

        if (active && tick - snapTick > maxHold) active = false;
        if (active && placedThisTick) placed = true;

        if (size < minAngle) return;

        if (active) {
            final float tolerance = Math.max(1f, snapSize * 0.03f);
            if (placed && Math.abs(yaw - fromYaw) <= tolerance && Math.abs(pitch - fromPitch) <= tolerance) {
                active = false;
                if (hits == 0 || tick - firstHitTick > 200) {
                    hits = 0;
                    firstHitTick = tick;
                }
                if (++hits >= 3) {
                    flag("donus=" + Math.round(snapSize) + " bekleme=" + (tick - snapTick) + " tick x" + hits);
                    hits = 0;
                }
                return;
            }
        }

        // Yeni buyuk donus: geri donus icin referans olarak onceki bakisi sakla
        active = true;
        placed = placedThisTick;
        snapTick = tick;
        snapSize = size;
        fromYaw = beforeYaw;
        fromPitch = beforePitch;
    }
}
