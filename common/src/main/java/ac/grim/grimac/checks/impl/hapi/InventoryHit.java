package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Sandik/menu acikken vurus: sunucunun actigi bir pencere (sandik, firin, eklenti menusu) istemcide
 * acikken oyuncu vuramaz; once pencereyi kapatmasi gerekir. Pencere durumu gecikme telafilidir
 * (acilis paketi istemciye ulasmadan acik sayilmaz). Oyuncunun kendi envanteri (E tusu) sunucuya
 * bildirilmedigi icin kapsam disidir.
 */
@CheckData(name = "InventoryHit", stableKey = "hapi.combat.inventory_hit", description = "Sandik/menu acikken vurus", decay = 0.05)
public final class InventoryHit extends Check {
    public InventoryHit(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    protected double defaultAlertFrom() {
        return 2;
    }

    void onAttack() {
        final int window = player.inventory.getOpenWindowID();
        if (window != 0) flag("pencere=" + window);
    }
}
