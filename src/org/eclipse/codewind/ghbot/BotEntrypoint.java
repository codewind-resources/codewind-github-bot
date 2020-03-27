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

package org.eclipse.codewind.ghbot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.codewind.ghbot.ChannelJobs.MMIssueStatusEntry;
import org.eclipse.codewind.ghbot.credentials.BotCredentials;
import org.eclipse.codewind.ghbot.credentials.FeatureFlags;
import org.eclipse.codewind.ghbot.credentials.GitHubCredentials;
import org.eclipse.codewind.ghbot.credentials.MattermostChannel;
import org.eclipse.codewind.ghbot.credentials.MattermostCredentials;
import org.eclipse.codewind.ghbot.credentials.SlackClient;
import org.eclipse.codewind.ghbot.db.EphemeralWritesKVStore;
import org.eclipse.codewind.ghbot.db.FileKVStore;
import org.eclipse.codewind.ghbot.db.GHDatabase;
import org.eclipse.codewind.ghbot.db.IKVStore;
import org.eclipse.codewind.ghbot.db.InMemoryKVCache;
import org.eclipse.codewind.ghbot.utils.GitHubRepoEvent;
import org.eclipse.codewind.ghbot.utils.Logger;
import org.eclipse.codewind.ghbot.utils.Utils;
import org.eclipse.codewind.ghbot.utils.Utils.JobUtil;
import org.eclipse.codewind.ghbot.yaml.YamlCredentialsRoot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.githubapimirror.client.api.GHOrganization;
import com.githubapimirror.client.api.GHRepository;
import com.zhapi.ZenHubClient;
import com.zhapi.client.ZenHubMirrorApiClient;

/**
 * Entry point for the application. Pass the configuration YAML file as the
 * first and only parameter.
 */
public class BotEntrypoint {

	private static final Logger log = Logger.getInstance();

	public static void main(String[] args) throws IOException {

		if (args.length != 1) {
			System.err.println("Missing argument: path to YAML configuration file.");
			return;
		}

		YamlCredentialsRoot yr;
		{
			ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

			yr = mapper.readValue(new String(Files.readAllBytes(Paths.get(args[0]))), YamlCredentialsRoot.class);

			yr.validate();
		}
		FeatureFlags featureFlags = new FeatureFlags(yr.getFeatureFlags());

		String zenhubApiKey = yr.getZenhub() != null ? yr.getZenhub().getApiKey() : null;

		ZenHubClient zhClient = null;
		if (zenhubApiKey != null && !zenhubApiKey.trim().isEmpty()) {
			zhClient = new ZenHubClient(yr.getZenhub().getServerUrl(), zenhubApiKey);
		}

		ZenHubMirrorApiClient zhamClient = null;
		if (featureFlags.isZenHubJob() && yr.getZham() != null) {
			zhamClient = new ZenHubMirrorApiClient(yr.getZham().getApiUrl(), yr.getZham().getPresharedKey());
		}

		SlackClient slackClient = new SlackClient(nullIfEmptyOrNullString(yr.getSlackWebhook()), featureFlags);

		MattermostCredentials creds = null;
		MattermostChannel mmChannel = null;
		if (yr.getMattermost() != null) {
			String mattermostUsername = nullIfEmptyOrNullString(yr.getMattermost().getUsername());
			String mattermostPassword = nullIfEmptyOrNullString(yr.getMattermost().getPassword());

			if (mattermostUsername != null && mattermostPassword != null) {
				creds = new MattermostCredentials(yr.getMattermost().getMattermostServer(), mattermostUsername,
						mattermostPassword);
				mmChannel = new MattermostChannel(creds, yr.getMattermost().getOutputChannel(), featureFlags);
			}

		}

		String triageUsername = null;
		String triagePassword = null;
		if (yr.getGithub() != null && yr.getGithub().getTriageRole() != null) {
			triageUsername = yr.getGithub().getTriageRole().getUsername();
			triagePassword = yr.getGithub().getTriageRole().getPassword();
		}

		GitHubCredentials ghCreds = new GitHubCredentials(yr.getGham().getServerUrl(), yr.getGham().getPsk(),
				yr.getGithub() != null ? yr.getGithub().getUsername() : null,
				yr.getGithub() != null ? yr.getGithub().getPassword() : null, triageUsername, triagePassword,
				featureFlags);

		Path authFilePath = yr.getAuthFile() != null && !yr.getAuthFile().isEmpty()
				&& !yr.getAuthFile().trim().equalsIgnoreCase("null") ? Paths.get(yr.getAuthFile()) : null;

		BotCredentials botCreds = new BotCredentials(ghCreds, slackClient, creds, mmChannel, zhClient, zhamClient,
				authFilePath, featureFlags);

		List<String> repoStringList = new ArrayList<String>(yr.getRepoList());

		// Find repos to analyze
		List<GHRepository> repos = new ArrayList<>();

		// Keep trying if we can't contact the server.
		Utils.runUntilSuccess(() -> {

			repos.clear();

			GHOrganization gho = ghCreds.getGhamClient().getOrganization("eclipse");
			if (gho == null) {
				throw new RuntimeException(
						"Unable to retrieve organization from GHAM; we are likely not connected to the network.");
			}

			List<GHRepository> allRepos = gho.getRepositories();

			// Find all repos in the repoStringList
			{

				for (String repoToFind : repoStringList) {
					boolean match = false;

					for (GHRepository allRepoEntry : allRepos) {
						if (allRepoEntry.getName().equals(repoToFind)) {
							repos.add(allRepoEntry);
							match = true;
						}
					}

					if (!match) {
						throw new RuntimeException("Unable to find: " + repoToFind);
					}

				}

			}
		}, 20 * 1000);

		IKVStore innerDb = new FileKVStore(new File(yr.getDatabasePath()));

		if (featureFlags.isEphemeralDBWrites()) {
			innerDb = new EphemeralWritesKVStore(innerDb);
		}

		innerDb = new InMemoryKVCache(innerDb);

		GHDatabase db = new GHDatabase(innerDb);

		log.out("* Enabled featureFlags: "
				+ (yr.getFeatureFlags() != null ? yr.getFeatureFlags().stream().reduce((a, b) -> a + " " + b).orElse("")
						: ""));

		log.out();

		runJobs(repos, db, botCreds);

	}

