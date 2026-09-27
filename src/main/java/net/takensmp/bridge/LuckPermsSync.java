package net.takensmp.bridge;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rank sync with LuckPerms. Only loaded when LuckPerms is installed.
 *  - Tells the bot a player's groups (on join, when their ranks change, or when asked).
 *  - Applies groups the bot asks for, but ONLY groups listed in
 *    sync.discord-can-grant in config.yml, so Discord can never hand out op-level ranks.
 */
public final class LuckPermsSync {

    private final TakenBridge plugin;
    private final LuckPerms lp;
    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();

    public LuckPermsSync(TakenBridge plugin) {
        this.plugin = plugin;
        this.lp = LuckPermsProvider.get();
        lp.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, e -> {
            UUID id = e.getUser().getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastSent.get(id);
            if (last != null && now - last < 2_000) return; // recalculations come in bursts
            lastSent.put(id, now);
            sendGroups(e.getUser());
        });
    }

    public void sendGroups(UUID uuid) {
        lp.getUserManager().loadUser(uuid).thenAccept(this::sendGroups);
    }

    private void sendGroups(User user) {
        if (user == null) return;
        List<String> groups = new ArrayList<>();
        for (InheritanceNode n : user.getNodes(NodeType.INHERITANCE)) groups.add(n.getGroupName());
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("t", "groups");
        f.put("uuid", user.getUniqueId().toString());
        f.put("name", user.getUsername());
        f.put("groups", groups);
        plugin.client().send(f);
    }

    /** Frame from the bot: {uuid, name, groups:[wanted], managed:[all Discord-controlled groups]} */
    public void apply(Map<String, Object> f) {
        UUID uuid;
        try {
            uuid = UUID.fromString(String.valueOf(f.get("uuid")));
        } catch (IllegalArgumentException e) {
            return;
        }
        Set<String> allowed = new HashSet<>();
        for (String g : plugin.getConfig().getStringList("sync.discord-can-grant")) allowed.add(g.toLowerCase(Locale.ROOT));
        Set<String> wanted = new HashSet<>();
        for (Object g : asList(f.get("groups"))) wanted.add(String.valueOf(g).toLowerCase(Locale.ROOT));
        List<String> refused = new ArrayList<>();
        List<String> managed = new ArrayList<>();
        for (Object g : asList(f.get("managed"))) {
            String group = String.valueOf(g).toLowerCase(Locale.ROOT);
            if (allowed.contains(group)) managed.add(group);
            else refused.add(group);
        }
        lp.getUserManager().modifyUser(uuid, user -> {
            for (String group : managed) {
                InheritanceNode node = InheritanceNode.builder(group).build();
                if (wanted.contains(group)) user.data().add(node);
                else user.data().remove(node);
            }
        }).whenComplete((ok, err) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("t", "set_groups_result");
            r.put("uuid", uuid.toString());
            r.put("name", String.valueOf(f.getOrDefault("name", uuid.toString())));
            if (err != null) r.put("error", err.getMessage());
            else if (!refused.isEmpty()) r.put("error", "not allowed from Discord by the plugin config: " + String.join(", ", refused));
            plugin.client().send(r);
        });
    }

    private static List<?> asList(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }
}
