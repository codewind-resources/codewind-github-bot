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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.codewind.ghbot.credentials.BotCredentials;

import com.githubapimirror.client.api.GHIssue;
import com.githubapimirror.client.api.GHRepository;

/**
 * The code in this job runs every X days (eg 3) and generates a report
 * containing various statistics (see below) about all the monitored
 * repositories.
 * 
 * The result of this job are then output separately to Slack/Mattermost by the
 * calling method.
 */
public class StatisticsReportJob {

	public static void runStatisticsReportJob(List<GHRepository> repos, BotCredentials botCreds) {

		SRJReport report = runJob(repos);

		if (botCreds.getMattermostChannel() != null) {

			List<String> messages = generateStatisticsReportMessage(report, false);
			messages.forEach(msg -> {
				botCreds.getMattermostChannel().createPost(msg);
			});
		}

		if (botCreds.getSlackClient() != null) {
			List<String> messages = generateStatisticsReportMessage(report, true);
			messages.forEach(msg -> {
				botCreds.getSlackClient().postToChannel(msg);
			});

		}
	}

	private static List<String> generateStatisticsReportMessage(SRJReport report, boolean forSlack) {

		long averageBugAge = report.getTotalAgeOfOpenBugsInDays() / report.getOpenBugs();

		long averageIssueAge = report.getTotalAgeOfOpenIssuesInDays() / report.getOpenIssues();

		String bold = slOrM("*", "**", forSlack);

		String bugLogo = " " + slOrM(":bug:", ":bug:", forSlack);
		String issueLogo = " " + slOrM(":spiral_note_pad:", ":blue_book:", forSlack);
		String hotLogo = " " + slOrM(":hd-fire:", ":hot_pepper:", forSlack);
		String stopshipLogo = " " + slOrM(":stop-2:", ":stop_sign:", forSlack);

		String calendarLogo = " " + slOrM(":calendar:", ":calendar:", forSlack);

		String bugs = "@Open bugs@: " + report.getOpenBugs() + bugLogo + ".  @Average bug age@: " + averageBugAge
				+ " days" + calendarLogo + ".  @Oldest bug@: " + report.getOldestOpenBug() + " days" + calendarLogo
				+ ".";

		String issues = "@Open issues@: " + report.getOpenIssues() + issueLogo + ".  @Average issue age@: "
				+ averageIssueAge + " days" + calendarLogo + ".  @Oldest issue@: " + report.getOldestOpenIssue()
				+ " days" + calendarLogo + ".";

		String hot = "@Open hot@: " + report.getOpenHot() + hotLogo + ".  @Open stopship@: " + report.getOpenStopShip()
				+ stopshipLogo + ".";

		String line = slOrM(":cw-logo-orange: :cw-logo-green: *_Status_* :codewind: :cw-logo-purple:\n", "\n--------\n",
				forSlack);

		return Arrays.asList(Arrays.asList(line, bugs, issues, hot, slOrM("", line, forSlack)).stream()
				.map(e -> e.replace("@", bold) + "\n").reduce((a, b) -> a + b).get());

	}

	private static String slOrM(String slack, String mattermost, boolean isSlack) {
		return isSlack ? slack : mattermost;
	}

