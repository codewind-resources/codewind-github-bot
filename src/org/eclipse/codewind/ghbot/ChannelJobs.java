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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.codewind.ghbot.credentials.BotCredentials;
import org.eclipse.codewind.ghbot.db.GHDatabase;
import org.eclipse.codewind.ghbot.db.WaitListEntryJson;
import org.eclipse.codewind.ghbot.utils.FileLogger;
import org.eclipse.codewind.ghbot.utils.GHRepoCache;
import org.eclipse.codewind.ghbot.utils.GitHubRepoEvent;
import org.eclipse.codewind.ghbot.utils.Logger;

import com.githubapimirror.client.api.GHIssue;
import com.githubapimirror.client.api.GHOrganization;
import com.githubapimirror.client.api.GHRepository;
import com.githubapimirror.client.api.events.GHIssueEventLabeledUnlabeled;

import net.bis5.mattermost.model.Post;
import net.bis5.mattermost.model.PostList;

/** Jobs related to outputting to Slack or Mattermost. */
public class ChannelJobs {

	private static final String[] AREA_LIST_SORTED = new String[] { "openapi", "design", "eclipse-ide", "appsody",
			"vscode-ide", "docs" };

	private static final Logger log = Logger.getInstance();

	private static final FileLogger fileLogger = Logger.getInstance().getFileLogger();

	static void runMattermostJob(List<MMIssueStatusEntry> issuesOnChannel, BotCredentials botCreds) {

		GHRepoCache cache = new GHRepoCache(botCreds.getGhCreds().getGhamClient());

		issuesOnChannel.forEach(issueEntry -> {

			GHOrganization org = cache.getOrganization(issueEntry.getOrg());
			if (org == null) {
				System.err.println("Org not found: " + issueEntry.getOrg());
				return;
			}

			GHRepository repo = cache.getRepository(org.getName(), issueEntry.getRepo());

			GHIssue issue = repo.getIssue(issueEntry.getIssueNumber());

			if (issue == null) {
				System.err.println("Issue not found: " + issueEntry.getIssueNumber());
				return;
			}

			analyzeEventStream(issueEntry, issueEntry.getOrg(), issueEntry.getRepo(), issue,
					issueEntry.getPost().getMessage(), botCreds, cache);

		});

	}

	static void runWaitListJob(GHDatabase db, BotCredentials botCreds) {

		log.out("Running wait list job.");

		GHRepoCache cache = new GHRepoCache(botCreds.getGhCreds().getGhamClient());

		long TWO_HOURS = TimeUnit.MILLISECONDS.convert(2, TimeUnit.HOURS);

		List<WaitListEntryJson> l = db.getAllWaitList();

		for (WaitListEntryJson wlej : l) {

			GHRepository repo = cache.getRepository(wlej.getOwner(), wlej.getRepo());
			if (repo == null) {
				// Unable to retrieve repo; we are likely not connected to the network.
				return;
			}

			GHIssue issue = repo.getIssue(wlej.getIssueNumber());

			boolean isAreaPresent = getAreaLogo(issue, true, wlej.getRepo()).isPresent();

			// Wait for the area tag, or 2 hours after creation, whatever comes first
			if (isAreaPresent || System.currentTimeMillis() - issue.getCreatedAt().getTime() > TWO_HOURS) {

				String repoName = repo.getName();
				String debugMsg = "started-waiting-at: [" + new Date(wlej.getStartedWaitingTimeInMsecs())
						+ "]  created-at: [" + issue.getCreatedAt() + "] " + repoName + "/" + issue.getNumber() + " - "
						+ issue.getTitle() + " [" + issue.getHtmlUrl() + "]";

				log.out("Removed from wait list: " + debugMsg);

				String slackResult = generateMessageFromIssue(repo, issue, issue.getReporter().getLogin(), true);

				if (!issue.isClosed()) {
					botCreds.getSlackClient().postToChannel(slackResult);

					// After we post to the channel, record the severity that we posted it.
					{
						Severity previousSeverity = Severity
								.fromStringOptional(
										db.getHighestIssueSeveritySeen(repo, wlej.getIssueNumber()).orElse(null))
								.orElse(null);

						Severity currentSeverity = calculateHighestSeverityLabelSeen(issue, previousSeverity)
								.orElse(Severity.NORMAL);

						// TODO: Convert to debug.
						fileOut("* Issue " + repoName + "/" + wlej.getIssueNumber()
								+ " posted to channel with severities: '" + previousSeverity + "' '" + currentSeverity
								+ "'");

						if (currentSeverity != null) {
							db.setHighestIssueSeveritySeen(repo, wlej.getIssueNumber(), currentSeverity.getLabelName());
						}

					}

				}

				db.removeFromWaitList(repo, issue.getNumber());

			}

		}

	}

