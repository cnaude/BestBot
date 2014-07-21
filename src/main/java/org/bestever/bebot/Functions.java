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
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.pircbotx.User;

public class Functions {

    /**
     * Checks for a valid port number Must be an integer between Bot.MAX_PORT
     * and Bot.MIN_PORT
     *
     * @param port
     * @return true if the port is valid
     */
    public static boolean checkValidPort(String port) {
        if (isNumeric(port)) {
            int numPort = Integer.valueOf(port);
            return numPort >= Bot.min_port && numPort < Bot.max_port;
        } else {
            return false;
        }
    }

    /**
     * Checks a message and number and returns the pluralized version (if need
     * be)
     *
     * @param message String - the phrase to be checked
     * @param number Int - the number to be checked
     * @return String - the pluralized? string
     */
    public static String pluralize(String message, int number) {
        if (message.contains("{s}")) {
            if (number == 1) {
                return message.replace("{s}", "");
            } else {
                return message.replace("{s}", "s");
            }
        }
        // Passed a string withous {s}
        return message;
    }

    /**
     * Removes duplicates from an arraylist by casting to a set
     *
     * @param l ArrayList - the list
     * @return cleaned ArrayList
     */
    public static ArrayList<String> removeDuplicateWads(ArrayList<String> l) {
        Set<String> setItems = new LinkedHashSet<>(l);
        l.clear();
        l.addAll(setItems);
        return l;
    }

    /**
     * Generates an MD5 hash
     *
     * @return 32 character MD5 hex string
     * @throws java.security.NoSuchAlgorithmException
     */
    public static String generateHash() throws NoSuchAlgorithmException {
        String seed = System.nanoTime() + "SOON";
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(seed.getBytes());
        return byteArrayToHex(md.digest());
    }

    /**
     * Checks if an IP address is in the range of another IP address
     *
     * @param ip String - IP address of the user
     * @param ipRange String - IP range (asterisks)
     * @return true/false
     * @throws UnknownHostException
     */
    public static boolean inRange(String ip, String ipRange) throws UnknownHostException {
        long startIP = new BigInteger(InetAddress.getByName(ipRange.replace("*", "0")).getAddress()).intValue();
        long endIP = new BigInteger(InetAddress.getByName(ipRange.replace("*", "255")).getAddress()).intValue();
        long sourceIP = new BigInteger(InetAddress.getByName(ip).getAddress()).intValue();
        return sourceIP >= startIP && sourceIP <= endIP;
    }

