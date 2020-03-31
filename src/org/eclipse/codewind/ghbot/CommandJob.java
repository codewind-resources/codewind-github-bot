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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.codewind.ghbot.credentials.BotCredentials;
import org.eclipse.codewind.ghbot.db.GHDatabase;
import org.eclipse.codewind.ghbot.utils.BotConstants;
import org.eclipse.codewind.ghbot.utils.FileLogger;
import org.eclipse.codewind.ghbot.utils.GitHubRepoEvent;
import org.eclipse.codewind.ghbot.utils.Logger;
import org.eclipse.codewind.ghbot.utils.Utils;
import org.eclipse.codewind.ghbot.utils.Utils.Triplet;
import org.eclipse.codewind.ghbot.yaml.YamlUserListRoot;
import org.eclipse.egit.github.core.Comment;
import org.eclipse.egit.github.core.Issue;
import org.eclipse.egit.github.core.Label;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.client.RequestException;
import org.eclipse.egit.github.core.service.IssueService;
import org.eclipse.egit.github.core.service.LabelService;
import org.eclipse.egit.github.core.service.RepositoryService;
import org.eclipse.egit.github.core.service.UserService;
import org.kohsuke.github.HttpException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.githubapimirror.client.api.GHIssue;
import com.githubapimirror.client.api.GHIssueComment;
import com.githubapimirror.client.api.GHRepository;
import com.githubapimirror.client.api.GHUser;
import com.zhapi.ApiResponse;
import com.zhapi.ZenHubClient;
import com.zhapi.json.BoardPipelineEntryJson;
import com.zhapi.json.ReleaseReportIssueJson;
import com.zhapi.json.ReleaseReportJson;
import com.zhapi.json.responses.GetBoardForRepositoryResponseJson;
import com.zhapi.json.responses.GetIssueDataResponseJson;
import com.zhapi.services.BoardService;
import com.zhapi.services.IssuesService;
import com.zhapi.services.ReleaseReportService;

/**
 * This class is responsible for processing GitHub comments that contain issue
 * commands (assign/unassign/etc), and acting on them (on behalf of the
 * requester) as the GH user specified in BotCredentials.
 */
public class CommandJob {

	private static final FileLogger fileLogger = Logger.getInstance().getFileLogger();

	private static final Logger log = Logger.getInstance();

	/** Public (eg non-secret) workspace ID for Codewind repo */
	private static final String CODEWIND_WORKSPACE_ID = "5d125da643e42a09a0a4a961";

	private static boolean READ_ONLY_MODE = false;

	private static long TIME_BETWEEN_RETRIES = 10 * 1000;

	private static final String[] VALID_COMMANDS = new String[] { "assign", "unassign", "area", "kind", "priority",
			"remove-kind", "remove-area", "remove-priority", "close", "reopen", "pipeline", "release", "remove-release",
			"verify", "tech-topic", "good-first-issue", "wontfix", "svt", "epic" };

	private static final String[] VALID_NO_PARAM_REMOVE_COMMANDS = new String[] { "remove-tech-topic",
			"remove-good-first-issue", "remove-wontfix", "remove-svt", "remove-epic" };

	private static final List<String> validCommands;

	static {
		List<String> commands = new ArrayList<>();
		commands.addAll(Arrays.asList(VALID_COMMANDS));
		commands.addAll(Arrays.asList(VALID_NO_PARAM_REMOVE_COMMANDS));

		validCommands = Collections.unmodifiableList(commands);
	}

	private final static int MAX_RETRY_FAILURES = 6;

	public static void runCommandJob(List<GitHubRepoEvent> newIssuesFromProcessRepo, GHDatabase db,
			BotCredentials botCreds) {

		if (newIssuesFromProcessRepo.size() == 0) {
			return;
		}

		log.out("Running command job.");

		// Sort changed events by created at
		List<GitHubRepoEvent> newIssues = newIssuesFromProcessRepo.stream()
				.sorted((a, b) -> a.getGhIssue().getCreatedAt().compareTo(b.getGhIssue().getCreatedAt()))
				.collect(Collectors.toList());

		HashSet<String /* repo+issue */> processedIssues = new HashSet<>();

		Map<String /* repo+issue */, Triplet> issuesContainingValidCommands = new HashMap<>();

		long ignoreCommentsBeforeMsecs = System.currentTimeMillis()
				- TimeUnit.MILLISECONDS.convert(BotConstants.PROCESS_COMMANDS_LESS_THAN_X_DAYS_OLD, TimeUnit.DAYS);

		// First we process the GHAM issues list, find any new commands, and then store
		// them in issuesContainingValidCommands.
		newIssues.forEach(event -> {

			GHRepository repo = event.getRepository();
			GHIssue issue = event.getGhIssue();

			String key = repo.getFullName() + "/" + issue;

			Long lastCommandProcessedForIssueTimestamp = db.getDateOfLastProcessedCommand(repo, issue.getNumber())
					.orElse(null);

			// Don't process an issue more than once.
			if (processedIssues.contains(key)) {
				return;
			}

			processedIssues.add(key);

			if (issue.getCreatedAt().getTime() > ignoreCommentsBeforeMsecs
					&& (lastCommandProcessedForIssueTimestamp == null
							|| issue.getCreatedAt().getTime() > lastCommandProcessedForIssueTimestamp)
					&& isAuthorizedUser(issue.getReporter(), botCreds)) {

				// If we find a valid command in the issue, then stop processing.
				if (containsValidCommand(issue.getBody())) {
					issuesContainingValidCommands.put(key, Utils.triplet(repo, issue, null));
				}

			}

			for (GHIssueComment c : issue.getComments()) {

				long lastCommentTime = c.getCreatedAt().getTime();

				if (lastCommentTime < ignoreCommentsBeforeMsecs) {
					// Skip command comments older than X days
					continue;
				}

				if (lastCommandProcessedForIssueTimestamp != null
						&& lastCommentTime < lastCommandProcessedForIssueTimestamp) {
					// Skip commands that we have already processed.
					continue;
				}

				if (!isAuthorizedUser(c.getUser(), botCreds)) {
					continue;
				}

				// If we find a valid command in the issue, then stop processing.
				if (containsValidCommand(c.getBody())) {
					issuesContainingValidCommands.put(key, Utils.triplet(repo, issue, null));
					break;
				}

			}

		}); // end newevents for branch

		for (Triplet issueTriplet : issuesContainingValidCommands.values()) {

			GHRepository repo = issueTriplet.getRepo();
			int issueNumber = issueTriplet.getIssue().getNumber();

			boolean processIssue = true;

			if (issueNumber == 844 && repo.getName().equals("codewind")) {
				// In production, avoid responding to commands on codewind/844.
				if (!botCreds.getFeatureFlags().isIssue844Only()) {
					processIssue = false;
				}
			} else {
				// During dev/test, only respond to commands on codewind/844.
				if (botCreds.getFeatureFlags().isIssue844Only()) {
					processIssue = false;
				}
			}

			if (!processIssue) {
				continue;
			}
			processIssue(repo, issueNumber, db, botCreds, ignoreCommentsBeforeMsecs);

		} // end triplet for issue

	}