	/** Detect issues that go from normal -> hot, or hot -> severe. */
	static void runUpgradeDetectionJob(List<GitHubRepoEvent> newIssuesFromProcessRepo, GHDatabase db,
			BotCredentials botCreds) {

		if (newIssuesFromProcessRepo.size() == 0) {
			return;
		}

		log.out("Running upgrade detection job.");

		// Remove issues we have already processed, then sort ascending by creation date
		List<GitHubRepoEvent> existingProcessedIssues = newIssuesFromProcessRepo.stream()
//				.filter(e -> db.isIssueProcessed(e.getRepository(), e.getGhIssue().getNumber()))
				.sorted((a, b) -> a.getGhIssue().getCreatedAt().compareTo(b.getGhIssue().getCreatedAt()))
				.collect(Collectors.toList());

		fileOut("Existing processed issues is: " + existingProcessedIssues.size());

		long threeHoursAgo = System.currentTimeMillis() - TimeUnit.MILLISECONDS.convert(3, TimeUnit.HOURS);

		existingProcessedIssues.forEach(e -> {
			GHRepository repo = e.getRepository();
			String repoName = repo.getName();

			GHIssue issue = e.getGhIssue();

			if (issue.isClosed()) {
				return;
			}

			Severity previousSeverity = Severity
					.fromStringOptional(db.getHighestIssueSeveritySeen(repo, issue.getNumber()).orElse(null))
					.orElse(null);

			Severity newSeverity = calculateHighestSeverityLabelSeen(issue, previousSeverity).orElse(null);

			String debugMsg = "[" + issue.getCreatedAt() + "] " + repoName + "/" + issue.getNumber() + " - "
					+ issue.getTitle() + " [" + issue.getHtmlUrl() + "]";

			fileOut("runUpgradeDetectionJob: " + previousSeverity + " -> " + newSeverity + "  of " + debugMsg + " ");

			if (previousSeverity == null || newSeverity == null || newSeverity == Severity.NORMAL) {
				return;
			}

			// If the severity is a downgrade, then return.
			if (previousSeverity.ordinal() >= newSeverity.ordinal()) {
				return;
			}

			boolean labeledWithinLastXHours = issue.getIssueEvents().stream()
					.filter(f -> f instanceof GHIssueEventLabeledUnlabeled).map(f -> (GHIssueEventLabeledUnlabeled) f)
					.filter(g -> g.isLabeled())
					// find all events within the last 3 hours
					.filter(g -> g.getCreatedAt() != null && g.getCreatedAt().getTime() > threeHoursAgo)
					.anyMatch(g -> g.getLabel() != null && g.getLabel().equalsIgnoreCase(newSeverity.getLabelName()));

			if (labeledWithinLastXHours) {
				log.out("Ignore issue upgrades that occur within the first X hours " + debugMsg);
				return;
			}

			String upgradeType = "";

			if (newSeverity == Severity.HOT) {
				upgradeType = "Hot "; // + slOrM(":hd-fire:", ":hot_pepper:", false);
			} else {
				upgradeType = "Stopship "; // + slOrM(":stop-2:", ":stop_sign:", false);
			}

			db.setHighestIssueSeveritySeen(repo, issue.getNumber(), newSeverity.getLabelName());

			if (botCreds.getMattermostChannel() != null) {
				String msg = generateMessageFromIssue(repo, issue, issue.getReporter().getLogin(), false);

				msg = slOrM(":arrowgreen:", ":small_red_triangle:", false) + " Upgraded to " + upgradeType + ": " + msg;
				botCreds.getMattermostChannel().createPost(msg);

				fileOut("Output to channel: " + msg);

			}

			if (botCreds.getSlackClient() != null) {
				String msg = generateMessageFromIssue(repo, issue, issue.getReporter().getLogin(), true);

				msg = slOrM(":arrowgreen:", ":small_red_triangle:", true) + " Upgraded to " + upgradeType + ": " + msg;

				// TODO: Uncomment this once feature is ready.
				// botCreds.getSlackClient().postToChannel(msg);

				fileOut("Output to channel: " + msg);

			}

		});

	}

