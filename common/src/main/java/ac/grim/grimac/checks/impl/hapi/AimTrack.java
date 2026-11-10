package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Kilitlenme (aimlock): hedef yana dogru hareket ederken oyuncunun bakisinin hedef merkezine gore
 * sapmasi neredeyse hic degismiyorsa nisani bir yazilim tutuyordur. Insan hedefi hitbox genisligi
 * icinde gezinerek, gecikmeli ve asarak takip eder.
 * <p>
 * Hedef konumu Grim'in gecikme telafili "olasi konum kutusu"nun merkezidir; kutu fazla belirsizse
 * ornek alinmaz. Esik canli veriyle kalibre edilmelidir (flag satirindaki std/ort degerleri).
 */
@CheckData(name = "AimTrack", stableKey = "hapi.aim.track", description = "Hareketli hedefe kusursuz kilitlenme (aimlock)", decay = 0.02)
public final class AimTrack extends Check {
    private static final int WINDOW = 40;
    private double maxStd; // onReload atar (super() icinde)
    private double lastYawTo;
    private int lastTick = Integer.MIN_VALUE / 2;
    private int lastSampleTick = Integer.MIN_VALUE / 2;
    private int n;
    private double sum, sumSq;

    public AimTrack(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    protected double defaultAlertFrom() {
        return 2;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        maxStd = config.getDoubleElse("hapi.combat.aim-track.max-offset-stddev", 0.35);
    }

    void onTick(int tick, float dYaw, int targetId) {
        final PacketEntity target = player.compensatedEntities.entityMap.get(targetId);
        if (target == null || target.isDead) return;

        final SimpleCollisionBox box = target.getPossibleLocationBoxes();
        if (box.maxX - box.minX > 0.75 || box.maxZ - box.minZ > 0.75) return; // konum fazla belirsiz

        final double dx = (box.minX + box.maxX) * 0.5 - player.x;
        final double dz = (box.minZ + box.maxZ) * 0.5 - player.z;
        final double distSq = dx * dx + dz * dz;
        if (distSq < 2.25 || distSq > 36) return;

        final double yawTo = Math.toDegrees(Math.atan2(-dx, dz));
        final boolean consecutive = tick == lastTick + 1;
        final double targetMoved = wrap(yawTo - lastYawTo);
        lastYawTo = yawTo;
        lastTick = tick;

        // Yalnizca hem hedefin acisal olarak kaydigi hem de oyuncunun dondugu tickler sayilir
        if (!consecutive || Math.abs(targetMoved) < 2 || Math.abs(dYaw) < 0.5f) return;

        if (tick - lastSampleTick > 100) {
            n = 0;
            sum = sumSq = 0;
        }
        lastSampleTick = tick;

        final double offset = wrap(player.yaw - yawTo);
        n++;
        sum += offset;
        sumSq += offset * offset;

        if (n >= WINDOW) {
            final double mean = sum / n;
            final double std = Math.sqrt(Math.max(0, sumSq / n - mean * mean));
            if (std < maxStd && Math.abs(mean) < 2.5) {
                flag("std=" + String.format("%.3f", std) + " ort=" + String.format("%.3f", mean) + " n=" + n);
            } else {
                reward();
            }
            n = 0;
            sum = sumSq = 0;
        }
    }

    private static double wrap(double degrees) {
        degrees %= 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }
}
