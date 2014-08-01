package org.bestever.bebot;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.pircbotx.PircBotX;
import org.pircbotx.exception.IrcException;

/**
 *
 * @author cnaude
 */
public class PircBotXThread {

    private final Timer timer = new Timer();
    final PircBotX pircBot;

    public PircBotXThread(final PircBotX pircBot) {
        this.pircBot = pircBot;
        System.out.println("Starting IRC runnable...");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    pircBot.startBot();
                } catch (IOException | IrcException ex) {
                    Logger.getLogger(PircBotXThread.class.getName()).log(Level.SEVERE, null, ex);
                }
            }

        }, 0);
        System.out.println("Done...");

    }

    public void cancel() {
        if (pircBot.isConnected()) {
            pircBot.sendIRC().quitServer("Goodbye!");
        }
        timer.cancel();
    }

}