	static void runGeneralJob(List<GHRepository> repos, List<MMIssueStatusEntry> issuesOnChannel,
			List<GitHubRepoEvent> newIssuesFromProcessRepo, GHDatabase db, BotCredentials botCreds) {

		if (newIssuesFromProcessRepo.size() == 0) {
			return;
		}

		long databaseInitDate = db.getDateDatabaseInitialized().orElse(0l);

		Map<String /* org+repo+id */, Boolean /* unused */> issuesOnMattermostChannel = new HashMap<>();
		{
			issuesOnChannel.forEach(e -> {
				String key = e.getOrg() + "/" + e.getRepo() + "/" + e.getIssueNumber();
				issuesOnMattermostChannel.put(key, true);
			});
		}

		log.out("Running general job.");

		// Remove issues we have already processed, then sort ascending by creation date
		List<GitHubRepoEvent> newIssues = newIssuesFromProcessRepo.stream()
				.filter(e -> !db.isIssueProcessed(e.getRepository(), e.getGhIssue().getNumber()))
				.sorted((a, b) -> a.getGhIssue().getCreatedAt().compareTo(b.getGhIssue().getCreatedAt()))
				.collect(Collectors.toList());

		newIssues.forEach(e -> {
			GHRepository repo = e.getRepository();
			String repoName = repo.getName();

			GHIssue issue = e.getGhIssue();

			boolean alreadyPostedOnMattermost = false;
			String key = repo.getOwnerName() + "/" + repoName + "/" + issue.getNumber();
			if (issuesOnMattermostChannel.containsKey(key)) {
				// Skip issues which are already posted in the channel.
				alreadyPostedOnMattermost = true;
			}

			String debugMsg = "[" + issue.getCreatedAt() + "] " + repoName + "/" + issue.getNumber() + " - "
					+ issue.getTitle() + " [" + issue.getHtmlUrl() + "]";

			log.out(debugMsg);

			String mmResult = generateMessageFromIssue(repo, issue, issue.getReporter().getLogin(), false);

			db.addIssueToProcessed(repo, issue.getNumber());

			// Ignore issues created before the database was initialized.
			if (issue.getCreatedAt().getTime() < databaseInitDate || databaseInitDate == 0) {
				return;
			}

			if (!alreadyPostedOnMattermost && botCreds.getMattermostChannel() != null) {
				botCreds.getMattermostChannel().createPost(mmResult);
			}

			if (issue.isClosed()) {
				return;
			}

			log.out("Added to wait list: " + debugMsg);

			db.addToWaitList(repo, issue.getNumber());

		});

	}