	private static void runJobs(List<GHRepository> repos, GHDatabase db, BotCredentials botCreds) {

		JobUtil jobUtil = Utils.jobUtil();

		while (true) {

			try {

				List<GitHubRepoEvent> issueEvents = ChannelJobs.getFirstNewRepoEvent(db, botCreds);

				// Jobs that are based on issue events
				if (issueEvents.size() > 0) {
					List<MMIssueStatusEntry> issuesOnMmChannel = ChannelJobs.getAllIssuePostsOnChannel(botCreds);

					if (botCreds.getMattermostCreds() != null && botCreds.getMattermostChannel() != null) {

						jobUtil.run("mattermost", 5 * 60 * 1000, () -> {
							ChannelJobs.runMattermostJob(issuesOnMmChannel, botCreds);
						});

					}

					log.out("Issue Events: " + issueEvents.size());
					for (GitHubRepoEvent ie : issueEvents) {
						System.out.println("- " + ie.getRepository().getName() + "/" + ie.getGhIssue().getNumber());
					}

					ChannelJobs.runGeneralJob(repos, issuesOnMmChannel, issueEvents, db, botCreds);

					if (botCreds.getFeatureFlags().isUpgradeDetection()) {
						ChannelJobs.runUpgradeDetectionJob(issueEvents, db, botCreds);
					}

					CommandJob.runCommandJob(issueEvents, db, botCreds);

				}

				// Jobs that are not based on issue events.
				{

					ChannelJobs.runWaitListJob(db, botCreds);

					if (botCreds.getFeatureFlags().isZenHubJob()) {

						jobUtil.run("zenhub-job2", 1 * 60 * 1000, () -> {
							ZenHubJob.run(repos, botCreds, db);
						});
						System.out.println("post"); // TODO: Remove this.

					}

					// Run statistics report job every 3 days
					Calendar c = Calendar.getInstance();
					if (c.get(Calendar.HOUR_OF_DAY) == 5) {

						Long lastRunInMsecs = db.getLastStatisticsReportJobRun().orElse(null);

						if (lastRunInMsecs == null) {
							lastRunInMsecs = System.currentTimeMillis();
							db.setLastStatisticsReportJobRun(lastRunInMsecs);

						}

						if (TimeUnit.DAYS.convert(System.currentTimeMillis() - lastRunInMsecs,
								TimeUnit.MILLISECONDS) >= 3) {

							db.setLastStatisticsReportJobRun(System.currentTimeMillis());

							StatisticsReportJob.runStatisticsReportJob(repos, botCreds);
						}

					}
				}

			} catch (Exception e) {
				// Prevent exceptions from ending the thread.
				e.printStackTrace();
			}

			// Clean the database once per day
			db.cleanOldEntriesIfApplicable();

			log.out("==================================================================");

			Utils.sleep(15 * 1000); // Don't move this inside the exception block

		}

	}

	private static String nullIfEmptyOrNullString(String str) {
		if (str == null) {
			return null;
		}
		if (str.trim().isEmpty()) {
			return null;
		}

		if (str.trim().equalsIgnoreCase("null")) {
			return null;
		}

		return str;

	}

}
