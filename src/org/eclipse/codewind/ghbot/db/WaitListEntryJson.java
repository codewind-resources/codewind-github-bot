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

package org.eclipse.codewind.ghbot.db;

/**
 * The bot job that is responsible for outputting new issues to Slack/Mattermost
 * maintains a wait list of new issues. That wait list contains issues where we
 * are not yet sure of their severity (hot/stopship) or which area they should
 * be in (ie because the 'area/' label for that issue has not yet been applied.)
 * 
 * This class is the JSON representation (which is persisted to the DB) of
 * issues that are on the wait list.
 */
public class WaitListEntryJson {
	String repo;
	int issueNumber;
	long startedWaitingTimeInMsecs;
	String owner;

	public WaitListEntryJson() {
	}

	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public String getRepo() {
		return repo;
	}

	public void setRepo(String repo) {
		this.repo = repo;
	}

	public int getIssueNumber() {
		return issueNumber;
	}

	public void setIssueNumber(int issueNumber) {
		this.issueNumber = issueNumber;
	}

	public long getStartedWaitingTimeInMsecs() {
		return startedWaitingTimeInMsecs;
	}

	public void setStartedWaitingTimeInMsecs(long startedWaitingTimeInMsecs) {
		this.startedWaitingTimeInMsecs = startedWaitingTimeInMsecs;
	}

}