    /**
     * Escapes quotes in a string
     *
     * @param input String - input
     * @return String - escaped string
     */
    public static String escapeQuotes(String input) {
        StringBuilder s = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c == '\"') {
                s.append('\\');
            }
            s.append(c);
        }
        return s.toString();
    }

    /**
     * Given a byte array, returns a hexadecimal string
     *
     * @param bytes byte array
     * @return 16bit hex string
     */
    public static String byteArrayToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Gets the name of a user by splitting their hostname
     * (*.users.zandronum.com)
     *
     * @param hostname The user's host name
     * @return username The user's actual IRC name
     */
    /*
    public static String getUserName(String hostname) {
        return hostname.replace(".users.zandronum.com", "");
    }*/
    
    /**
     *
     * @param user
     * @param userMask
     * @return
     */
    public static boolean checkUserMask(User user, String userMask) {
        String mask[] = userMask.split("[\\!\\@]", 3);
        if (mask.length == 3) {
            String gUser = RegexGlobber.createRegexFromGlob(mask[0]);
            String gLogin = RegexGlobber.createRegexFromGlob(mask[1]);
            String gHost = RegexGlobber.createRegexFromGlob(mask[2]);
            System.out.println("gUser: " + gUser);
            System.out.println("gLogin: " + gLogin);
            System.out.println("gHost: " + gHost);
            System.out.println("rUser: " + user.getNick());
            System.out.println("rLogin: " + user.getLogin());
            System.out.println("rHost: " + user.getHostmask());
            return (user.getNick().matches(gUser)
                    && user.getLogin().matches(gLogin)
                    && user.getHostmask().matches(gHost));
        }
        return false;
    }
    
    /**
     *
     * @param nick
     * @param login
     * @param host
     * @param userMask
     * @return
     */
    public static boolean checkUserMask(String nick, String login, String host, String userMask) {
        String mask[] = userMask.split("[\\!\\@]", 3);
        if (mask.length == 3) {
            String gUser = RegexGlobber.createRegexFromGlob(mask[0]);
            String gLogin = RegexGlobber.createRegexFromGlob(mask[1]);
            String gHost = RegexGlobber.createRegexFromGlob(mask[2]);
            return (nick.matches(gUser)
                    && login.matches(gLogin)
                    && host.matches(gHost));
        }
        return false;
    }

    /**
     * Checks to see if a user is logged on their Zandronum IRC account
     *
     * @param hostname The user's hostname
     * @return username True if logged in, false if not
     */
    /*
    public static boolean checkLoggedIn(String hostname) {
        hostname = hostname.replace(".users.zandronum.com", "");
        return !hostname.contains(".");
    } */

    /**
     * Checks to see if a number is numeric In a recent update, now checks
     * safely for nulls should such a thing happen
     *
     * @param maybeid The String to check (does parse double)
     * @return True if it is a number, false if it's not
     */
    public static boolean isNumeric(String maybeid) {
        try {
            Double.parseDouble(maybeid);
        } catch (NumberFormatException | NullPointerException nfe) {
            return false;
        }
        return true;
    }

    /**
     * Checks to see if a given port is in use
     *
     * @param checkport The port to check
     * @return True if it's available, false if not
     */
    public static boolean checkIfPortAvailable(int checkport) {
        ServerSocket ss = null;
        DatagramSocket ds = null;
        try {
            ss = new ServerSocket(checkport);
            ss.setReuseAddress(true);
            ds = new DatagramSocket(checkport);
            ds.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            //e.printStackTrace();
        } finally {
            if (ds != null) {
                ds.close();
            }
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                }
            }
        }
        return false;
    }

    /**
     * Checks for an available port from minport up to (but NOT including)
     * maxport
     *
     * @param minport The minimum port to check
     * @param maxport The maximum port that is one above what you would check
     * (ex: 20200 would be the same as checking up to 20199)
     * @return The first available port, or 0 if no port is available
     */
    public static int getFirstAvailablePort(int minport, int maxport) {
        for (int p = minport; p < maxport; p++) {
            if (checkIfPortAvailable(p)) {
                return p;
            }
        }
        return 0;
    }

    /**
     * Function that takes a time in seconds and converts it to a string with
     * days, hours, minutes and seconds.
     *
     * @param milliseconds in long format
     * @return A String in a readable format
     */
    public static String calculateTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        int day = (int) TimeUnit.SECONDS.toDays(seconds);
        long hours = TimeUnit.SECONDS.toHours(seconds) - (day * 24);
        long minute = TimeUnit.SECONDS.toMinutes(seconds) - (TimeUnit.SECONDS.toHours(seconds) * 60);
        long second = TimeUnit.SECONDS.toSeconds(seconds) - (TimeUnit.SECONDS.toMinutes(seconds) * 60);
        return day + " days " + hours + " hours " + minute + " minutes and " + second + " second(s).";
    }

    /**
     * Check if a file exists
     *
     * @param file String path to file
     * @return true if exists, false if not
     */
    public static boolean fileExists(String file) {
        System.out.println("FILE: " + file);
        File f = new File(file);
        return f.exists();
    }

    /**
     * Returns a cleaned string for file inputs
     *
     * @param input String - the string to clean
     * @return cleaned string
     */
    public static String cleanInputFile(String input) {
        return input.replace("/", "").trim();
    }

    /**
     * Implodes a character between a string array
     *
     * @param inputArray String[] - array to combine
     * @param glueString String - delimiter
     * @return String containing all array elements seperated by glue string
     */
    public static String implode(String[] inputArray, String glueString) {
        String output = "";
        if (inputArray.length > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(inputArray[0]);
            for (int i = 1; i < inputArray.length; i++) {
                sb.append(glueString);
                sb.append(inputArray[i].trim());
            }
            output = sb.toString();
        }
        return output;
    }

    /**
     * Implodes a character between a string array
     *
     * @param inputArray String[] - array to combine
     * @param glueString String - delimiter
     * @return String containing all array elements seperated by glue string
     */
    public static String implode(ArrayList<String> inputArray, String glueString) {
        String output = "";
        if (inputArray.size() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(inputArray.get(0));
            for (int i = 1; i < inputArray.size(); i++) {
                sb.append(glueString);
                sb.append(inputArray.get(i).trim());
            }
            output = sb.toString();
        }
        return output;
    }
}
