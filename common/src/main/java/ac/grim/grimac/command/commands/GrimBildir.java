package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;

/** HapiAC: /grim bildir <mesaj> — MiniMessage metnini hapi.bildirim iznine sahip çevrimiçi oyunculara yollar. */
public class GrimBildir implements BuildableCommand {
    public static final String RECEIVE_PERMISSION = "hapi.bildirim";

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(commandManager.commandBuilder("grim", "grimac")
                .literal("bildir")
                .permission("grim.bildir")
                .required("mesaj", StringParser.greedyStringParser())
                .handler(context -> {
                    Component message = MessageUtil.miniMessage(context.<String>get("mesaj"));
                    int sent = 0;
                    for (PlatformPlayer player : GrimAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers()) {
                        if (!player.hasPermission(RECEIVE_PERMISSION, false)) continue;
                        player.sendMessage(message);
                        sent++;
                    }
                    context.sender().sendMessage(Component.text("Bildirim " + sent + " oyuncuya gönderildi."));
                })
                .build());
    }
}
