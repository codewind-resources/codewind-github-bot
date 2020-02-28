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

package org.eclipse.codewind.ghbot.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.githubapimirror.client.api.GHOrganization;
import com.githubapimirror.client.api.GHRepository;
import com.githubapimirror.client.api.GitHub;

/**
 * Cache references to the org and repo, for the lifetime of the cache object.
 * 
 * NOT thread safe.
 */
public class GHRepoCache {

	private final HashMap<String /* org */, GHOrganization> orgMap = new HashMap<>();

	private final HashMap<String /* org */, Map<String, GHRepository>> reposMap = new HashMap<>();

	private final GitHub client;

	public GHRepoCache(GitHub client) {
		this.client = client;
	}

	public GHOrganization getOrganization(String orgName) {

		GHOrganization ghOrg = orgMap.get(orgName);

		if (ghOrg == null) {
			ghOrg = client.getOrganization(orgName);
			if (ghOrg == null) {
				return null;
			}

			orgMap.put(orgName, ghOrg);

			List<GHRepository> repos = ghOrg.getRepositories();
			Map<String, GHRepository> repoMap = new HashMap<>();

			repos.forEach(e -> {
				repoMap.put(e.getName(), e);
			});

			reposMap.put(orgName, repoMap);

		}

		return ghOrg;

	}

	public GHRepository getRepository(String orgName, String repoName) {

		GHOrganization org = getOrganization(orgName);
		if (org == null) {
			return null;
		}

		Map<String, GHRepository> m = reposMap.get(orgName);

		return m.get(repoName);
	}
}
