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

import java.nio.file.Files;
import java.nio.file.Path;

import com.zhapi.ZenHubClient;

/** Immutable list of credentials required by bot functionality. Thread safe. */
public class BotCredentials {

	private final GitHubCredentials ghCreds;
	private final SlackClient slackClient;
	private final MattermostCredentials mattermostCreds;
	private final MattermostChannel mattermostChannel;

	private final ZenHubClient zenhubClient;

	private final FeatureFlags featureFlags;

	// Nullable
	private final Path pathToAuthorizedGitHubLogins;

	public BotCredentials(GitHubCredentials ghCreds, SlackClient slackClient, MattermostCredentials mattermostCreds,
			MattermostChannel mattermostChannel, ZenHubClient zenhubClient, Path pathToAuthorizedGitHubLogins,
			FeatureFlags featureFlags) {
		this.ghCreds = ghCreds;
		this.slackClient = slackClient;
		this.mattermostCreds = mattermostCreds;
		this.mattermostChannel = mattermostChannel;
		this.zenhubClient = zenhubClient;
		this.pathToAuthorizedGitHubLogins = pathToAuthorizedGitHubLogins;

		this.featureFlags = featureFlags;

		if (this.pathToAuthorizedGitHubLogins != null) {

			if (!Files.exists(pathToAuthorizedGitHubLogins)) {
				throw new RuntimeException("Could not find login file: " + pathToAuthorizedGitHubLogins);
			}

		}
	}

	public GitHubCredentials getGhCreds() {
		return ghCreds;
	}

	public SlackClient getSlackClient() {
		return slackClient;
	}

	public MattermostCredentials getMattermostCreds() {
		return mattermostCreds;
	}

	public MattermostChannel getMattermostChannel() {
		return mattermostChannel;
	}

	public ZenHubClient getZenhubClient() {
		return zenhubClient;
	}

	public Path getPathToAuthorizedGitHubLogins() {
		return pathToAuthorizedGitHubLogins;
	}

	public FeatureFlags getFeatureFlags() {
		return featureFlags;
	}
}
