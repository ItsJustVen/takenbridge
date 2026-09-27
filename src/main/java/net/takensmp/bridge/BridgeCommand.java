package net.takensmp.bridge;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.io.File;
import java.util.List;

/** /takenbridge pair <code> | status | unpair  (console or ops) */
public final class BridgeCommand implements TabExecutor {

    private final TakenBridge plugin;

    public BridgeCommand(TakenBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        BridgeClient client = plugin.client();
        switch (sub) {
            case "pair" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /" + label + " pair <code>   (get the code from /bridge pair in Discord)");
                    return true;
                }
                client.pair(args[1]);
                sender.sendMessage("Pairing with Discord… watch the console for \"Paired with Discord\".");
            }
            case "unpair" -> {
                client.forget();
                File f = new File(plugin.getDataFolder(), "pairing.yml");
                if (f.exists() && !f.delete()) sender.sendMessage("Couldn't delete pairing.yml — remove it by hand.");
                sender.sendMessage("Unpaired. The bridge is off until you pair again.");
            }
            case "status" -> {
                sender.sendMessage("TakenBridge " + plugin.getPluginMeta().getVersion() + " — " + client.status()
                        + (client.isConnected() ? " ✔" : "")
                        + (client.queued() > 0 ? " (" + client.queued() + " events waiting)" : ""));
                if (!client.isPaired()) sender.sendMessage("Not paired. Run /bridge pair in Discord, then /" + label + " pair <code>.");
                else sender.sendMessage("Pinned bot certificate: " + client.fingerprint());
            }
            default -> sender.sendMessage("Usage: /" + label + " <pair <code>|status|unpair>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("pair", "status", "unpair").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