	private static void processIssue(GHRepository repo, int issueNumber, GHDatabase db, BotCredentials botCreds,
			long ignoreCommentsBeforeMsecs) {

		String debugStr = repo.getName() + "/" + issueNumber;

		// Retrieve the timestamp of the most recent comment/body processed, so that we
		// can ignore any comments that are farther in the past (to prevent processing
		// the same comment twice)
		final Long lastCommandProcessedForIssueTimestamp = db.getDateOfLastProcessedCommand(repo, issueNumber)
				.orElse(null);

		GitHubClient egit = botCreds.getGhCreds().getTriageEGitClient();
		IssueService issueService = new IssueService(egit);

		Exception success;

		// Retrieve the issue and comments
		List<Comment> comments = new ArrayList<>();
		CReference<Issue> issueRef = new CReference<>();
		{
			success = Utils.runWithMaxRetries(() -> {

				comments.clear();

				issueRef.set(issueService.getIssue(repo.getOwnerName(), repo.getName(), issueNumber));

				comments.addAll(issueService.getComments(repo.getOwnerName(), repo.getName(), issueNumber));

			}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

			if (success != null || issueRef.get() == null) {
				err("Unable to retrieve issue or issue comments", success, debugStr);
				db.setDateOfLastProcessedCommand(repo, issueNumber, System.currentTimeMillis());
				return;
			}
		}

		Issue issue = issueRef.get();

		// First process the body, if applicable.
		if (issue.getCreatedAt().getTime() > ignoreCommentsBeforeMsecs && lastCommandProcessedForIssueTimestamp == null
				&& isAuthorizedUser(issue.getUser(), botCreds)) {

			CommandReferenceResult result = processIssueBodyOrComment(new CommandReference(issue), repo, issueNumber,
					db, botCreds);

			if (result.getError().isPresent()) {
				CommandReferenceError err = result.getError().get();
				postError(repo.getOwnerName(), repo.getName(), issue, issue.getUser().getLogin(), err, botCreds);
			}
		}

		// Next, process comments.
		for (Comment c : comments) {

			// Skip comments older than X days old
			if (c.getCreatedAt().getTime() < ignoreCommentsBeforeMsecs) {
				continue;
			}

			// Skip comments if we have already processed them.
			if (lastCommandProcessedForIssueTimestamp != null
					&& c.getCreatedAt().getTime() <= lastCommandProcessedForIssueTimestamp) {
				continue;
			}

			if (!isAuthorizedUser(c.getUser(), botCreds)) {
				continue;
			}

			CommandReferenceResult result = processIssueBodyOrComment(new CommandReference(c), repo, issueNumber, db,
					botCreds);

			if (result.getError().isPresent()) {
				CommandReferenceError err = result.getError().get();
				postError(repo.getOwnerName(), repo.getName(), issue, c.getUser().getLogin(), err, botCreds);
			}

		}

	}

	/**
	 * Attempt to perform all of the commands of an issue description or a comment.
	 */
	private static CommandReferenceResult processIssueBodyOrComment(CommandReference entity, GHRepository repo,
			int issueNumber, GHDatabase db, BotCredentials botCreds) {

		GitHubClient egit = botCreds.getGhCreds().getEgitClient();
		IssueService issueService = new IssueService(egit);

		Exception success;

		String debugStr = repo.getName() + "/" + issueNumber;

		boolean allowWrites = !READ_ONLY_MODE
				&& (botCreds == null || !botCreds.getFeatureFlags().isDisableExternalWrites());

		// Retrieve the issue and comments
		Issue[] issueArr = new Issue[1];
		{
			success = Utils.runWithMaxRetries(() -> {

				issueArr[0] = issueService.getIssue(repo.getOwnerName(), repo.getName(), issueNumber);

			}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

			if (success != null || issueArr[0] == null) {
				err("Unable to retrieve issue", success, debugStr);
				return new CommandReferenceResult();
			}
		}

		UserService us = new UserService(egit);

		Issue issue = issueArr[0];

		// Acquire the state of the live issue from GitHub
		IssueState issueState;
		List<String> oldIssueAssignees;
		List<String> oldPipelines;
		{
			boolean isIssueOpen = issue.getState().equals("open");

			List<String> issueLabels = new ArrayList<String>(
					issue.getLabels().stream().map(e -> e.getName()).collect(Collectors.toList()));

			List<String> issueAssignees = new ArrayList<>();

			success = Utils.runWithMaxRetries(() -> {
				issueAssignees.clear();
				issueAssignees.addAll(botCreds.getGhCreds().getIssueAssignees(repo.getOwnerName(), repo.getName(),
						issue.getNumber()));

			}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

			if (success != null) {
				err("Unable to retrieve issue assignees", success, debugStr);
				return new CommandReferenceResult();
			}

			List<String /* pipeline id */> pipelines = new ArrayList<>();

			ZenHubClient zhc = botCreds.getZenhubClient();
			if (zhc != null) {

				success = Utils.runWithMaxRetries(() -> {
					IssuesService is = new IssuesService(zhc);

					GetIssueDataResponseJson response = is.getIssueData(repo.getRepositoryId(), issueNumber)
							.getResponse();
					if (response != null) {

						List<String> innerPipelines = new ArrayList<>();
						if (response.getPipeline() != null) {
							innerPipelines.add(response.getPipeline().getName());
						}

						if (response.getPipelines() != null) {
							innerPipelines.addAll(response.getPipelines().stream().map(e -> e.getName())
									.collect(Collectors.toList()));
						}

						pipelines.clear();
						pipelines.addAll(innerPipelines.stream().distinct().collect(Collectors.toList()));
					}

				}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

				if (success != null) {
					err("Unable to retrieve zenhub pipeline value", success, debugStr);
					return new CommandReferenceResult();
				}

			}

			issueState = new IssueState(issueLabels, issueAssignees, isIssueOpen, pipelines);

			oldPipelines = new ArrayList<>(pipelines);
			oldIssueAssignees = Collections.unmodifiableList(new ArrayList<>(issueAssignees));
		}

		// Process the body of the issue or comment, and update this methods internal
		// representation of the assignees, labels, and state.
		{

			long mostRecentProcessedTextBody;
			if (entity.getType() == CommandReference.CRType.BODY) {
				mostRecentProcessedTextBody = issue.getCreatedAt().getTime();
			} else {
				Comment c = entity.getComment();
				mostRecentProcessedTextBody = c.getCreatedAt().getTime();
			}

			db.setDateOfLastProcessedCommand(repo, issueNumber, mostRecentProcessedTextBody);

			CReference<ProcessBodyReturn> response = new CReference<>();

			success = Utils.runWithMaxRetries(() -> {

				IssueState cloneState = issueState.deepClone();

				String body;
				User actingUser;

				if (entity.getType() == CommandReference.CRType.BODY) {
					body = issue.getBody();
					actingUser = issue.getUser();
				} else {
					body = entity.getComment().getBody();
					actingUser = entity.getComment().getUser();
				}

				ProcessBodyReturn pbr = processBody(body, cloneState, actingUser, us, debugStr, botCreds).orElse(null);

				if (pbr != null) {
					response.set(pbr);
					return;
				}

				// On success, update the original labels, assignees, and state
				issueState.copyFrom(cloneState);

			}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

			if (success != null) {
				err("Unable to update issue label, assignees, or state.", success, debugStr);
				return new CommandReferenceResult();
			}

			if (response.get() != null) {
				// Return an error from the processBody
				ProcessBodyReturn result = response.get();

				if (entity.getType() == CommandReference.CRType.BODY) {
					String bodyUrl = "https://github.com/" + repo.getOwnerName() + "/" + repo.getName() + "/issues/"
							+ issueNumber + "#issue-" + issue.getId();

					return new CommandReferenceResult(new CommandReferenceError(result.getErrorMsg(), issue.getBody(),
							result.getCommandLine(), bodyUrl));

				} else {
					String commentUrl = "https://github.com/" + repo.getOwnerName() + "/" + repo.getName() + "/issues/"
							+ issueNumber + "#issuecomment-" + entity.getComment().getId();

					return new CommandReferenceResult(new CommandReferenceError(result.getErrorMsg(),
							entity.getComment().getBody(), result.getCommandLine(), commentUrl));

				}

			}

		}

		// Convert new labels list to egit labels
		List<Label> labels = new ArrayList<>();
		LabelService labelService = new LabelService(egit);
		for (String issueLabel : issueState.getLabels().stream().distinct().collect(Collectors.toList())) {

			// TODO: LOWER - Implement a connection to the actual line.

			CReference<String> ref = new CReference<String>();

			success = Utils.runWithMaxRetries(() -> {

				Label l = null;
				try {
					if (issueLabel.contains(" ")) {
						// The EGit client has a bug where it doesn't work with labels with spaces, so
						// we fake the label here.
						l = new Label();
						l.setName(issueLabel);

					} else {
						l = labelService.getLabel(repo.getOwnerName(), repo.getName(), issueLabel);
					}

				} catch (RequestException re) {
					if (!re.getMessage().contains("404")) {
						// If a label can't be found, ignore it.
						throw re;
					}
				}

				if (l == null) {
					ref.set(issueLabel);
					err("Could not find label: " + issueLabel, debugStr);
				}

				labels.add(l);
			}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

			if (ref.get() != null) {
				CommandReferenceError err = new CommandReferenceError("Could not find label: " + ref.get(), null, null,
						null);
				return new CommandReferenceResult(err);
			}

			if (success != null) {
				err("General issue occurred when retrieving label: " + issueLabel, success, debugStr);
				return new CommandReferenceResult();
			}

		}

		// Update assignees if they have changed.
		{
			List<String> issueAssignees = issueState.getAssignees();
			// TODO: LOWER - Assignees to a particular comment/line.
			String newAssignees = issueAssignees.stream().distinct().sorted().reduce((a, b) -> a + "/" + b).orElse("");
			String oldAssignees = oldIssueAssignees.stream().sorted().reduce((a, b) -> a + "/" + b).orElse("");

			if (!newAssignees.equals(oldAssignees)) {

				CReference<String> ref = new CReference<>();

				if (allowWrites) {

					success = Utils.runWithMaxRetries(() -> {

						try {

							List<String> assigneesToAdd = new ArrayList<>();
							List<String> assigneesToRemove = new ArrayList<>();

							compareStringLists(issueAssignees, oldIssueAssignees, assigneesToAdd, assigneesToRemove,
									new ArrayList<>() /* ignore */);

							botCreds.getGhCreds().setAssigneesWithTriage(repo.getOwnerName(), repo.getName(),
									issueNumber, assigneesToAdd, assigneesToRemove);

							out("Successfully assigned issue assignees: " + issueAssignees, debugStr);

						} catch (HttpException he) {
							if (he.getMessage().contains("Validation Failed")) {
								// Catch successful requests that failed validation, and just ignore them.
								err("Bad user message", he, debugStr);
								ref.set("error"); // set an arbitrary non-empty string
							} else {
								throw he;
							}
						}

					}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);
				} else {
					out("* READ-ONLY MODE - Writing assignees: " + issueAssignees, debugStr);
					success = null;
				}

				if (ref.get() != null) {
					List<String> newAssigneesArray = new ArrayList<>(issueAssignees);
					for (Iterator<String> it = newAssigneesArray.iterator(); it.hasNext();) {
						String as = it.next();
						if (oldIssueAssignees.contains(as)) {
							it.remove();
						}
					}
					String newAssigneesMsg = newAssigneesArray.stream().reduce((a, b) -> a + " " + b).orElse("N/A");

					CommandReferenceError err = new CommandReferenceError(
							"GitHub didn't allow me to assign the following users: " + newAssigneesMsg
									+ "\n\nNote that only org members, repo collaborators and people who have commented on this issue/PR can be assigned.",
							null, null, null);

					return new CommandReferenceResult(err);
				}

				if (success != null) {
					err("Unable to update assignees", success, debugStr);
					return new CommandReferenceResult();
				}

			}
		}

		// Update labels if they have changed.
		{

			List<String> newLabelsAsStrings = labels.stream().map(e -> e.getName()).sorted()
					.collect(Collectors.toList());

			List<String> oldLabelsAsStrings = issue.getLabels().stream().map(e -> e.getName()).sorted()
					.collect(Collectors.toList());

			// TODO: LOWER - Tie labels to a particular comment/body.
			String newLabelsStr = newLabelsAsStrings.stream().reduce((a, b) -> a + "/" + b).orElse("");
			String oldLabelsStr = oldLabelsAsStrings.stream().reduce((a, b) -> a + "/" + b).orElse("");

			if (!newLabelsStr.equals(oldLabelsStr)) {

				if (allowWrites) {
					success = Utils.runWithMaxRetries(() -> {

						List<String> labelsToAdd = new ArrayList<>();
						List<String> labelsToRemove = new ArrayList<>();

						compareStringLists(newLabelsAsStrings, oldLabelsAsStrings, labelsToAdd, labelsToRemove,
								new ArrayList<>() /* ignore */);

						botCreds.getGhCreds().setLabelsWithTriage(repo.getOwnerName(), repo.getName(), issueNumber,
								labelsToAdd, labelsToRemove);

						out("Successfully assigned issue labels: " + labels, debugStr);

					}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);
				} else {
					out("* READ-ONLY MODE - Writing labels: " + labels, debugStr);
					success = null;
				}

				if (success != null) {

					for (Iterator<String> it = newLabelsAsStrings.iterator(); it.hasNext();) {
						String as = it.next();
						if (oldLabelsAsStrings.contains(as)) {
							it.remove();
						}
					}

					err("Unable to find label: " + newLabelsAsStrings, success, debugStr);

					String labelListStr = newLabelsAsStrings.stream().reduce((a, b) -> (a + b)).orElse("N/A");

					CommandReferenceError error = new CommandReferenceError(
							"The label(s) " + labelListStr + " cannot be applied. ", null, null, null);

					return new CommandReferenceResult(error);
				}

			}
		}

		// Update open state, if changed
		if (issue.getState().equals("open") != issueState.isIssueOpen()) {

			String newState = issueState.isIssueOpen() ? "open" : "closed";

			if (allowWrites) {
				success = Utils.runWithMaxRetries(() -> {

					if (!issueState.issueOpen) {
						// If the target issue state is closed, then close
						botCreds.getGhCreds().closeIssue(repo.getOwnerName(), repo.getName(), issueNumber);
					} else {
						// Otherwise open.
						botCreds.getGhCreds().reopenIssue(repo.getOwnerName(), repo.getName(), issueNumber);
					}

				}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

				if (success == null) {
					out("Changed issue state to: " + newState, debugStr);
				} else {
					out("Unable to changed issue state to: " + newState, debugStr);
				}

			} else {
				out("* READ-ONLY MODE - Set new issue state: " + newState, debugStr);
				success = null;
			}

		}

		// Update pipeline, if changed
		{
			String oldPipelineStr = oldPipelines.stream().distinct().sorted().reduce((a, b) -> (a + " " + b))
					.orElse("");
			String newPipelinesStr = issueState.getPipelines() != null
					? issueState.getPipelines().stream().sorted().distinct().reduce((a, b) -> (a + " " + b)).orElse("")
					: "";

			if (!oldPipelineStr.equals(newPipelinesStr) && issueState.getPipelines() != null
					&& issueState.getPipelines().size() > 0) {

				if (issueState.getPipelines().size() > 1 || oldPipelines.size() > 1) {
					CommandReferenceError error = new CommandReferenceError(
							"Cannot move an issue that is in multiple pipelines -  old:" + oldPipelineStr + " new: "
									+ newPipelinesStr,
							null, null, null);

					return new CommandReferenceResult(error);

				}

				String workspaceId = CODEWIND_WORKSPACE_ID;

				String pipelineName = issueState.getPipelines().get(0);

				CReference<BoardPipelineEntryJson> bpej = new CReference<BoardPipelineEntryJson>();

				success = Utils.runWithMaxRetries(() -> {

					BoardService boardService = new BoardService(botCreds.getZenhubClient());
					GetBoardForRepositoryResponseJson gbResult = boardService
							.getZenHubBoardForRepo(repo.getRepositoryId(), workspaceId).getResponse();

					bpej.set(gbResult.getPipelines().stream().filter(e -> e.getName().equalsIgnoreCase(pipelineName))
							.findFirst().orElse(null));

				}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

				if (bpej.get() == null || success != null) {
					CommandReferenceError error = new CommandReferenceError(
							"Unable to locate pipeline with name: `" + pipelineName + "`", null, null, null);

					return new CommandReferenceResult(error);

				}

				if (allowWrites) {
					success = Utils.runWithMaxRetries(() -> {

						if (botCreds.getFeatureFlags().isDisableExternalWrites()) {
							return;
						}

						String pipelineId = bpej.get().getId();

						IssuesService service = new IssuesService(botCreds.getZenhubClient());

						service.moveIssueToPipeline(workspaceId, repo.getRepositoryId(), issueNumber, pipelineId,
								"bottom");

					}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);
				} else {
					out("* READ-ONLY MODE - move issue to pipeline: " + bpej.get().getName(), debugStr);
					success = null;
				}

				if (success != null) {
					CommandReferenceError error = new CommandReferenceError(
							"Unable to move issue to pipeline: " + pipelineName, null, null, null);

					return new CommandReferenceResult(error);

				}

			}

		}

		// Update release, if applicable
		if (issueState.getNewReleasesToAdd().size() > 0 || issueState.getNewReleasesToRemove().size() > 0) {

			ReleaseReportService rrs = new ReleaseReportService(botCreds.getZenhubClient());

			for (String releaseParam : issueState.getNewReleasesToAdd()) {

				if (allowWrites) {
					Optional<CommandReferenceResult> r = addOrRemoveReleaseReport(releaseParam, true, repo, issueNumber,
							rrs, botCreds);

					if (r.isPresent()) {
						return r.get();
					}
				} else {
					out("* READ-ONLY MODE - Add release report: " + releaseParam, debugStr);
				}

			}

			for (String releaseParam : issueState.getNewReleasesToRemove()) {

				if (allowWrites) {

					Optional<CommandReferenceResult> r = addOrRemoveReleaseReport(releaseParam, false, repo,
							issueNumber, rrs, botCreds);

					if (r.isPresent()) {
						return r.get();
					}

				} else {
					out("* READ-ONLY MODE - Add remove release report: " + releaseParam, debugStr);
				}

			}

		}

		return new CommandReferenceResult();
	}

	private static Optional<CommandReferenceResult> addOrRemoveReleaseReport(String releaseParam, boolean isAdd,
			GHRepository repo, int issueNumber, ReleaseReportService rrs, BotCredentials creds) {

		CReference<String> nonExceptionErrorOccurred = new CReference<String>();

		Exception success = Utils.runWithMaxRetries(() -> {

			ApiResponse<List<ReleaseReportJson>> ar0 = rrs.getReleaseReportsForRepo(repo.getRepositoryId());
			List<ReleaseReportJson> rrjList = ar0.getResponse();

			String releaseId = rrjList.stream().filter(e -> e.getTitle().equals(releaseParam))
					.map(e -> e.getRelease_id()).findFirst().orElse(null);

			if (releaseId == null) {
				nonExceptionErrorOccurred.set("Unable to find release: " + releaseParam);
				return;
			}

			List<ReleaseReportIssueJson> toAdd = Collections.emptyList();
			List<ReleaseReportIssueJson> toRemove = Collections.emptyList();

			if (isAdd) {
				toAdd = Arrays.asList(new ReleaseReportIssueJson(repo.getRepositoryId(), issueNumber));
			} else {
				toRemove = Arrays.asList(new ReleaseReportIssueJson(repo.getRepositoryId(), issueNumber));
			}

			if (creds.getFeatureFlags().isDisableExternalWrites()) {
				return;
			}

			rrs.addOrRemoveIssuesFromReleaseReport(releaseId, toAdd, toRemove);

		}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

		if (success != null || nonExceptionErrorOccurred.get() != null) {

			CommandReferenceError error = new CommandReferenceError("Unable to update release: " + releaseParam, null,
					null, null);

			return Optional.of(new CommandReferenceResult(error));

		}

		return Optional.empty();
	}

	private static void printStackTrace(Exception e, String debugMsg) {

		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);

		err(sw.toString(), debugMsg);

	}

	public static void compareStringLists(List<String> inputA, List<String> inputB, List<String> outputAOnly,
			List<String> outputBOnly, List<String> outputInBoth) {

		outputAOnly.addAll(inputA.stream().filter(e -> !inputB.contains(e)).collect(Collectors.toList()));
		outputBOnly.addAll(inputB.stream().filter(e -> !inputA.contains(e)).collect(Collectors.toList()));
		outputInBoth.addAll(inputA.stream().filter(e -> inputB.contains(e)).collect(Collectors.toList()));

	}

	private static boolean containsValidCommand(String str) {

		try {
			List<String> commandLines = Arrays.asList(str.split("[\\r\\n]+")).stream().map(e -> e.trim())
					.filter(e -> e.startsWith("/")).map(e -> e.substring(1)).collect(Collectors.toList());

			for (String commandLine : commandLines) {

				String[] tokens = commandLine.split("\\s+");

				if (tokens.length == 0) {
					continue;
				}

				tokens[0] = tokens[0].toLowerCase();

				String command = tokens[0];

				if (!validCommands.contains(command)) {
					continue;
				}

				return true;
			}
		} catch (Exception e) {
			// We prevent badly formed commands from escaping this method.
			printStackTrace(e, str);
		}

		return false;
	}

	static Optional<ProcessBodyReturn> processBody(String descriptionOrCommentText, IssueState state, User actingUser,
			UserService us, String bodyDebug, BotCredentials botCreds) throws IOException {

		List<String> commandLines = Arrays.asList(descriptionOrCommentText.split("[\\r\\n]+")).stream()
				.map(e -> e.trim()).filter(e -> e.startsWith("/")).map(e -> e.substring(1))
				.collect(Collectors.toList());

		commandLines = replaceProblematicCommands(commandLines);

		for (final String commandLine : commandLines) {

			String[] tokens = commandLine.split("\\s+");

			tokens[0] = tokens[0].toLowerCase();

			String command = tokens[0];

			if (!validCommands.contains(command)) {
				continue;
			}

			out("- Processing command '" + commandLine + "'", bodyDebug);

			List<String> commandParams = new ArrayList<>();
			// Add parameters after the command, if applicable.
			for (int x = 1; x < tokens.length; x++) {
				String token = tokens[x];
				commandParams.add(token);
			}

			if (command.equals("release") || command.equals("remove-release")) {

				Optional<ProcessBodyReturn> o = assertAtLeastOneParam(command, commandLine, commandParams, bodyDebug);
				if (o.isPresent()) {
					return o;
				}

				for (String releaseParam : commandParams) {
					if (command.equals("release")) {
						state.getNewReleasesToAdd().add(releaseParam);

					} else {
						state.getNewReleasesToRemove().add(releaseParam);
					}

				}

			} else if (command.startsWith("remove-")) {
				// /remove-kind

				int index = command.indexOf("-");

				// kind, area, etc (or the label itself for 0 param)
				String afterRemovePart = command.substring(index + 1);

				List<String> toRemove = new ArrayList<>();

				if (commandParams.size() == 0) {
					// A remove command without a param

					if (Arrays.asList(VALID_NO_PARAM_REMOVE_COMMANDS).contains(command)) {
						toRemove.add(afterRemovePart);
					} else {
						String msg = "Command '" + command + "' requires at least one parameter.";
						err(msg, bodyDebug);

						return Optional.of(new ProcessBodyReturn(msg, commandLine));
					}

				} else {
					// A remove command with one or more params.
					for (String param : commandParams) {
						String valueToRemove = afterRemovePart + "/" + param;
						toRemove.add((valueToRemove).toLowerCase());
						out("User asked to remove " + valueToRemove, bodyDebug);
					}

				}

				HashSet<String> labelsNotFound = new HashSet<>(toRemove);

				for (Iterator<String> it = state.getLabels().iterator(); it.hasNext();) {
					String label = it.next();

					if (toRemove.contains(label.toLowerCase())) {
						out("Removing label: " + label, bodyDebug);
						it.remove();
						labelsNotFound.remove(label.toLowerCase());
					}
				}

				if (labelsNotFound.size() > 0) {
					String msg = "Those labels are not set on the issue: "
							+ labelsNotFound.stream().reduce((a, b) -> a + " " + b).orElse("N/A");
					err(msg, bodyDebug);

					return Optional.of(new ProcessBodyReturn(msg, commandLine));
				}

			} else if (command.equals("assign") || command.equals("unassign")) {

				String debugSpecifiedUser = null;
				List<User> specifiedUserForAction = new ArrayList<>();
				if (commandParams.size() > 0) {

					for (String specifiedUser : commandParams) {

						// User has specified someone to assign
						specifiedUser = specifiedUser.trim();

						while (specifiedUser.startsWith("@")) { // remove @ if needed
							specifiedUser = specifiedUser.substring(1).trim();
						}

						if (specifiedUser.equals("me")) {
							specifiedUser = actingUser.getLogin();
						}

						debugSpecifiedUser = specifiedUser;

						try {
							specifiedUserForAction.add(us.getUser(specifiedUser));
						} catch (RequestException re) {
							if (re.getMessage().contains("404")) {

								err("User not found: " + specifiedUser, bodyDebug);

								String outputMsg = "GitHub didn't allow me to assign the following users: "
										+ specifiedUser
										+ "\n\nNote that only org members, repo collaborators and people who have commented on this issue/PR can be assigned.";

								return Optional.of(new ProcessBodyReturn(outputMsg, commandLine));

							} else {
								throw re;
							}
						}

					}

				} else {
					// User wants to (un)assign themselves
					specifiedUserForAction.add(actingUser);

				}

				if (specifiedUserForAction.size() > 0) {

					List<String> assignees = state.getAssignees();

					for (User sua : specifiedUserForAction) {

						if (command.equals("assign")) {

							// Add to assignees by login name
							if (!assignees.contains(sua.getLogin())) {
								assignees.add(sua.getLogin());
							}

						} else {
							// Unassign assignees by login name
							if (assignees.contains(sua.getLogin())) {
								assignees.remove(sua.getLogin());
							}

						}

					}

				} else {

					String msg = "Could not find user: " + debugSpecifiedUser;
					err(msg, bodyDebug);

					String outputMsg = "GitHub didn't allow me to assign the following users: " + debugSpecifiedUser
							+ "\n\nNote that only org members, repo collaborators and people who have commented on this issue/PR can be assigned.";

					return Optional.of(new ProcessBodyReturn(outputMsg, commandLine));
				}
			} else if (command.equals("close")) {

				Optional<ProcessBodyReturn> o = assertNoParams(command, commandLine, commandParams, bodyDebug);
				if (o.isPresent()) {
					return o;
				}

				state.setIssueOpen(false);

			} else if (command.equals("reopen")) {

				Optional<ProcessBodyReturn> o = assertNoParams(command, commandLine, commandParams, bodyDebug);
				if (o.isPresent()) {
					return o;
				}

				state.setIssueOpen(true);

			} else if (command.equals("verify")) {
				Optional<ProcessBodyReturn> o = assertNoParams(command, commandLine, commandParams, bodyDebug);
				if (o.isPresent()) {
					return o;
				}

				state.getPipelines().clear();
				state.getPipelines().add("Verify");

			} else if (command.equals("pipeline")) {

				Optional<ProcessBodyReturn> o = assertAtLeastOneParam(command, commandLine, commandParams, bodyDebug);
				if (o.isPresent()) {
					return o;
				}

				String pipelineName = commandParams.stream().map(e -> {
					// Strip leading and trailing quotes
					String result = e;
					while (result.startsWith("\"")) {
						result = result.substring(1);
					}
					while (result.endsWith("\"")) {
						result = result.substring(0, result.length() - 1);
					}
					return result;
				}).reduce((a, b) -> a + " " + b).get().trim();

				state.getPipelines().clear();
				state.getPipelines().add(pipelineName);

			} else if (command.equals("tech-topic") || command.equals("good-first-issue") || command.equals("wontfix")
					|| command.equals("svt") || command.equals("epic")) {

				List<String> labels = state.getLabels();

				if ((botCreds != null && (botCreds.getFeatureFlags().isDisableExternalWrites()) || READ_ONLY_MODE)) {
					out("Skipping adding label: " + command, bodyDebug);
				} else {
					out("Adding label: " + command, bodyDebug);
					labels.add(command);
				}

			} else {

				// Handle all other commands

				if (commandParams.size() == 1) {
					List<String> labels = state.getLabels();

					String labelToAdd = command + "/" + commandParams.get(0);

					if (!labels.contains(labelToAdd)) {
						labels.add(labelToAdd);
					}

					if ((botCreds != null && (botCreds.getFeatureFlags().isDisableExternalWrites())
							|| READ_ONLY_MODE)) {
						out("Skipping adding label: " + labelToAdd, bodyDebug);
					} else {
						out("Adding label: " + labelToAdd, bodyDebug);
					}

				} else {

					String msg = "Unexpected command format: " + commandLine;
					err(msg, bodyDebug);

					return Optional.of(new ProcessBodyReturn(msg, commandLine));

				}

			}

		}

		return Optional.empty();
	}

	private static List<String> replaceProblematicCommands(List<String> commandLines) {

		List<String> result = new ArrayList<>();

		for (String line : commandLines) {

			String compare = line.replace(" ", "").trim();
			// Convert '/pipeline closed' -> '/close', since the 'Closed' pipeline is not
			// actually a real ZenHub pipeline (contrary to the ZH Web UI)
			if (compare.equals("pipelineclosed")) {
				result.add("close");
			} else {
				result.add(line);
			}

		}

		return result;

	}

	private static Optional<ProcessBodyReturn> assertNoParams(String command, String commandLine,
			List<String> commandParams, String bodyDebug) {
		if (commandParams.size() == 0) {
			return Optional.empty();
		}
		String msg = "Command '" + command + "' does not support any parameters.";

		err(msg, bodyDebug);

		return Optional.of(new ProcessBodyReturn(msg, commandLine));

	}

	private static Optional<ProcessBodyReturn> assertAtLeastOneParam(String command, String commandLine,
			List<String> commandParams, String bodyDebug) {
		if (commandParams.size() > 0) {
			return Optional.empty();
		}
		String msg = "Command '" + command + "' requires at least one parameter.";

		err(msg, bodyDebug);

		return Optional.of(new ProcessBodyReturn(msg, commandLine));

	}

	@SuppressWarnings("unused")
	private static Optional<ProcessBodyReturn> assertExactlyOneParam(String command, String commandLine,
			List<String> commandParams, String bodyDebug) {
		if (commandParams.size() != 1) {
			return Optional.empty();
		}
		String msg = "Command '" + command + "' requires exactly one parameter.";

		err(msg, bodyDebug);

		return Optional.of(new ProcessBodyReturn(msg, commandLine));

	}

	private static void out(String text, String debug) {
		if (debug == null) {
			debug = "";
		}

		String msg = text + "  [" + debug + "]";

		log.out(msg);
		fileLogger.out(msg);
	}

	private static void err(String text, String debug) {

		err(text, null, debug);
	}

	private static void err(String text, Exception e, String debug) {
		if (debug == null) {
			debug = "";
		}

		String msg = text + "  [" + debug + "]";

		log.out(msg);
		fileLogger.out(msg);

		if (e != null) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			fileLogger.err(sw.toString());
		}
	}

	private static boolean isAuthorizedUser(User user, BotCredentials creds) {
		if (user == null) {
			return false;
		}
		return isAuthorizedUser(user.getLogin(), creds);
	}

	private static boolean isAuthorizedUser(GHUser ghUser, BotCredentials creds) {
		if (ghUser == null) {
			return false;
		}

		return isAuthorizedUser(ghUser.getLogin(), creds);

	}

	private static boolean isAuthorizedUser(String login, BotCredentials creds) {
		if (login == null) {
			return false;
		}

		if (creds.getPathToUserIdList() == null) {
			// Default to true if no authorized list.
			return true;
		} else if (!Files.exists(creds.getPathToUserIdList()) || !Files.isReadable(creds.getPathToUserIdList())) {

			System.err.println("Path to user id list is set, but does not exist or is inaccessible: "
					+ creds.getPathToUserIdList());
			return false;

		} else {
			boolean matchFound;
			try {

				ObjectMapper om = new ObjectMapper(new YAMLFactory());

				YamlUserListRoot root = om.readValue(Utils.getTimeCachedFileContents(creds.getPathToUserIdList()),
						YamlUserListRoot.class);

				if (root.getUserIDs() == null) {
					return false;
				}

				matchFound = root.getUserIDs().stream().map(e -> e.getGithub()).filter(f -> f != null)
						.anyMatch(g -> g.equalsIgnoreCase(login));

				if (!matchFound) {
					System.err.println("Rejected user login: " + login);
				}

			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			return matchFound;
		}

	}

	private static void postError(String ownerName, String repoName, Issue issue, String actionUser,
			CommandReferenceError err, BotCredentials creds) {

		String debugMsg = ownerName + "/" + repoName + "/" + issue.getNumber();

		String errMsg = err.getErrMsg().orElse("A generic error occured. :thinking:");

		String msg = "@" + actionUser + ": " + errMsg;

		if (err.getCommand().isPresent()) {
			msg += "<details>\n";
			if (err.getUrl().isPresent()) {
				msg += "\n\nIn response to [this](" + err.getUrl().get() + "):\n";
			} else {
				msg += "\n\nIn response to this:\n";
			}

			msg += "> " + err.getCommand().get() + "\n";
			msg += "</details>\n";
		}

		if (READ_ONLY_MODE || creds.getFeatureFlags().isDisableExternalWrites()) {
			out("Skipping - Posting error message to comment: " + msg, debugMsg);
			return;
		}

		out("Posting error message to comment: " + msg, debugMsg);

		// TODO: LOWER - Implement 'Instructions for interacting with me...'
//		msg += "Instructions for interacting with me using PR comments are available here.";

		String msgFinal = msg;

		Exception exception = Utils.runWithMaxRetries(() -> {
			RepositoryService repoService = new RepositoryService(creds.getGhCreds().getTriageEGitClient());
			Repository repo = repoService.getRepository(ownerName, repoName);

			creds.getGhCreds().createComment(repo, issue.getNumber(), msgFinal);

		}, TIME_BETWEEN_RETRIES, MAX_RETRY_FAILURES);

		if (exception != null) {
			err("Exception thrown on post error", exception, debugMsg);
		}
	}

	/**
	 * The state of a live GitHub issue (eg from github.com/zenhub.com), as
	 * determined by 'processIssueBodyOrComment' and stored in this structure.
	 */
	public static class IssueState {
		private List<String> labels;
		private List<String> assignees;
		private boolean issueOpen;
		private List<String> pipelines;

		private List<String> newReleasesToAdd;
		private List<String> newReleasesToRemove;

		private IssueState() {
		}

		public IssueState(List<String> labels, List<String> assignees, boolean issueOpen, List<String> pipelines) {
			this.labels = labels;
			this.assignees = assignees;
			this.issueOpen = issueOpen;
			this.pipelines = pipelines;
			this.newReleasesToAdd = new ArrayList<>();
			this.newReleasesToRemove = new ArrayList<>();
		}

		public void copyFrom(IssueState state) {
			this.labels = new ArrayList<>(sanitizeNull(state.labels));
			this.assignees = new ArrayList<>(sanitizeNull(state.assignees));
			this.issueOpen = state.issueOpen;
			this.pipelines = state.pipelines;
			this.newReleasesToAdd = new ArrayList<>(sanitizeNull(state.newReleasesToAdd));
			this.newReleasesToRemove = new ArrayList<>(sanitizeNull(state.newReleasesToRemove));
		}

		public IssueState deepClone() {
			IssueState result = new IssueState();
			result.labels = new ArrayList<>(sanitizeNull(this.labels));
			result.assignees = new ArrayList<>(sanitizeNull(this.assignees));
			result.issueOpen = this.issueOpen;
			result.pipelines = new ArrayList<>(sanitizeNull(this.pipelines));
			result.newReleasesToAdd = new ArrayList<>(sanitizeNull(this.newReleasesToAdd));
			result.newReleasesToRemove = new ArrayList<>(sanitizeNull(this.newReleasesToRemove));
			return result;
		}

		private <T> List<T> sanitizeNull(List<T> list) {
			if (list == null) {
				return Collections.emptyList();
			}

			return list;
		}

		public List<String> getLabels() {
			return labels;
		}

		public void setLabels(List<String> labels) {
			this.labels = labels;
		}

		public List<String> getAssignees() {
			return assignees;
		}

		public void setAssignees(List<String> assignees) {
			this.assignees = assignees;
		}

		public boolean isIssueOpen() {
			return issueOpen;
		}

		public void setIssueOpen(boolean issueOpen) {
			this.issueOpen = issueOpen;
		}

		public List<String> getPipelines() {
			return pipelines;
		}

		public List<String> getNewReleasesToAdd() {
			return newReleasesToAdd;
		}

		public void setNewReleasesToAdd(List<String> newReleasesToAdd) {
			this.newReleasesToAdd = newReleasesToAdd;
		}

		public List<String> getNewReleasesToRemove() {
			return newReleasesToRemove;
		}

		public void setNewReleasesToRemove(List<String> newReleasesToRemove) {
			this.newReleasesToRemove = newReleasesToRemove;
		}

	}

	/**
	 * Generic reference to an object, for use in moving objects out of lambda
	 * expressions.
	 */
	public static class CReference<T> {
		private T ref;

		public CReference(T t) {
			this.ref = t;
		}

		public CReference() {
		}

		public T get() {
			return ref;
		}

		public void set(T ref) {
			this.ref = ref;
		}

	}

	/**
	 * Return value of processBody(...); contains an error message if the
	 * comment/body text could not be processed.
	 */
	private static class ProcessBodyReturn {
		final String errorMsg;
		final String commandLine;

		public ProcessBodyReturn(String errorMsg, String commandLine) {
			this.errorMsg = errorMsg;
			this.commandLine = commandLine;
		}

		public String getErrorMsg() {
			return errorMsg;
		}

		public String getCommandLine() {
			return commandLine;
		}

	}

	/**
	 * Return value of processAtomicIssueEntity(...); may optionally contain an
	 * error when a command could not be completed for a issue body or comment.
	 */
	private static class CommandReferenceResult {
		CommandReferenceError error;

		public CommandReferenceResult() {
		}

		public CommandReferenceResult(CommandReferenceError error) {
			this.error = error;
		}

		public Optional<CommandReferenceError> getError() {
			return Optional.ofNullable(error);
		}

	}

	/**
	 * When an error occurs, this class is returned, in order to provide error
	 * message and context around the error.
	 */
	private static class CommandReferenceError {

		private final String errMsg;
		private final String body;
		private final String url;
		private final String command;

		public CommandReferenceError(String errMsg, String body, String command, String url) {
			this.errMsg = errMsg;
			this.body = body;
			this.url = url;
			this.command = command;
		}

		@SuppressWarnings("unused")
		public Optional<String> getBody() {
			return Optional.ofNullable(body);
		}

		public Optional<String> getUrl() {
			return Optional.ofNullable(url);
		}

		public Optional<String> getCommand() {
			return Optional.ofNullable(command);
		}

		public Optional<String> getErrMsg() {
			return Optional.ofNullable(errMsg);
		}
	}

	/**
	 * A reference to either an issue description, or a comment on an issue. These
	 * are both locations that commands can be specified.
	 */
	private static class CommandReference {
		public static enum CRType {
			BODY, COMMENT
		};

		private final CRType type;
		private final Comment comment;
		private final Issue issue;

		public CommandReference(Issue issue) {
			this.type = CRType.BODY;
			this.issue = issue;
			this.comment = null;
		}

		public CommandReference(Comment comment) {
			this.type = CRType.COMMENT;
			this.comment = comment;
			this.issue = null;
		}

		public CRType getType() {
			return type;
		}

		public Comment getComment() {
			return comment;
		}

		@SuppressWarnings("unused")
		public Issue getIssue() {
			return issue;
		}

	}
}
