package ac.grim.grimac.command.commands;

import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.command.CommandUtils;
import ac.grim.grimac.hapi.HapiFlagLog;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;

import java.util.List;
import java.util.Map;

/** HapiAC: /grim top [check|oyuncu|dunya|geri|sifirla] [adet] — acilistan (ya da son sifirlamadan) beri flag dokumu. */
public class GrimTop implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(commandManager.commandBuilder("grim", "grimac")
                .literal("top")
                .permission("grim.top")
                .optional("tur", StringParser.stringParser(), CommandUtils.fromStrings("check", "oyuncu", "dunya", "geri", "sifirla"))
                .optional("adet", IntegerParser.integerParser(1, 50))
                .handler(context -> handle(context.sender(),
                        context.getOrDefault("tur", "check").toLowerCase(),
                        context.getOrDefault("adet", 10)))
                .build());
    }

    private void handle(Sender sender, String kindName, int limit) {
        final HapiFlagLog.Kind kind;
        switch (kindName) {
            case "check" -> kind = HapiFlagLog.Kind.CHECK;
            case "oyuncu" -> kind = HapiFlagLog.Kind.PLAYER;
            case "dunya" -> kind = HapiFlagLog.Kind.WORLD;
            case "geri" -> kind = HapiFlagLog.Kind.SETBACK;
            case "sifirla" -> {
                HapiFlagLog.reset();
                sender.sendMessage(Component.text("HapiAC flag sayaçları sıfırlandı.", NamedTextColor.GREEN));
                return;
            }
            default -> {
                sender.sendMessage(Component.text("Kullanım: /grim top [check|oyuncu|dunya|geri|sifirla] [adet]", NamedTextColor.RED));
                return;
            }
        }

        final long minutes = Math.max(1, (System.currentTimeMillis() - HapiFlagLog.since()) / 60_000);
        final long total = HapiFlagLog.total(kind);
        final String unit = kind == HapiFlagLog.Kind.SETBACK ? "geri çekme, nedene göre" : "flag (" + kindName + ")";
        sender.sendMessage(Component.text()
                .append(Component.text("HapiAC ", NamedTextColor.GREEN))
                .append(Component.text("» son " + minutes + " dk, toplam " + total + " " + unit, NamedTextColor.WHITE))
                .build());

        final List<Map.Entry<String, Long>> top = HapiFlagLog.top(kind, limit);
        if (top.isEmpty()) {
            sender.sendMessage(Component.text("  Kayıt yok.", NamedTextColor.GRAY));
            return;
        }
        int rank = 1;
        for (Map.Entry<String, Long> entry : top) {
            final long percent = total == 0 ? 0 : Math.round(entry.getValue() * 100.0 / total);
            sender.sendMessage(Component.text()
                    .append(Component.text("  " + rank++ + ". ", NamedTextColor.GRAY))
                    .append(Component.text(entry.getKey(), NamedTextColor.WHITE))
                    .append(Component.text("  " + entry.getValue(), NamedTextColor.GREEN))
                    .append(Component.text("  %" + percent, NamedTextColor.GRAY))
                    .build());
        }
    }
}
