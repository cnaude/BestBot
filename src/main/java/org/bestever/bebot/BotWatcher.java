package org.bestever.bebot;

import java.util.Timer;
import java.util.TimerTask;
import static org.bestever.bebot.Logger.LOGLEVEL_IMPORTANT;
import static org.bestever.bebot.Logger.logMessage;

/**
 * This thread checks each bot for connectivity and reconnects when appropriate.
 *
 * @author Chris Naude
 *
 */
public class BotWatcher {

    private final Bot bot;
    private final Timer timer;

    /**
     *
     * @param bot
     */
    public BotWatcher(final Bot bot) {
        this.bot = bot;
        this.timer = new Timer();
        startWatcher();
    }

    private void startWatcher() {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (bot.isConnectedBlocking()) {
                    bot.setConnected(true);
                } else {
                    bot.setConnected(false);
                    if (bot.cfg_data.ircAutoReconnect) {
                        logMessage(LOGLEVEL_IMPORTANT, "Attempting to reconnect to IRC...");
                        bot.reconnect();
                    }
                }
            }

        }, 0, 5000);
    }

    /**
     *
     */
    public void cancel() {
        timer.cancel();
    }

}