	static List<MMIssueStatusEntry> getAllIssuePostsOnChannel(BotCredentials creds) {

		if (creds.getMattermostCreds() == null) {
			return Collections.emptyList();
		}

		Map<String /* org+repo+issue */, List<MMIssueStatusEntry>> issuesMap = new HashMap<>();

		List<MMIssueStatusEntry> result = new ArrayList<>();

		PostList pl = creds.getMattermostChannel().getRecentPosts();
		for (String order : pl.getOrder()) {
			Post p = pl.getPosts().get(order);

			String msg = p.getMessage();

			if (p.getUserId() == null || !p.getUserId().equals(creds.getMattermostCreds().getUser().getId())) {
				continue;
			}

			int addressIndex = msg.indexOf("https://");
			if (addressIndex == -1) {
				continue;
			}

			int addressEnd = msg.indexOf(")", addressIndex);
			if (addressEnd == -1) {
				continue;
			}

//			log.out("channel post: " + msg + " " + p.getId() + " " + p.getCreateAt() + " " + p.getEditAt() + " "
//					+ p.getUpdateAt() + " " + p.getParentId());

			try {
				String url = msg.substring(addressIndex, addressEnd).trim();
				String[] urlArr = url.split(Pattern.quote("/"));

				int issueNumber = Integer.parseInt(urlArr[urlArr.length - 1]);
				String repoName = urlArr[urlArr.length - 3];
				String orgName = urlArr[urlArr.length - 4];

				String key = orgName + "/" + repoName + "/" + issueNumber;

				MMIssueStatusEntry ise = new MMIssueStatusEntry(p, orgName, repoName, issueNumber);

				List<MMIssueStatusEntry> l = issuesMap.computeIfAbsent(key, e -> new ArrayList<MMIssueStatusEntry>());
				l.add(ise);

			} catch (Exception e) {
				log.out("skipping " + msg + " " + e.getClass().getSimpleName());
			}

		}

		issuesMap.values().forEach(iseList -> {

			// Sort ascending by creation date.
			Collections.sort(iseList, (a, b) -> {
				long l = a.getPost().getEditAt() - b.getPost().getEditAt();
				return (int) l;
			});

			MMIssueStatusEntry lastInList = iseList.get(iseList.size() - 1);
			result.add(lastInList);
		});

		return result;

	}

	public static List<String> extractTagsFromMessage(String msg) {
		int msgTagsIndex = msg.lastIndexOf("]");

		if (msgTagsIndex == -1) {
			return Collections.emptyList();
		}

		List<String> reactions = new ArrayList<>();

		Stack<Integer> indexOfReactionStart = new Stack<>();

		for (int x = msgTagsIndex + 1; x < msg.length(); x++) {

			if (msg.charAt(x) == ':') {

				if (indexOfReactionStart.size() == 0) {
					indexOfReactionStart.add(x);
				} else {
					int start = indexOfReactionStart.pop();
					reactions.add(msg.substring(start + 1, x));
				}
			}
		}

		if (indexOfReactionStart.size() > 0) {
			throw new RuntimeException("Unbalanced message: " + msg);
		}

		return reactions;

	}

	private static void analyzeEventStream(MMIssueStatusEntry ise, String org, String repoName, GHIssue issue,
			String msg, BotCredentials botCreds, GHRepoCache cache) {

		GHRepository ghRepo = cache.getRepository(org, repoName);

		GHIssue ghIssue = ghRepo.getIssue(issue.getNumber());
		if (ghIssue == null) {
			// This occurs when an issue has moved; the Git Java client API we uses has no
			// obvious way to detect this.
			System.err.println("Ignoring: " + repoName + "/" + issue.getNumber());
			return;
		}

		String newMsg = generateMessageFromIssue(ghRepo, ghIssue, ghIssue.getReporter().getLogin(), false);

		if (!newMsg.equalsIgnoreCase(msg)) {
			Post post = ise.getPost();
			post.setMessage(newMsg);
			botCreds.getMattermostChannel().updatePost(post);

			log.out("Updating " + org + "/" + repoName + "/" + issue.getNumber());
			log.out("- From: " + msg);
			log.out("-   To: " + newMsg);
			log.out();
		}
	}

