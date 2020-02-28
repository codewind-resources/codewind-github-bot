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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.egit.github.core.client.GitHubClient;
import org.kohsuke.github.AbuseLimitHandler;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.RateLimitHandler;

import com.githubapimirror.client.api.GHConnectInfo;
import com.githubapimirror.client.api.GitHub;

/**
 * Maintains references to various GitHub clients: EGit, GHAM, and Kohsuke. We
 * need multiple API clients (EGit and Kohsuke) because both appear to be
 * missing different key pieces of the GitHub API.
 */
public class GitHubCredentials {

	private final com.githubapimirror.client.api.GitHub ghamClient;

	private final GitHubClient egitClient;

	private final GitHubClient triageEGitClient;

	private final org.kohsuke.github.GitHub kohGitClient;

	private final String ghUsername;
	private final String ghPassword;

	private final Optional<String> triageRoleUsername;
	private final Optional<String> triageRolePassword;

	public GitHubCredentials(String ghamUrl, String ghamPsk, String ghUsername, String ghPassword,
			String triageRoleUsername, String triageRolePassword) throws IOException {

		ghamClient = new com.githubapimirror.client.api.GitHub(new GHConnectInfo(ghamUrl, ghamPsk));

		egitClient = new GitHubClient();
		egitClient.setCredentials(ghUsername, ghPassword);

		GitHubBuilder builder = GitHubBuilder.fromEnvironment();
		builder = builder.withPassword(ghUsername, ghPassword);
		kohGitClient = builder.withRateLimitHandler(RateLimitHandler.FAIL).withAbuseLimitHandler(AbuseLimitHandler.FAIL)
				.build();

		this.ghUsername = ghUsername;
		this.ghPassword = ghPassword;

		this.triageRoleUsername = Optional.ofNullable(triageRoleUsername);
		this.triageRolePassword = Optional.ofNullable(triageRolePassword);

		triageEGitClient = new GitHubClient();
		if (this.triageRoleUsername.isPresent() && this.triageRolePassword.isPresent()) {
			triageEGitClient.setCredentials(triageRoleUsername, triageRolePassword);
		} else {
			triageEGitClient.setCredentials(ghUsername, ghPassword);
		}

	}

	public GitHub getGhamClient() {
		return ghamClient;
	}

	public GitHubClient getTriageEGitClient() {
		return triageEGitClient;
	}

	public GitHubClient getEgitClient() {
		return egitClient;
	}

	/**
	 * We use the kohsuke GitHub library to get assignees, because EGit doesn't
	 * support it.
	 */
	public List<String> getIssueAssignees(String org, String repo, int issueNumber) throws IOException {

		GHOrganization ghOrg = kohGitClient.getOrganization(org);
		GHRepository ghRepo = ghOrg.getRepository(repo);
		GHIssue ghIssue = ghRepo.getIssue(issueNumber);

		List<GHUser> users = ghIssue.getAssignees();

		if (users != null) {

			return users.stream().map(e -> e.getLogin()).collect(Collectors.toList());

		}

		return Collections.emptyList();
	}

	private GitHubTriageAPI getTriageAPI() {
		GitHubTriageAPI triageAPI;
		if (triageRoleUsername.isPresent() && triageRoleUsername.isPresent()) {
			triageAPI = new GitHubTriageAPI(triageRoleUsername.get(), triageRolePassword.get());
		} else {
			triageAPI = new GitHubTriageAPI(ghUsername, ghPassword);
		}

		return triageAPI;
	}

	public void setAssigneesWithTriage(String org, String repo, int issueNumber, List<String> assigneesToAdd,
			List<String> assigneesToRemove) throws IOException {

		GitHubTriageAPI triageAPI = getTriageAPI();

		if (!assigneesToAdd.isEmpty()) {
			triageAPI.addAssignees(org, repo, issueNumber, assigneesToAdd);
		}

		if (!assigneesToRemove.isEmpty()) {
			triageAPI.removeAssignees(org, repo, issueNumber, assigneesToRemove);
		}
	}

	public void setLabelsWithTriage(String org, String repo, int issueNumber, List<String> labelsToAdd,
			List<String> labelsToRemove) throws IOException {

		GitHubTriageAPI triageAPI = getTriageAPI();

		if (!labelsToAdd.isEmpty()) {
			triageAPI.addLabels(org, repo, issueNumber, labelsToAdd);
		}

		if (!labelsToRemove.isEmpty()) {
			triageAPI.removeLabels(org, repo, issueNumber, labelsToRemove);
		}

	}

	/** Likewise EGit doesn't support setting multiple assignees, so here we are */
	public void setIssueAssignees(String org, String repo, int issueNumber, List<String> logins) throws IOException {

		GHOrganization ghOrg = kohGitClient.getOrganization(org);
		GHRepository ghRepo = ghOrg.getRepository(repo);
		GHIssue ghIssue = ghRepo.getIssue(issueNumber);

		List<GHUser> user = new ArrayList<>();

		for (String login : logins) {
			user.add(kohGitClient.getUser(login));
		}

		ghIssue.setAssignees(user);

	}

	/**
	 * Likewise, the EGit close mechanism will cause >1 assignee to be removed, so
	 * we use non-EGit close.
	 */
	public void closeIssue(String org, String repo, int issueNumber) throws IOException {

		GHOrganization ghOrg = kohGitClient.getOrganization(org);
		GHRepository ghRepo = ghOrg.getRepository(repo);
		GHIssue ghIssue = ghRepo.getIssue(issueNumber);

		ghIssue.close();
	}
}
