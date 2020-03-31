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

import java.io.IOException;
import java.util.Optional;

import org.eclipse.codewind.ghbot.utils.BotConstants;
import org.eclipse.codewind.ghbot.utils.Logger;

import com.github.seratch.jslack.Slack;
import com.github.seratch.jslack.api.webhook.Payload;
import com.github.seratch.jslack.api.webhook.WebhookResponse;

/**
 * Posts messages to the Slack channel specified by the given webhookUrl. Each
 * post operation may potentially be rate-limited to prevent flooding the server
 * with requests.
 */
public class SlackClient {

	private final Logger log = Logger.getInstance();

	private final Optional<String> webhookUrl;

	private final RateLimiter rateLimiter = new RateLimiter(SlackClient.class.getSimpleName(),
			BotConstants.RATE_LIMIT_X_CHAT_REQUESTS_PER_HOUR, BotConstants.RATE_LIMIT_HOUR_IN_SECONDS);

	private final FeatureFlags featureFlags;

	public SlackClient(String webhookUrl, FeatureFlags featureFlags) {
		this.webhookUrl = Optional.ofNullable(webhookUrl);
		this.featureFlags = featureFlags;
	}

	public void postToChannel(String msg) {
		if (!webhookUrl.isPresent()) {
			return;
		}

		if (!BotConstants.DISABLE_POST_TO_CHANNEL && !featureFlags.isDisableExternalWrites()) {
			rateLimiter.signalAction();
			rateLimiter.delayIfNeeded();

			Payload payload = Payload.builder().text(msg).build();

			Slack slack = Slack.getInstance();
			WebhookResponse response;
			try {
				response = slack.send(webhookUrl.get(), payload);
				Integer code = response.getCode();
				String body = response.getBody();

				if (code != 200) {
					log.out("" + code + " " + body);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			log.err("Skipping 'updatePost' for" + msg);
		}

	}

}
