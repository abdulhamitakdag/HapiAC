package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.hapi.HapiFlagLog;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Istemcinin bildirdigi marka ve mod kanallari ("minecraft:register").
 * <p>
 * Giristen birkac saniye sonra oyuncu basina bir kez: kanallar flag loguna "CLIENT" satiri olarak yazilir ve
 * marka / kanal adlarindan biri "alert-patterns" listesindeki bir parcayi iceriyorsa flag uretilir.
 * Hile istemcileri kendini gizleyebilir; bu check yalnizca kendini bildiren modlari (harita, sematik vb.) gosterir.
 */
@CheckData(name = "ClientMod", stableKey = "hapi.client.mod", description = "Izlenen bir mod kanali ya da istemci markasi ile giris", decay = 0)
public final class ClientMod extends Check implements PacketReceiveListener {
    private static final int MAX_CHANNELS = 96;
    private static final int MAX_NAME = 64;
    private static final int REPORT_AFTER_TICKS = 100;

    private List<String> patterns; // onReload atar (super() icinde)
    private boolean logEnabled;

    private final TreeSet<String> channels = new TreeSet<>();
    private int ticks;
    private boolean reported;

    public ClientMod(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        logEnabled = config.getBooleanElse("hapi.client.log", true);
        final List<String> lowered = new ArrayList<>();
        for (String p : config.getStringListElse("hapi.client.alert-patterns", new ArrayList<>())) {
            if (p != null && !p.isBlank()) lowered.add(p.toLowerCase(Locale.ROOT));
        }
        patterns = lowered;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (reported) return;
        final PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PLUGIN_MESSAGE) {
            final WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            handle(packet.getChannelName(), packet.getData());
        } else if (type == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            final WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
            handle(packet.getChannelName(), packet.getData());
        } else if (isTickPacket(type) && ++ticks >= REPORT_AFTER_TICKS) {
            report();
        }
    }

    private void handle(String channel, byte[] data) {
        if (data == null || !("minecraft:register".equals(channel) || "REGISTER".equals(channel))) return;
        for (String name : new String(data, StandardCharsets.UTF_8).split("\0")) {
            if (channels.size() >= MAX_CHANNELS) return;
            if (name.isEmpty() || name.length() > MAX_NAME) continue;
            channels.add(clean(name));
        }
    }

    private static String clean(String name) {
        final StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = Character.toLowerCase(name.charAt(i));
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ':' || c == '_' || c == '-' || c == '.' || c == '/' ? c : '?');
        }
        return sb.toString();
    }

    private void report() {
        reported = true;
        if (player.disableGrim) return;
        if (logEnabled) HapiFlagLog.recordClient(player, String.join(" ", channels));
        if (patterns.isEmpty()) return;

        final String brand = String.valueOf(player.getBrand()).toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            if (brand.contains(p)) {
                flag("marka=" + brand);
                return;
            }
            for (String ch : channels) {
                if (ch.contains(p)) {
                    flag("kanal=" + ch);
                    return;
                }
            }
        }
    }
}
