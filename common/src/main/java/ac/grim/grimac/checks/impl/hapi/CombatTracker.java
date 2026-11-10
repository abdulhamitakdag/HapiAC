package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

/**
 * HapiAC vurus modulunun tek giris noktasi.
 * <p>
 * Performans: oyuncu basina paket yoluna yalnizca BU sinif eklenir (1 paket dinleyici + 1 donus
 * dinleyici); alti sezgisel check dogrudan cagrilir. Sicak yolda nesne olusturulmaz, tum durum
 * ilkel alanlarda tutulur. Nisan analizleri yalnizca oyuncu bir OYUNCUYA vurduktan sonraki
 * pencere boyunca calisir; dovus disinda tick basina maliyet birkac karsilastirmadir.
 */
public final class CombatTracker extends GrimProcessor implements PacketReceiveListener, RotationListener {
    private static final int NEVER = Integer.MIN_VALUE / 2;

    private AimSnap aimSnap;
    private AimLinear aimLinear;
    private AimGrid aimGrid;
    private AimTrack aimTrack;
    private AttackRhythm attackRhythm;
    private ClickRate clickRate;
    private AimAxis aimAxis;
    private HitAccuracy hitAccuracy;
    private TargetSwitch targetSwitch;
    private InventoryHit inventoryHit;
    private CombatLag combatLag;

    private int windowTicks; // onReload atar (super() icinde)
    private boolean moduleEnabled;

    private int tick;
    private int lastPlayerAttackTick = NEVER;
    private int targetId = -1;

    // icinde bulunulan tick
    private float dYaw, dPitch;
    private int attacksThisTick;
    private int playerAttacksThisTick;
    private int swingsThisTick;
    private boolean attackedPlayerThisTick;
    // onceki tickler
    private float prevDYaw, prev2DYaw;
    private boolean prevAttackedPlayer;
    // isinlanma / arac degisimi gibi zorlanmis donuslerden sonra nisan analizini atlanacak tick sayisi
    private int tainted;
    // sinematik kamera tespiti (yumusatilmis donus izgaraya oturmaz -> AimGrid muaf)
    private float lastAbsDYaw, lastJolt;
    private int cinematicTicks;
    private int lastCinematicTick = NEVER;

    public CombatTracker(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        moduleEnabled = config.getBooleanElse("hapi.combat.enabled", true);
        windowTicks = config.getIntElse("hapi.combat.window-ticks", 60);
    }

    private void init() {
        aimSnap = player.checkManager.get(AimSnap.class);
        aimLinear = player.checkManager.get(AimLinear.class);
        aimGrid = player.checkManager.get(AimGrid.class);
        aimTrack = player.checkManager.get(AimTrack.class);
        attackRhythm = player.checkManager.get(AttackRhythm.class);
        clickRate = player.checkManager.get(ClickRate.class);
        aimAxis = player.checkManager.get(AimAxis.class);
        hitAccuracy = player.checkManager.get(HitAccuracy.class);
        targetSwitch = player.checkManager.get(TargetSwitch.class);
        inventoryHit = player.checkManager.get(InventoryHit.class);
        combatLag = player.checkManager.get(CombatLag.class);
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
    public void onPacketReceive(final PacketReceiveEvent event) {
        final PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Client.ATTACK) {
            onAttack(new WrapperPlayClientAttack(event).getEntityId());
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                onAttack(packet.getEntityId());
            }
        } else if (isAnimation(type)) {
            swingsThisTick++;
        } else if (isTickPacket(type)) {
            onTickEnd();
        }
    }

    // Sinematik kamerada donus ivmesi cok yavas ve surekli degisir (ivmenin degisimi sifira yakin ama sifir degil)
    private void updateCinematic() {
        final float abs = Math.abs(dYaw);
        final float jolt = Math.abs(abs - lastAbsDYaw);
        final float joltChange = Math.abs(jolt - lastJolt);
        lastAbsDYaw = abs;
        lastJolt = jolt;
        if (joltChange > 0f && joltChange < 0.05f) {
            if (cinematicTicks < 20) cinematicTicks++;
        } else if (cinematicTicks > 0) {
            cinematicTicks--;
        }
        if (cinematicTicks > 8) lastCinematicTick = tick;
    }

    private void onAttack(int entityId) {
        attacksThisTick++;
        if (aimSnap == null) init();
        final boolean active = moduleEnabled && !player.disableGrim
                && player.gamemode != GameMode.CREATIVE && player.gamemode != GameMode.SPECTATOR;
        if (active) inventoryHit.onAttack();

        PacketEntity entity = player.compensatedEntities.entityMap.get(entityId);
        if (entity != null && !entity.isDead && entity.getType() == EntityTypes.PLAYER) {
            // Baska bir oyuncuya, bir onceki vurustan en cok 2 tick sonra (ayni tick haric: onu MultiInteract yakalar)
            if (active && entityId != targetId && !attackedPlayerThisTick && tick - lastPlayerAttackTick <= 2) {
                final PacketEntity previous = player.compensatedEntities.entityMap.get(targetId);
                if (previous != null && !previous.isDead) targetSwitch.onSwitch(tick, previous, entity);
            }
            attackedPlayerThisTick = true;
            playerAttacksThisTick++;
            targetId = entityId;
        }
    }

    private void onTickEnd() {
        if (aimSnap == null) init();

        // Tick paketi atlayabilen istemcilerde (1.9 - 1.21.1) tick sayaci guvenilmez; modul bu oyuncularda calismaz
        final boolean exempt = !moduleEnabled || player.disableGrim || player.canSkipTicks()
                || player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR
                || player.inVehicle() || !player.cameraEntity.isSelf();

        updateCinematic();

        if (!exempt) {
            clickRate.onTick(attacksThisTick);

            if (attackedPlayerThisTick) {
                attackRhythm.onAttack(tick);
                lastPlayerAttackTick = tick;
            }
            combatLag.onTick(tick - lastPlayerAttackTick <= windowTicks);

            if (tainted > 0) {
                tainted--;
                aimLinear.reset();
                aimAxis.reset();
            } else if (tick - lastPlayerAttackTick <= windowTicks) {
                aimLinear.onTick(dYaw);
                aimAxis.onTick(dYaw, dPitch);
                if (tick - lastCinematicTick >= 120) aimGrid.onTick(dYaw);
                hitAccuracy.onTick(swingsThisTick, playerAttacksThisTick);
                aimTrack.onTick(tick, dYaw, targetId);
                // Vurus bir onceki tickteydi: T-1, T, T+1 donusleri artik elimizde
                if (prevAttackedPlayer) {
                    aimSnap.onAttackWindow(prev2DYaw, prevDYaw, dYaw);
                    hitAccuracy.onAttackWindow(prev2DYaw, prevDYaw, dYaw);
                }
            }
        }

        prev2DYaw = prevDYaw;
        prevDYaw = dYaw;
        prevAttackedPlayer = attackedPlayerThisTick && !exempt;
        dYaw = 0;
        dPitch = 0;
        attacksThisTick = 0;
        playerAttacksThisTick = 0;
        swingsThisTick = 0;
        attackedPlayerThisTick = false;
        tick++;
    }
}
