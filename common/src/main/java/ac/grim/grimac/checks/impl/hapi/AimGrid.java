package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.aim.processor.AimProcessor;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Fare izgarasi (GCD) tutarliligi. Vanilla istemcide her donus, hassasiyet ayarindan tureyen sabit bir
 * adimin tam katidir. Grim'in AimProcessor'u bu adimi (modeYaw) zaten cikariyor; burada dovus sirasindaki
 * donuslerin o izgaraya oturup oturmadigina bakilir. Izgarayi taklit etmeyen aim assist / aimbot'lar
 * kesirli adim uretir.
 * <p>
 * Bilinen mesru istisnalar: sinematik kamera, oyun kolu (Controlify vb.), dovus ortasinda hassasiyet
 * degistirme. Bu yuzden varsayilan alarm esigi 2'dir ve check tek basina ceza sebebi yapilmamalidir.
 */
@CheckData(name = "AimGrid", stableKey = "hapi.aim.grid", description = "Donusler fare hassasiyet izgarasina oturmuyor (aim assist)", decay = 0.02)
public final class AimGrid extends Check {
    private static final int WINDOW = 50;
    private AimProcessor aim;
    private double maxOffRatio; // onReload atar (super() icinde)
    private int samples;
    private int off;

    public AimGrid(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    protected double defaultAlertFrom() {
        return 2;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        maxOffRatio = config.getDoubleElse("hapi.combat.aim-grid.max-off-grid-ratio", 0.7);
    }

    void onTick(float dYaw) {
        final float abs = Math.abs(dYaw);
        if (abs < 1f || abs > 30f) return;

        if (aim == null) aim = player.checkManager.get(AimProcessor.class);
        final double step = aim.modeYaw;
        if (step < 0.005) return; // hassasiyet henuz cikarilamadi

        // float birikim hatasi (kare basina ~1 ulp) izgara adimina gore buyukse ornek guvenilmez
        if (Math.ulp(player.yaw) * 16 > step * 0.1) return;

        final double dots = abs / step;
        final double frac = Math.abs(dots - Math.rint(dots));
        samples++;
        if (frac > 0.15) off++;

        if (samples >= WINDOW) {
            if (off >= WINDOW * maxOffRatio) {
                flag("izgara-disi=" + off + "/" + samples + " adim=" + String.format("%.4f", step));
            } else {
                reward();
            }
            samples = 0;
            off = 0;
        }
    }
}
