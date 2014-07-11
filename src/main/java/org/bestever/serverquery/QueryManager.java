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
package org.bestever.serverquery;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import org.bestever.bebot.Bot;

/**
 * Runs on its own thread and handles incoming requests for servery querying
 * <br>
 * This is on it's own thread so it can control any bottlenecking/spam safely
 * via a queue
 */
public class QueryManager {

    private final Timer timer = new Timer();

    /**
     * Bot
     */
    private final Bot bot;

    /**
     * Contains a list of requests that it will process in order
     */
    private final LinkedBlockingQueue<ServerQueryRequest> queryRequests;

    /**
     * Indicates if this thread is processing a query or not
     */
    private boolean processingQuery = false;

    /**
     * We should not exceed four requests at the same time, this would indicate
     * we are getting flooded
     */
    public static final int MAX_REQUESTS = 4;

    /**
     * Initializes the QueryManager object, does not run it (must be done
     * manually)
     *
     * @param bot
     */
    public QueryManager(Bot bot) {
        this.queryRequests = new LinkedBlockingQueue<>(MAX_REQUESTS);
        this.bot = bot;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {                
                // If we are not processing a request and we have a query, handle it
                if (!processingQuery && queryRequests.size() > 0) {
                    processQuery();
                }

            }
        }, 0, 1000);        
    }

    /**
     * Adds the given query to the list to be processed
     *
     * @param query The serverquery we want to make
     * @return True if it was added, false if the queue is full or it could not
     * be added
     */
    public boolean addRequest(ServerQueryRequest query) {
        if (queryRequests.size() >= MAX_REQUESTS) {
            return false;
        }
        return queryRequests.add(query);
    }

    /**
     * Allows an external thread (socket handler) to tell this thread it is done
     */
    public void signalProcessQueryComplete() {
        processingQuery = false;
    }

    /**
     * Begins processing a query in the queue
     */
    private void processQuery() {
        // Prevent our thread from trying to do two queries at once
        processingQuery = true;

        // This should never be null because we check in run() if the size is > 0
        ServerQueryRequest targetQuery = queryRequests.poll();
        if (targetQuery != null) {
            QueryHandler query = new QueryHandler(targetQuery, bot, this); // Must give it a reference to ourselves to signal query completion
            query.start();
        } else {
            bot.sendMessageToChannel("targetQuery was somehow null, aborting query.");
            signalProcessQueryComplete();
        }
    }

    /**
     *
     */
    public void cancel() {
        timer.cancel();
    }

}
