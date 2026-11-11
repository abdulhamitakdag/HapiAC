package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import org.jetbrains.annotations.NotNull;

/**
 * Otomatik jump reset: savrulmanin uygulandigi tickte ziplamak savrulmayi azaltir (vanilla davranisi,
 * elle yapilmasi mesru). Insan bunu vurusu sezip tusa onceden basarak dener; tam o ticke denk getirme
 * orani sinirlidir. Hile istemcisi savrulma paketini gorup her seferinde ayni tickte ziplar.
 * <p>
 * Yalniz "yerde bekleyen ve ziplama tusuna basmayan" oyuncunun aldigi savrulmalar sayilir (tusu basili
 * tutan ya da zaten havada olan oyuncu ornek uretmez); pencere dolunca kusursuz ziplama orani bakilir.
 */
@CheckData(name = "JumpReset", stableKey = "hapi.velocity.jump_reset", description = "Otomatik jump reset (savrulma tickinde kusursuz ziplama)", decay = 0.01)
public final class JumpReset extends Check implements PostPredictionListener {
    private int sampleSize, minGroundTicks; // onReload atar (super() icinde)
    private double minRatio;

    private int groundTicks;
    private boolean prevJumpKey;
    private int samples, perfect;

    public JumpReset(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        sampleSize = config.getIntElse("hapi.velocity.jump-reset.sample-size", 20);
        minRatio = config.getDoubleElse("hapi.velocity.jump-reset.min-ratio", 0.85);
        minGroundTicks = config.getIntElse("hapi.velocity.jump-reset.min-ground-ticks", 3);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        // Tus durumu yalniz 1.21.2+ istemcilerde bildirilir
        if (player.canSkipTicks()) return;
        final boolean jumpKey = player.packetStateData.knownInput.jump();

        if (predictionComplete.isChecked() && !predictionComplete.getData().isTeleport()
                && groundTicks >= minGroundTicks && !prevJumpKey
                && player.predictedVelocity.isKnockback() && !player.inVehicle()) {
            samples++;
            if (jumpKey && player.predictedVelocity.isJump()) perfect++;
            if (samples >= sampleSize) {
                if (perfect >= minRatio * samples) flag("kusursuz=" + perfect + "/" + samples);
                samples = 0;
                perfect = 0;
            }
        }

        groundTicks = player.onGround ? groundTicks + 1 : 0;
        prevJumpKey = jumpKey;
    }
}
