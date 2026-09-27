package net.takensmp.bridge;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** In-game /link and /unlink. The Discord bot makes and checks the codes. */
public final class LinkCommand implements TabExecutor {

    private final TakenBridge plugin;

    public LinkCommand(TakenBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can link accounts.");
            return true;
        }
        if (!plugin.client().isConnected()) {
            plugin.tell(player, plugin.getConfig().getString("messages.discord-offline",
                    "<red>Discord linking is offline right now. Try again in a few minutes.</red>"));
            return true;
        }
        boolean unlink = command.getName().equalsIgnoreCase("unlink");
        Map<String, Object> frame = GameListener.who(player);
        frame.put("t", unlink ? "unlink_request" : "link_request");
        plugin.client().send(frame);
        if (!unlink) {
            plugin.tell(player, plugin.getConfig().getString("messages.link-requested", "<gray>Getting your link code…</gray>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
