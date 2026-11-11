package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Oyuncuya henuz hic yollanmamis bir varlik kimligine vurus. Varlik kimlikleri sunucuda sirayla artar;
 * kristal hileleri bir sonraki kristalin kimligini tahmin edip dogma paketi gelmeden vurus yollar
 * ("id predict"). Vanilla istemci yalnizca kendisine bildirilmis varliklara vurabilir.
 */
@CheckData(name = "AttackPredict", stableKey = "hapi.crystal.attack_predict", description = "Henuz gonderilmemis varliga vurus (kimlik tahmini)", decay = 0.01)
public final class AttackPredict extends Check {
    public AttackPredict(@NotNull GrimPlayer player) {
        super(player);
    }

    void onPredict(int entityId, int maxSentId) {
        flag("hedef=" + entityId + " enbuyuk=" + maxSentId);
    }
}
