// --------------------------------------------------------------------------
// Copyright (C) 2012-2013 Best-Ever
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// --------------------------------------------------------------------------
package org.bestever.bebot;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Timer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jline.console.ConsoleReader;
import static org.bestever.bebot.AccountType.ADMIN;
import static org.bestever.bebot.AccountType.MODERATOR;
import static org.bestever.bebot.AccountType.REGISTERED;
import static org.bestever.bebot.AccountType.isAccountTypeOf;
import static org.bestever.bebot.Logger.LOGLEVEL_CRITICAL;
import static org.bestever.bebot.Logger.LOGLEVEL_DEBUG;
import static org.bestever.bebot.Logger.LOGLEVEL_IMPORTANT;
import static org.bestever.bebot.Logger.LOGLEVEL_NORMAL;
import static org.bestever.bebot.Logger.LOGLEVEL_TRIVIAL;
import static org.bestever.bebot.Logger.logMessage;
import org.bestever.serverquery.QueryManager;
import org.bestever.serverquery.ServerQueryRequest;
import org.pircbotx.Channel;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.DisconnectEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.KickEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.PartEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.events.QuitEvent;
import org.pircbotx.hooks.events.TopicEvent;

/**
 * This is where the bot methods are run and handling of channel/PM input are
 * processed
 */
public class Bot extends ListenerAdapter {

    /**
     * Bot bot object
     */
    private PircBotX bot;

    private PircBotXThread pircBotThread;

    /**
     * List of active user sessions
     */
    private static HashMap<String, String> userSessions;

    /**
     * Path to the configuration file relative to the bot
     */
    private String config_file;

    /**
     * IRC message queue
     *
     */
    protected IRCMessageQueueWatcher ircMessageQueue;

    /**
     * Bot connection watcher
     */
    protected BotWatcher botWatcher;

    /**
     * Holds connection status. Use this to check connection status.
     */
    protected boolean connected;

    /**
     * The lowest port (the base port) that the bot uses. This should NEVER be
     * changed because the mysql table relies on the minimum port to stay the
     * same so it can grab the proper ID (primary key) which corresponds to
     * server storage and ports.
     */
    public static int min_port;

    /**
     * The highest included port number that the bot uses
     */
    public static int max_port;

    /**
     * When the bot was started
     */
    public long time_started;

    /**
     * A toggle variable for allowing hosting
     */
    private boolean botEnabled = true;

    /**
     * Contains the config data
     */
    public ConfigData cfg_data;

    /**
     * Contained a array list of all the servers
     */
    public LinkedList<Server> servers;

    /**
     * Holds the timer (for timed broadcasts)
     */
    public Timer timer;

    /**
     * A query manager thread for handling external server requests
     */
    private final QueryManager queryManager;

    // Debugging purposes only
    public static Bot staticBot;

    private void buildAndStartIrcBot() {
        Configuration.Builder configBuilder = new Configuration.Builder()
                .setName(cfg_data.ircName)
                .setLogin(cfg_data.ircUser)
                .setVersion(cfg_data.ircVersion)
                .setServer(cfg_data.ircServer, cfg_data.ircPort, cfg_data.ircPass)
                .addAutoJoinChannel(cfg_data.ircChannel)
                .addListener(this);

        Configuration configuration = configBuilder.buildConfiguration();
        bot = new PircBotX(configuration);
        pircBotThread = new PircBotXThread(bot);
    }

    /**
     * Reconnect
     */
    public void reconnect() {
        pircBotThread.cancel();
        buildAndStartIrcBot();
    }

    /**
     *
     * @return
     */
    public boolean isConnectedBlocking() {
        return bot.isConnected();
    }

    /**
     *
     * @return
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     *
     * @param connected
     */
    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /**
     * Set the bot up with the constructor
     *
     * @param cfgfile
     */
    @SuppressWarnings("LeakingThisInConstructor")
    public Bot(ConfigData cfgfile) {
        userSessions = new HashMap<>();
        cfg_data = cfgfile;
        buildAndStartIrcBot();
        ircMessageQueue = new IRCMessageQueueWatcher(this);
        botWatcher = new BotWatcher(this);

        // Set up the logger
        Logger.setLogFile(cfg_data.bot_logfile);

        Logger.setLogLevel(cfg_data.bot_loglevel);
        System.out.println("Log level: " + Logger.log_level);

        // Set up the bot and join the channel
        logMessage(LOGLEVEL_IMPORTANT, "A Initializing BestBot v" + cfg_data.ircVersion);

        // Set initial ports
        Bot.min_port = cfg_data.bot_min_port;
        Bot.max_port = cfg_data.bot_max_port;

        // Set up the notice timer (if set)
        if (cfg_data.bot_notice != null) {
            logMessage(LOGLEVEL_IMPORTANT, "Starting notice timer: " + cfg_data.bot_notice_interval);
            timer = new Timer();
            timer.scheduleAtFixedRate(new NoticeTimer(this), 1000, cfg_data.bot_notice_interval * 1000);
        }

        // Set up the server arrays
        this.servers = new LinkedList<>();

        // Set up MySQL
        MySQL.setMySQL(this, cfg_data.mysql_host, cfg_data.mysql_user, cfg_data.mysql_pass, cfg_data.mysql_port, cfg_data.mysql_db);

        // Load persistent sessions
        MySQL.loadSessions();

        // Get the time the bot was started
        this.time_started = System.currentTimeMillis();

        // Begin a server query thread that will run
        queryManager = new QueryManager(this);
    }

    /**
     * Gets the minimum port to be used by the bot
     *
     * @return An integer containing the minimum port used
     */
    public int getMinPort() {
        return min_port;
    }

    /**
     * Returns the max port used by the bot
     *
     * @return An integer containing the max port used
     */
    public int getMaxPort() {
        return max_port;
    }

    /**
     * Reloads the configuration file
     */
    public void reloadConfigFile() {
        try {
            this.cfg_data = new ConfigData(this.config_file);
        } catch (IOException e) {
            logMessage(LOGLEVEL_CRITICAL, "Could not reload configuration file.");
        }
    }

    /**
     * Adds a wad to the automatic server startup
     *
     * @param wad String - the name of the wad
     * @param sender
     */
    public void addExtraWad(String wad, String sender) {
        if (!Functions.fileExists(cfg_data.bot_wad_directory_path + wad)) {
            asyncIRCMessage(sender, "Cannot add " + wad + " as it does not exist.");
            return;
        }
        for (String listWad : cfg_data.bot_extra_wads) {
            if (listWad.equalsIgnoreCase(wad)) {
                asyncIRCMessage(sender, "Cannot add " + listWad + " as it already exists in the wad startup list.");
                return;
            }
        }
        cfg_data.bot_extra_wads.add(wad);
        asyncIRCMessage(sender, "Added " + wad + " to the wad startup list.");
    }

    /**
     * Removes a wad from the wad startup list
     *
     * @param wad String - name of the wad
     * @param sender
     */
    public void deleteExtraWad(String wad, String sender) {
        for (String listWad : cfg_data.bot_extra_wads) {
            if (listWad.equalsIgnoreCase(wad)) {
                cfg_data.bot_extra_wads.remove(wad);
                asyncIRCMessage(sender, "Wad " + wad + " was removed from the wad startup list.");
                return;
            }
        }
        asyncIRCMessage(sender, "Wad " + wad + " was not found in the wad startup list.");
    }

