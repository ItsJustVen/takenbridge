package net.takensmp.bridge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Copies console output to Discord. Lines are buffered (newest kept, at most
 * {@link #MAX_BUFFER}) and shipped once a second, and only while connected, so
 * a chatty console can never crowd out chat events in the bridge queue.
 */
public final class ConsoleForwarder extends AbstractAppender {

    private static final int MAX_BUFFER = 300;
    private static final int MAX_PER_FRAME = 60;

    private final TakenBridge plugin;
    private final ArrayDeque<String> buffer = new ArrayDeque<>();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private int taskId = -1;

    public ConsoleForwarder(TakenBridge plugin) {
        super("TakenBridgeConsole", null, null, true, Property.EMPTY_ARRAY);
        this.plugin = plugin;
    }

    public void install() {
        start();
        ((Logger) LogManager.getRootLogger()).addAppender(this);
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, 20L, 20L).getTaskId();
    }

    public void uninstall() {
        ((Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }

    @Override
    public void append(LogEvent event) {
        // Never log from in here: it would loop straight back into append().
        String msg = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        if (msg == null || msg.isEmpty()) return;
        String prefix;
        synchronized (time) {
            prefix = "[" + time.format(new Date(event.getTimeMillis())) + " " + event.getLevel().name() + "]: ";
        }
        String line = prefix + msg; // plugin messages already start with [PluginName]
        if (event.getThrown() != null) line += " (" + event.getThrown() + ")";
        synchronized (buffer) {
            for (String part : line.split("\n")) {
                buffer.addLast(part);
                if (buffer.size() > MAX_BUFFER) buffer.pollFirst();
            }
        }
    }

    private void flush() {
        if (!plugin.getConfig().getBoolean("console.enabled", true)) return;
        BridgeClient client = plugin.client();
        if (client == null || !client.isConnected()) return;
        List<String> lines = new ArrayList<>();
        synchronized (buffer) {
            while (!buffer.isEmpty() && lines.size() < MAX_PER_FRAME) lines.add(buffer.pollFirst());
        }
        if (lines.isEmpty()) return;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("t", "console");
        f.put("lines", lines);
        client.send(f);
    }

    // ------------------------------------------------------------------ commands from Discord

    /** "/minecraft:op Steve" -> "op steve" */
    static String normalize(String cmd) {
        return cmd.trim().replaceFirst("^/+", "").replaceFirst("^[A-Za-z0-9_.-]+:", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Blocked patterns are word prefixes; "*" matches any single word. */
    static boolean isBlocked(String cmd, List<String> patterns) {
        String[] words = normalize(cmd).split(" ");
        for (String p : patterns) {
            String[] pw = normalize(p).split(" ");
            if (pw.length > words.length) continue;
            boolean match = true;
            for (int i = 0; i < pw.length && match; i++) match = pw[i].equals("*") || pw[i].equals(words[i]);
            if (match) return true;
        }
        return false;
    }
}
