package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Totem patladiktan sonra diger ele yeniden totem koyma tepkisi. Sure, istemcinin patlamayi gordugu andan
 * (transaction yaniti) takas paketine kadar gecen ISTEMCI TICK sayisidir; ag gecikmesi olcume karismaz.
 * <ul>
 * <li><b>aninda</b>: tepki "instant-ticks" tick veya daha kisa (insan gorsel tepkisi en az 3-4 tick), 2 tekrar.</li>
 * <li><b>tutarli</b>: ust uste "consistent-samples" tepkinin hepsi birebir ayni tick sayisi (en cok 8 tick).</li>
 * </ul>
 * Patlamadan hemen once de takas tusunu surekli basan oyuncu (mesru spam teknigi) olcume alinmaz.
 */
@CheckData(name = "AutoTotem", stableKey = "hapi.inventory.auto_totem", description = "Totem patlar patlamaz yeniden totem takma (AutoTotem)", decay = 0.01)
public final class AutoTotem extends Check {
    private static final int MAX_CONSISTENT_TICKS = 8;

    private int instantTicks; // onReload atar (super() icinde)
    private int consistentSamples;

    private int fast;
    private int lastTicks = -1;
    private int same;

    public AutoTotem(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        instantTicks = config.getIntElse("hapi.inventory.auto-totem.instant-ticks", 1);
        consistentSamples = config.getIntElse("hapi.inventory.auto-totem.consistent-samples", 5);
    }

    void onRetotem(int ticks) {
        if (ticks < 0 || ticks > 20) { // acele etmeyen oyuncu: olcum degil
            same = 0;
            lastTicks = -1;
            return;
        }

        if (ticks <= instantTicks) {
            if (++fast >= 2) {
                flag("aninda tepki=" + ticks + " tick x" + fast);
                fast = 1;
                return;
            }
        } else if (fast > 0) {
            fast--;
        }

        if (ticks == lastTicks) {
            same++;
        } else {
            same = 1;
            lastTicks = ticks;
            if (fast == 0) reward();
        }
        if (same >= consistentSamples && ticks <= MAX_CONSISTENT_TICKS) {
            flag("tutarli tepki=" + ticks + " tick x" + same);
            same = 0;
            lastTicks = -1;
        }
    }
}
