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

package org.eclipse.codewind.ghbot.yaml;

import static org.eclipse.codewind.ghbot.yaml.YamlUtils.assertNonEmptyInYaml;

import java.util.ArrayList;
import java.util.List;

/** YAML representation of bot configuration file. */
public class YamlCredentialsRoot {

	YamlGitHubActorCredentials github;

	YamlMattermostCredentials mattermost;

	YamlZenHubCredentials zenhub;
	YamlGHAM gham;

	String databasePath;

	String slackWebhook;

	String authFile;

	List<String> repoList = new ArrayList<>();

	List<String> featureFlags = new ArrayList<>();

	public void validate() {

		if (github != null) {
			github.validate();
		}

		if (mattermost != null) {
			mattermost.validate();
		}

		if (zenhub == null) {
			throw new RuntimeException("ZenHub credentials were not specified.");
		} else {
			zenhub.validate();
		}

		assertNonEmptyInYaml("databasePath", databasePath);

		if (gham == null) {
			throw new RuntimeException("Required GHAM values were not specified.");
		} else {
			gham.validate();
		}

	}

	public YamlGitHubActorCredentials getGithub() {
		return github;
	}

	public void setGithub(YamlGitHubActorCredentials github) {
		this.github = github;
	}

	public YamlMattermostCredentials getMattermost() {
		return mattermost;
	}

	public void setMattermost(YamlMattermostCredentials mattermost) {
		this.mattermost = mattermost;
	}

	public YamlZenHubCredentials getZenhub() {
		return zenhub;
	}

	public void setZenhub(YamlZenHubCredentials zenhub) {
		this.zenhub = zenhub;
	}

	public String getDatabasePath() {
		return databasePath;
	}

	public void setDatabasePath(String databasePath) {
		this.databasePath = databasePath;
	}

	public String getSlackWebhook() {
		return slackWebhook;
	}

	public void setSlackWebhook(String slackWebhook) {
		this.slackWebhook = slackWebhook;
	}

	public String getAuthFile() {
		return authFile;
	}

	public void setAuthFile(String authFile) {
		this.authFile = authFile;
	}

	public YamlGHAM getGham() {
		return gham;
	}

	public void setGham(YamlGHAM gham) {
		this.gham = gham;
	}

	public List<String> getRepoList() {
		return repoList;
	}

	public void setRepoList(List<String> repoList) {
		this.repoList = repoList;
	}

	public List<String> getFeatureFlags() {
		return featureFlags;
	}

	public void setFeatureFlags(List<String> featureFlags) {
		this.featureFlags = featureFlags;
	}

}
