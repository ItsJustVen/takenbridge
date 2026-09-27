package net.takensmp.bridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class TakenBridge extends JavaPlugin implements BridgeClient.Handler {

    private BridgeClient client;
    private File pairingFile;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pairingFile = new File(getDataFolder(), "pairing.yml");
        YamlConfiguration pairing = YamlConfiguration.loadConfiguration(pairingFile);

        String host = getConfig().getString("bot.host", "216.173.77.67");
        int port = getConfig().getInt("bot.port", 7006);
        client = new BridgeClient(host, port, "TakenSMP", getPluginMeta().getVersion(), this);
        client.setCredentials(pairing.getString("token"), pairing.getString("fingerprint"));

        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        BridgeCommand command = new BridgeCommand(this);
        var cmd = getCommand("takenbridge");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }
        LinkCommand link = new LinkCommand(this);
        for (String name : List.of("link", "unlink")) {
            var c = getCommand(name);
            if (c != null) {
                c.setExecutor(link);
                c.setTabCompleter(link);
            }
        }

        client.start();
        if (!client.isPaired()) {
            getLogger().warning("Not paired with Discord yet. Run /bridge pair in Discord, then: takenbridge pair <code>");
        }
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("t", "status");
        started.put("state", "start");
        started.put("max", Bukkit.getMaxPlayers());
        client.send(started);
    }

    @Override
    public void onDisable() {
        if (client == null) return;
        Map<String, Object> stopped = new LinkedHashMap<>();
        stopped.put("t", "status");
        stopped.put("state", "stop");
        client.send(stopped);
        client.stop(1_500); // give "server stopped" a moment to reach Discord
    }

    public BridgeClient client() { return client; }

    // ------------------------------------------------------------------ BridgeClient.Handler

    @Override
    public void onFrame(Map<String, Object> frame) {
        if (!isEnabled()) return;
        Bukkit.getScheduler().runTask(this, () -> handleFrame(frame));
    }

    @Override
    public void onStatus(String status, String detail) {
        String msg = "Discord bridge: " + status + (detail != null ? " — " + detail : "");
        switch (status) {
            case "connected" -> getLogger().info(msg);
            case "rejected", "error" -> getLogger().warning(msg);
            default -> getLogger().log(Level.FINE, msg);
        }
    }

    @Override
    public void onPaired(String token, String fingerprint) {
        getLogger().info("Paired with Discord. Bot certificate pinned: " + fingerprint);
        Bukkit.getScheduler().runTask(this, () -> savePairing(token, fingerprint));
    }

    void savePairing(String token, String fingerprint) {
        YamlConfiguration pairing = new YamlConfiguration();
        pairing.options().setHeader(List.of(
                "Written by TakenBridge after pairing. Keep this file private.",
                "Delete it (or run 'takenbridge unpair') to disconnect from Discord."));
        pairing.set("token", token);
        pairing.set("fingerprint", fingerprint);
        try {
            pairing.save(pairingFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save pairing.yml", e);
        }
    }

    // ------------------------------------------------------------------ Discord -> game

    private void handleFrame(Map<String, Object> f) {
        String t = String.valueOf(f.get("t"));
        switch (t) {
            case "dchat" -> showDiscordChat(f);
            case "bot" -> showBotMessage(f);
            case "req_players" -> client.send(playerList());
            case "tell" -> tellPlain(f);
            case "link_code" -> showLinkCode(f);
            case "linked" -> onLinked(f);
            case "unlinked" -> onUnlinked(f);
            default -> getLogger().fine("Unknown frame from Discord: " + t);
        }
    }

    private void showDiscordChat(Map<String, Object> f) {
        String author = String.valueOf(f.getOrDefault("author", "Discord"));
        String message = String.valueOf(f.getOrDefault("msg", ""));
        if (message.isBlank()) return;
        Object replyTo = f.get("replyTo");
        String reply = replyTo == null ? "" : "(↩ " + replyTo + ") ";
        TextColor color = TextColor.fromHexString(String.valueOf(f.getOrDefault("color", "#ffffff")));
        if (color == null) color = TextColor.color(0xFFFFFF);

        String format = getConfig().getString("formats.discord-to-game",
                "<#5865F2>[Discord]</#5865F2> <rolecolor><name></rolecolor><gray>:</gray> <gray><reply></gray><white><message></white>");
        // unparsed(): Discord users can't inject formatting, click events, etc.
        Component line = miniMessage.deserialize(format,
                Placeholder.unparsed("name", author),
                Placeholder.unparsed("message", message),
                Placeholder.unparsed("reply", reply),
                Placeholder.styling("rolecolor", color));
        Bukkit.getServer().broadcast(line);
    }

    /** A message from the bot itself (e.g. the helper answering "!ask"). */
    private void showBotMessage(Map<String, Object> f) {
        String message = String.valueOf(f.getOrDefault("msg", ""));
        if (message.isBlank()) return;
        String format = getConfig().getString("formats.bot-to-game",
                "<gold>[<name>]</gold> <yellow><message></yellow>");
        Component line = miniMessage.deserialize(format,
                Placeholder.unparsed("name", String.valueOf(f.getOrDefault("name", "Discord"))),
                Placeholder.unparsed("message", message));
        Object to = f.get("to");
        if (to != null) {
            Player target = Bukkit.getPlayerExact(String.valueOf(to));
            if (target != null) target.sendMessage(line);
            return;
        }
        Bukkit.getServer().broadcast(line);
    }

    // ------------------------------------------------------------------ linking

    /** Send a staff-configured MiniMessage line to a player. */
    void tell(Player player, String miniMessageText) {
        if (player != null && miniMessageText != null && !miniMessageText.isBlank()) {
            player.sendMessage(miniMessage.deserialize(miniMessageText));
        }
    }

    private static Player byUuid(Map<String, Object> f) {
        try {
            return Bukkit.getPlayer(UUID.fromString(String.valueOf(f.get("uuid"))));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Plain text from the bot (never parsed as formatting). */
    private void tellPlain(Map<String, Object> f) {
        Player p = byUuid(f);
        if (p == null) return;
        p.sendMessage(miniMessage.deserialize("<yellow><msg></yellow>", Placeholder.unparsed("msg", String.valueOf(f.getOrDefault("msg", "")))));
    }

    private void showLinkCode(Map<String, Object> f) {
        Player p = byUuid(f);
        if (p == null) return;
        String code = String.valueOf(f.get("code")).replaceAll("[^A-Z0-9]", ""); // bot codes are A-Z/0-9 only
        String minutes = String.valueOf(f.getOrDefault("minutes", 10));
        String format = getConfig().getString("messages.link-code",
                "<green>Your Discord link code: <click:copy_to_clipboard:'%code%'><hover:show_text:'<gray>Click to copy</gray>'><yellow><bold>%code%</bold></yellow></hover></click></green>"
                        + "<newline><gray>In Discord, type</gray> <white>/link code:%code%</white> <gray>within %minutes% minutes.</gray>");
        tell(p, format.replace("%code%", code).replace("%minutes%", minutes));
    }

    private void onLinked(Map<String, Object> f) {
        Player p = byUuid(f);
        String discordName = String.valueOf(f.getOrDefault("discordName", "Discord"));
        if (p != null) {
            String format = getConfig().getString("messages.linked", "<green>✔ Linked to Discord as <white><discord></white>!</green>");
            p.sendMessage(miniMessage.deserialize(format, Placeholder.unparsed("discord", discordName)));
        }
        if (Boolean.TRUE.equals(f.get("first"))) {
            String name = String.valueOf(f.getOrDefault("name", p != null ? p.getName() : ""));
            if (!name.matches("[A-Za-z0-9_.*]{1,32}")) return; // only real player names go into console commands
            for (String cmd : getConfig().getStringList("link.rewards")) {
                if (cmd == null || cmd.isBlank()) continue;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", name));
            }
        }
    }

    private void onUnlinked(Map<String, Object> f) {
        Player p = byUuid(f);
        if (p != null) tell(p, getConfig().getString("messages.unlinked", "<gray>Your account is no longer linked to Discord.</gray>"));
    }

    Map<String, Object> playerList() {
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (GameListener.isVanished(p)) continue;
            players.add(GameListener.who(p));
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("t", "players");
        f.put("players", players);
        f.put("max", Bukkit.getMaxPlayers());
        return f;
    }
}
