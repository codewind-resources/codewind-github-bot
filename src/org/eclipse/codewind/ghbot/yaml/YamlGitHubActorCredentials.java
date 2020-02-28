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

/**
 * YAML representation of the username and password for the user that will be
 * updating issues in Github.
 */
public class YamlGitHubActorCredentials {

	String username;

	String password;

	YamlGitHubActorTriageCredentials triageRole;

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public YamlGitHubActorTriageCredentials getTriageRole() {
		return triageRole;
	}

	public void setTriageRole(YamlGitHubActorTriageCredentials triageRole) {
		this.triageRole = triageRole;
	}

	public void validate() {
		assertNonEmptyInYaml("username", username);
		assertNonEmptyInYaml("password", password);

		if (triageRole != null) {
			triageRole.validate();
		}
	}

	/**
	 * GitHub has a 'triage' team role which is less powerful than a full committer,
	 * but this is what we can get from Eclipse org webmasters. Use these
	 * credentials for anything that doesn't require full fat credentials..
	 */
	public static class YamlGitHubActorTriageCredentials {
		String username;
		String password;

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public void validate() {
			assertNonEmptyInYaml("username", username);
			assertNonEmptyInYaml("password", password);
		}
	}
}
