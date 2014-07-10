package org.bestever.bebot;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 *
 * @author Chris Naude Poll the command queue and dispatch to Bukkit
 */
public class IRCMessageQueueWatcher {

    private final Bot bot;
    private final Timer timer;
    private final BlockingQueue<IRCMessage> queue = new LinkedBlockingQueue<>();

    /**
     *
     * @param bot
     */
    public IRCMessageQueueWatcher(final Bot bot) {
        this.bot = bot;
        this.timer = new Timer();
        startWatcher();
    }

    private void startWatcher() {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                queueAndSend();
            }

        }, 0, 5);
    }

    private void queueAndSend() {
        IRCMessage ircMessage = queue.poll();
        if (ircMessage != null) {            
            if (ircMessage.ctcpResponse) {
                bot.blockingCTCPMessage(ircMessage.target, ircMessage.message);
            } else {
                bot.blockingIRCMessage(ircMessage.target, ircMessage.message);
            }
        }
    }

    public void cancel() {
        timer.cancel();
    }

    public String clearQueue() {
        int size = queue.size();
        if (!queue.isEmpty()) {
            queue.clear();
        }
        return "Elements removed from message queue: " + size;
    }

    /**
     *
     * @param ircMessage
     */
    public void add(IRCMessage ircMessage) {
        queue.offer(ircMessage);
    }
}
