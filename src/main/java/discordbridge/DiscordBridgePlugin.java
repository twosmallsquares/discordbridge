package discordbridge;

import arc.files.Fi;
import arc.Events;
import arc.util.Log;
import arc.util.Timer;
import arc.util.serialization.Json;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Groups;
import mindustry.mod.Plugin;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;

/**
 * DiscordSRV-style bridge, all in one file: runs INSIDE the Mindustry server
 * process as a plugin and connects out to Discord itself - no separate bot process.
 *
 * Drop DiscordBridge.jar in your server's /config/mods folder, start the server once
 * to generate discord-bridge-config.json next to it, fill in the token + channel IDs,
 * then restart.
 *
 * Discord commands (typed as plain messages in the relevant channel):
 *   playerlist   -> replies with an embed listing everyone currently online
 */
public class DiscordBridgePlugin extends Plugin {

    // ---------------- config ----------------

    public static class Config {
        public String botToken = "YOUR_BOT_TOKEN_HERE";

        public String chatChannelId = "0";          // in-game chat <-> discord
        public String consoleRelayChannelId = "0";  // server console output -> discord
        public String consoleInputChannelId = "0";  // discord messages -> server console
        public String playerListChannelId = "0";    // topic updates + responds to "playerlist"

        public String serverName = "My Mindustry Server";

        public String chatFormat = "%player%: %message%";
        public String discordToGameFormat = "Discord > %user%: %message%";
        public String serverStartMessage = "\u2705 Server has started.";
        public String serverStopMessage = "\uD83D\uDD34 Server is stopping.";

        public boolean relayConsoleToDiscord = true;
        public boolean allowConsoleInput = true;
        public boolean updatePlayerListTopic = true;
        public int playerListUpdateSeconds = 60;
    }

    public Config config;
    public JDA jda;

    private final File configFile = new File("discord-bridge-config.json");
    private final Json json = new Json();
    private final StringBuilder consoleBuffer = new StringBuilder();

