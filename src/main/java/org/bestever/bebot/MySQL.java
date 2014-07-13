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

import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import static org.bestever.bebot.Logger.LOGLEVEL_CRITICAL;
import static org.bestever.bebot.Logger.LOGLEVEL_IMPORTANT;
import static org.bestever.bebot.Logger.LOGLEVEL_NORMAL;
import static org.bestever.bebot.Logger.logMessage;
import org.pircbotx.User;

/**
 * MySQL Class for handling all of the database inserts/fetching
 */
public class MySQL {

    /**
     * Holds the Bot
     */
    private static Bot bot;

    /**
     * Holds the MySQL hostname
     */
    private static String mysql_host;

    /**
     * Holds the MySQL username
     */
    private static String mysql_user;

    /**
     * Holds the MySQL password
     */
    private static String mysql_pass;

    /**
     * Holds the MySQL port
     */
    private static int mysql_port;

    /**
     * Holds the MySQL database
     */
    public static String mysql_db;

    /**
     * A constant in the database to indicate a server is considered online
     */
    public static final int SERVER_ONLINE = 1;

    /**
     * Constructor for the MySQL Object
     *
     * @param bot instance of the bot
     * @param host MySQL hostname
     * @param user MySQL username
     * @param pass MySQL Password
     * @param port MySQL Port
     * @param db MySQL Database
     */
    public static void setMySQL(Bot bot, String host, String user, String pass, int port, String db) {
        MySQL.bot = bot;
        MySQL.mysql_host = host;
        MySQL.mysql_user = user;
        MySQL.mysql_pass = pass;
        MySQL.mysql_port = port;
        MySQL.mysql_db = db;
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            logMessage(LOGLEVEL_CRITICAL, "Could not initialize MySQL Driver!");
            System.exit(-1);
        }
    }

    /**
     * Experimental function that allows for dynamic mysql query execution.
     * Instead of writing a new method for each query, we will be able to call
     * this method with the statement and parameters. As per normal
     * preparedStatement procedures, the query will need to include ? in place
     * of variables. The ? are processed in sequential order. This method does
     * not support insertions/deletions/alterations, use executeUpdate() for
     * that.
     *
     * @param query String - the query, with variables replaces with ?
     * @param arguments Object... - an array of objects, one for each variable
     * (?)
     * @return ArrayList with a hasmap key => value pair for each row.
     */
    public static ArrayList executeQuery(String query, Object... arguments) {
        ArrayList<HashMap<String, Object>> rows = new ArrayList<>();
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            // Go through each argument and check what type they are
            // We will then bind the value to the prepared statement
            if (arguments.length > 0) {
                for (int i = 0; i < arguments.length; i++) {
                    if (arguments[i] instanceof String) {
                        pst.setString(i + 1, String.valueOf(arguments[i]));
                    } else if (arguments[i] instanceof Integer || arguments[i] instanceof Short || arguments[i] instanceof Byte) {
                        pst.setInt(i + 1, (int) arguments[i]);
                    }
                }
            }
            ResultSet r = pst.executeQuery();
            ResultSetMetaData md = r.getMetaData();
            int columns = md.getColumnCount();
            while (r.next()) {
                HashMap row = new HashMap(columns);
                for (int j = 1; j <= columns; j++) {
                    // Add each column as the key, and field as the value, to the hashmap
                    row.put(md.getColumnName(j), r.getObject(j));
                }
                // Add the hashmap to the arraylist
                rows.add(row);
            }
        } catch (SQLException e) {
            logMessage(LOGLEVEL_IMPORTANT, "There was a MySQL error in executeQuery. Statement: " + query + " Arguments:");
            for (Object argument : arguments) {
                logMessage(LOGLEVEL_IMPORTANT, String.valueOf(argument));
            }
        }
        return rows;
    }

    /**
     * Returns the connection
     */
    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + mysql_host + ":" + mysql_port + "/" + mysql_db, mysql_user, mysql_pass);
    }

    /**
     * Create a custom wadpage for our wads
     *
     * @param wads String[] - the wads to add
     * @return
     */
    public static String createWadPage(String wads) {
        String query = "INSERT INTO `" + mysql_db + "`.`wad_pages` (`key`, `wad_string`) VALUES (?, ?)";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            try {
                String hash = Functions.generateHash();
                pst.setString(1, hash);
                pst.setString(2, wads);
                pst.executeUpdate();
                return hash;
            } catch (NoSuchAlgorithmException e) {
            }
        } catch (SQLException e) {
            logMessage(LOGLEVEL_IMPORTANT, "Could not add wad page. (SQL Error)");
        }
        return null;
    }

    /**
     * Removes a wad from the wad blacklist
     *
     * @param filename String - name of the file
     * @param sender String - name of the sender
     */
    public static void removeWadFromBlacklist(String filename, String sender) {
        String query = "DELETE FROM `" + mysql_db + "`.`blacklist` WHERE `name` = ?";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            pst.setString(1, filename);
            if (pst.executeUpdate() <= 0) {
                bot.blockingIRCMessage(sender, "Wad '" + filename + "' is not in the blacklist.");
            } else {
                bot.blockingIRCMessage(sender, "Removed '" + filename + "' from the blacklist.");
            }
        } catch (SQLException e) {
            logMessage(LOGLEVEL_IMPORTANT, "Could not delete file from the blacklist. (SQL Error)");
        }
    }

    /**
     * Adds a wad (filename and md5 hash) to the wad blacklist
     *
     * @param filename String - name of the file to add
     * @param sender String - name of the sender
     */
    public static void addWadToBlacklist(String filename, String sender) {
        String query = "SELECT `md5`,`name` FROM `" + mysql_db + "`.`blacklist` WHERE `name` = ?";
        try {
            Connection con = getConnection();
            PreparedStatement pst = con.prepareStatement(query);
            pst.setString(1, filename);
            ResultSet r = pst.executeQuery();
            if (r.next()) {
                bot.blockingIRCMessage(sender, "Wad '" + filename + "' already exists in blacklist.");
            } else {
                query = "SELECT `md5`,`wadname` FROM `" + mysql_db + "`.`wads` WHERE `wadname` = ?";
                pst = con.prepareStatement(query);
                pst.setString(1, filename);
                r = pst.executeQuery();
                String name, md5;
                if (r.next()) {
                    name = r.getString("wadname");
                    md5 = r.getString("md5");
                } else {
                    bot.blockingIRCMessage(sender, "Wad '" + filename + "' was not found in our repository.");
                    return;
                }
                query = "INSERT INTO `" + mysql_db + "`.`blacklist` (`name`,`md5`) VALUES (?, ?)";
                pst = con.prepareStatement(query);
                pst.setString(1, name);
                pst.setString(2, md5);
                int result = pst.executeUpdate();
                if (result == 1) {
                    bot.blockingIRCMessage(sender, "Added '" + name + "' to the blacklist with hash " + md5);
                } else {
                    bot.blockingIRCMessage(sender, "There was an error adding the wad to the blacklist. Please contact an administrator.");
                }
            }
        } catch (SQLException e) {

            logMessage(LOGLEVEL_IMPORTANT, "Could not blacklist wad (SQL Error)");
        }
    }

    /**
     * Checks any number of wads against the wad blacklist
     *
     * @param fileName String... - name of the file(s)
     * @return true if is blacklisted, false if not
     */
    public static boolean checkHashes(String... fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT `wadname`,`md5` FROM `").append(mysql_db).append("`.`wads` WHERE `wadname` IN (");
        int i = 0;
        for (; i < fileName.length; i++) {
            if (i == fileName.length - 1) {
                sb.append("?");
            } else {
                sb.append("?, ");
            }
        }
        sb.append(")");
        String query = sb.toString();
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            for (int j = 1; j <= i; j++) {
                pst.setString(j, fileName[j - 1]);
            }
            ResultSet checkHashes = pst.executeQuery();
            try (Statement stm = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
                ResultSet blacklistedHashes = stm.executeQuery("SELECT `name`,`md5` FROM `" + mysql_db + "`.`blacklist`;");
                while (checkHashes.next()) {
                    blacklistedHashes.beforeFirst();
                    while (blacklistedHashes.next()) {
                        if (blacklistedHashes.getString("md5").equalsIgnoreCase(checkHashes.getString("md5"))) {
                            bot.blockingIRCMessage(bot.cfg_data.ircChannel, "Wad " + checkHashes.getString("wadname")
                                    + " matches blacklist " + blacklistedHashes.getString("name") + " (hash: " + blacklistedHashes.getString("md5") + ")");
                            return false;
                        }
                    }
                }
            }
        } catch (SQLException e) {

            logMessage(LOGLEVEL_IMPORTANT, "Could not get hashes of file (SQL Error)");
            return false;
        }
        return true;
    }

    /**
     * Gets a ban reason for the specified IP
     *
     * @param ip String - IP address
     * @return String - the ban reason
     */
    public static String getBanReason(String ip) {
        String query = "SELECT `reason` FROM `" + mysql_db + "`.`banlist` WHERE `ip` = ?";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            pst.setString(1, ip);
            ResultSet r = pst.executeQuery();
            return r.getString("reason");
        } catch (SQLException e) {

            logMessage(LOGLEVEL_IMPORTANT, "Could not get ban reason.");
            return "null reason";
        }
    }

    /**
     * Checks if an IP address is banned
     *
     * @param ip String - ip address
     * @return true/false
     * @throws java.net.UnknownHostException
     */
    public static String checkBanned(String ip) throws UnknownHostException {
        String query = "SELECT * FROM `" + mysql_db + "`.`banlist`";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            ResultSet r = pst.executeQuery();
            while (r.next()) {
                String decIP = r.getString("ip");
                if (decIP.contains("*")) {
                    if (Functions.inRange(ip, decIP)) {
                        return decIP;
                    }
                } else if (decIP.equals(ip)) {
                    return decIP;
                }
            }
            return null;
        } catch (SQLException e) {

            logMessage(LOGLEVEL_IMPORTANT, "Could not check ban.");
            return null;
        }
    }

    /**
     * Adds a ban to the banlist
     *
     * @param ip String - ip of the person to ban
     * @param reason String - the reason to show they are banned for
     * @param sender
     */
    public static void addBan(String ip, String reason, String sender) {
        String query = "INSERT INTO `" + mysql_db + "`.`banlist` VALUES (?, ?) ON DUPLICATE KEY UPDATE `reason` = ?";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            pst.setString(1, ip);
            pst.setString(2, reason);
            pst.setString(3, reason);
            if (pst.executeUpdate() == 1) {
                bot.blockingIRCMessage(sender, "Added " + ip + " to banlist.");
            } else {
                bot.blockingIRCMessage(sender, "That IP address is already banned!");
            }
        } catch (SQLException e) {

            logMessage(LOGLEVEL_IMPORTANT, "Could not add ban to banlist");
        }
    }

    /**
     * Deletes an IP address from the banlist
     *
     * @param ip String - the IP address to remove
     * @param sender
     */
    public static void delBan(String ip, String sender) {
        String query = "DELETE FROM `" + mysql_db + "`.`banlist` WHERE `ip` = ?";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            pst.setString(1, ip);
            if (pst.executeUpdate() <= 0) {
                bot.blockingIRCMessage(sender, "IP does not exist.");
            } else {
                // Temporary list to avoid concurrent modification exception
                List<Server> tempList = new LinkedList<>(bot.servers);
                for (Server server : tempList) {
                    server.in.println("delban " + ip);
                }
                bot.blockingIRCMessage(sender, "Removed " + ip + " from banlist.");
            }
        } catch (SQLException e) {

            logMessage(LOGLEVEL_IMPORTANT, "Could not delete ip from banlist");
        }
    }

    public static String getUserName(String nick, String login, String hostmask) {
        String query = "SELECT * FROM " + mysql_db + ".`hostmasks`";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {            
            ResultSet r = pst.executeQuery();
            while (r.next()) {
                if (Functions.checkUserMask(nick, login, hostmask, r.getString("hostmask"))) {
                    return r.getString("name");
                }
            }
        } catch (SQLException e) {
            logMessage(LOGLEVEL_IMPORTANT, "SQL_ERROR in 'getUserName()'");
        }
        return "";
    }
    
    public static String getUserName(User user) {
        return getUserName(user.getNick(), user.getLogin(), user.getHostmask());
    }

    /**
     * Gets the maximum number of servers the user is allowed to host
     *
     * @param nick
     * @param login
     * @param hostmask
     * @return server_limit Int - maximum server limit of the user
     */
    public static int getMaxSlots(String nick, String login, String hostmask) {
        String query = "SELECT `server_limit` FROM " + mysql_db + ".`login` WHERE `username` = ?";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            pst.setString(1, getUserName(nick, login, hostmask));
            ResultSet r = pst.executeQuery();
            if (r.next()) {
                return r.getInt("server_limit");
            } else {
                return 0;
            }
        } catch (SQLException e) {
            logMessage(LOGLEVEL_IMPORTANT, "SQL_ERROR in 'getMaxSlots()'");

        }
        return AccountType.GUEST; // Return 0, which is a guest and means it was not found; also returns this if not logged in
    }

    /**
     * Queries the database and returns the level of the user
     *
     * @param hostname of the user
     * @return level for success, 0 for fail, -1 for non-existent username
     */
    public static int getLevel(String hostname) {
        String query = "SELECT * FROM " + mysql_db + ".`hostmasks`";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            ResultSet r = pst.executeQuery();
            if (r.next()) {
                return r.getInt("level");
            } else {
                return 0;
            }
        } catch (SQLException e) {
            logMessage(LOGLEVEL_IMPORTANT, "SQL_ERROR in 'getLevel()'");

        }
        return AccountType.GUEST; // Return 0, which is a guest and means it was not found; also returns this if not logged in
    }

    /**
     * Inserts an account into the database (assuming the user is logged in to
     * IRC)
     *
     * @param user
     * @param password password of the user
     * @param sender
     */
    public static void registerAccount(User user, String password, String sender) {
        logMessage(LOGLEVEL_NORMAL, "Handling account registration from " + sender + ".");
        // Query to check if the username already exists
        String checkQuery = "SELECT `username` FROM " + mysql_db + ".`login` WHERE `username` = ?";

        // Query to add entry to database
        String executeQuery = "INSERT INTO " + mysql_db + ".`login` ( `username`, `password`, `level`, `activated`, `server_limit`, `remember_token` ) VALUES ( ?, ?, 1, 1, 4, null )";
        try (Connection con = getConnection(); PreparedStatement cs = con.prepareStatement(checkQuery); PreparedStatement xs = con.prepareStatement(executeQuery)) {
            // Query and check if see if the username exists
            cs.setString(1, getUserName(user));
            ResultSet r = cs.executeQuery();

            // The username already exists!
            if (r.next()) {
                bot.blockingIRCMessage(sender, "Account already exists!");
            } else {
                // Prepare, bind & execute
                xs.setString(1, getUserName(user));
                // Hash the PW with BCrypt
                xs.setString(2, BCrypt.hashpw(password, BCrypt.gensalt(14)));
                if (xs.executeUpdate() == 1) {
                    bot.blockingIRCMessage(sender, "Account created! Your username is " + getUserName(user) + " and your password is " + password);
                } else {
                    bot.blockingIRCMessage(sender, "There was an error registering your account.");
                }
            }
        } catch (SQLException e) {
            logMessage(LOGLEVEL_IMPORTANT, "ERROR: SQL_ERROR in 'registerAccount()'");

            bot.blockingIRCMessage(sender, "There was an error registering your account.");
        }
    }

    /**
     * Changes the password of a logged in user (assuming the user is logged
     * into IRC)
     *
     * @param user
     * @param password the user's password
     * @param sender
     */
    public static void changePassword(User user, String password, String sender) {
        logMessage(LOGLEVEL_NORMAL, "Password change request from " + sender + ".");
        // Query to check if the username already exists
        String checkQuery = "SELECT `username` FROM " + mysql_db + ".`login` WHERE `username` = ?";

        // Query to update password
        String executeQuery = "UPDATE " + mysql_db + ".`login` SET `password` = ? WHERE `username` = ?";
        try (Connection con = getConnection(); PreparedStatement cs = con.prepareStatement(checkQuery); PreparedStatement xs = con.prepareStatement(executeQuery)) {
            // Query and check if see if the username exists
            cs.setString(1, getUserName(user));
            ResultSet r = cs.executeQuery();

            // The username doesn't exist!
            if (!r.next()) {
                bot.blockingIRCMessage(sender, "Username does not exist.");
            } else {
                // Prepare, bind & execute
                xs.setString(1, BCrypt.hashpw(password, BCrypt.gensalt(14)));
                xs.setString(2, r.getString("username"));
                if (xs.executeUpdate() == 1) {
                    bot.blockingIRCMessage(sender, "Successfully changed your password!");
                } else {
                    bot.blockingIRCMessage(sender, "There was an error changing your password (executeUpdate error). Try again or contact an administrator with this message.");
                }
            }
        } catch (SQLException e) {
            System.out.println("ERROR: SQL_ERROR in 'changePassword()'");
            logMessage(LOGLEVEL_IMPORTANT, "SQL_ERROR in 'changePassword()'");

            bot.blockingIRCMessage(sender, "There was an error changing your password account (thrown SQLException). Try again or contact an administrator with this message.");
        }
    }

    /**
     * Saves a server host command to a row
     *
     * @param user
     * @param words String Array - array of words
     */
    public static void saveSlot(User user, String[] words) {
        if (words.length > 2) {
            String hostmessage = Functions.implode(Arrays.copyOfRange(words, 2, words.length), " ");
            if ((words.length > 2) && (Functions.isNumeric(words[1]))) {
                int slot = Integer.parseInt(words[1]);
                if (slot > 0 && slot < 11) {
                    try (Connection con = getConnection()) {
                        String query = "SELECT `slot` FROM " + mysql_db + ".`save` WHERE `slot` = ? && `username` = ?";
                        PreparedStatement pst = con.prepareStatement(query);
                        pst.setInt(1, slot);
                        pst.setString(2, getUserName(user));
                        try (ResultSet rs = pst.executeQuery()) {
                            boolean empty = true;
                            while (rs.next()) {
                                empty = false;
                            }
                            if (empty) {
                                query = "INSERT INTO " + mysql_db + ".`save` (`serverstring`, `slot`, `username`) VALUES (?, ?, ?)";
                            } else {
                                query = "UPDATE " + mysql_db + ".`save` SET `serverstring` = ? WHERE `slot` = ? && `username` = ?";
                            }
                            pst = con.prepareStatement(query);
                            pst.setString(1, hostmessage);
                            pst.setInt(2, slot);
                            pst.setString(3, getUserName(user));
                            pst.executeUpdate();
                        }
                        bot.blockingIRCMessage(bot.cfg_data.ircChannel, "Successfully updated save list.");
                    } catch (SQLException e) {
                        logMessage(LOGLEVEL_IMPORTANT, "SQL Error in 'saveSlot()'");

                    }
                } else {
                    bot.blockingIRCMessage(bot.cfg_data.ircChannel, "You may only specify slot 1 to 10.");
                }
            }
        } else {
            bot.blockingIRCMessage(bot.cfg_data.ircChannel, "Incorrect syntax! Correct usage is .save 1-10 <host_message>");
        }
    }

    /**
     * Loads server saved with the .save command
     *
     * @param user
     * @param words String[] - their message
     * @param level Int - their user level
     * @param channel String - the channel
     * @param sender String - sender's name
     */
    public static void loadSlot(User user, String[] words, int level, String channel, String sender) {
        if (words.length == 2) {
            if (Functions.isNumeric(words[1])) {
                int slot = Integer.parseInt(words[1]);
                if (slot > 10 || slot < 1) {
                    bot.blockingIRCMessage(bot.cfg_data.ircChannel, "Slot must be between 1 and 10.");
                    return;
                }
                try (Connection con = getConnection()) {
                    String query = "SELECT `serverstring` FROM " + mysql_db + ".`save` WHERE `slot` = ? && `username` = ?";
                    PreparedStatement pst = con.prepareStatement(query);
                    pst.setInt(1, slot);
                    pst.setString(2, getUserName(user));
                    ResultSet r = pst.executeQuery();
                    if (r.next()) {
                        String hostCommand = r.getString("serverstring");
                        bot.processHost(user, level, channel, sender, hostCommand, false, bot.getMinPort());
                    } else {
                        bot.blockingIRCMessage(bot.cfg_data.ircChannel, "You do not have anything saved to that slot!");
                    }
                } catch (SQLException e) {
                    Logger.logMessage(LOGLEVEL_IMPORTANT, "SQL Error in 'loadSlot()'");

                }
            }
        } else {
            bot.blockingIRCMessage(bot.cfg_data.ircChannel, "Incorrect syntax! Correct syntax is .load 1 to 10");
        }
    }

    /**
     * Logs a server to the database
     *
     * @param servername String - the name of the server
     * @param unique_id String - the server's unique ID
     * @param username String - username of server host
     */
    public static void logServer(String servername, String unique_id, String username) {
        String query = "INSERT INTO `" + mysql_db + "`.`serverlog` (`unique_id`, `servername`, `username`, `date`) VALUES (?, ?, ?, NOW())";
        try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
            pst.setString(1, unique_id);
            pst.setString(2, servername);
            pst.setString(3, username);
            pst.executeUpdate();
            pst.close();
        } catch (SQLException e) {
            Logger.logMessage(LOGLEVEL_IMPORTANT, "SQL Exception in logServer()");

        }
    }

    /**
     * Shows a server host string saved with the .save command
     *
     * @param user
     * @param hostname String - the user's hostname
     * @param words String[] - array of words of message
     */
    public static void showSlot(User user, String hostname, String[] words) {
        if (words.length == 2) {
            if (Functions.isNumeric(words[1])) {
                int slot = Integer.parseInt(words[1]);
                if (slot > 0 && slot < 11) {
                    String query = "SELECT `serverstring`,`slot` FROM `server`.`save` WHERE `slot` = ? && `username` = ?";
                    try (Connection con = getConnection(); PreparedStatement pst = con.prepareStatement(query)) {
                        pst.setInt(1, slot);
                        pst.setString(2, getUserName(user));
                        ResultSet rs = pst.executeQuery();
                        if (rs.next()) {
                            bot.blockingIRCMessage(bot.cfg_data.ircChannel, "In slot " + rs.getString("slot") + ": " + rs.getString("serverstring"));
                        } else {
                            bot.blockingIRCMessage(bot.cfg_data.ircChannel, "You do not have anything saved to that slot!");
                        }
                    } catch (SQLException e) {
                        Logger.logMessage(LOGLEVEL_IMPORTANT, "SQL Error in showSlot()");

                    }
                } else {
                    bot.blockingIRCMessage(bot.cfg_data.ircChannel, "Slot must be between 1 and 10!");
                }
            } else {
                bot.blockingIRCMessage(bot.cfg_data.ircChannel, "Slot must be a number.");
            }
        } else {
            bot.blockingIRCMessage(bot.cfg_data.ircChannel, "Incorrect syntax! Correct usage is .load <slot>");
        }
    }

}