    /**
     * This function goes through the linkedlist of servers and removes servers
     *
     * @param server Server - the server object
     */
    public void removeServerFromLinkedList(Server server) {
        logMessage(LOGLEVEL_DEBUG, "Removing server from linked list.");
        if (servers == null || servers.isEmpty()) {
            return;
        }
        ListIterator<Server> it = servers.listIterator();
        while (it.hasNext()) {
            // Check if they refer to the exact same object via reference, if so then we want to remove that
            if (it.next() == server) {
                it.remove();
                return;
            }
        }
    }

    /**
     * Returns a Server from the linked list based on the port number provided
     *
     * @param port The port to check
     * @return The server object reference if it exists, null if there's no such
     * object
     */
    public Server getServer(int port) {
        logMessage(LOGLEVEL_TRIVIAL, "Getting server at port " + port + ".");
        if (servers == null || servers.isEmpty()) {
            return null;
        }
        ListIterator<Server> it = servers.listIterator();
        Server desiredServer;
        while (it.hasNext()) {
            desiredServer = it.next();
            if (desiredServer.port == port) {
                return desiredServer;
            }
        }
        return null;
    }

    public List<Server> getUserServers(User user) {
        return getUserServers(getUserName(user));
    }

    public List<Server> getUserServers(String nick, String login, String hostmask) {
        return getUserServers(getUserName(nick, login, hostmask));
    }

    /**
     * Returns a list of servers belonging to the specified user
     *
     * @param username
     * @return a list of server objects
     */
    public List<Server> getUserServers(String username) {
        List<Server> serverList = new ArrayList<>();
        logMessage(LOGLEVEL_DEBUG, "Getting all servers for user " + username + ".");
        if (servers == null || servers.isEmpty()) {
            return serverList;
        }

        for (Server server : servers) {
            if (server.username.equals(username)) {
                serverList.add(server);
            }
        }

        return serverList;
    }

    /**
     * This searches through the linkedlist to kill the server on that port, the
     * method does not actually kill it, but signals a boolean to terminate
     * which the thread that is running it will handle the termination itself
     * and removal from the linkedlist.
     *
     * @param portString The port desired to kill
     */
    private void killServer(String portString) {
        logMessage(LOGLEVEL_NORMAL, "Killing server on port " + portString + ".");
        // Ensure it is a valid port
        if (!Functions.isNumeric(portString)) {
            sendMessageToChannel("Invalid port number (" + portString + "), not terminating server.");
            return;
        }

        // Since our port is numeric, parse it
        int port = Integer.parseInt(portString);

        // Handle users sending in a small value (thus saving time
        if (port < min_port) {
            sendMessageToChannel("Invalid port number (ports start at " + min_port + "), not terminating server.");
            return;
        }

        // See if the port is in our linked list, if so signify for it to die
        Server targetServer = getServer(port);
        if (targetServer != null) {
            targetServer.auto_restart = false;
            targetServer.killServer();
        } else {
            sendMessageToChannel("Could not find a server with the port " + port + "!");
        }
    }

    /**
     * Toggles the auto-restart feature on or off
     *
     * @param level int - the user's level (bitmask)
     * @param keywords String[] - array of words in message sent
     */
    private void toggleAutoRestart(int level, String[] keywords) {
        if (isAccountTypeOf(level, ADMIN, MODERATOR)) {
            if (keywords.length == 2) {
                if (Functions.isNumeric(keywords[1])) {
                    Server s = getServer(Integer.parseInt(keywords[1]));
                    if (s.auto_restart) {
                        s.auto_restart = false;
                        sendMessageToChannel("Autorestart disabled on server.");
                    } else {
                        s.auto_restart = true;
                        sendMessageToChannel("Autorestart set up on server.");
                    }
                }
            } else {
                sendMessageToChannel("Correct usage is .autorestart <port>");
            }
        } else {
            sendMessageToChannel("You do not have permission to use this command.");
        }
    }

    /**
     * Toggles the protected server state on or off (protected servers are
     * immune to killinactive)
     *
     * @param level int - the user's level (bitmask)
     * @param keywords String[] - array of words in message sent
     */
    private void protectServer(int level, String[] keywords) {
        if (isAccountTypeOf(level, ADMIN, MODERATOR)) {
            if (keywords.length == 2) {
                if (Functions.isNumeric(keywords[1])) {
                    Server s = getServer(Integer.parseInt(keywords[1]));
                    if (s.protected_server) {
                        s.protected_server = false;
                        sendMessageToChannel("Kill protection disabled.");
                    } else {
                        s.protected_server = true;
                        sendMessageToChannel("Kill protection enabled.");
                    }
                }
            } else {
                sendMessageToChannel("Correct usage is .protect <port>");
            }
        } else {
            sendMessageToChannel("You do not have permission to use this command.");
        }
    }

    /**
     * Sends a message to all servers
     *
     * @param level int - the user's level
     * @param keywords String[] - array of words in message sent
     */
    private void globalBroadcast(int level, String[] keywords) {
        if (isAccountTypeOf(level, ADMIN, MODERATOR)) {
            if (keywords.length > 1) {
                if (servers != null) {
                    String message = Joiner.on(" ").join(Arrays.copyOfRange(keywords, 1, keywords.length), " ");
                    for (Server s : servers) {
                        s.in.println("say \\cf--------------\\cc; say GLOBAL ANNOUNCEMENT: " + Functions.escapeQuotes(message) + "; say \\cf--------------\\cc;");
                    }
                    sendMessageToChannel("Global broadcast sent.");
                } else {
                    sendMessageToChannel("There are no servers running at the moment.");
                }
            }
        } else {
            sendMessageToChannel("You do not have the required privileges to send a broadcast.");
        }
    }

    /**
     * Sends a message to all servers
     *
     * @param user user - the user
     * @param keywords String[] - array of words in message sent
     */
    private void globalBroadcast(User user, String message) {
        if (servers != null) {
            for (Server s : servers) {
                s.in.println("say \"[IRC] <" + user.getNick() + "> " + Functions.escapeQuotes(message) + "\"");
            }
        }
    }

    /**
     * Sends a command to specified server
     *
     * @param level int - the user's level
     * @param keywords String[] - message
     * @param recipient String - who to return the message to (since this can be
     * accessed via PM as well as channel)
     */
    private void sendCommand(User user, int level, String[] keywords, String recipient) {
        if (isAccountTypeOf(level, REGISTERED, MODERATOR, ADMIN)) {
            if (keywords.length > 2) {
                if (Functions.isNumeric(keywords[1])) {
                    int port = Integer.parseInt(keywords[1]);
                    String message = Joiner.on(" ").join(Arrays.copyOfRange(keywords, 2, keywords.length));
                    Server s = getServer(port);
                    if (s != null) {
                        if (!getUserName(user).isEmpty() && isAccountTypeOf(level, MODERATOR)) {
                            s.in.println(message);
                            if (keywords[2].equalsIgnoreCase("sv_rconpassword") && keywords.length > 2) {
                                s.rcon_password = keywords[3];
                            }
                            blockingIRCMessage(recipient, "Command successfully sent.");
                        } else {
                            blockingIRCMessage(recipient, "You do not own this server.");
                        }
                    } else {
                        blockingIRCMessage(recipient, "Server does not exist.");
                    }
                } else {
                    blockingIRCMessage(recipient, "Port must be a number!");
                }
            } else {
                blockingIRCMessage(recipient, "Incorrect syntax! Correct syntax is .send <port> <command>");
            }
        }
    }

