package org.bestever.bebot;

/**
 *
 * @author cnaude
 */
public class IRCMessage {

    public String target;
    public String message;
    public boolean ctcpResponse;

    public IRCMessage(String target, String message, boolean ctcpResponse) {
        this.target = target;
        this.message = message;
        this.ctcpResponse = ctcpResponse;
    }
}
