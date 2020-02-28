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

import com.githubapimirror.client.api.GHIssue;
import com.githubapimirror.client.api.GHRepository;

/**
 * A reference to an issue in a GH repository; in the context of the method this
 * class is used, the referenced issue/repo will be one whose content has
 * changed (for example a new comment on the issue, ie an event)
 */
public class GitHubRepoEvent {

	private final GHIssue ghIssue;
	private final GHRepository repository;

	public GitHubRepoEvent(GHIssue ghIssue, GHRepository repository) {
		this.ghIssue = ghIssue;
		this.repository = repository;
	}

	public GHIssue getGhIssue() {
		return ghIssue;
	}

	public GHRepository getRepository() {
		return repository;
	}

}