	private static String generateMessageFromIssue(GHRepository repo, GHIssue issue, String issueUser,
			final boolean forSlack) {

		List<String> issueLabels = issue.getLabels();

		boolean issue_isBug;
		boolean issue_isHot;
		boolean issue_isClosed = false;

		boolean issue_isStopShip = false;

		{
			issue_isBug = issueLabels.contains("kind/bug");
			issue_isHot = issueLabels.contains("hot") || issueLabels.contains("priority/hot");
			issue_isStopShip = issueLabels.contains("priority/stopship");
			issue_isClosed = issue.isClosed();
		}

		String titlePrefixSuffix = issue_isClosed ? slOrM("~", "~~", forSlack) : slOrM("", "**", forSlack);

		String repoName = repo.getName();

		String outputMsg;

		if (forSlack) {
			outputMsg = "*" + repoName + "/" + issue.getNumber() + "* - ";
		} else {
			outputMsg = "[" + repoName + "/" + issue.getNumber() + "](" + issue.getHtmlUrl() + ") - ";
		}

		outputMsg += titlePrefixSuffix;

		outputMsg += sanitizeTitle(issue.getTitle(), forSlack);

		outputMsg += titlePrefixSuffix;

		outputMsg += " [_" + issueUser + "_]";

		Optional<String> areaLogo = getAreaLogo(issue, forSlack, repoName);

		String logo = areaLogo.isPresent() ? areaLogo.get() : slOrM(":codewind:", ":grey_question:", forSlack);

		String postLogos = "";

		if (issue_isBug) {
			postLogos += " " + slOrM(":bug:", ":bug:", forSlack);
		}

		if (issue_isHot) {
			postLogos += " " + slOrM(":hd-fire:", ":hot_pepper:", forSlack);
		}

		if (issue_isStopShip) {
			postLogos += " " + slOrM(":stop-2:", ":stop_sign:", forSlack);
		}

		outputMsg = (logo + "  " + outputMsg).trim() + postLogos;

		if (forSlack) {
			outputMsg += " " + issue.getHtmlUrl();
		}

		return outputMsg;

	}

	private static Optional<String> getAreaLogo(GHIssue issue, boolean forSlack, String repoName) {

		String mainArea = getArea(issue.getLabels()).orElse("");

		String logo;

		String issueTitle_NoSpacesLower = issue.getTitle().replace(" ", "").replace("-", "").toLowerCase().trim();

		if (repoName.contains("website")) {
			logo = slOrM(":spider_web:", ":spider_web:", forSlack);

		} else if (mainArea.contains("intellij") || containsWholeWord(issue.getTitle(), "intellij")) {
			logo = slOrM(":intellij_idea:", ":intellij-idea-logo-2019:", forSlack);

		} else if (mainArea.contains("design") || issueTitle_NoSpacesLower.startsWith("design:")) {
			logo = slOrM(":cio-design:", ":paintbrush:", forSlack);

		} else if (repoName.contains("appsody") || mainArea.contains("appsody")
				|| containsWholeWord(issue.getTitle(), "appsody")) {
			logo = slOrM(":appsody:", ":appsody-logo-2019:", forSlack);

		} else if (repoName.contains("installer") || containsWholeWord(issue.getTitle(), "cwctl")
				|| containsWholeWord(issue.getTitle(), "cwcli")) {
			logo = slOrM(":gear:", ":gear:", forSlack);

		} else if (repoName.contains("openapi") || mainArea.contains("openapi")
				|| issueTitle_NoSpacesLower.startsWith("openapi")) {
			logo = slOrM(":openapi-logo-2019:", ":openapi-logo:", forSlack);

		} else if (mainArea.contains("vscode") || repoName.contains("vscode")
				|| containsWholeWord(issue.getTitle(), "vscode") || containsWholeWord(issue.getTitle(), "vs code")) {
			logo = slOrM(":vscode2k19:", ":vscode-logo:", forSlack);

		} else if (mainArea.contains("eclipse-ide") || repoName.contains("eclipse")
				|| containsWholeWord(issue.getTitle(), "eclipse")) {
			logo = slOrM(":eclipse-logo-2019:", ":eclipse-logo:", forSlack);

		} else if (repoName.contains("-che-") || containsWholeWord(issue.getTitle(), "che")) {
			logo = slOrM(":che:", ":che-logo:", forSlack);

		} else if (containsWholeWord(issue.getTitle(), "performance")
				|| containsWholeWord(issue.getTitle(), "metrics")) {
			logo = slOrM(":stopwatch:", ":stopwatch:", forSlack);

		} else if (repoName.contains("-docs") || mainArea.contains("docs")
				|| ((issueTitle_NoSpacesLower.startsWith("doc") || issueTitle_NoSpacesLower.startsWith("[doc")
						|| issueTitle_NoSpacesLower.startsWith("doc:"))
						&& !issueTitle_NoSpacesLower.contains("docker"))) {
			logo = ":books:";

		} else if (containsWholeWord(issue.getTitle(), "odo")) {
			logo = slOrM(":openshift:", ":openshift-logo-2019:", forSlack);

		} else if (mainArea.length() > 0) {
			logo = ":codewind:";

		} else {
			logo = null;
		}

		return Optional.ofNullable(logo);

	}

