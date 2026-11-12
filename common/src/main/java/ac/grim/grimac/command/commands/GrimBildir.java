package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * HapiAC hile bildirimi.
 * /grim bildirim — hapi.bildirim iznine sahip oyuncu bildirimleri kendisi için açar/kapatır (kalıcı, varsayılan kapalı).
 * /grim bildir <mesaj> — MiniMessage metnini bildirimi açık olan çevrimiçi oyunculara yollar.
 */
public class GrimBildir implements BuildableCommand {
    public static final String RECEIVE_PERMISSION = "hapi.bildirim";

    private Set<UUID> enabled; // ilk kullanımda dosyadan okunur

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(commandManager.commandBuilder("grim", "grimac")
                .literal("bildirim")
                .permission(RECEIVE_PERMISSION)
                .handler(context -> toggle(context.sender()))
                .build());
        commandManager.command(commandManager.commandBuilder("grim", "grimac")
                .literal("bildir")
                .permission("grim.bildir")
                .required("mesaj", StringParser.greedyStringParser())
                .handler(context -> {
                    Component message = MessageUtil.miniMessage(context.<String>get("mesaj"));
                    Set<UUID> open = enabled();
                    int sent = 0;
                    for (PlatformPlayer player : GrimAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers()) {
                        if (!open.contains(player.getUniqueId()) || !player.hasPermission(RECEIVE_PERMISSION, false)) continue;
                        player.sendMessage(message);
                        sent++;
                    }
                    context.sender().sendMessage(Component.text("Bildirim " + sent + " oyuncuya gönderildi."));
                })
                .build());
    }

    private void toggle(Sender sender) {
        if (!sender.isPlayer()) {
            sender.sendMessage(Component.text("Bu komutu yalnızca oyuncular kullanabilir.", NamedTextColor.RED));
            return;
        }
        Set<UUID> open = enabled();
        UUID id = sender.getUniqueId();
        boolean now = !open.remove(id) && open.add(id);
        save(open);
        sender.sendMessage(now
                ? Component.text("Hile bildirimleri açıldı.", NamedTextColor.GREEN)
                : Component.text("Hile bildirimleri kapatıldı.", NamedTextColor.RED));
    }

    private static File file() {
        return new File(new File(GrimAPI.INSTANCE.getGrimPlugin().getDataFolder(), "hapi"), "bildirim-acik.txt");
    }

    private synchronized Set<UUID> enabled() {
        if (enabled != null) return enabled;
        Set<UUID> set = ConcurrentHashMap.newKeySet();
        File file = file();
        if (file.isFile()) {
            try {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    try {
                        set.add(UUID.fromString(line.trim()));
                    } catch (IllegalArgumentException ignored) {
                        // bozuk satır atlanır
                    }
                }
            } catch (IOException ignored) {
                // okunamazsa liste boş başlar
            }
        }
        return enabled = set;
    }

    private static synchronized void save(Set<UUID> open) {
        File file = file();
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.writeString(file.toPath(), open.stream().map(UUID::toString).collect(Collectors.joining("\n")), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // yazılamazsa ayar yalnız bu oturumda geçerli kalır
        }
    }
}
