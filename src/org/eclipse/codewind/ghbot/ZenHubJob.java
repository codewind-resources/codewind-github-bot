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

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.codewind.ghbot.credentials.BotCredentials;
import org.eclipse.codewind.ghbot.db.GHDatabase;
import org.eclipse.codewind.ghbot.utils.Logger;
import org.eclipse.egit.github.core.service.IssueService;

import com.githubapimirror.client.api.GHIssue;
import com.githubapimirror.client.api.GHRepository;
import com.zhapi.ApiResponse;
import com.zhapi.client.BoardService;
import com.zhapi.client.ZenHubMirrorApiClient;
import com.zhapi.client.ZenHubMirrorEventsService;
import com.zhapi.json.IssueEventJson;
import com.zhapi.json.responses.GetBoardForRepositoryResponseJson;
import com.zhapi.shared.json.RepositoryChangeEventJson;

public class ZenHubJob {

	private static final Logger log = Logger.getInstance();

	private final static long IGNORE_MOVES_OLDER_THAN_X_MSECS = TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS);

	public static void run(List<GHRepository> repos, BotCredentials credentials, GHDatabase db) {

		ZenHubMirrorApiClient zham = credentials.getZhamClient();
		ZenHubMirrorEventsService eventsService = new ZenHubMirrorEventsService(zham);

		// Find all the repositories that have had their ZH data updated
		HashSet<Long> reposUpdated = new HashSet<>();
		{
			long lastEventSeen = db.getLastZhamEventIdSeen().orElse(-1l);
			long newLastEventSeen = lastEventSeen;

			// Increment by one since the eventsService query is
			// greater-than-or-equal-to, and we don't want to match a previously returned
			// event id.
			lastEventSeen++;

			for (RepositoryChangeEventJson rcej : eventsService.getResourceChangeEvents(lastEventSeen)) {
				reposUpdated.add(rcej.getRepoId());

				if (rcej.getTime() > newLastEventSeen) {
					newLastEventSeen = rcej.getTime();
				}

				log.out("Repository update seen: " + rcej.getRepoId() + " " + rcej.getTime());
			}
			db.setLastZhamEventIdSeen(newLastEventSeen);
			log.out("Set last ZhamEventId: " + newLastEventSeen);
		}

		// We don't post verify messages for anything older than X (eg 1) days old.
		long ignoreMovesOlderThanThis = System.currentTimeMillis() - IGNORE_MOVES_OLDER_THAN_X_MSECS;

		com.zhapi.services.IssuesService zhIssuesService = new com.zhapi.services.IssuesService(
				credentials.getZenhubClient());
		BoardService bs = new BoardService(zham);

		for (Long updatedRepo : reposUpdated) {

			GHRepository repo = repos.stream().filter(e -> e.getRepositoryId() == updatedRepo).findFirst()
					.orElseThrow(RuntimeException::new);

			// TODO: Convert to debug once verify is ready
			System.out.println("ZHJob scanning repo: " + repo.getFullName());

			ApiResponse<GetBoardForRepositoryResponseJson> ar = bs.getZenHubBoardForRepo(updatedRepo);

			if (ar.getResponse() == null) {
				log.err("Did not get a board response for " + updatedRepo);
				continue;
			}

			GetBoardForRepositoryResponseJson board = ar.getResponse();
			if (board.getPipelines() == null) {
				log.err("Board pipelines were empty for " + updatedRepo);
				continue;
			}

			Set<Integer> issuesInVerifyPipeline = board.getPipelines().stream()
					.filter(e -> e.getName().equalsIgnoreCase("Verify")).flatMap(e -> e.getIssues().stream())
					.map(e -> e.getIssue_number()).collect(Collectors.toSet());

			Set<Integer> dbExpectedVerifyIssues = new HashSet<>(db.getIssuesInVerifyPipeline(repo));

			// Issues in the verify state from ZH, but that we expected NOT to be in verify
			// state in DB:
			// - post message (but check the event data first)
			Set<Integer> group2 = issuesInVerifyPipeline.stream().filter(e -> !dbExpectedVerifyIssues.contains(e))
					.collect(Collectors.toSet());
			group2.forEach(issue -> db.addIssueLastSeenInVerifyPipeline(repo, issue));

			// Issues not in the verify state from ZH, but that expected to be in verify
			// state in DB.
			Set<Integer> group3 = dbExpectedVerifyIssues.stream().filter(e -> !issuesInVerifyPipeline.contains(e))
					.collect(Collectors.toSet());
			group3.forEach(issue -> db.removeIssueLastSeenInVerifyPipeline(repo, issue));

			// TODO: Convert to info once verify is ready.
			log.out("group2: " + group2);
			log.out("group3: " + group3);

			if (group2.size() == 0 && group3.size() == 0) {
				continue;
			}

			for (Integer issue : group2) {

				ApiResponse<List<IssueEventJson>> air = zhIssuesService.getIssueEvents(updatedRepo, issue);
				if (air == null || air.getResponse() == null) {
					log.err("Unaable to get issue events for issue: " + issue);
					continue;
				}

				IssueEventJson lastEvent = air.getResponse().stream()
						.sorted((a, b) -> b.getCreated_at().compareTo(a.getCreated_at())).findFirst().orElse(null);

				if (lastEvent == null) {
					log.err("Couldn't find last event for " + repo.getFullName() + " " + issue);
					continue;
				}

				if (lastEvent.getCreated_at() == null
						|| lastEvent.getCreated_at().getTime() < ignoreMovesOlderThanThis) {
					log.err("Last move was older than our threshold for " + repo.getFullName() + " " + issue);
					continue;
				}

				if (lastEvent.getTo_pipeline() == null || lastEvent.getTo_pipeline().getName() == null) {
					log.err("From pipeline was null" + repo.getFullName() + " " + issue);
					continue;
				}

				if (!lastEvent.getTo_pipeline().getName().equalsIgnoreCase("verify")) {
					log.out("Issue was not in verify pipeline " + repo.getFullName() + " " + issue);
					continue;
				}

				GHIssue ghIssue = repo.getIssue(issue);

				String message = "@" + ghIssue.getReporter().getLogin() + " - this issue is now ready to be verified.";

				IssueService is = new IssueService(credentials.getGhCreds().getEgitClient());

//				List<IssueEvent> issueEvents = new ArrayList<>();
//				is.pageIssueEvents(repo.getOwnerName(), repo.getName(), issue).forEach(e -> {
//					if (e != null) {
//						issueEvents.addAll(e);
//					}
//				});
//
//				// Sort descending by creation time
//				Collections.sort(issueEvents, (a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
//
//				for (IssueEvent ie : issueEvents) {
//
//					
//				}

				try {
					// Look through the existing comments on the issue to see if we have already
					// posted the verification message AFTER the last pipeline change to Verify.
					boolean haveWeAlreadyPostedTheComment = is.getComments(repo.getOwnerName(), repo.getName(), issue)
							.stream().filter(e -> e.getBody() != null && e.getBody().contains(message))
							.anyMatch(e -> e.getCreatedAt().getTime() > lastEvent.getCreated_at().getTime());

					if (!haveWeAlreadyPostedTheComment) {
						log.out("!!!! Post message to GitHub: " + repo.getFullName() + " " + issue + " -> " + message);
					} else {
						log.out("Skipping post of message to GitHub: " + repo.getFullName() + " " + issue + " -> "
								+ message);
					}

				} catch (IOException e1) {
					log.err("Unable to get and post comment");
					e1.printStackTrace();
				}

			}

		}

	}
}