    /**
     * Have the bot handle message events
     *
     * @param event
     */
    @Override
    public void onMessage(MessageEvent event) {
        String message = event.getMessage();
        String channel = event.getChannel().getName();
        String nick = event.getUser().getNick();
        User user = event.getUser();
        // Perform these only if the message starts with a period (to save processing time on trivial chat)
        if (event.getMessage().startsWith(".")) {
            // Generate an array of keywords from the message
            String[] keywords = event.getMessage().split(" ");

            int userlevel = MySQL.getLevel(getUserName(user));
            switch (keywords[0].toLowerCase()) {
                case ".autorestart":
                    toggleAutoRestart(userlevel, keywords);
                    break;
                case ".broadcast":
                    globalBroadcast(userlevel, keywords);
                    break;
                case ".commands":
                    sendMessageToChannel("Allowed commands: " + processCommands(userlevel));
                    break;
                case ".cpu":
                    sendMessageToChannel(getServerCPU());
                    break;
                case ".disconnect":
                    if (isAccountTypeOf(userlevel, ADMIN)) {
                        bot.sendIRC().quitServer("Good bye!");
                    }
                    break;
                case ".file":
                    processFile(keywords, channel);
                    break;
                case ".get":
                    processGet(userlevel, keywords);
                    break;
                case ".delete":
                    deleteFile(event.getUser(), userlevel, keywords);
                    break;
                case ".download":
                    downloadFile(event.getUser(), userlevel, keywords);
                    break;
                case ".help":
                    if (cfg_data.bot_help.isEmpty()) {
                        sendMessageToChannel("No help! Please update ini file.");
                    } else {
                        sendMessageToChannel(cfg_data.bot_help);
                    }
                    break;
                case ".host":
                    processHost(event.getUser(), userlevel, nick, channel, message, false, getMinPort());
                    break;
                case ".kill":
                    processKill(event.getUser(), userlevel, keywords);
                    break;
                case ".killall":
                    processKillAll(userlevel);
                    break;
                case ".killmine":
                    processKillMine(event.getUser(), userlevel);
                    break;
                case ".list":
                    listPlayers();
                    break;
                case ".killinactive":
                    processKillInactive(userlevel, keywords);
                    break;
                case ".liststartwads":
                    sendMessageToChannel("These wads are automatically loaded when a server is started: " + Joiner.on(", ").join(cfg_data.bot_extra_wads));
                    break;
                case ".load":
                    MySQL.loadSlot(getUserName(user), keywords, userlevel, channel, nick);
                    break;
                case ".notice":
                    setNotice(keywords, userlevel);
                    break;
                case ".off":
                    processOff(userlevel);
                    break;
                case ".op":
                    op(user, false);
                    break;
                case ".on":
                    processOn(userlevel);
                    break;
                case ".owner":
                    processOwner(event.getUser(), userlevel, keywords);
                    break;
                case ".protect":
                    protectServer(userlevel, keywords);
                    break;
                case ".query":
                    handleQuery(userlevel, keywords);
                    break;
                case ".quit":
                    processQuit(userlevel);
                    break;
                case ".rcon":
                    if (isAccountTypeOf(userlevel, ADMIN, MODERATOR, REGISTERED)) {
                        sendMessageToChannel("Please PM the bot for the rcon.");
                    }
                    break;
                case ".reloadconfig":
                    if (isAccountTypeOf(userlevel, ADMIN)) {
                        reloadConfigFile();
                        sendMessageToChannel("Configuration file has been successfully reloaded.");
                    }
                    break;
                case ".save":
                    MySQL.saveSlot(user, message);
                    break;
                case ".send":
                    if (isAccountTypeOf(userlevel, ADMIN, MODERATOR)) {
                        sendCommand(user, userlevel, keywords, cfg_data.ircChannel);
                    }
                    break;
                case ".myservers":
                    processServers(event.getUser());
                    break;
                case ".servers":
                    processServers();
                    break;
                case ".slot":
                    MySQL.showSlot(user, keywords);
                    break;
                case ".uptime":
                    if (keywords.length == 1) {
                        sendMessageToChannel("I have been running for " + Functions.calculateTime(System.currentTimeMillis() - time_started));
                    } else {
                        calculateUptime(keywords[1]);
                    }
                    break;
                case ".whoami":
                    sendMessageToChannel(getLoggedIn(user));
                    break;
                default:
                    break;
            }
        } else {
            globalBroadcast(user, message);
        }
    }

    /**
     * Op user if level high enough
     */
    private void op(User user, boolean auto) {
        if (user.getChannelsOpIn().contains(getChannel(cfg_data.ircChannel))) {
            return;
        }
        if (AccountType.isAccountTypeOf(MySQL.getLevel(getUserName(user)), AccountType.MODERATOR)) {
            op(cfg_data.ircChannel, user.getNick());
        } else {
            if (!auto) {
                sendMessageToChannel("Sorry " + user.getNick() + " I can't do that.");
            }
        }
    }

    /**
     * Broadcasts the uptime of a specific server
     *
     * @param port String - port number
     */
    public void calculateUptime(String port) {
        if (Functions.isNumeric(port)) {
            int portValue = Integer.valueOf(port);
            Server s = getServer(portValue);
            if (s != null) {
                if (portValue >= Bot.min_port && portValue < Bot.max_port) {
                    sendMessageToChannel(s.port + " has been running for " + Functions.calculateTime(System.currentTimeMillis() - s.time_started));
                } else {
                    sendMessageToChannel("Port must be between " + Bot.min_port + " and " + Bot.max_port);
                }
            } else {
                sendMessageToChannel("There is no server running on port " + port);
            }
        } else {
            sendMessageToChannel("Port must be a number (ex: .uptime 15000)");
        }
    }

    /**
     * Sets the notice (global announcement to all servers)
     *
     * @param keywords String[] - array of words (message)
     * @param userlevel int - bitmask level
     */
    public void setNotice(String[] keywords, int userlevel) {
        if (keywords.length == 1) {
            sendMessageToChannel("Notice is: " + cfg_data.bot_notice);
            return;
        }
        if (isAccountTypeOf(userlevel, ADMIN, MODERATOR)) {
            cfg_data.bot_notice = Joiner.on(" ").join(Arrays.copyOfRange(keywords, 1, keywords.length));
            sendMessageToChannel("New notice has been set.");
        } else {
            sendMessageToChannel("You do not have permission to set the notice.");
        }
    }