	private static boolean containsWholeWord(String str, String word) {

		// Convert all non-spaces to spaces
		str = str.chars().mapToObj(c -> (char) c).map(e -> Character.isLetterOrDigit(e) ? e : ' ')
				.map(e -> Character.toString(e)).reduce((a, b) -> a + b).get();

		str = str.toLowerCase();

		word = word.toLowerCase();

		return str.startsWith(word + " ") || str.contains(" " + word + " ") || str.endsWith(" " + word);

	}

	private static Optional<String> getArea(List<String> issueLabels) {

		List<String> areasFromIssueLabels = issueLabels.stream().filter(e -> e.contains("area/"))
				.map(e -> e.substring(e.indexOf("/") + 1)).collect(Collectors.toList());

		if (areasFromIssueLabels.size() == 0) {
			return Optional.empty();
		}

		for (String area : areasFromIssueLabels) {

			for (String AREA : AREA_LIST_SORTED) {

				if (area.contains(AREA)) {
					return Optional.of(area);
				}

			}
		}

		return Optional.of(areasFromIssueLabels.get(0));

	}

	private static String slOrM(String slack, String mattermost, boolean isSlack) {
		return isSlack ? slack : mattermost;
	}

	private static String sanitizeTitle(String str, boolean isSlack) {
//		str = str.replace("/", "&#47;");
		if (!isSlack) {
			str = str.replace("[", "&#91;");
			str = str.replace("]", "&#93;");
		}

		str = str.replace("`", "");
		str = str.replace("**", "");

		// If a user includes an emoji in the GitHub title, then tweak the format
		// slightly.
		List<String> knownEmojiiToSanitize = Arrays.asList("intellij", "eclipse", "che", "windows", "svt");
		for (String knownEmoji : knownEmojiiToSanitize) {
			str = str.replaceAll("(?i)\\:" + knownEmoji + "\\:", "\\: " + knownEmoji + " \\:");
		}

		return str.trim();
	}

