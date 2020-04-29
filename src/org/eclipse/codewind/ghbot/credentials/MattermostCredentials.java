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

import org.eclipse.codewind.ghbot.utils.BotConstants;

import net.bis5.mattermost.client4.ApiResponse;
import net.bis5.mattermost.client4.MattermostClient;
import net.bis5.mattermost.client4.model.ApiError;
import net.bis5.mattermost.model.Channel;
import net.bis5.mattermost.model.Post;
import net.bis5.mattermost.model.User;

/**
 * Maintains a reference to the Mattermost client and the Mattermost account
 * (user).
 */
public class MattermostCredentials {

	private final MattermostClient client;
	private final User user;

	private final FeatureFlags featureFlags;

	/** Rate limiter for all Mattermost writes */
	private final RateLimiter centralRateLimiter = new RateLimiter(MattermostChannel.class.getSimpleName(),
			BotConstants.RATE_LIMIT_X_CHAT_REQUESTS_PER_HOUR, BotConstants.RATE_LIMIT_HOUR_IN_SECONDS);

	public MattermostCredentials(String mattermostServerUrl, String username, String password,
			FeatureFlags featureFlags) {

		// Create client instance
		client = MattermostClient.builder().url(mattermostServerUrl).logLevel(Level.OFF).ignoreUnknownProperties()
				.build();

		this.user = client.login(username, password);
		this.featureFlags = featureFlags;
	}

	public MattermostClient getClient() {
		return client;
	}

	public User getUser() {
		return user;
	}

	public RateLimiter getCentralRateLimiter() {
		return centralRateLimiter;
	}

	public void directMessage(String otherUsername, String message) {
		if (!BotConstants.DISABLE_POST_TO_CHANNEL && !this.featureFlags.isDisableExternalWrites()) {

			centralRateLimiter.signalAction();
			centralRateLimiter.delayIfNeeded();

			String otherUserId = client.getUserByUsername(otherUsername).readEntity().getId();

			ApiResponse<Channel> ar = client.createDirectChannel(user.getId(), otherUserId);

			if (ar == null || ar.getRawResponse().getStatus() != 201) {
				if (ar != null) {
					ApiError apiError = ar.readError();
					System.out.println(ar.getRawResponse().getStatus());
					System.out.println(apiError.getMessage());
					System.err.println(apiError.getDetailedError());
				}

				throw new RuntimeException("Unable to create direct channel to " + otherUsername);
			}

			Channel ch = ar.readEntity();

			client.createPost(new Post(ch.getId(), message));

		}
	}
}
