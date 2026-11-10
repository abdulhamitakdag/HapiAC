package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/** Son 20 istemci tick'inde (1 sn) gonderilen vurus paketi sayisi ust siniri. */
@CheckData(name = "ClickRate", stableKey = "hapi.combat.click_rate", description = "Saniyede cok fazla vurus (autoclicker)", decay = 0.02)
public final class ClickRate extends Check {
    private final byte[] ring = new byte[20];
    private int index;
    private int sum;
    private int maxAttacks; // onReload atar (super() icinde)

    public ClickRate(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        maxAttacks = config.getIntElse("hapi.combat.click-rate.max-attacks-per-second", 18);
    }

    void onTick(int attacks) {
        if (attacks > 100) attacks = 100;
        sum += attacks - ring[index];
        ring[index] = (byte) attacks;
        if (++index == ring.length) index = 0;

        if (sum > maxAttacks) {
            flag("vurus/sn=" + sum);
            java.util.Arrays.fill(ring, (byte) 0);
            sum = 0;
        }
    }
}