	public static List<GitHubRepoEvent> getFirstNewRepoEvent(GHDatabase db, BotCredentials botCreds) {

		List<Object[/* repository, issue number */]> work = new ArrayList<>();

		{
			// This prevents result from containing duplicates
			final Map<String /* repo+issue # */, Boolean/* unused */> issueAdded = new HashMap<>();

			long eventsSince = System.currentTimeMillis() - TimeUnit.MILLISECONDS.convert(4, TimeUnit.DAYS);

			GHRepoCache cache = new GHRepoCache(botCreds.getGhCreds().getGhamClient());

			// For each unprocessed event, add it to the work list
			botCreds.getGhCreds().getGhamClient().getResourceChangeEvents(eventsSince).stream().forEach(e -> {

				if (db.isResourceEventProcessed(e.getUuid())) {
					return;
				} else {
					db.setResourceEventAsProcessed(e.getUuid());
				}

				String key = e.getRepo() + "/" + e.getIssueNumber();

				if (issueAdded.containsKey(key)) {
					return;
				} else {
					issueAdded.put(key, true);
				}

				GHRepository r = cache.getRepository(e.getOwner(), e.getRepo());

				Object[] pair = new Object[] { r, e.getIssueNumber() };

				work.add(pair);

			});
		}

		List<GitHubRepoEvent> result = new ArrayList<>();

		{
			// Split GHRE by repository, into a map
			HashMap<String /* fullRepoName */, List<Object[]>> repoToEventMap = new HashMap<>();
			for (Object[] objArr : work) {
				GHRepository repo = (GHRepository) objArr[0];

				List<Object[]> l = repoToEventMap.computeIfAbsent(repo.getFullName(), e -> new ArrayList<Object[]>());

				l.add(objArr);

			}

			// For each repository, bulk acquire the issue list, and store the result in
			// issueMap
			repoToEventMap.forEach((repoFullName, eventList) -> {

				List<Integer> issuesToAcquire = eventList.stream().map(e -> (Integer) e[1]).distinct().sorted()
						.collect(Collectors.toList());

				GHRepository repo = (GHRepository) eventList.get(0)[0];
				repo.bulkListIssues(issuesToAcquire).forEach(issue -> {

					result.add(new GitHubRepoEvent(issue, repo));
				});

			});

		}

		return result;
	}

	private static void fileOut(String str) {

		log.out(str);
		if (fileLogger != null) {
			fileLogger.out(str);
		}
	}

	private static Optional<Severity> calculateHighestSeverityLabelSeen(GHIssue issue, Severity existingSeverity) {

		List<Severity> severities = issue.getLabels().stream().map(e -> Severity.fromStringOptional(e).orElse(null))
				.filter(e -> e != null).collect(Collectors.toList());

		if (existingSeverity != null) {
			severities.add(existingSeverity);
		}

		Collections.sort(severities, Severity.sortDescendingByOrdinal());

		if (severities.size() == 0) {
			return Optional.empty();
		}

		return Optional.of(severities.get(0));

	}

	private static enum Severity {
		NORMAL("priority/normal"), HOT("priority/hot"), STOPSHIP("priority/stopship");

		private final String labelName;

		private Severity(String labelName) {
			this.labelName = labelName;
		}

		public String getLabelName() {
			return labelName;
		}

		public static Optional<Severity> fromStringOptional(String str) {
			if (str == null) {
				return Optional.empty();
			}
			return Arrays.asList(Severity.values()).stream().filter(e -> str.equals(e.getLabelName())).findFirst();
		}

		@SuppressWarnings("unused")
		public static Severity fromString(String str) {
			return fromStringOptional(str).get();
		}

		public static Comparator<Severity> sortDescendingByOrdinal() {
			return (a, b) -> { // Sort descending by ordinal
				return b.ordinal() - a.ordinal();
			};
		}

	}

	/**
	 * The 'getAllIssuePostsOnChannel' function will read from the chat from a
	 * Mattermost channel and parse the lines of chat into issue notifications.
	 * Those parsed values are stored in this structure.
	 */
	public static class MMIssueStatusEntry {
		private final Post post;
		private final String org;
		private final String repo;
		private final int issueNumber;

		public MMIssueStatusEntry(Post post, String org, String repo, int issueNumber) {
			this.post = post;
			this.org = org;
			this.repo = repo;
			this.issueNumber = issueNumber;
		}

		public String getOrg() {
			return org;
		}

		public String getRepo() {
			return repo;
		}

		public int getIssueNumber() {
			return issueNumber;
		}

		public Post getPost() {
			return post;
		}

	}

}
