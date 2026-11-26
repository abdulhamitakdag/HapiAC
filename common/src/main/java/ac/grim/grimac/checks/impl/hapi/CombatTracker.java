package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.hapi.HapiFlagLog;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.util.Vector3d;
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
    private SprintReset sprintReset;
    private Sezgi sezgi;

    private int windowTicks; // onReload atar (super() icinde)
    private boolean moduleEnabled;
    private boolean telemetryEnabled;

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

    // Suren dovusun ozeti (bkz. endFight)
    private int fightStartTick = NEVER;
    private int fightHits, fightSwings, fightMoving, fightSwitches, fightPeakRate;
    private int fightDistCount, fightFar;
    private double fightDistSum, fightDistMax;
    private int rateSum;
    private final byte[] rateRing = new byte[20];
    private int rateIndex;

    // ---- Sezgi olcutleri ve ham telemetri (yalniz dovus surerken) ----
    private static final int TEL_MAX = 400;
    private static final int LAGS = 6;
    private static final int F_ATTACK = 1, F_SWING = 2, F_ON_TARGET = 4, F_MODEL = 8, F_TAINTED = 16, F_OTHER_ATTACK = 32;
    private short[] telDYaw, telDPitch, telErrYaw, telErrPitch, telDist, telTargetW; // ilk dovuste ayrilir
    private byte[] telFlags, telCooldown;
    private int telN;
    // bekleme suresi: son sallamadan / el degisiminden beri gecen istemci ticki (vanilla attackStrengthTicker)
    private int cooldownTicks;
    private int attackCooldownTicks = -1; // bu tickteki ilk oyuncu vurusunda yakalanir
    // oyuncunun bir onceki tick sonundaki konumu (istemci isini bu konumdan atar)
    private double prevX, prevY, prevZ;
    private boolean prevPosValid;
    // hedefin istemci ekranindaki konumu: sunucu konumuna 3 adimli enterpolasyon (bkz. AimTrack)
    private int modelTargetId = -1;
    private Vector3d modelServerPos;
    private double renderX, renderY, renderZ;
    private int lerpLeft;
    private boolean modelValid;
    private int onTargetStreak;
    // olcut biriktiricileri
    private int edgeN, spamN, instantN, centreN, corrN;
    private final int[] edgeHist = new int[8];
    private double centreSum;
    private final float[] accelRing = new float[LAGS];
    private int accelIndex;
    private final double[] corrXY = new double[LAGS];
    private double corrXX, corrYY;
    private float prevTargetW, prevDYawForCorr;

    public CombatTracker(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        moduleEnabled = config.getBooleanElse("hapi.combat.enabled", true);
        windowTicks = config.getIntElse("hapi.combat.window-ticks", 60);
        telemetryEnabled = config.getBooleanElse("hapi.combat.telemetry", true);
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
        sprintReset = player.checkManager.get(SprintReset.class);
        sezgi = player.checkManager.get(Sezgi.class);
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
            cooldownTicks = 0;
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            cooldownTicks = 0;
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

    /** Reach, oyuncuya atilan ve erisim sinirini asmayan her vurusun mesafesini bildirir. */
    public void onLegalHit(double distance) {
        if (fightStartTick == NEVER) return;
        fightDistCount++;
        fightDistSum += distance;
        if (distance > fightDistMax) fightDistMax = distance;
        if (distance >= 2.8) fightFar++;
    }

    /**
     * Dovus penceresi kapandiginda tek satirlik ozet yazar. Flag degildir: hilesiz oyuncularin da
     * dagilimini toplar ki isabet, mesafe ve tiklama esikleri canli veriyle ayarlanabilsin.
     */
    private void endFight() {
        if (fightHits >= 10) {
            final int ticks = lastPlayerAttackTick - fightStartTick + 1;
            HapiFlagLog.recordCombat(player, fightHits, "sure=" + ticks
                    + " sallama=" + fightSwings
                    + " hareketli=" + fightMoving
                    + " hedef=" + (fightSwitches + 1)
                    + " tepe=" + fightPeakRate
                    + " mesafe=" + (fightDistCount == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.2f/%.2f", fightDistSum / fightDistCount, fightDistMax))
                    + " uzak=" + fightFar + "/" + fightDistCount
                    + sezgiSummary());
            if (telemetryEnabled && telN > 0) writeTelemetry();
        }
        resetSezgi();
        fightStartTick = NEVER;
        fightHits = fightSwings = fightMoving = fightSwitches = fightPeakRate = 0;
        fightDistCount = fightFar = 0;
        fightDistSum = fightDistMax = 0;
        rateSum = 0;
        java.util.Arrays.fill(rateRing, (byte) 0);
    }

    /** Olcutleri hesaplar, Sezgi'ye verir ve DOVUS satirina eklenecek metni dondurur. */
    private String sezgiSummary() {
        final double[] x = new double[4];
        final int timed = edgeN + spamN;
        // kenar: vuruslarin, bekleme suresinin doldugu ilk iki tickten birine (hangisi coksa) dusen payi
        x[0] = timed >= 8 ? Math.max(edgeHist[0], edgeHist[1]) / (double) timed : Double.NaN;
        // ani: nisan hedefe girdigi (ya da bekleme doldugu) tickte atilan vuruslarin payi
        x[1] = timed >= 8 ? instantN / (double) timed : Double.NaN;
        // merkez: vurus aninda yatay nisan hatasinin hitbox yari genisligine orani (0 = tam merkez)
        x[2] = centreN >= 8 ? centreSum / centreN : Double.NaN;
        // iz: hedefin acisal ivmesiyle oyuncunun donus ivmesi arasinda erken (0-1 tick) eksi gec (3-4 tick) iliski
        final double norm = Math.sqrt(corrXX * corrYY);
        x[3] = corrN >= 30 && norm > 1e-6 ? (corrXY[0] + corrXY[1] - corrXY[3] - corrXY[4]) / norm : Double.NaN;

        final StringBuilder sb = new StringBuilder(96);
        for (int f = 0; f < 4; f++) {
            sb.append(' ').append(ac.grim.grimac.hapi.HapiSezgi.NAMES[f]).append('=');
            if (Double.isNaN(x[f])) sb.append('-');
            else sb.append(String.format(java.util.Locale.ROOT, "%.2f", x[f]));
        }
        sb.append(" bekleme=").append(spamN);
        for (int v : edgeHist) sb.append('/').append(v);
        try {
            sezgi.onFight(x, edgeN, fightHits);
        } catch (RuntimeException ignored) { // puanlama hatasi ozet satirini engellemesin
        }
        return sb.toString();
    }

    private void writeTelemetry() {
        final StringBuilder sb = new StringBuilder(telN * 28);
        for (int i = 0; i < telN; i++) {
            if (i > 0) sb.append(' ');
            sb.append(telDYaw[i]).append(',').append(telDPitch[i]).append(',').append(telErrYaw[i]).append(',')
                    .append(telErrPitch[i]).append(',').append(telDist[i]).append(',').append(telTargetW[i]).append(',')
                    .append(telFlags[i]).append(',').append(telCooldown[i]);
        }
        HapiFlagLog.recordTelemetry(player, "vurus=" + fightHits + " hiz="
                + String.format(java.util.Locale.ROOT, "%.2f", player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED))
                + " hedef=" + (fightSwitches + 1), sb.toString());
    }

    private void resetSezgi() {
        telN = 0;
        edgeN = spamN = instantN = centreN = corrN = 0;
        java.util.Arrays.fill(edgeHist, 0);
        centreSum = corrXX = corrYY = 0;
        java.util.Arrays.fill(corrXY, 0);
        java.util.Arrays.fill(accelRing, 0f);
    }

    private static short clampShort(double v) {
        return (short) Math.max(-32000, Math.min(32000, Math.round(v)));
    }

    private static double wrap(double degrees) {
        degrees %= 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }

    /**
     * Dovus surerken her istemci tickinde: nisanin hedefe gore hatasi, hedefin acisal hareketi ve
     * vurus zamanlamasi. Istemci, N. tickteki vurusu N-1. tick sonundaki konumdan, N. tickin
     * donusuyle ve hedefin N-1. tick sonundaki ekran konumuna gore atar; hesap bu yuzden hedef
     * modeli ilerletilmeden once yapilir.
     */
    private void sezgiTick(boolean taintedNow) {
        final PacketEntity target = player.compensatedEntities.entityMap.get(targetId);
        final boolean targetOk = target != null && !target.isDead;

        double errYaw = 0, errPitch = 0, dist = 0, halfWidth = 1;
        boolean geometry = false, onTarget = false;
        if (targetOk && modelValid && modelTargetId == targetId && prevPosValid && !taintedNow) {
            final double dx = renderX - prevX, dz = renderZ - prevZ;
            final double horizontal = Math.sqrt(dx * dx + dz * dz);
            final double eyeY = prevY + player.getEyeHeight();
            if (horizontal > 0.4 && horizontal < 8) {
                geometry = true;
                errYaw = wrap(player.yaw - Math.toDegrees(Math.atan2(-dx, dz)));
                final double centreDy = renderY + 0.9 - eyeY;
                errPitch = player.pitch + Math.toDegrees(Math.atan2(centreDy, horizontal));
                dist = Math.sqrt(horizontal * horizontal + centreDy * centreDy);
                halfWidth = Math.toDegrees(Math.atan2(0.3, horizontal));
                final double pitchTop = -Math.toDegrees(Math.atan2(renderY + 1.8 - eyeY, horizontal));
                final double pitchBottom = -Math.toDegrees(Math.atan2(renderY - eyeY, horizontal));
                onTarget = dist <= 3.6 && Math.abs(errYaw) <= halfWidth + 0.5
                        && player.pitch >= pitchTop - 1 && player.pitch <= pitchBottom + 1;
            }
        }

        // vurus zamanlamasi (yalniz oyuncuya vurus)
        byte cooldownOffset = Byte.MIN_VALUE;
        if (attackedPlayerThisTick && attackCooldownTicks >= 0) {
            final double speed = player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED);
            final int ready = (int) Math.ceil(20.0 / Math.max(speed, 0.1) - 0.5);
            final int offset = attackCooldownTicks - ready;
            cooldownOffset = (byte) Math.max(-100, Math.min(100, offset));
            if (offset < 0) {
                spamN++;
            } else {
                edgeN++;
                edgeHist[Math.min(offset, edgeHist.length - 1)]++;
                if (geometry && Math.min(offset, onTargetStreak) == 0) instantN++;
            }
            if (geometry) {
                centreN++;
                centreSum += Math.min(Math.abs(errYaw) / halfWidth, 2);
            }
        }
        onTargetStreak = onTarget ? onTargetStreak + 1 : 0;

        // hedef modelini bu tickin sonuna ilerlet
        float targetW = 0;
        if (targetOk) {
            final Vector3d serverPos = target.trackedServerPosition.getPos();
            if (modelTargetId != targetId || !modelValid) {
                modelTargetId = targetId;
                modelServerPos = serverPos;
                renderX = serverPos.x;
                renderY = serverPos.y;
                renderZ = serverPos.z;
                lerpLeft = 0;
                modelValid = true;
                onTargetStreak = 0;
            } else {
                final double oldX = renderX, oldZ = renderZ;
                if (serverPos != modelServerPos) {
                    modelServerPos = serverPos;
                    lerpLeft = 3;
                }
                if (lerpLeft > 0) {
                    renderX += (serverPos.x - renderX) / lerpLeft;
                    renderY += (serverPos.y - renderY) / lerpLeft;
                    renderZ += (serverPos.z - renderZ) / lerpLeft;
                    lerpLeft--;
                }
                // yalniz hedefin kendi hareketinden dogan acisal hiz (oyuncunun konumu sabit tutulur)
                if (geometry) {
                    targetW = (float) wrap(Math.toDegrees(Math.atan2(-(renderX - player.x), renderZ - player.z))
                            - Math.toDegrees(Math.atan2(-(oldX - player.x), oldZ - player.z)));
                }
            }
        } else {
            modelValid = false;
        }

        // hedefin acisal ivmesi ile oyuncunun donus ivmesi: gecikmeli capraz iliski
        final float targetAccel = geometry ? targetW - prevTargetW : 0;
        final float ownAccel = dYaw - prevDYawForCorr;
        prevTargetW = targetW;
        prevDYawForCorr = dYaw;
        accelRing[accelIndex] = targetAccel;
        if (geometry && dist >= 1.5) {
            for (int lag = 0; lag < LAGS; lag++) {
                corrXY[lag] += accelRing[(accelIndex - lag + LAGS) % LAGS] * ownAccel;
            }
            corrXX += targetAccel * targetAccel;
            corrYY += ownAccel * ownAccel;
            if (Math.abs(targetAccel) > 0.3f) corrN++;
        }
        if (++accelIndex == LAGS) accelIndex = 0;

        if (telemetryEnabled && telN < TEL_MAX) {
            if (telDYaw == null) {
                telDYaw = new short[TEL_MAX];
                telDPitch = new short[TEL_MAX];
                telErrYaw = new short[TEL_MAX];
                telErrPitch = new short[TEL_MAX];
                telDist = new short[TEL_MAX];
                telTargetW = new short[TEL_MAX];
                telFlags = new byte[TEL_MAX];
                telCooldown = new byte[TEL_MAX];
            }
            telDYaw[telN] = clampShort(dYaw * 100);
            telDPitch[telN] = clampShort(dPitch * 100);
            telErrYaw[telN] = clampShort(errYaw * 100);
            telErrPitch[telN] = clampShort(errPitch * 100);
            telDist[telN] = clampShort(dist * 100);
            telTargetW[telN] = clampShort(targetW * 100);
            telFlags[telN] = (byte) ((attackedPlayerThisTick ? F_ATTACK : 0) | (swingsThisTick > 0 ? F_SWING : 0)
                    | (onTarget ? F_ON_TARGET : 0) | (geometry ? F_MODEL : 0) | (taintedNow ? F_TAINTED : 0)
                    | (attacksThisTick > playerAttacksThisTick ? F_OTHER_ATTACK : 0));
            telCooldown[telN] = cooldownOffset;
            telN++;
        }
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
            if (active && fightStartTick != NEVER && entityId != targetId) fightSwitches++;
            if (!attackedPlayerThisTick) attackCooldownTicks = cooldownTicks;
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
                if (fightStartTick == NEVER) fightStartTick = tick;
                fightHits += playerAttacksThisTick;
            }
            if (fightStartTick != NEVER) {
                // Uzun dovus 20 saniyelik dilimlerle yazilir (oyuncu dovus ortasinda cikarsa ozet kaybolmasin)
                if (tick - lastPlayerAttackTick > windowTicks || tick - fightStartTick >= 400) {
                    endFight();
                } else {
                    fightSwings += swingsThisTick;
                    rateSum += Math.min(playerAttacksThisTick, 100) - rateRing[rateIndex];
                    rateRing[rateIndex] = (byte) Math.min(playerAttacksThisTick, 100);
                    if (++rateIndex == rateRing.length) rateIndex = 0;
                    if (rateSum > fightPeakRate) fightPeakRate = rateSum;
                }
            }
            if (fightStartTick != NEVER) {
                try {
                    sezgiTick(tainted > 0);
                } catch (RuntimeException e) { // olcum hatasi diger vurus check'lerini bozmamali
                    resetSezgi();
                }
            }
            combatLag.onTick(tick - lastPlayerAttackTick <= windowTicks);
            sprintReset.onTick(tick, attackedPlayerThisTick);

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
                    if (Math.abs(prev2DYaw) > 2f || Math.abs(prevDYaw) > 2f || Math.abs(dYaw) > 2f) fightMoving++;
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
        attackCooldownTicks = -1;
        cooldownTicks++;
        prevX = player.x;
        prevY = player.y;
        prevZ = player.z;
        prevPosValid = !exempt;
        tick++;
    }
}
