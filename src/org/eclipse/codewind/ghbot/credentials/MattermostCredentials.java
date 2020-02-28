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

import java.util.logging.Level;

import net.bis5.mattermost.client4.MattermostClient;
import net.bis5.mattermost.model.User;

/**
 * Maintains a reference to the Mattermost client and the Mattermost account
 * (user).
 */
public class MattermostCredentials {

	private final MattermostClient client;
	private final User user;

	public MattermostCredentials(String mattermostServerUrl, String username, String password) {

		// Create client instance
		client = MattermostClient.builder().url(mattermostServerUrl).logLevel(Level.OFF).ignoreUnknownProperties()
				.build();

		this.user = client.login(username, password);
	}

	public MattermostClient getClient() {
		return client;
	}

	public User getUser() {
		return user;
	}
}
