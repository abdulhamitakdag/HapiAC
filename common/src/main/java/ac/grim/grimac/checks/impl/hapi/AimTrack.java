package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.util.Vector3d;
import org.jetbrains.annotations.NotNull;

/**
 * Kilitlenme (aimlock): hedef yana dogru hareket ederken oyuncunun bakisinin hedef merkezine gore
 * sapmasi neredeyse hic degismiyorsa nisani bir yazilim tutuyordur. Insan hedefi hitbox genisligi
 * icinde gezinerek, gecikmeli ve asarak takip eder.
 * <p>
 * Hedefin ekrandaki konumu burada modellenir: sunucunun bildirdigi son konuma (gecikme telafili
 * {@code trackedServerPosition}) dogru istemcinin yaptigi 3 adimli enterpolasyon. Grim'in "olasi
 * konum kutusu" kullanilmaz; o kutu Reach icin en kotu durum zarfidir ve hareketli hedefte
 * bloklarca genis kalir. Paketin hangi istemci tickinde uygulandigi +-1 tick belirsiz oldugundan
 * sapma iki hizalamayla (bu tick / onceki tick) ayri ayri biriktirilir, kucuk olan degerlendirilir.
 * Esik canli veriyle kalibre edilmelidir (flag satirindaki std/ort degerleri).
 */
@CheckData(name = "AimTrack", stableKey = "hapi.aim.track", description = "Hareketli hedefe kusursuz kilitlenme (aimlock)", decay = 0.02)
public final class AimTrack extends Check {
    private static final int WINDOW = 40;
    private static final int LERP_STEPS = 3;
    private double maxStd; // onReload atar (super() icinde)

    // hedefin modellenen ekran konumu
    private int modelTargetId = -1;
    private Vector3d lastServerPos; // referans karsilastirmasi: her hareket paketi yeni nesne atar
    private double renderX, renderZ;
    private int lerpLeft;

    private double lastYawTo;
    private int lastTick = Integer.MIN_VALUE / 2;
    private int lastSampleTick = Integer.MIN_VALUE / 2;
    private int n;
    private double sum0, sumSq0, sum1, sumSq1;

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

        final Vector3d serverPos = target.trackedServerPosition.getPos();
        final boolean consecutive = tick == lastTick + 1 && targetId == modelTargetId;
        lastTick = tick;

        if (!consecutive) { // model koptu: hedefi dogrudan son konuma oturt, bu tick ornek alma
            modelTargetId = targetId;
            lastServerPos = serverPos;
            renderX = serverPos.x;
            renderZ = serverPos.z;
            lerpLeft = 0;
            lastYawTo = yawTo(renderX, renderZ);
            return;
        }

        if (serverPos != lastServerPos) {
            lastServerPos = serverPos;
            lerpLeft = LERP_STEPS;
        }
        if (lerpLeft > 0) {
            renderX += (serverPos.x - renderX) / lerpLeft;
            renderZ += (serverPos.z - renderZ) / lerpLeft;
            lerpLeft--;
        }

        final double dx = renderX - player.x;
        final double dz = renderZ - player.z;
        final double distSq = dx * dx + dz * dz;
        final double prevYawTo = lastYawTo;
        final double yawTo = Math.toDegrees(Math.atan2(-dx, dz));
        lastYawTo = yawTo;
        if (distSq < 2.25 || distSq > 36) return;

        // Yalnizca hem hedefin acisal olarak kaydigi hem de oyuncunun dondugu tickler sayilir
        if (Math.abs(wrap(yawTo - prevYawTo)) < 2 || Math.abs(dYaw) < 0.5f) return;

        if (tick - lastSampleTick > 100) resetWindow();
        lastSampleTick = tick;

        final double o0 = wrap(player.yaw - yawTo);
        final double o1 = wrap(player.yaw - prevYawTo);
        n++;
        sum0 += o0;
        sumSq0 += o0 * o0;
        sum1 += o1;
        sumSq1 += o1 * o1;

        if (n >= WINDOW) {
            final double mean0 = sum0 / n, mean1 = sum1 / n;
            final double std0 = Math.sqrt(Math.max(0, sumSq0 / n - mean0 * mean0));
            final double std1 = Math.sqrt(Math.max(0, sumSq1 / n - mean1 * mean1));
            final boolean first = std0 <= std1;
            final double std = first ? std0 : std1, mean = first ? mean0 : mean1;
            if (std < maxStd && Math.abs(mean) < 2.5) {
                flag("std=" + String.format("%.3f", std) + " ort=" + String.format("%.3f", mean) + " n=" + n + " hiza=" + (first ? 0 : 1));
            } else {
                reward();
            }
            resetWindow();
        }
    }

    private void resetWindow() {
        n = 0;
        sum0 = sumSq0 = sum1 = sumSq1 = 0;
    }

    private double yawTo(double x, double z) {
        return Math.toDegrees(Math.atan2(-(x - player.x), z - player.z));
    }

    private static double wrap(double degrees) {
        degrees %= 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }
}
