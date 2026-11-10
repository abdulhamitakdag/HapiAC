package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.jetbrains.annotations.NotNull;

// This works around 1.3 timer, to prevent too high abuse - maybe there's a better solution?
@CheckData(name = "TimerLimit", stableKey = "grim.timer.limit", description = "The player has sent too many packets after high latency", setback = 10)
public class TimerLimit extends Timer {

    // At what ping should we start to limit the balance advantage? (nanos)
    private long limitAbuseOverPing;

    public TimerLimit(GrimPlayer player) {
        super(player);
    }

    @Override
    public void doCheck(final PacketReceiveEvent event) {
        // 1:1 with Timer minus cancelling the packet
        if (timerBalanceRealTime > System.nanoTime()) {
            // If timer check already flagged, don't flag.
            if (!event.isCancelled()) {
                if (flag() && shouldSetback()) {
                    player.getSetbackTeleportUtil().executeNonSimulatingSetback();
                }
            }

            // Reset the violation by 1 movement
            timerBalanceRealTime -= 50e6;
        }

        limitFallBehind();
    }

    @Override
    protected void limitFallBehind() {
        // Limit using transaction ping if over 1000ms (default)
        long playerClock = lastMovementPlayerClock;
        if (limitAbuseOverPing != -1 && System.nanoTime() - playerClock > limitAbuseOverPing) {
            playerClock = System.nanoTime() - limitAbuseOverPing;
        }
        timerBalanceRealTime = Math.max(timerBalanceRealTime, playerClock - clockDrift);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        // HapiAC: deger TimerLimit altinda yoksa TimerA altindakine dus (eski config'lerde esik ve drift oraya yazilmis)
        clockDrift = (long) (config.getDoubleElse(getConfigName() + ".drift", config.getDoubleElse("TimerA.drift", 120.0)) * 1e6);
        limitAbuseOverPing = config.getLongElse(getConfigName() + ".ping-abuse-limit-threshold", config.getLongElse("TimerA.ping-abuse-limit-threshold", 1000L));
        if (limitAbuseOverPing != -1) {
            limitAbuseOverPing *= (long) 1e6;
        }
    }
}