	private static SRJReport runJob(List<GHRepository> repos) {

		int openBugs = 0;

		int openIssues = 0;

		long totalAgeOfOpenIssuesInDays = 0;

		long totalAgeOfOpenBugsInDays = 0;

		long oldestOpenBug = -1;

		long oldestOpenIssue = -1;

		int openHot = 0;

		int openStopShip = 0;

		for (GHRepository repo : repos) {

			for (GHIssue issue : repo.bulkListIssues()) {

				if (issue.isClosed() || issue.isPullRequest()) {
					continue;
				}

				long ageInMsecs = System.currentTimeMillis() - issue.getCreatedAt().getTime();
				long ageInDays = TimeUnit.DAYS.convert(ageInMsecs, TimeUnit.MILLISECONDS);

				if (issue.getLabels().stream().anyMatch(e -> e.equalsIgnoreCase("kind/bug"))) {
					openBugs++;

					totalAgeOfOpenBugsInDays += ageInDays;

					if (oldestOpenBug < ageInDays) {
						oldestOpenBug = ageInDays;
					}
				} else {
					openIssues++;

					if (issue.getCreatedAt() != null) {
						totalAgeOfOpenIssuesInDays += ageInDays;
					}

					if (oldestOpenIssue < ageInDays) {
						oldestOpenIssue = ageInDays;
					}
				}

				if (issue.getLabels().stream().anyMatch(e -> e.toLowerCase().contains("priority/stopship"))) {
					openStopShip++;
				}

				if (issue.getLabels().stream().anyMatch(e -> e.toLowerCase().contains("priority/hot"))) {
					openHot++;
				}

			}

		}

//		System.out.println(openBugs + " " + (totalAgeOfOpenBugsInDays / openBugs) + " " + oldestOpenBug);
//		System.out.println(openIssues + " " + (totalAgeOfOpenIssuesInDays / openIssues) + " " + oldestOpenIssue);
//		System.out.println(openStopShip + " " + openHot);

		SRJReport result = new SRJReport();

		result.setOpenBugs(openBugs);
		result.setOpenIssues(openIssues);

		result.setTotalAgeOfOpenBugsInDays(totalAgeOfOpenBugsInDays);
		result.setTotalAgeOfOpenIssuesInDays(totalAgeOfOpenIssuesInDays);

		result.setOpenStopShip(openStopShip);
		result.setOpenHot(openHot);

		result.setOldestOpenIssue(oldestOpenIssue);
		result.setOldestOpenBug(oldestOpenBug);

		return result;

	}

	/** Repository statistics report result */
	private static class SRJReport {
		int openBugs = 0;

		int openIssues = 0;

		long totalAgeOfOpenIssuesInDays = 0;

		long totalAgeOfOpenBugsInDays = 0;

		long oldestOpenBug = -1;

		long oldestOpenIssue = -1;

		int openHot = 0;

		int openStopShip = 0;

		public int getOpenBugs() {
			return openBugs;
		}

		public void setOpenBugs(int openBugs) {
			this.openBugs = openBugs;
		}

		public int getOpenIssues() {
			return openIssues;
		}

		public void setOpenIssues(int openIssues) {
			this.openIssues = openIssues;
		}

		public long getTotalAgeOfOpenIssuesInDays() {
			return totalAgeOfOpenIssuesInDays;
		}

		public void setTotalAgeOfOpenIssuesInDays(long totalAgeOfOpenIssuesInDays) {
			this.totalAgeOfOpenIssuesInDays = totalAgeOfOpenIssuesInDays;
		}

		public long getTotalAgeOfOpenBugsInDays() {
			return totalAgeOfOpenBugsInDays;
		}

		public void setTotalAgeOfOpenBugsInDays(long totalAgeOfOpenBugsInDays) {
			this.totalAgeOfOpenBugsInDays = totalAgeOfOpenBugsInDays;
		}

		public long getOldestOpenBug() {
			return oldestOpenBug;
		}

		public void setOldestOpenBug(long oldestOpenBug) {
			this.oldestOpenBug = oldestOpenBug;
		}

		public long getOldestOpenIssue() {
			return oldestOpenIssue;
		}

		public void setOldestOpenIssue(long oldestOpenIssue) {
			this.oldestOpenIssue = oldestOpenIssue;
		}

		public int getOpenHot() {
			return openHot;
		}

		public void setOpenHot(int openHot) {
			this.openHot = openHot;
		}

		public int getOpenStopShip() {
			return openStopShip;
		}

		public void setOpenStopShip(int openStopShip) {
			this.openStopShip = openStopShip;
		}

	}
}
