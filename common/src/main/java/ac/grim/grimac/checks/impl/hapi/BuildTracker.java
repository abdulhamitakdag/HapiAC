package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.BlockPlaceListener;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import ac.grim.grimac.utils.math.GrimMath;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.NotNull;

/**
 * HapiAC yapi (blok koyma) modulunun tek giris noktasi.
 * <p>
 * Grim blok koymanin vanilla kurallarina uydugunu denetler (bakis, erisim, gorunen yuz) ama bilincli olarak
 * scaffold sezgiseli icermez. Bu sinif tick basina blok koyma sayisini, kopru (ayak alti) koymalarini ve
 * donusleri toplar; uc sezgisel check'i dogrudan cagirir. Blok koymayan oyuncuda tick basina maliyet
 * birkac karsilastirmadir, nesne olusturulmaz.
 */
public final class BuildTracker extends GrimProcessor implements PacketReceiveListener, RotationListener, BlockPlaceListener {
    private ScaffoldSnap scaffoldSnap;
    private FastPlace fastPlace;
    private BridgePerfect bridgePerfect;

    private boolean moduleEnabled; // onReload atar (super() icinde)

    private int tick;
    private float dYaw, dPitch;
    private int placesThisTick;
    private boolean bridgeThisTick;
    private int bridgeX, bridgeY, bridgeZ;
    private int tainted;

    public BuildTracker(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        moduleEnabled = config.getBooleanElse("hapi.build.enabled", true);
    }

    @Override
    public void process(final RotationUpdate update) {
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation) {
            tainted = 3;
            return;
        }
        dYaw = update.deltaYaw();
        dPitch = update.deltaPitch();
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        if (!place.isBlock) return; // elde blok yok: kapi/sandik tiklamasi vb.
        placesThisTick++;

        // Kopru koymasi: uzerinde durulan katmana, ayagin altindaki bloga yandan eklenen blok
        if (!place.isFaceHorizontal() || !(player.onGround || player.lastOnGround)) return;
        final Vector3i clicked = place.position;
        final Vector3i placed = place.getPlacedBlockPos();
        if (placed.getY() != GrimMath.floor(player.y - 1.0E-4)) return;
        if (Math.abs(clicked.getX() + 0.5 - player.x) > 1.3 || Math.abs(clicked.getZ() + 0.5 - player.z) > 1.3) return;
        bridgeThisTick = true;
        bridgeX = placed.getX();
        bridgeY = placed.getY();
        bridgeZ = placed.getZ();
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (isTickPacket(event.getPacketType())) onTickEnd();
    }

    private void onTickEnd() {
        if (scaffoldSnap == null) {
            scaffoldSnap = player.checkManager.get(ScaffoldSnap.class);
            fastPlace = player.checkManager.get(FastPlace.class);
            bridgePerfect = player.checkManager.get(BridgePerfect.class);
        }

        // Tick paketi atlayabilen istemcilerde (1.9 - 1.21.1) tick sayaci guvenilmez
        final boolean exempt = !moduleEnabled || player.disableGrim || player.canSkipTicks()
                || player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR
                || player.inVehicle() || !player.cameraEntity.isSelf();

        if (!exempt) {
            fastPlace.onTick(placesThisTick);
            bridgePerfect.onTick(tick, placesThisTick, bridgeThisTick, bridgeX, bridgeY, bridgeZ, player.isSneaking);
            if (tainted > 0) {
                tainted--;
                scaffoldSnap.reset();
            } else {
                scaffoldSnap.onTick(tick, dYaw, dPitch, placesThisTick > 0);
            }
        }

        dYaw = 0;
        dPitch = 0;
        placesThisTick = 0;
        bridgeThisTick = false;
        tick++;
    }
}
