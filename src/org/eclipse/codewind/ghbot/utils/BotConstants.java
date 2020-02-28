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

package org.eclipse.codewind.ghbot.utils;

/** Constants that affect the operation of the bot throughout the codebase. */
public class BotConstants {

	/** The maximum number of posts to make to Slack/Mattermost every 30 seconds. */
	public static final int RATE_LIMIT_X_REQUESTS_PER_30_SECONDS = 10;

	/**
	 * Set this to true if debugging and don't want to post to the channel. See also
	 * the corresponding feature flag.
	 */
	public static final boolean DISABLE_POST_TO_CHANNEL = false;

	/**
	 * Set this to true if debugging and don't want to update the database. See also
	 * the corresponding feature flag.
	 */
	public static final boolean READONLY_DATABASE = DISABLE_POST_TO_CHANNEL;

	public static final int PROCESS_COMMANDS_LESS_THAN_X_DAYS_OLD = 1;

}