    /**
     * Generate formatted CPU usage output
     *
     * @return
     */
    public String getServerCPU() {
        return "Server load average: " + String.valueOf(ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }

    /**
     * Purges an IP address from all banlists
     *
     * @param ip String - the target's IP address
     */
    private void purgeBans(String ip) {
        List<Server> tempList = new LinkedList<>(servers);
        for (Server s : tempList) {
            s.in.println("delban " + ip);
        }
        sendMessageToChannel("Purged " + ip + " from all banlists.");
    }

    /**
     * Returns the login of the invoking user
     *
     * @param hostname String - the user's hostname
     * @param level int - bitmask level of the user
     * @return whether the user is logged in or not
     */
    private String getLoggedIn(User user) {
        if (isValidUser(user)) {
            return "You are logged in as " + getUserName(user);
        } else {
            return "You are not logged in.";
        }
    }

    /**
     * This displays commands available for the user
     *
     * @param userlevel The level based on AccountType enumeration
     */
    private String processCommands(int userlevel) {
        logMessage(LOGLEVEL_TRIVIAL, "Displaying processComamnds().");
        if (isAccountTypeOf(userlevel, ADMIN)) {
            return ".addban .addstartwad .autorestart .banwad .broadcast .commands .cpu .delban .delete .delstartwad .download .file .get .help"
                    + " .host .kill .killall .killmine .killinactive .liststartwads .load "
                    + ".notice .off .on .owner .protect .purgebans .query .quit .rcon .save .send .servers .slot .unbanwad .uptime .whoami";
        } else if (isAccountTypeOf(userlevel, MODERATOR)) {
            return ".addban .addstartwad .autorestart .banwad .broadcast .commands .cpu .delban .delstartwad .file .get .help .host"
                    + " .kill .killmine .killinactive .liststartwads .load "
                    + ".notice .owner .protect .purgebans .query .rcon .save .send .servers .slot .unbanwad .uptime .whoami";
        } else if (isAccountTypeOf(userlevel, REGISTERED)) {
            return ".commands .cpu .file .get .help .host .kill .killmine .load .owner .query .rcon .save .servers .slot .uptime .whoami";
        } else {
            return "[Not logged in, guests have limited access] .commands .cpu .file .help .servers .uptime .whoami";
        }
    }

    /**
     * Sends a message to the channel, from the bot
     *
     * @param keywords String[] - message, split by spaces
     * @param sender String - name of the sender
     */
    private void messageChannel(String[] keywords, String sender) {
        if (keywords.length < 2) {
            asyncIRCMessage(sender, "Incorrect syntax! Correct usage is .msg your_message");
        } else {
            String message = Joiner.on(" ").join(Arrays.copyOfRange(keywords, 1, keywords.length));
            sendMessageToChannel(message);
        }
    }

    /**
     * This checks to see if the file exists in the wad directory (it is
     * lower-cased)
     *
     * @param keywords The keywords sent (should be a length of two)
     * @param channel The channel to respond to
     */
    private void processFile(String[] keywords, String channel) {
        logMessage(LOGLEVEL_TRIVIAL, "Displaying processFile().");
        if (keywords.length == 2) {
            File file = new File(cfg_data.bot_wad_directory_path + Functions.cleanInputFile(keywords[1].toLowerCase()));
            if (file.exists()) {
                blockingIRCMessage(channel, "File '" + keywords[1].toLowerCase() + "' exists on the server.");
            } else {
                blockingIRCMessage(channel, "Not found!");
            }
        } else {
            blockingIRCMessage(channel, "Incorrect syntax, use: .file <filename.wad>");
        }
    }

    /**
     * Gets a field requested by the user
     *
     * @param userlevel The user's bitmask level
     * @param keywords The field the user wants
     */
    private void processGet(int userlevel, String[] keywords) {
        logMessage(LOGLEVEL_TRIVIAL, "Displaying processGet().");
        if (isAccountTypeOf(userlevel, ADMIN, MODERATOR, REGISTERED)) {
            if (keywords.length != 3) {
                sendMessageToChannel("Proper syntax: .get <port> <property>");
                return;
            }
            if (!Functions.isNumeric(keywords[1])) {
                sendMessageToChannel("Port is not a valid number");
                return;
            }
            Server tempServer = getServer(Integer.parseInt(keywords[1]));
            if (tempServer == null) {
                sendMessageToChannel("There is no server running on this port.");
                return;
            }
            sendMessageToChannel(tempServer.getField(keywords[2]));
        }
    }

    /**
     * Passes the host command off to a static method to create the server
     *
     * @param username
     * @param userlevel The user's bitmask level
     * @param sender * @param hostname IRC data associated with the sender
     * @param channel
     * @param message The entire message to be processed
     * @param autoRestart
     * @param port
     */
    public void processHost(String username, int userlevel, String sender, String channel, String message, boolean autoRestart, int port) {
        logMessage(LOGLEVEL_NORMAL, "Processing the host command for " + username + " with the message \"" + message + "\".");
        if (botEnabled || isAccountTypeOf(userlevel, ADMIN, MODERATOR)) {
            if (isAccountTypeOf(userlevel, REGISTERED)) {
                int slots = MySQL.getMaxSlots(username);
                int userServers;
                userServers = getUserServers(username).size();
                if (slots > userServers) {
                    Server.handleHostCommand(username, this, servers, sender, channel, message, userlevel, autoRestart, port);
                } else {
                    sendMessageToChannel("You have reached your server limit (" + slots + ")");
                }
            } else {
                sendMessageToChannel("Sorry you are not registered!");
            }
        } else {
            sendMessageToChannel("The bot is currently disabled from hosting for the time being. Sorry for any inconvenience!");
        }
    }

    public static void addUserSession(String usermask, String username) {
        userSessions.put(usermask, username);
        MySQL.saveSession(usermask, username);
    }

    public static void addUserSession(User user, String username) {
        addUserSession(genUserKey(user), username);
    }

    public static boolean checkSession(User user) {
        return userSessions.containsKey(genUserKey(user));
    }

    public static void expireSession(User user) {
        if (userSessions.containsKey(genUserKey(user))) {
            logMessage(LOGLEVEL_NORMAL, "Expiring user session: " + getUserName(user));
            userSessions.remove(genUserKey(user));
        }
    }

    public static String genUserKey(User user) {
        return genUserKey(user.getNick(), user.getLogin(), user.getHostmask());
    }

    public static String getUserName(User user) {
        return getUserName(user.getNick(), user.getLogin(), user.getHostmask());
    }

    public static String getUserName(String nick, String login, String hostmask) {
        if (userSessions.containsKey(genUserKey(nick, login, hostmask))) {
            return userSessions.get(genUserKey(nick, login, hostmask));
        }
        return "";
    }

    public static String genUserKey(String nick, String login, String hostmask) {
        return nick + "!" + login + "@" + hostmask;
    }

    /**
     * Passes the host command off to a static method to create the server
     *
     * @param user
     * @param userlevel The user's bitmask level
     * @param sender * @param hostname IRC data associated with the sender
     * @param channel
     * @param message The entire message to be processed
     * @param autoRestart
     * @param port
     */
    public void processHost(User user, int userlevel, String sender, String channel, String message, boolean autoRestart, int port) {
        processHost(getUserName(user), userlevel, sender, channel, message, autoRestart, port);
    }

    private void deleteFile(User user, int userlevel, String[] keywords) {
        if (isValidUser(user) && AccountType.isAccountTypeOf(userlevel, AccountType.ADMIN)) {
            if (keywords.length == 2) {
                String fileName = keywords[1];
                File newFile = new File(cfg_data.bot_wad_directory_path + fileName);
                if (newFile.exists()) {
                    if (newFile.delete()) {
                        sendMessageToChannel("Successfully delete file: " + fileName);
                    } else {
                        sendMessageToChannel("Unable to delete file: " + fileName);
                    }
                } else {
                    sendMessageToChannel("No such file: " + fileName);
                }
            } else {
                sendMessageToChannel("Usage: .delete example.wad");
            }
        } else {
            sendMessageToChannel("You are not logged on!");
        }
    }

    /**
     * Download wad file from internet
     */
    private void downloadFile(User user, int userlevel, String[] keywords) {
        if (isValidUser(user) && AccountType.isAccountTypeOf(userlevel, AccountType.REGISTERED)) {
            if (keywords.length == 2) {
                String URL = keywords[1];
                String fileName;
                Pattern regex = Pattern.compile("(ftp|http|https)://.*\\/(.*)$");
                Matcher m = regex.matcher(URL);

                if (m.find()) {
                    fileName = m.group(2);
                } else {
                    sendMessageToChannel("Invalid URL!: " + URL);
                    return;
                }

                File newFile = new File(cfg_data.bot_wad_directory_path + fileName);
                if (newFile.exists()) {
                    sendMessageToChannel("File already exists!: " + fileName);
                    return;
                }
                
                sendMessageToChannel("Downloading: " + URL);

                URL website;
                try {
                    website = new URL(URL);
                    ReadableByteChannel rbc = Channels.newChannel(website.openStream());
                    FileOutputStream fos = new FileOutputStream(cfg_data.bot_wad_directory_path + fileName);
                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                    if (newFile.exists()) {
                        String md5 = chkMD5(cfg_data.bot_wad_directory_path + fileName);
                        sendMessageToChannel("File downloaded: " + fileName + " (" + md5 + ")");
                    }
                } catch (MalformedURLException ex) {
                    sendMessageToChannel("Error: " + ex.getMessage());
                } catch (IOException ex) {
                    sendMessageToChannel("Error: " + ex.getMessage());
                }

            } else {
                sendMessageToChannel("Usage: .download http://example.com/some.wad");
            }
        } else {
            sendMessageToChannel("You are not logged on!");
        }
    }

    public String chkMD5(String filename) throws FileNotFoundException, IOException {
        String md5;
        try (FileInputStream fis = new FileInputStream(new File(filename))) {
            md5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(fis);
        }
        return md5;
    }

    /**
     * Attempts to kill a server based on the port
     *
     * @param userlevel The user's bitmask level
     * @param keywords The keywords to be processed
     * @param hostname hostname from the sender
     */
    private void processKill(User user, int userlevel, String[] keywords) {
        logMessage(LOGLEVEL_NORMAL, "Processing kill.");
        // Ensure proper syntax
        if (keywords.length != 2) {
            sendMessageToChannel("Proper syntax: .kill <port>");
            return;
        }

        // Safety net
        if (servers == null) {
            sendMessageToChannel("Critical error: Linkedlist is null, contact an administrator.");
            return;
        }

        // If server list is empty
        if (servers.isEmpty()) {
            sendMessageToChannel("There are currently no servers running!");
            return;
        }

        // Registered can only kill their own servers
        if (isAccountTypeOf(userlevel, REGISTERED)) {
            if (Functions.isNumeric(keywords[1])) {
                Server server = getServer(Integer.parseInt(keywords[1]));
                if (server != null) {
                    if (!getUserName(user).isEmpty() || isAccountTypeOf(userlevel, MODERATOR, ADMIN)) {
                        if (server.serverprocess != null) {
                            server.auto_restart = false;
                            server.serverprocess.terminateServer();
                        } else {
                            sendMessageToChannel("Error: Server process is null, contact an administrator.");
                        }
                    } else {
                        sendMessageToChannel("Error: You do not own this server!");
                    }
                } else {
                    sendMessageToChannel("Error: There is no server running on this port.");
                }
            } else {
                sendMessageToChannel("Improper port number.");
            }
            // Admins/mods can kill anything
        } else if (isAccountTypeOf(userlevel, ADMIN, MODERATOR)) {
            killServer(keywords[1]); // Can pass string, will process it in the method safely if something goes wrong
        }
    }

    /**
     * When requested it will kill every server in the linked list
     *
     * @param userlevel The user level of the person requesting
     */
    private void processKillAll(int userlevel) {
        logMessage(LOGLEVEL_IMPORTANT, "Processing killall.");
        if (isAccountTypeOf(userlevel, ADMIN)) {
            // If we use this.servers instead of a temporary list, it will remove the servers from the list while iterating over them
            // This will throw a concurrent modification exception
            // As a temporary solution, we can create a temporary list that will hold the values of the real list at the time it was called
            List<Server> tempList = new LinkedList<>(servers);
            int serverCount = servers.size();
            if (tempList.size() > 0) {
                for (Server s : tempList) {
                    s.hide_stop_message = true;
                    s.auto_restart = false;
                    s.killServer();
                }
                sendMessageToChannel(Functions.pluralize("Killed a total of " + serverCount + " server{s}.", serverCount));
            } else {
                sendMessageToChannel("There are no servers running.");
            }
        } else {
            sendMessageToChannel("You do not have permission to use this command.");
        }
    }

    /**
     * This will look through the list and kill all the servers that the
     * hostname owns
     *
     * @param userlevel The level of the user
     * @param hostname The hostname of the person invoking this command
     */
    private void processKillMine(User user, int userlevel) {
        logMessage(LOGLEVEL_TRIVIAL, "Processing killmine.");
        if (isAccountTypeOf(userlevel, ADMIN, MODERATOR, REGISTERED)) {
            List<Server> serverList = getUserServers(user);
            if (serverList != null) {
                ArrayList<String> ports = new ArrayList<>();
                for (Server s : serverList) {
                    s.auto_restart = false;
                    s.hide_stop_message = true;
                    s.killServer();
                    ports.add(String.valueOf(s.port));
                }
                if (ports.size() > 0) {
                    sendMessageToChannel(Functions.pluralize("Killed " + ports.size() + " server{s} (" + Joiner.on(", ").join(ports) + ")", ports.size()));
                } else {
                    sendMessageToChannel("You do not have any servers running.");
                }
            } else {
                sendMessageToChannel("There are no servers running.");
            }
        }
    }

    /**
     * List all players on all servers
     *
     */
    private void listPlayers() {
        logMessage(LOGLEVEL_TRIVIAL, "Processing killmine.");
        if (!servers.isEmpty()) {
            for (Server s : servers) {
                sendMessageToChannel(s.getPlayers());
            }
        } else {
            sendMessageToChannel("There are no servers running.");
        }

    }

    /**
     * This will kill inactive servers based on the days specified in the second
     * parameter
     *
     * @param userlevel The user's bitmask level
     * @param keywords The field the user wants
     */
    private void processKillInactive(int userlevel, String[] keywords) {
        logMessage(LOGLEVEL_NORMAL, "Processing a kill of inactive servers.");
        if (isAccountTypeOf(userlevel, ADMIN, MODERATOR)) {
            if (keywords.length < 2) {
                sendMessageToChannel("Proper syntax: .killinactive <days since> (ex: use .killinactive 3 to kill servers that haven't seen anyone for 3 days)");
                return;
            }
            if (Functions.isNumeric(keywords[1])) {
                ArrayList<String> ports = new ArrayList<>();
                int numOfDays = Integer.parseInt(keywords[1]);
                if (numOfDays > 0) {
                    if (servers == null || servers.isEmpty()) {
                        sendMessageToChannel("No servers to kill.");
                        return;
                    }
                    sendMessageToChannel("Killing servers with " + numOfDays + "+ days of inactivity.");
                    // Temporary list to avoid concurrent modification exception
                    List<Server> tempList = new LinkedList<>(servers);
                    for (Server s : tempList) {
                        if (System.currentTimeMillis() - s.serverprocess.last_activity > (Server.DAY_MILLISECONDS * numOfDays)) {
                            if (!s.protected_server) {
                                s.hide_stop_message = true;
                                s.auto_restart = false;
                                ports.add(String.valueOf(s.port));
                                s.serverprocess.terminateServer();
                            }
                        }
                    }
                    if (ports.isEmpty()) {
                        sendMessageToChannel("No servers were killed.");
                    } else {
                        sendMessageToChannel(Functions.pluralize("Killed " + ports.size() + " server{s} (" + Joiner.on(" ").join(ports) + ")", ports.size()));
                    }
                } else {
                    sendMessageToChannel("Using zero or less for .killinactive is not allowed.");
                }
            } else {
                sendMessageToChannel("Unexpected parameter for method.");
            }
        }
    }

    /**
     * Admins can turn off hosting with this
     *
     * @param userlevel The user's bitmask level
     */
    private void processOff(int userlevel) {
        logMessage(LOGLEVEL_IMPORTANT, "An admin has disabled hosting.");
        if (botEnabled) {
            if (isAccountTypeOf(userlevel, ADMIN)) {
                botEnabled = false;
                sendMessageToChannel("Bot disabled.");
            }
        }
    }

    /**
     * Admins can re-enable hosting with this
     *
     * @param userlevel The user's bitmask level
     */
    private void processOn(int userlevel) {
        logMessage(LOGLEVEL_IMPORTANT, "An admin has re-enabled hosting.");
        if (!botEnabled) {
            if (isAccountTypeOf(userlevel, ADMIN)) {
                botEnabled = true;
                sendMessageToChannel("Bot enabled.");
            }
        }
    }

    /**
     * This checks for who owns the server on the specified port
     *
     * @param userlevel The level of the user requesting the data
     * @param keywords The keywords to pass
     */
    private void processOwner(User user, int userlevel, String[] keywords) {
        logMessage(LOGLEVEL_DEBUG, "Processing an owner.");
        if (keywords.length == 2) {
            if (Functions.isNumeric(keywords[1])) {
                Server s = getServer(Integer.parseInt(keywords[1]));
                if (s != null) {
                    sendMessageToChannel("The owner of port " + keywords[1] + " is: " + s.sender + "[" + user.getNick() + "].");
                } else {
                    sendMessageToChannel("There is no server running on " + keywords[1] + ".");
                }
            } else {
                sendMessageToChannel("Invalid port number.");
            }
        } else {
            sendMessageToChannel("Improper syntax, use: .owner <port>");
        }
    }

    /**
     * Will attempt to query a server and generate a line of text
     *
     * @param userlevel The level of the user
     * @param keywords The keywords sent
     */
    private void handleQuery(int userlevel, String[] keywords) {
        if (isAccountTypeOf(userlevel, ADMIN, MODERATOR, REGISTERED)) {
            if (keywords.length == 2) {
                String[] ipFragment = keywords[1].split(":");
                if (ipFragment.length == 2) {
                    if (ipFragment[0].length() > 0 && ipFragment[1].length() > 0 && Functions.isNumeric(ipFragment[1])) {
                        int port = Integer.parseInt(ipFragment[1]);
                        if (port > 0 && port < 65535) {
                            sendMessageToChannel("Attempting to query " + keywords[1] + ", please wait...");
                            ServerQueryRequest request = new ServerQueryRequest(ipFragment[0], port);
                            if (queryManager != null) {
                                if (!queryManager.addRequest(request)) {
                                    sendMessageToChannel("Too many people requesting queries. Please try again later.");
                                }
                            } else {
                                sendMessageToChannel("Query manager is stopped!");
                            }
                        } else {
                            sendMessageToChannel("Port value is not between 0 - 65536 (ends exclusive), please fix your IP:port and try again.");
                        }
                    } else {
                        sendMessageToChannel("Missing (or too many) port delimiters, Usage: .query <ip:port>   (example: .query 98.173.12.44:20555)");
                    }
                } else {
                    sendMessageToChannel("Missing (or too many) port delimiters, Usage: .query <ip:port>   (example: .query 98.173.12.44:20555)");
                }
            } else {
                sendMessageToChannel("Usage: .query <ip:port>   (example: .query 98.173.12.44:20555)");
            }
        }
    }

    /**
     * Handles RCON stuff
     *
     * @param userlevel int - the user's level (permissions)
     * @param keywords String[] - message split by spaces
     * @param sender String - the nickname of the sender
     * @param hostname String - the hostname of the sender
     */
    private void processRcon(User user, int userlevel, String[] keywords, String sender) {
        logMessage(LOGLEVEL_NORMAL, "Processing a request for rcon (from " + sender + ").");
        if (isAccountTypeOf(userlevel, REGISTERED, MODERATOR, ADMIN)) {
            if (keywords.length == 2) {
                if (Functions.isNumeric(keywords[1])) {
                    int port = Integer.parseInt(keywords[1]);
                    Server s = getServer(port);
                    if (s != null) {
                        if (!getUserName(user).isEmpty() && isAccountTypeOf(userlevel, MODERATOR, ADMIN)) {
                            asyncIRCMessage(sender, "RCON: " + s.rcon_password);
                            asyncIRCMessage(sender, "ID: " + s.server_id);
                            asyncIRCMessage(sender, "LOG: " + cfg_data.bot_logs_url + s.server_id + ".txt");
                        } else {
                            asyncIRCMessage(sender, "You do not own this server.");
                        }
                    } else {
                        asyncIRCMessage(sender, "Server does not exist.");
                    }
                } else {
                    asyncIRCMessage(sender, "Port must be a number!");
                }
            } else {
                asyncIRCMessage(sender, "Incorrect syntax! Correct syntax is .rcon <port>");
            }
        }
    }

    /**
     * Invoking this command terminates the bot completely
     *
     * @param userlevel The user's bitmask level
     */
    private void processQuit(int userlevel) {
        logMessage(LOGLEVEL_CRITICAL, "Requested bot termination. Shutting down program.");
        if (isAccountTypeOf(userlevel, ADMIN)) {
            System.exit(0);
        }
    }

    /**
     * Sends a message to the channel with a list of servers from the user
     *
     * @param keywords String[] - the message
     */
    private void processServers(User user) {
        logMessage(LOGLEVEL_NORMAL, "Getting a list of servers.");
        List<Server> serverList = getUserServers(user);
        if (serverList != null && serverList.size() > 0) {
            for (Server server : serverList) {
                processServer(server);
            }
        } else {
            sendMessageToChannel("User " + user.getNick() + " has no servers running.");
        }
    }

    /**
     * Sends a message to the channel with a list of servers from the user
     *
     * @param keywords String[] - the message
     */
    private void processServer(Server server) {
        sendMessageToChannel(server.port + " - " + server.username + " - " + server.servername
                + (server.wads.isEmpty() ? "" : " - [Wads: " + Joiner.on(", ").join(server.wads) + "]")
                + ((server.maplist.isEmpty()) ? "" : " - [Maps: " + Joiner.on(", ").join(server.maplist) + "]")
        );
    }

    /**
     * Sends a message to the channel with a list of all servers
     *
     * @param keywords String[] - the message
     */
    private void processServers() {
        for (Server server : servers) {
            processServer(server);
        }
    }

    public boolean isValidUser(User user) {
        return !getUserName(user).isEmpty();
    }

    /**
     * Have the bot handle private message events
     *
     * @param event
     */
    @Override
    public void onPrivateMessage(PrivateMessageEvent event) {
        String message = event.getMessage();
        String nick = event.getUser().getNick();
        User user = event.getUser();
        String[] keywords = message.split(" ");

        switch (keywords[0].toLowerCase()) {
            case "login":
                if (keywords.length > 2) {
                    if (MySQL.userLogin(user, keywords[1], keywords[2])) {
                        asyncIRCMessage(nick, "Successfully logged on.");
                        addUserSession(user, keywords[1]);
                        op(user, false);
                    } else {
                        asyncIRCMessage(nick, "Invalid username or password!");
                    }
                } else {
                    asyncIRCMessage(nick, "Incorrect syntax! Usage is: /msg " + cfg_data.ircName + " login <username> <password>");
                }
                break;
            case "register":
                if (keywords.length > 2) {
                    MySQL.registerAccount(user, keywords[1], keywords[2]);
                } else {
                    asyncIRCMessage(nick, "Incorrect syntax! Usage is: /msg " + cfg_data.ircName + " register <username> <password>");
                }
                break;
            default:
                break;
        }

        if (isValidUser(user)) {
            int userlevel = MySQL.getLevel(getUserName(user));
            switch (keywords[0].toLowerCase()) {
                case ".addban":
                    if (isAccountTypeOf(userlevel, MODERATOR, ADMIN) && keywords.length > 1) {
                        MySQL.addBan(message.split(" ")[1], Joiner.on(" ").join(Arrays.copyOfRange(message.split(" "), 2, message.split(" ").length)), nick);
                    }
                    break;
                case ".addstartwad":
                    if (isAccountTypeOf(userlevel, MODERATOR, ADMIN) && keywords.length > 1) {
                        addExtraWad(Joiner.on(" ").join(Arrays.copyOfRange(message.split(" "), 1, message.split(" ").length)), nick);
                    }
                    break;
                case ".delstartwad":
                    if (isAccountTypeOf(userlevel, MODERATOR, ADMIN) && keywords.length > 1) {
                        deleteExtraWad(Joiner.on(" ").join(Arrays.copyOfRange(message.split(" "), 1, message.split(" ").length)), nick);
                    }
                    break;
                case ".rcon":
                    processRcon(user, userlevel, keywords, nick);
                    break;
                case "changepass":
                case "changepassword":
                case "changepw":
                    if (keywords.length == 2) {
                        MySQL.changePassword(user, keywords[1], nick);
                    } else {
                        asyncIRCMessage(nick, "Incorrect syntax! Usage is: /msg " + cfg_data.ircName + " changepw <new_password>");
                    }
                    break;
                case ".banwad":
                    if (isAccountTypeOf(userlevel, MODERATOR, ADMIN)) {
                        MySQL.addWadToBlacklist(Joiner.on(" ").join(Arrays.copyOfRange(keywords, 1, keywords.length)), nick);
                    }
                    break;
                case ".unbanwad":
                    if (isAccountTypeOf(userlevel, MODERATOR, ADMIN)) {
                        MySQL.removeWadFromBlacklist(Joiner.on(" ").join(Arrays.copyOfRange(keywords, 1, keywords.length)), nick);
                    }
                    break;
                case ".delban":
                    if (isAccountTypeOf(userlevel, MODERATOR, ADMIN) && keywords.length > 1) {
                        MySQL.delBan(message.split(" ")[1], nick);
                    }
                    break;
                case ".msg":
                    if (isAccountTypeOf(userlevel, ADMIN, MODERATOR)) {
                        messageChannel(keywords, nick);
                    }
                    break;
                case ".purgebans":
                    if (isAccountTypeOf(userlevel, ADMIN, MODERATOR)) {
                        purgeBans(keywords[1]);
                    }
                    break;
                case ".raw":
                    if (isAccountTypeOf(userlevel, ADMIN)) {
                        bot.sendRaw().rawLineNow(Joiner.on(" ").join(Arrays.copyOfRange(keywords, 1, keywords.length)));
                    }
                case ".rejoin":
                    if (isAccountTypeOf(userlevel, ADMIN)) {
                        for (Channel channel : bot.getUserBot().getChannels()) {
                            channel.send().part();
                        }
                        bot.sendIRC().joinChannel(cfg_data.ircChannel);
                    }
                    break;
                case ".send":
                    if (isAccountTypeOf(userlevel, ADMIN, MODERATOR)) {
                        sendCommand(user, userlevel, keywords, nick);
                    }
                    break;
                default:
                    break;
            }
        } else {
            asyncIRCMessage(nick, "You are not logged in!");
        }
    }

    /**
     * Have the bot handle kicks, this is useful for rejoining when kicked
     *
     * @param event
     */
    @Override
    public void onKick(KickEvent event) {
        // Rejoin the channel if kicked
        if (event.getRecipient().getNick().equalsIgnoreCase(bot.getUserBot().getNick())
                && event.getChannel().getName().equalsIgnoreCase(cfg_data.ircChannel)) {
            bot.sendIRC().joinChannel(cfg_data.ircChannel);
        }
    }

    /**
     * Handles channel joins
     *
     * @param event
     */
    @Override
    public void onJoin(JoinEvent event) {
        String channel = event.getChannel().getName();
        String nick = event.getUser().getNick();
        User user = event.getUser();
        logMessage(LOGLEVEL_NORMAL, event.getUser().getNick() + " has joined (" + event.getUser().getLogin() + "@" + event.getUser().getHostmask() + ")");
        if (channel.equalsIgnoreCase(cfg_data.ircChannel)) {
            if (!nick.equalsIgnoreCase(bot.getNick())) {
                op(user, true);
            }
        } else if (!channel.equalsIgnoreCase(cfg_data.ircChannel)) {
            if (nick.equalsIgnoreCase(bot.getNick())) {
                logMessage(LOGLEVEL_NORMAL, "Leaving invalid channel: " + channel);
                event.getChannel().send().part("How did I get here?");
            }
        }
    }

    /**
     * Handles channel topic events
     *
     * @param event
     */
    @Override
    public void onTopic(TopicEvent event) {
        String topic = event.getTopic();
        if (event.getChannel().getName().equalsIgnoreCase(cfg_data.ircChannel)) {
            if (!event.getUser().getNick().equalsIgnoreCase(bot.getNick())) {
                if (topic != null) {
                    if (topic.isEmpty()) {
                        event.getChannel().send().setTopic(cfg_data.ircTopic);
                    }
                }
            }
        }
    }

    /**
     * Handles channel part event
     *
     * @param event
     */
    @Override
    public void onPart(PartEvent event) {
        logMessage(LOGLEVEL_NORMAL, event.getUser().getNick() + " has left (" + event.getReason() + ")");
        //expireSession(event.getUser());
    }

    /**
     * Handles quit event
     *
     * @param event
     */
    @Override
    public void onQuit(QuitEvent event) {
        logMessage(LOGLEVEL_NORMAL, event.getUser().getNick() + " has quit (" + event.getReason() + ")");
        //expireSession(event.getUser());
    }

    /**
     * Allows external objects to send messages to the core channel
     *
     * @param msg The message to deploy
     */
    public void sendMessageToChannel(String msg) {
        asyncIRCMessage(cfg_data.ircChannel, msg);
    }

    public void asyncIRCMessage(final String target, final String message) {
        ircMessageQueue.add(new IRCMessage(target, message, false));
    }

    public void asyncCTCPMessage(final String target, final String message) {
        ircMessageQueue.add(new IRCMessage(target, message, true));
    }

    /**
     * Contains the main methods that are run on start up for the bot The
     * arguments should contain the path to the Bot.cfg file only
     *
     * @param args
     */
    public static void main(String[] args) {
        // We need only one argument to the config
        if (args.length != 1) {
            System.out.println("Incorrect arguments, please have only one arg to your ini path");
            return;
        }

        // Attempt to load the config
        ConfigData cfg_data;
        try {
            cfg_data = new ConfigData(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("Warning: ini file has a string where a number should be!");
            return;
        } catch (IOException e) {
            System.out.println("Warning: ini file IOException!");
            return;
        }

        // Start the bot
        Bot b = new Bot(cfg_data);
        b.config_file = args[0];

        ConsoleReader reader;
        try {
            reader = new ConsoleReader();

        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(Bot.class
                    .getName()).log(Level.SEVERE, null, ex);

            return;
        }
        reader.setBellEnabled(false);
        /*try {
            reader.(new PrintWriter(new FileWriter("writer.debug", true)));

        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(Bot.class
                    .getName()).log(Level.SEVERE, null, ex);

            return;
        }*/

        String line;
        PrintWriter out = new PrintWriter(System.out);

        try {
            while ((line = reader.readLine("DoomBot> ")) != null) {
                out.flush();
                if (line.equalsIgnoreCase("disconnect")) {
                    b.bot.sendIRC().quitServer("Disconnecting via console.");
                }
                if (line.equalsIgnoreCase("connect")) {
                    try {
                        b.bot.startBot();
                    } catch (IrcException ex) {
                        java.util.logging.Logger.getLogger(Bot.class
                                .getName()).log(Level.SEVERE, null, ex);
                    }
                }
                if (line.startsWith("op ")) {
                    String opArgs[] = line.split(" ");
                    if (opArgs.length > 1) {
                        for (int i = 1; i < opArgs.length; i++) {
                            System.out.printf("** Giving ops to %s in %s\n", opArgs[i], cfg_data.ircChannel);
                            b.op(cfg_data.ircChannel, opArgs[i]);
                        }
                    }
                }
                if (line.startsWith("deop ")) {
                    String opArgs[] = line.split(" ");
                    if (opArgs.length > 1) {
                        for (int i = 1; i < opArgs.length; i++) {
                            System.out.printf("** Removing ops from %s in %s\n", opArgs[i], cfg_data.ircChannel);
                            b.deOp(cfg_data.ircChannel, opArgs[i]);
                        }
                    }
                }
                if (line.startsWith("kick ")) {
                    String opArgs[] = line.split(" ");
                    if (opArgs.length > 1) {
                        for (int i = 1; i < opArgs.length; i++) {
                            System.out.printf("** Kicking %s from %s\n", opArgs[i], cfg_data.ircChannel);
                            b.kick(cfg_data.ircChannel, opArgs[i]);
                        }
                    }
                }
                if (line.startsWith("voice ")) {
                    String opArgs[] = line.split(" ");
                    if (opArgs.length > 1) {
                        for (int i = 1; i < opArgs.length; i++) {
                            System.out.printf("** Giving voice to %s in %s\n", opArgs[i], cfg_data.ircChannel);
                            b.voice(cfg_data.ircChannel, opArgs[i]);
                        }
                    }
                }
                if (line.startsWith("devoice ")) {
                    String opArgs[] = line.split(" ");
                    if (opArgs.length > 1) {
                        for (int i = 1; i < opArgs.length; i++) {
                            System.out.printf("** Removing voice from %s in %s\n", opArgs[i], cfg_data.ircChannel);
                            b.deVoice(cfg_data.ircChannel, opArgs[i]);
                        }
                    }
                }
                if (line.equalsIgnoreCase("list")) {
                    b.listUsers();
                }
                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                    b.pircBotThread.cancel();
                    b.processKillAll(ADMIN);
                    b.queryManager.cancel();
                    b.ircMessageQueue.cancel();
                    b.botWatcher.cancel();
                    b.processQuit(ADMIN);
                    break;

                }
            }
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(Bot.class
                    .getName()).log(Level.SEVERE, null, ex);
        }
    }

    /*
     * Blocking IRC message
     */
    public void blockingIRCMessage(String target, String message) {
        if (this.isConnected()) {
            bot.sendIRC().message(target, message);
        }
    }

    /**
     *
     * @param channelName
     * @param nick
     */
    public void op(String channelName, String nick) {
        Channel channel;
        channel = getChannel(channelName);
        if (channel != null) {
            for (User user : channel.getUsers()) {
                if (user.getNick().equals(nick)) {
                    channel.send().op(user);
                    return;
                }
            }
        }
    }

    public Channel getChannel(String channelName) {
        Channel channel = null;
        for (Channel c : getChannels()) {
            if (c.getName().equalsIgnoreCase(channelName)) {
                return c;
            }
        }
        return channel;
    }

    public ImmutableSortedSet<Channel> getChannels() {
        if (bot.getNick().isEmpty()) {
            return ImmutableSortedSet.<Channel>naturalOrder().build();
        }
        return bot.getUserBot().getChannels();
    }

    /**
     *
     * @param channelName
     * @param nick
     */
    public void deOp(String channelName, String nick) {
        Channel channel;
        channel = getChannel(channelName);
        if (channel != null) {
            for (User user : channel.getUsers()) {
                if (user.getNick().equals(nick)) {
                    channel.send().deOp(user);
                    return;
                }
            }
        }
    }

    /**
     *
     * @param channelName
     * @param nick
     */
    public void deVoice(String channelName, String nick) {
        Channel channel;
        channel = getChannel(channelName);
        if (channel != null) {
            for (User user : channel.getUsers()) {
                if (user.getNick().equals(nick)) {
                    channel.send().deVoice(user);
                    return;
                }
            }
        }
    }

    /**
     *
     * @param channelName
     * @param nick
     */
    public void kick(String channelName, String nick) {
        Channel channel;
        channel = getChannel(channelName);
        if (channel != null) {
            for (User user : channel.getUsers()) {
                if (user.getNick().equals(nick)) {
                    channel.send().kick(user);
                    return;
                }
            }
        }
    }

    /**
     *
     * @param channelName
     * @param nick
     */
    public void voice(String channelName, String nick) {
        Channel channel;
        channel = getChannel(channelName);
        if (channel != null) {
            for (User user : channel.getUsers()) {
                if (user.getNick().equals(nick)) {
                    channel.send().voice(user);
                    return;
                }
            }
        }
    }

    /**
     *
     */
    public void listUsers() {
        if (!this.isConnected()) {
            return;
        }
        List<String> channelUsers = new ArrayList<>();
        Channel channel = getChannel(cfg_data.ircChannel);
        if (channel != null) {
            System.out.printf("** Users in %s **\n", channel.getName());
            for (User user : channel.getUsers()) {
                String nick = user.getNick();
                nick = getNickPrefix(user, channel) + nick;
                if (user.isAway()) {
                    nick = nick + " | Away";
                }
                channelUsers.add(nick);
            }
            Collections.sort(channelUsers, Collator.getInstance());
            for (String userName : channelUsers) {
                System.out.printf(" %s\n", userName);
            }
        } else {
            System.out.println("Invalid channel: " + cfg_data.ircChannel);
        }
    }

    public String getNickPrefix(User user, Channel channel) {
        try {
            if (user.getChannels() != null) {
                if (user.isIrcop()) {
                    return "~";
                } else if (user.getChannelsSuperOpIn().contains(channel)) {
                    return "&";
                } else if (user.getChannelsOpIn().contains(channel)) {
                    return "@";
                } else if (user.getChannelsHalfOpIn().contains(channel)) {
                    return "%";
                } else if (user.getChannelsVoiceIn().contains(channel)) {
                    return "+";
                }
            }
        } catch (Exception ex) {
            System.out.println("Error: " + ex.getMessage());
        }
        return "";
    }

    /*
     * Blocking CTCP message
     */
    public void blockingCTCPMessage(String target, String message) {
        if (this.isConnected()) {
            bot.sendIRC().ctcpResponse(target, message);
        }
    }

    /**
     * Set connection status on connect
     *
     * @param event
     */
    @Override
    public void onConnect(ConnectEvent event) {
        logMessage(LOGLEVEL_IMPORTANT, "Connected to IRC.");
        String motd = bot.getServerInfo().getMotd();
        if (motd == null) {
            System.out.println("No IRC motd.");
        } else {
            System.out.println(bot.getServerInfo().getMotd());
        }
        this.setConnected(true);
    }

    /**
     * Set connection status on disconnect
     *
     * @param event
     */
    @Override
    public void onDisconnect(DisconnectEvent event) {
        logMessage(LOGLEVEL_IMPORTANT, "Disconnected from IRC.");
        this.setConnected(false);
    }

}
