package net.takensmp.bridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Minecraft -> Discord events. Everything runs at MONITOR so other plugins decide first. */
public final class GameListener implements Listener {

    private final TakenBridge plugin;

    public GameListener(TakenBridge plugin) {
        this.plugin = plugin;
    }

    /** Floodgate gives Bedrock players UUIDs whose top half is all zeros. */
    static boolean isBedrock(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0L;
    }

    /** EssentialsX, SuperVanish, PremiumVanish etc. all set the "vanished" metadata. */
    static boolean isVanished(Player p) {
        for (MetadataValue v : p.getMetadata("vanished")) {
            if (v.asBoolean()) return true;
        }
        return false;
    }

    static Map<String, Object> who(Player p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", p.getUniqueId().toString());
        m.put("name", p.getName());
        m.put("bedrock", isBedrock(p));
        return m;
    }

    /** Render (incl. vanilla translations like death messages) and flatten to plain text. */
    static String plain(Component c) {
        if (c == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(c, Locale.US)).trim();
    }

    private boolean on(String key) {
        return plugin.getConfig().getBoolean("relay." + key, true);
    }

    private boolean hidden(Player p) {
        return plugin.getConfig().getBoolean("relay.hide-vanished", true) && isVanished(p);
    }

    private void send(String type, Player p, Map<String, Object> extra) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("t", type);
        f.putAll(who(p));
        if (extra != null) f.putAll(extra);
        plugin.client().send(f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // Cancelled chat (muted players, chat channels, anti-spam) never reaches here.
        if (!on("chat")) return;
        String text = plain(event.message());
        if (text.isEmpty()) return;
        send("chat", event.getPlayer(), Map.of("msg", text));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!on("joins") || hidden(p)) return;
        send("join", p, Map.of("first", !p.hasPlayedBefore()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (!on("joins") || hidden(p)) return;
        send("quit", p, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (!on("deaths") || hidden(p)) return;
        String msg = plain(event.deathMessage());
        if (msg.isEmpty()) return;
        send("death", p, Map.of("msg", msg));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player p = event.getPlayer();
        if (!on("advancements") || hidden(p)) return;
        // message() is the chat announcement; null for recipes, hidden advancements,
        // or when the announceAdvancements gamerule is off.
        String msg = plain(event.message());
        if (msg.isEmpty()) return;
        send("adv", p, Map.of("msg", msg));
    }
}
