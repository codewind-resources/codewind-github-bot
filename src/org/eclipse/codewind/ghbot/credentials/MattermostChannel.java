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

import java.util.concurrent.TimeUnit;

import org.eclipse.codewind.ghbot.utils.BotConstants;

import net.bis5.mattermost.client4.MattermostClient;
import net.bis5.mattermost.model.Channel;
import net.bis5.mattermost.model.ChannelList;
import net.bis5.mattermost.model.Post;
import net.bis5.mattermost.model.PostList;

/**
 * Responsible for interfacing with the Mattermost client API: write to a chat
 * channel, update a previous chat channel message, and get list of previous
 * channel messages.
 */
public class MattermostChannel {

	@SuppressWarnings("unused")
	private final String channelName;

	private final Channel channel;

	private final MattermostCredentials credentials;

	private final FeatureFlags featureFlags;

	private final RateLimiter rateLimiter = new RateLimiter(MattermostChannel.class.getSimpleName(),
			BotConstants.RATE_LIMIT_X_REQUESTS_PER_30_SECONDS, 30);

	public MattermostChannel(MattermostCredentials credentials, String channelName, FeatureFlags featureFlags) {

		this.channelName = channelName;

		this.credentials = credentials;

		this.featureFlags = featureFlags;

		// Create client instance

		MattermostClient client = credentials.getClient();

		String teamId = client.getAllTeams().readEntity().stream().filter(e -> e.getName().equals("eclipse"))
				.findFirst().get().getId();

		ChannelList cl = client.getChannelsForTeamForUser(teamId, credentials.getUser().getId()).readEntity();

		this.channel = cl.stream().filter(e -> e.getName().equals(channelName)).findFirst().get();

	}

	public PostList getRecentPosts() {
		long time = System.currentTimeMillis() - TimeUnit.MILLISECONDS.convert(8, TimeUnit.DAYS);
		return credentials.getClient().getPostsSince(channel.getId(), time).readEntity();
	}

	public void createPost(String msg) {
		if (!BotConstants.DISABLE_POST_TO_CHANNEL && !this.featureFlags.isDisableExternalWrites()) {
			rateLimiter.addMessage();
			rateLimiter.delayIfNeeded();
			credentials.getClient().createPost(new Post(channel.getId(), msg));
		} else {
			System.err.println("Skipping 'createPost' for" + msg);
		}
	}

	public void updatePost(Post post) {
		if (!BotConstants.DISABLE_POST_TO_CHANNEL && !this.featureFlags.isDisableExternalWrites()) {
			credentials.getClient().updatePost(post);
		} else {
			System.err.println("Skipping 'updatePost' for" + post.getMessage());
		}
	}
}
