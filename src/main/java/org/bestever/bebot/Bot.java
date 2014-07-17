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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Timer;
import java.util.logging.Level;
import jline.ConsoleReader;
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
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.KickEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;

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
     * Path to the configuration file relative to the bot
     */
    private String config_file;

    /**
     * IRC message queue
     *
     */
    protected IRCMessageQueueWatcher ircMessageQueue;

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
                .setAutoReconnect(cfg_data.ircAutoReconnect)
                .addListener(this);

        Configuration configuration = configBuilder.buildConfiguration();
        bot = new PircBotX(configuration);
        pircBotThread = new PircBotXThread(bot);
    }

    /**
     * Set the bot up with the constructor
     *
     * @param cfgfile
     */
    @SuppressWarnings("LeakingThisInConstructor")
    public Bot(ConfigData cfgfile) {
        cfg_data = cfgfile;
        buildAndStartIrcBot();
        ircMessageQueue = new IRCMessageQueueWatcher(this);

        // Set up the logger
        Logger.setLogFile(cfg_data.bot_logfile);

        // Set up the bot and join the channel
        logMessage(LOGLEVEL_IMPORTANT, "Initializing BestBot v" + cfg_data.ircVersion);

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

    /**
     * Returns a list of servers belonging to the specified user
     *
     * @param nick
     * @param login
     * @param hostmask
     * @return a list of server objects
     */
    public List<Server> getUserServers(String nick, String login, String hostmask) {
        logMessage(LOGLEVEL_DEBUG, "Getting all servers from " + nick + ".");
        if (servers == null || servers.isEmpty()) {
            return null;
        }
        List<Server> serverList = new ArrayList<>();
        if (!MySQL.getUserName(nick, login, hostmask).isEmpty()) {
            ListIterator<Server> it = servers.listIterator();
            while (it.hasNext()) {
                Server desiredServer = it.next();
                if (Functions.checkUserMask(nick, login, hostmask, desiredServer.ircHostmask)) {
                    serverList.add(desiredServer);
                }
            }
        }
        return serverList;
    }

    /**
     * Returns a list of servers belonging to the specified user
     *
     * @param user
     * @return a list of server objects
     */
    public List<Server> getUserServers(User user) {
        return getUserServers(user.getNick(), user.getLogin(), user.getHostmask());
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
                    String message = Functions.implode(Arrays.copyOfRange(keywords, 1, keywords.length), " ");
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
                    String message = Functions.implode(Arrays.copyOfRange(keywords, 2, keywords.length), " ");
                    Server s = getServer(port);
                    if (s != null) {
                        if (!MySQL.getUserName(user).isEmpty() && isAccountTypeOf(level, MODERATOR)) {
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
        String hostmask = event.getUser().getHostmask();
        String nick = event.getUser().getNick();
        String login = event.getUser().getLogin();
        User user = event.getUser();
        // Perform these only if the message starts with a period (to save processing time on trivial chat)
        if (event.getMessage().startsWith(".")) {
            // Generate an array of keywords from the message
            String[] keywords = event.getMessage().split(" ");

            // Use soon!
            // String username = MySQL.getUserName(hostname);
            // Support custom hostnames
            /*
             if (!Functions.checkLoggedIn(hostname)) {
             if (!MySQL.getUsername(hostname).equals("None")) {
             hostname = MySQL.getUsername(hostname);
             }
             } */
            // Perform function based on input (note: login is handled by the MySQL function/class); also mostly in alphabetical order for convenience
            int userLevel = MySQL.getLevel(user);
            switch (keywords[0].toLowerCase()) {
                case ".autorestart":
                    toggleAutoRestart(userLevel, keywords);
                    break;
                case ".broadcast":
                    globalBroadcast(userLevel, keywords);
                    break;
                case ".commands":
                    sendMessageToChannel("Allowed commands: " + processCommands(userLevel));
                    break;
                case ".cpu":
                    sendMessageToChannel(String.valueOf(ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()));
                    break;
                case ".disconnect":
                    if (isAccountTypeOf(userLevel, ADMIN)) {
                        bot.sendIRC().quitServer("Good bye!");
                    }
                    break;
                case ".file":
                    processFile(keywords, channel);
                    break;
                case ".get":
                    processGet(userLevel, keywords);
                    break;
                case ".help":
                    if (cfg_data.bot_help.isEmpty()) {
                        sendMessageToChannel("There is no helpfile.");
                    } else {
                        sendMessageToChannel(cfg_data.bot_help);
                    }
                    break;
                case ".host":
                    processHost(event.getUser(), userLevel, channel, nick, message, false, getMinPort());
                    break;
                case ".kill":
                    processKill(event.getUser(), userLevel, keywords);
                    break;
                case ".killall":
                    processKillAll(userLevel);
                    break;
                case ".killmine":
                    processKillMine(event.getUser(), userLevel);
                    break;
                case ".killinactive":
                    processKillInactive(userLevel, keywords);
                    break;
                case ".liststartwads":
                    sendMessageToChannel("These wads are automatically loaded when a server is started: " + Functions.implode(cfg_data.bot_extra_wads, ", "));
                    break;
                case ".load":
                    MySQL.loadSlot(event.getUser(), keywords, userLevel, channel, nick);
                    break;
                case ".notice":
                    setNotice(keywords, userLevel);
                    break;
                case ".off":
                    processOff(userLevel);
                    break;
                case ".on":
                    processOn(userLevel);
                    break;
                case ".owner":
                    processOwner(event.getUser(), userLevel, keywords);
                    break;
                case ".protect":
                    protectServer(userLevel, keywords);
                    break;
                case ".query":
                    handleQuery(userLevel, keywords);
                    break;
                case ".quit":
                    processQuit(userLevel);
                    break;
                case ".rcon":
                    if (isAccountTypeOf(userLevel, ADMIN, MODERATOR, REGISTERED)) {
                        sendMessageToChannel("Please PM the bot for the rcon.");
                    }
                    break;
                case ".reloadconfig":
                    if (isAccountTypeOf(userLevel, ADMIN)) {
                        reloadConfigFile();
                        sendMessageToChannel("Configuration file has been successfully reloaded.");
                    }
                    break;
                case ".save":
                    MySQL.saveSlot(event.getUser(), keywords);
                    break;
                case ".send":
                    if (isAccountTypeOf(userLevel, ADMIN, MODERATOR)) {
                        sendCommand(event.getUser(), userLevel, keywords, cfg_data.ircChannel);
                    }
                    break;
                case ".servers":
                    processServers(event.getUser());
                    break;
                case ".slot":
                    MySQL.showSlot(event.getUser(), hostmask, keywords);
                    break;
                case ".uptime":
                    if (keywords.length == 1) {
                        sendMessageToChannel("I have been running for " + Functions.calculateTime(System.currentTimeMillis() - time_started));
                    } else {
                        calculateUptime(keywords[1]);
                    }
                    break;
                case ".whoami":
                    sendMessageToChannel(getLoggedIn(event.getUser(), userLevel));
                    break;
                default:
                    break;
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
     * @param userLevel int - bitmask level
     */
    public void setNotice(String[] keywords, int userLevel) {
        if (keywords.length == 1) {
            sendMessageToChannel("Notice is: " + cfg_data.bot_notice);
            return;
        }
        if (isAccountTypeOf(userLevel, ADMIN, MODERATOR)) {
            cfg_data.bot_notice = Functions.implode(Arrays.copyOfRange(keywords, 1, keywords.length), " ");
            sendMessageToChannel("New notice has been set.");
        } else {
            sendMessageToChannel("You do not have permission to set the notice.");
        }
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
    private String getLoggedIn(User user, int level) {
        if (isAccountTypeOf(level, REGISTERED)) {
            return "You are logged in as " + MySQL.getUserName(user);
        } else {
            return "You are not logged in or do not have an account with BE. Please visit http://www.best-ever.org/ for instructions on how to register";
        }
    }

    /**
     * This displays commands available for the user
     *
     * @param userLevel The level based on AccountType enumeration
     */
    private String processCommands(int userLevel) {
        logMessage(LOGLEVEL_TRIVIAL, "Displaying processComamnds().");
        if (isAccountTypeOf(userLevel, ADMIN)) {
            return ".addban .addstartwad .autorestart .banwad .broadcast .commands .cpu .delban .delstartwad .file .get .help"
                    + " .host .kill .killall .killmine .killinactive .liststartwads .load "
                    + ".notice .off .on .owner .protect .purgebans .query .quit .rcon .save .send .servers .slot .unbanwad .uptime .whoami";
        } else if (isAccountTypeOf(userLevel, MODERATOR)) {
            return ".addban .addstartwad .autorestart .banwad .broadcast .commands .cpu .delban .delstartwad .file .get .help .host"
                    + " .kill .killmine .killinactive .liststartwads .load "
                    + ".notice .owner .protect .purgebans .query .rcon .save .send .servers .slot .unbanwad .uptime .whoami";
        } else if (isAccountTypeOf(userLevel, REGISTERED)) {
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
            String message = Functions.implode(Arrays.copyOfRange(keywords, 1, keywords.length), " ");
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
     * @param userLevel The user's bitmask level
     * @param keywords The field the user wants
     */
    private void processGet(int userLevel, String[] keywords) {
        logMessage(LOGLEVEL_TRIVIAL, "Displaying processGet().");
        if (isAccountTypeOf(userLevel, ADMIN, MODERATOR, REGISTERED)) {
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
     * @param userLevel The user's bitmask level
     * @param channel IRC data associated with the sender
     * @param sender * @param hostname IRC data associated with the sender
     * @param nick
     * @param message The entire message to be processed
     * @param hostmask
     * @param login
     * @param autoRestart
     * @param port
     */
    public void processHost(int userLevel, String channel, String sender, String nick, String login, String hostmask, String message, boolean autoRestart, int port) {
        logMessage(LOGLEVEL_NORMAL, "Processing the host command for " + nick + " with the message \"" + message + "\".");
        if (botEnabled || isAccountTypeOf(userLevel, ADMIN, MODERATOR)) {
            if (isAccountTypeOf(userLevel, REGISTERED)) {
                int slots = MySQL.getMaxSlots(nick, login, hostmask);
                int userServers;
                userServers = getUserServers(nick, login, hostmask).size();
                if (slots > userServers) {
                    Server.handleHostCommand(nick, login, hostmask, this, servers, channel, sender, message, userLevel, autoRestart, port);
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

    /**
     * Passes the host command off to a static method to create the server
     *
     * @param user
     * @param userLevel The user's bitmask level
     * @param channel IRC data associated with the sender
     * @param sender * @param hostname IRC data associated with the sender
     * @param message The entire message to be processed
     * @param autoRestart
     * @param port
     */
    public void processHost(User user, int userLevel, String channel, String sender, String message, boolean autoRestart, int port) {
        processHost(userLevel, channel, sender, user.getNick(), user.getLogin(), user.getHostmask(), message, autoRestart, port);
    }

    /**
     * Attempts to kill a server based on the port
     *
     * @param userLevel The user's bitmask level
     * @param keywords The keywords to be processed
     * @param hostname hostname from the sender
     */
    private void processKill(User user, int userLevel, String[] keywords) {
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
        if (isAccountTypeOf(userLevel, REGISTERED)) {
            if (Functions.isNumeric(keywords[1])) {
                Server server = getServer(Integer.parseInt(keywords[1]));
                if (server != null) {
                    if (!MySQL.getUserName(user).isEmpty() || isAccountTypeOf(userLevel, MODERATOR, ADMIN)) {
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
        } else if (isAccountTypeOf(userLevel, ADMIN, MODERATOR)) {
            killServer(keywords[1]); // Can pass string, will process it in the method safely if something goes wrong
        }
    }

    /**
     * When requested it will kill every server in the linked list
     *
     * @param userLevel The user level of the person requesting
     */
    private void processKillAll(int userLevel) {
        logMessage(LOGLEVEL_IMPORTANT, "Processing killall.");
        if (isAccountTypeOf(userLevel, ADMIN)) {
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
     * @param userLevel The level of the user
     * @param hostname The hostname of the person invoking this command
     */
    private void processKillMine(User user, int userLevel) {
        logMessage(LOGLEVEL_TRIVIAL, "Processing killmine.");
        if (isAccountTypeOf(userLevel, ADMIN, MODERATOR, REGISTERED)) {
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
                    sendMessageToChannel(Functions.pluralize("Killed " + ports.size() + " server{s} (" + Functions.implode(ports, ", ") + ")", ports.size()));
                } else {
                    sendMessageToChannel("You do not have any servers running.");
                }
            } else {
                sendMessageToChannel("There are no servers running.");
            }
        }
    }

    /**
     * This will kill inactive servers based on the days specified in the second
     * parameter
     *
     * @param userLevel The user's bitmask level
     * @param keywords The field the user wants
     */
    private void processKillInactive(int userLevel, String[] keywords) {
        logMessage(LOGLEVEL_NORMAL, "Processing a kill of inactive servers.");
        if (isAccountTypeOf(userLevel, ADMIN, MODERATOR)) {
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
                        sendMessageToChannel(Functions.pluralize("Killed " + ports.size() + " server{s} (" + Functions.implode(ports, ", ") + ")", ports.size()));
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
     * @param userLevel The user's bitmask level
     */
    private void processOff(int userLevel) {
        logMessage(LOGLEVEL_IMPORTANT, "An admin has disabled hosting.");
        if (botEnabled) {
            if (isAccountTypeOf(userLevel, ADMIN)) {
                botEnabled = false;
                sendMessageToChannel("Bot disabled.");
            }
        }
    }

    /**
     * Admins can re-enable hosting with this
     *
     * @param userLevel The user's bitmask level
     */
    private void processOn(int userLevel) {
        logMessage(LOGLEVEL_IMPORTANT, "An admin has re-enabled hosting.");
        if (!botEnabled) {
            if (isAccountTypeOf(userLevel, ADMIN)) {
                botEnabled = true;
                sendMessageToChannel("Bot enabled.");
            }
        }
    }

    /**
     * This checks for who owns the server on the specified port
     *
     * @param userLevel The level of the user requesting the data
     * @param keywords The keywords to pass
     */
    private void processOwner(User user, int userLevel, String[] keywords) {
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
     * @param userLevel The level of the user
     * @param keywords The keywords sent
     */
    private void handleQuery(int userLevel, String[] keywords) {
        if (isAccountTypeOf(userLevel, ADMIN, MODERATOR, REGISTERED)) {
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
     * @param userLevel int - the user's level (permissions)
     * @param keywords String[] - message split by spaces
     * @param sender String - the nickname of the sender
     * @param hostname String - the hostname of the sender
     */
    private void processRcon(User user, int userLevel, String[] keywords, String sender) {
        logMessage(LOGLEVEL_NORMAL, "Processing a request for rcon (from " + sender + ").");
        if (isAccountTypeOf(userLevel, REGISTERED, MODERATOR, ADMIN)) {
            if (keywords.length == 2) {
                if (Functions.isNumeric(keywords[1])) {
                    int port = Integer.parseInt(keywords[1]);
                    Server s = getServer(port);
                    if (s != null) {
                        if (!MySQL.getUserName(user).isEmpty() && isAccountTypeOf(userLevel, MODERATOR, ADMIN)) {
                            asyncIRCMessage(sender, "RCON: " + s.rcon_password);
                            asyncIRCMessage(sender, "ID: " + s.server_id);
                            asyncIRCMessage(sender, "LOG: http://cnaude.org/logs/" + s.server_id + ".txt");
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
     * @param userLevel The user's bitmask level
     */
    private void processQuit(int userLevel) {
        logMessage(LOGLEVEL_CRITICAL, "Requested bot termination. Shutting down program.");
        if (isAccountTypeOf(userLevel, ADMIN)) {
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
                sendMessageToChannel(server.port + ": " + server.servername + ((server.wads != null)
                        ? " with wads " + Functions.implode(server.wads, ", ") : ""));
            }
        } else {
            sendMessageToChannel("User " + user.getNick() + " has no servers running.");
        }
    }

    public boolean isValidUser(User user) {
        return true;
        //return !MySQL.getUserName(user).isEmpty();
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

        // As of now, you can only perform commands if you are logged in, so we don't need an else here
        if (isValidUser(user)) {
            // Generate an array of keywords from the message (similar to onMessage)
            String[] keywords = message.split(" ");
            int userLevel = MySQL.getLevel(user);
            switch (keywords[0].toLowerCase()) {
                case ".addban":
                    if (isAccountTypeOf(userLevel, MODERATOR, ADMIN) && keywords.length > 1) {
                        MySQL.addBan(message.split(" ")[1], Functions.implode(Arrays.copyOfRange(message.split(" "), 2, message.split(" ").length), " "), nick);
                    }
                    break;
                case ".addstartwad":
                    if (isAccountTypeOf(userLevel, MODERATOR, ADMIN) && keywords.length > 1) {
                        addExtraWad(Functions.implode(Arrays.copyOfRange(message.split(" "), 1, message.split(" ").length), " "), nick);
                    }
                    break;
                case ".delstartwad":
                    if (isAccountTypeOf(userLevel, MODERATOR, ADMIN) && keywords.length > 1) {
                        deleteExtraWad(Functions.implode(Arrays.copyOfRange(message.split(" "), 1, message.split(" ").length), " "), nick);
                    }
                    break;
                case ".rcon":
                    processRcon(user, userLevel, keywords, nick);
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
                case "login":
                    if (keywords.length > 2) {
                        if (MySQL.userLogin(user, keywords[1], keywords[2])) {
                            asyncCTCPMessage(nick, "Successfulled logged on.");
                        } else {
                            asyncCTCPMessage(nick, "Invalid username or password!");
                        }
                    } else {
                        asyncIRCMessage(nick, "Incorrect syntax! Usage is: /msg " + cfg_data.ircName + " login <username> <password>");
                    }
                    break;
                case ".banwad":
                    if (isAccountTypeOf(userLevel, MODERATOR, ADMIN)) {
                        MySQL.addWadToBlacklist(Functions.implode(Arrays.copyOfRange(keywords, 1, keywords.length), " "), nick);
                    }
                    break;
                case ".unbanwad":
                    if (isAccountTypeOf(userLevel, MODERATOR, ADMIN)) {
                        MySQL.removeWadFromBlacklist(Functions.implode(Arrays.copyOfRange(keywords, 1, keywords.length), " "), nick);
                    }
                    break;
                case ".delban":
                    if (isAccountTypeOf(userLevel, MODERATOR, ADMIN) && keywords.length > 1) {
                        MySQL.delBan(message.split(" ")[1], nick);
                    }
                    break;
                case ".msg":
                    if (isAccountTypeOf(userLevel, ADMIN, MODERATOR)) {
                        messageChannel(keywords, nick);
                    }
                    break;
                case ".purgebans":
                    if (isAccountTypeOf(userLevel, ADMIN, MODERATOR)) {
                        purgeBans(keywords[1]);
                    }
                    break;
                case ".raw":
                    if (isAccountTypeOf(userLevel, ADMIN)) {
                        bot.sendRaw().rawLineNow(Functions.implode(Arrays.copyOfRange(keywords, 1, keywords.length), " "));
                    }
                case ".rejoin":
                    if (isAccountTypeOf(userLevel, ADMIN)) {
                        for (Channel channel : bot.getUserBot().getChannels()) {
                            channel.send().part();
                        }
                        bot.sendIRC().joinChannel(cfg_data.ircChannel);
                    }
                    break;
                case "register":
                    if (keywords.length > 2) {
                        MySQL.registerAccount(user, keywords[1], keywords[2]);
                    } else {
                        asyncIRCMessage(nick, "Incorrect syntax! Usage is: /msg " + cfg_data.ircName + " register <username> <password>");
                    }
                    break;
                case ".send":
                    if (isAccountTypeOf(userLevel, ADMIN, MODERATOR)) {
                        sendCommand(user, userLevel, keywords, nick);
                    }
                    break;
                default:
                    break;
            }
        } else {
            asyncIRCMessage(nick, "Your account is not logged in properly to the IRC network. Please log in and re-query.");
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
        // Process joins
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

        Character mask = null;
        String trigger = null;
        ConsoleReader reader;
        try {
            reader = new ConsoleReader();
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(Bot.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        reader.setBellEnabled(false);
        try {
            reader.setDebug(new PrintWriter(new FileWriter("writer.debug", true)));
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(Bot.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }

        String line;
        PrintWriter out = new PrintWriter(System.out);

        try {
            while ((line = reader.readLine("DoomBot> ")) != null) {
                out.flush();
                if ((trigger != null) && (line.compareTo(trigger) == 0)) {
                    line = reader.readLine("password> ", mask);
                }
                if (line.equalsIgnoreCase("irc disconnect")) {
                    b.bot.sendIRC().quitServer("Disconnecting via console.");
                }
                if (line.equalsIgnoreCase("irc connect")) {
                    try {
                        b.bot.startBot();
                    } catch (IrcException ex) {
                        java.util.logging.Logger.getLogger(Bot.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                    b.pircBotThread.cancel();
                    b.processKillAll(ADMIN);
                    b.queryManager.cancel();
                    b.processQuit(ADMIN);
                    break;
                }
            }
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(Bot.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /*
     * Blocking IRC message
     */
    public void blockingIRCMessage(String target, String message) {
        if (bot.isConnected()) {
            bot.sendIRC().message(target, message);
        }
    }

    /*
     * Blocking CTCP message
     */
    public void blockingCTCPMessage(String target, String message) {
        if (bot.isConnected()) {
            bot.sendIRC().ctcpResponse(target, message);
        }
    }

}
