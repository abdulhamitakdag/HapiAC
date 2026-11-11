package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import org.jetbrains.annotations.NotNull;

/**
 * HapiAC envanter / otomasyon modulunun tek giris noktasi.
 * <p>
 * Envanter tiklamalarini, eldeki slot degisimlerini ve totem patlamasini izler; dort sezgisel check'i
 * dogrudan cagirir. Envanterle ilgilenmeyen oyuncuda tick basina maliyet birkac karsilastirmadir.
 */
public final class InventoryTracker extends GrimProcessor implements PacketReceiveListener, PacketSendListener {
    private static final int OFFHAND_SLOT = 45; // oyuncu envanteri (pencere 0)
    private static final int OFFHAND_BUTTON = 40; // SWAP tiklamasinda "diger el" tusu
    private static final int SPAM_WINDOW_TICKS = 12;
    private static final int POP_TIMEOUT_TICKS = 100;
    private static final int PICK_ALL_WINDOW_TICKS = 10;
    private static final int NEVER = Integer.MIN_VALUE / 2;

    private AutoTotem autoTotem;
    private InventoryBurst inventoryBurst;
    private InventoryRhythm inventoryRhythm;
    private SilentSwap silentSwap;

    private boolean moduleEnabled; // onReload atar (super() icinde)

    private int tick;

    // envanter tiklamalari
    private int distinctClicksThisTick;
    private int lastClickSlot = Integer.MIN_VALUE;
    private boolean nonQuickMoveThisTick;
    private int lastLoneQuickMoveTick = NEVER;

    // tek tick icinde slot degistir -> eylem -> geri don
    private int heldSlot = -1;
    private int slotBeforeSwap = -1;
    private boolean swappedThisTick;
    private boolean actionSinceSwap;

    // totem
    private boolean popPending;
    private boolean popAfterSpam;
    private int popTick;
    private int offhandActionTick;
    private int lastOffhandActionTick = NEVER;

    public InventoryTracker(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        moduleEnabled = config.getBooleanElse("hapi.inventory.enabled", true);
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_STATUS) return;
        final WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
        if (status.getStatus() != 35 || status.getEntityId() != player.entityID) return; // 35 = olumsuzluk totemi patladi
        // Tepki suresi istemci tickiyle olculur: patlama paketinden hemen once yollanan transaction'in yaniti
        // geldigi an istemci patlamayi gormustur. Boylece ag gecikmesi ve tick fazi olcume karismaz.
        player.sendTransaction();
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), this::onPopSeen);
    }

    private void onPopSeen() {
        popPending = true;
        popTick = tick;
        offhandActionTick = NEVER;
        // Patlamadan hemen once de diger el takasi yolluyorsa oyuncu tusu surekli basiyordur (mesru "spam" teknigi)
        popAfterSpam = tick - lastOffhandActionTick <= SPAM_WINDOW_TICKS;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        final PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            final WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
            final WindowClickType clickType = click.getWindowClickType();
            final int slot = click.getSlot();
            if (clickType != WindowClickType.QUICK_CRAFT && slot >= 0 && slot != lastClickSlot) {
                distinctClicksThisTick++;
                lastClickSlot = slot;
                if (clickType != WindowClickType.QUICK_MOVE || click.getButton() != 0) nonQuickMoveThisTick = true;
            }
            if ((click.getWindowId() == 0 && slot == OFFHAND_SLOT)
                    || (clickType == WindowClickType.SWAP && click.getButton() == OFFHAND_BUTTON)) {
                onOffhandAction();
            }
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            final DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
                onOffhandAction();
            } else if (action == DiggingAction.START_DIGGING) {
                onAction();
            }
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            onHeldSlot(new WrapperPlayClientHeldItemChange(event).getSlot());
        } else if (type == PacketType.Play.Client.USE_ITEM || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                || type == PacketType.Play.Client.INTERACT_ENTITY || type == PacketType.Play.Client.ATTACK) {
            onAction();
        } else if (isTickPacket(type)) {
            onTickEnd();
        }
    }

    private void onOffhandAction() {
        lastOffhandActionTick = tick;
        if (popPending) offhandActionTick = tick;
    }

    private void onAction() {
        if (swappedThisTick) actionSinceSwap = true;
    }

    private void onHeldSlot(int slot) {
        if (swappedThisTick && actionSinceSwap && slot == slotBeforeSwap && slot != heldSlot) {
            if (silentSwap == null) init();
            if (active()) silentSwap.onSilentSwap(tick, slotBeforeSwap, heldSlot);
            swappedThisTick = false;
        } else if (slot != heldSlot) {
            slotBeforeSwap = heldSlot;
            swappedThisTick = heldSlot >= 0;
            actionSinceSwap = false;
        }
        heldSlot = slot;
    }

    private void init() {
        autoTotem = player.checkManager.get(AutoTotem.class);
        inventoryBurst = player.checkManager.get(InventoryBurst.class);
        inventoryRhythm = player.checkManager.get(InventoryRhythm.class);
        silentSwap = player.checkManager.get(SilentSwap.class);
    }

    private boolean active() {
        // Tick paketi atlayabilen istemcilerde (1.9 - 1.21.1) tick sayaci guvenilmez
        return moduleEnabled && !player.disableGrim && !player.canSkipTicks()
                && player.gamemode != GameMode.CREATIVE && player.gamemode != GameMode.SPECTATOR;
    }

    private void onTickEnd() {
        if (autoTotem == null) init();

        if (active()) {
            // Vanilla "shift + cift tik": ilk tik tek bir shift-tiklama yollar, ikincisi ayni turdeki butun
            // yiginlari tek tickte tasir. Bu yuzden tek shift-tiklamadan hemen sonraki shift patlamasi mesrudur.
            final boolean pickAll = distinctClicksThisTick > 1 && !nonQuickMoveThisTick
                    && tick - lastLoneQuickMoveTick <= PICK_ALL_WINDOW_TICKS;
            if (distinctClicksThisTick == 1 && !nonQuickMoveThisTick) lastLoneQuickMoveTick = tick;
            if (!pickAll) inventoryBurst.onTick(distinctClicksThisTick);
            inventoryRhythm.onTick(tick, distinctClicksThisTick);

            if (popPending) {
                if (offhandActionTick != NEVER && player.inventory.getOffHand().getType() == ItemTypes.TOTEM_OF_UNDYING) {
                    popPending = false;
                    if (!popAfterSpam) autoTotem.onRetotem(offhandActionTick - popTick);
                } else if (tick - popTick > POP_TIMEOUT_TICKS) {
                    popPending = false;
                }
            }
        } else {
            popPending = false;
        }

        distinctClicksThisTick = 0;
        nonQuickMoveThisTick = false;
        swappedThisTick = false;
        actionSinceSwap = false;
        tick++;
    }
}
