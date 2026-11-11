package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.jetbrains.annotations.NotNull;

/**
 * HapiAC kristal modulunun tek giris noktasi.
 * <p>
 * Oyuncuya yollanan varlik dogma paketlerini izler: en buyuk varlik kimligini ve son end kristallerinin
 * istemcide gorundugu ticki tutar. Vuruslarda iki check'i dogrudan cagirir. Kristal dogmayan ortamda
 * maliyet, dogma paketi basina bir tur karsilastirmasidir.
 */
public final class CrystalTracker extends GrimProcessor implements PacketReceiveListener, PacketSendListener {
    private static final int RING = 8;
    private static final int UNSEEN = Integer.MAX_VALUE;
    // 1.20.2 oncesinde canli varlik ve oyuncular ayri paketle dogar; en buyuk kimlik guvenilir olmaz
    private static final boolean SINGLE_SPAWN_PACKET =
            PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_20_2);

    private CrystalSpeed crystalSpeed;
    private AttackPredict attackPredict;

    private boolean moduleEnabled; // onReload atar (super() icinde)

    private int tick;
    private volatile int maxEntityId = -1;

    // son kristaller: kimlik ve istemcinin gordugu tick (gorulmediyse UNSEEN)
    private final int[] crystalId = new int[RING];
    private final int[] crystalSeenTick = new int[RING];
    private int crystalHead;

    private int swingsSinceBreak;
    private int attacksSinceBreak;

    public CrystalTracker(@NotNull GrimPlayer player) {
        super(player);
        java.util.Arrays.fill(crystalId, -1);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        moduleEnabled = config.getBooleanElse("hapi.crystal.enabled", true);
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.SPAWN_ENTITY) return;
        final WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
        final int id = spawn.getEntityId();
        if (id > maxEntityId) maxEntityId = id;
        if (!moduleEnabled || spawn.getEntityType() != EntityTypes.END_CRYSTAL) return;

        // Kristalin istemcide gorundugu tick: dogma paketinden hemen onceki transaction'in yaniti geldiginde
        player.sendTransaction();
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> onCrystalSeen(id));
        // Yanit gelmeden vurulursa "gorulmeden vuruldu" sayilsin diye kayit simdiden acilir
        player.runSafely(() -> {
            crystalId[crystalHead] = id;
            crystalSeenTick[crystalHead] = UNSEEN;
            crystalHead = (crystalHead + 1) % RING;
        });
    }

    private void onCrystalSeen(int id) {
        for (int i = 0; i < RING; i++) {
            if (crystalId[i] == id) {
                crystalSeenTick[i] = tick;
                return;
            }
        }
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        final PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Client.ATTACK) {
            onAttack(new WrapperPlayClientAttack(event).getEntityId());
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            final WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                onAttack(packet.getEntityId());
            }
        } else if (isAnimation(type)) {
            swingsSinceBreak++;
        } else if (isTickPacket(type)) {
            tick++;
        }
    }

    private void onAttack(int entityId) {
        attacksSinceBreak++;
        if (!moduleEnabled || player.disableGrim || player.canSkipTicks()
                || player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR) return;
        if (crystalSpeed == null) {
            crystalSpeed = player.checkManager.get(CrystalSpeed.class);
            attackPredict = player.checkManager.get(AttackPredict.class);
        }

        if (SINGLE_SPAWN_PACKET && entityId > maxEntityId && maxEntityId >= 0) {
            attackPredict.onPredict(entityId, maxEntityId);
            return;
        }

        for (int i = 0; i < RING; i++) {
            if (crystalId[i] != entityId) continue;
            final int seen = crystalSeenTick[i];
            crystalId[i] = -1; // ayni kristale ikinci vurus olcum degil
            // Her vurusun kendi kol sallamasi vurustan SONRA gelir: bu vurusunki henuz gelmedi,
            // onceki kirma vurusununki ise sayacin -1'den baslamasiyla dusulur
            final int wasted = swingsSinceBreak - (attacksSinceBreak - 1);
            crystalSpeed.onBreak(tick, seen == UNSEEN ? -1 : tick - seen, wasted);
            swingsSinceBreak = -1;
            attacksSinceBreak = 0;
            return;
        }
    }
}
