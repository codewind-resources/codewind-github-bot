/*******************************************************************************
 * Copyright (c) 2020 IBM Corporation
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.codewind.ghbot.credentials;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.codewind.ghbot.utils.Utils;

/**
 * Any method that requires rate limiting should create an instance of this
 * class, call addMessage() when a rate-limitable event occurs, and call
 * delayIfNeeded() to ensure the rate limit constraint is met.
 * 
 * Thread safe.
 */
public class RateLimiter {

	private final List<Long> messagePostTimes_synch = new ArrayList<>();

	private final long maxRequestsInTimePeriod;

	private final long timePeriodInMsecs;

	private final String name;

	public RateLimiter(String name, int maxRequestsInTimePeriod, int timePeriodInSeconds) {

		this.name = name;

		this.maxRequestsInTimePeriod = maxRequestsInTimePeriod;

		this.timePeriodInMsecs = TimeUnit.MILLISECONDS.convert(timePeriodInSeconds, TimeUnit.SECONDS);

	}

	public void signalAction() {
		synchronized (messagePostTimes_synch) {
			this.messagePostTimes_synch.add(System.currentTimeMillis());
		}
	}

	/**
	 * Ensure we do not post more than X messages per trailing 30 seconds. (eg 10 in
	 * 30)
	 */
	public void delayIfNeeded() {

		int count;

		do {
			count = 0;

			long xSecondsAgo = System.currentTimeMillis() - this.timePeriodInMsecs;
			synchronized (messagePostTimes_synch) {

				for (Iterator<Long> it = messagePostTimes_synch.iterator(); it.hasNext();) {

					// If older than, eg 30 seconds, remove the event and don't count it.
					long curr = it.next();
					if (curr < xSecondsAgo) {
						it.remove();
					} else {
						count++;
					}

				}

			}

			if (count >= maxRequestsInTimePeriod) {
				System.err.println("Rate limiter '" + name + "' is action of " + name + ": " + count);
				Utils.sleep(1000);
			}

		} while (count > maxRequestsInTimePeriod);

	}

}
