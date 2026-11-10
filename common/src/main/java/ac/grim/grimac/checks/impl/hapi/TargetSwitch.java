package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.util.Vector3d;
import org.jetbrains.annotations.NotNull;

/**
 * Hedef degistirme (switch / multi aura): birbirinden genis aciyla ayrik iki oyuncuya en cok 2 tick
 * arayla art arda vurmak. Insan bu kadar donup ikinci hedefi nisanlayip tiklayamaz; arada bir denk
 * gelebilecegi icin 10 saniyede 3 tekrar aranir. Ayni tickteki coklu vurusu Grim (MultiInteract) yakalar.
 */
@CheckData(name = "TargetSwitch", stableKey = "hapi.combat.target_switch", description = "Aninda hedef degistirme (switch/multi aura)", decay = 0.02)
public final class TargetSwitch extends Check {
    private static final int WINDOW_TICKS = 200;
    private static final int REPEATS = 3;
    private double minAngle; // onReload atar (super() icinde)
    private int firstTick = Integer.MIN_VALUE / 2;
    private int count;

    public TargetSwitch(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        minAngle = config.getDoubleElse("hapi.combat.target-switch.min-angle", 50);
    }

    void onSwitch(int tick, PacketEntity from, PacketEntity to) {
        final double angle = Math.abs(wrap(yawTo(to) - yawTo(from)));
        if (angle < minAngle) return;

        if (tick - firstTick > WINDOW_TICKS) {
            firstTick = tick;
            count = 0;
        }
        if (++count >= REPEATS) {
            flag("aci=" + String.format("%.0f", angle) + " x" + count);
            count = 0;
            firstTick = tick;
        }
    }

    private double yawTo(PacketEntity entity) {
        final Vector3d pos = entity.trackedServerPosition.getPos();
        return Math.toDegrees(Math.atan2(-(pos.x - player.x), pos.z - player.z));
    }

    private static double wrap(double degrees) {
        degrees %= 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }
}