    @Override
    public void init() {
        loadConfig();

        if (config.botToken == null || config.botToken.isEmpty() || config.botToken.equals("YOUR_BOT_TOKEN_HERE")) {
            Log.err("[DiscordBridge] No bot token set in discord-bridge-config.json. Bridge disabled until configured.");
            return;
        }

        try {
            jda = JDABuilder.createDefault(config.botToken)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                .addEventListeners(new DiscordListener())
                .build();
            jda.awaitReady();
            Log.info("[DiscordBridge] Connected to Discord as @" + jda.getSelfUser().getAsTag());
        } catch (Exception e) {
            Log.err("[DiscordBridge] Failed to start JDA", e);
            return;
        }

        hookGameEvents();
        if (config.relayConsoleToDiscord) hookConsoleRelay();
        if (config.updatePlayerListTopic) startPlayerListUpdater();

        sendToChannel(config.chatChannelId, config.serverStartMessage);

        // Best-effort "server stopped" notice. Uses a blocking send + blocking
        // shutdown so the message actually leaves before the JVM exits.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (jda != null && config.chatChannelId != null && !config.chatChannelId.equals("0")) {
                    TextChannel channel = jda.getTextChannelById(config.chatChannelId);
                    if (channel != null) channel.sendMessage(config.serverStopMessage).complete();
                }
            } catch (Exception ignored) {
                // JVM is on its way down either way; nothing useful to do with this.
            } finally {
                if (jda != null) jda.shutdown();
            }
        }));
    }

    private void loadConfig() {
        try {
            if (!configFile.exists()) {
                config = new Config();
                Files.writeString(configFile.toPath(), json.prettyPrint(config));
                Log.info("[DiscordBridge] Wrote default config to " + configFile.getAbsolutePath()
                    + " - fill in your bot token and channel IDs, then restart.");
            } else {
                config = json.fromJson(Config.class, new Fi(configFile));
            }
        } catch (Exception e) {
            Log.err("[DiscordBridge] Failed to load config, using in-memory defaults", e);
            config = new Config();
        }
    }

    // ---------------- game -> discord ----------------

    private void hookGameEvents() {
        Events.on(PlayerChatEvent.class, e -> {
            if (e.message.startsWith("/")) return; // don't relay commands
            sendToChannel(config.chatChannelId,
                config.chatFormat
                    .replace("%player%", e.player.name)
                    .replace("%message%", e.message));
        });

        Events.on(PlayerJoin.class, e ->
            sendToChannel(config.chatChannelId, "**" + e.player.name + "** joined the server."));

        Events.on(PlayerLeave.class, e ->
            sendToChannel(config.chatChannelId, "**" + e.player.name + "** left the server."));
    }

    private void sendToChannel(String channelId, String message) {
        if (jda == null || channelId == null || channelId.equals("0")) return;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel != null) channel.sendMessage(message).queue();
    }

    // ---------------- console -> discord ----------------
    // Buffers console output and flushes every couple seconds so a busy server
    // doesn't blow through Discord's rate limit with one message per log line.

    private void hookConsoleRelay() {
        PrintStream original = System.out;
        System.setOut(new PrintStream(original) {
            @Override
            public void println(String line) {
                original.println(line);
                synchronized (consoleBuffer) {
                    consoleBuffer.append(line).append("\n");
                }
            }
        });

        Timer.schedule(() -> {
            String chunk;
            synchronized (consoleBuffer) {
                if (consoleBuffer.isEmpty()) return;
                chunk = consoleBuffer.toString();
                consoleBuffer.setLength(0);
            }
            for (String part : splitForDiscord(chunk)) {
                sendToChannel(config.consoleRelayChannelId, "```" + part + "```");
            }
        }, 2f, 2f);
    }

    private String[] splitForDiscord(String text) {
        int max = 1900; // stay under Discord's 2000-char cap, leaving room for the code fence
        if (text.length() <= max) return new String[]{text};
        int parts = (text.length() / max) + 1;
        String[] out = new String[parts];
        for (int i = 0; i < parts; i++) {
            out[i] = text.substring(i * max, Math.min(text.length(), (i + 1) * max));
        }
        return out;
    }

    // ---------------- discord -> game / console ----------------

    private class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;
            String channelId = event.getChannel().getId();
            String content = event.getMessage().getContentDisplay();

            if (channelId.equals(config.chatChannelId)) {
                String formatted = config.discordToGameFormat
                    .replace("%user%", event.getMember() != null
                        ? event.getMember().getEffectiveName()
                        : event.getAuthor().getName())
                    .replace("%message%", content);
                Groups.player.each(p -> p.sendMessage(formatted));
                Log.info(formatted);
            } else if (channelId.equals(config.consoleInputChannelId) && config.allowConsoleInput) {
                Log.info("[DiscordBridge] Console command from Discord (" + event.getAuthor().getName() + "): " + content);
                runServerCommand(content);
            } else if (channelId.equals(config.playerListChannelId) && content.trim().equalsIgnoreCase("playerlist")) {
                event.getChannel().asTextChannel().sendMessageEmbeds(buildPlayerListEmbed().build()).queue();
            }
        }
    }

    private void runServerCommand(String line) {
        // TODO: wire this to your actual ServerControl's CommandHandler instance,
        // e.g. serverControl.handler.handleMessage(line);
        // That handler is what parses "host", "stop", "kick <name>", etc. on the
        // real console - grabbing a reference to it is the one piece that varies
        // by how your server jar boots the plugin, so it's left as a stub here.
        Log.info("[DiscordBridge] (stub) would execute server command: " + line);
    }

    // ---------------- player list ----------------

    private void startPlayerListUpdater() {
        Timer.schedule(this::updatePlayerList, 1f, config.playerListUpdateSeconds);
    }

    private void updatePlayerList() {
        if (jda == null) return;
        TextChannel channel = jda.getTextChannelById(config.playerListChannelId);
        if (channel == null) return;
        channel.getManager().setTopic(config.serverName + " \u2014 " + Groups.player.size() + " online").queue();
    }

    // Builds the "playerlist" reply: one line per online player, their name only.
    private EmbedBuilder buildPlayerListEmbed() {
        int count = Groups.player.size();
        EmbedBuilder eb = new EmbedBuilder()
            .setTitle(config.serverName + " \u2014 " + count + " player(s) online");

        StringBuilder sb = new StringBuilder();
        Groups.player.each(p -> sb.append(p.name).append("\n"));

        eb.setDescription(sb.length() == 0 ? "*nobody's online*" : sb.toString());
        return eb;
    }
}
