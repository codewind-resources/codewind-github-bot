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

import java.util.ArrayList;
import java.util.List;

/** Java representation of the user list YAML configuration file */
public class YamlUserListRoot {

	List<YamlUserListEntry> userIDs = new ArrayList<>();

	public List<YamlUserListEntry> getUserIDs() {
		return userIDs;
	}

	public void setUserIDs(List<YamlUserListEntry> userIDs) {
		this.userIDs = userIDs;
	}

	/** An entry in the user list can specify either type of id */
	public static class YamlUserListEntry {
		String github;
		String mattermost;

		public String getGithub() {
			return github;
		}

		public void setGithub(String github) {
			this.github = github;
		}

		public String getMattermost() {
			return mattermost;
		}

		public void setMattermost(String mattermost) {
			this.mattermost = mattermost;
		}

	}
}
