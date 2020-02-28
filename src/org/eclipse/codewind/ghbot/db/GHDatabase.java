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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.codewind.ghbot.utils.BotConstants;
import org.eclipse.codewind.ghbot.utils.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubapimirror.client.api.GHRepository;

/**
 * This class uses a given key-value store database (see constructor parameter
 * and IKVStore javadoc) to implement the queries described by each of the
 * methods of this class.
 */
public class GHDatabase {

	private final Logger log = Logger.getInstance();

	private final String KEY_DATE_DATABASE_INITIALIZED = "dateDatabaseInitialized";
	private final String KEY_LAST_CLEANUP_IN_MSECS = "lastCleanupInMsecs";

	private static final String KEY_TIME_WHEN_STATS_REPORT_JOB_LAST_RUN = "statisticsReportJobLastRun";

	private final static long ONE_DAY = TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS);

	private final IKVStore db;

	public GHDatabase(IKVStore db) {
		this.db = db;

		if (!getDateDatabaseInitialized().isPresent()) {
			setDateDatabaseInitialized(System.currentTimeMillis());
		}
	}

	public Optional<Long> getDateOfLastProcessedCommand(GHRepository repo, int issueNum) {
		String key = "last-command-timestamp-" + repo.getOwnerName() + "_" + repo.getName() + "-" + issueNum;
		Optional<String> result = db.getString(key);
		if (!result.isPresent()) {
			return Optional.empty();
		}

		return Optional.of(Long.parseLong(result.get()));
	}

	public void setDateOfLastProcessedCommand(GHRepository repo, int issueNum, long timestamp) {
		String key = "last-command-timestamp-" + repo.getOwnerName() + "_" + repo.getName() + "-" + issueNum;

		// Sanity test that the LCT never goes backwards.
		db.getString(key).filter(e -> e != null).ifPresent(e -> {
			try {
				Long val = Long.parseLong(e);
				if (val > timestamp) {
					System.err.println("Error: Attempt to set a database value that was OLDER than the current value.");
					Thread.dumpStack();
				}

			} catch (NumberFormatException ex) {
				ex.printStackTrace();
			}
		});

		db.persistString(key, Long.toString(timestamp));
	}

	public Optional<Long> getDateDatabaseInitialized() {
		Optional<String> result = db.getString(KEY_DATE_DATABASE_INITIALIZED);

		if (result.isPresent()) {
			return Optional.of(Long.parseLong(result.get()));
		}

		return Optional.empty();
	}

	public void setDateDatabaseInitialized(long date) {
		db.persistString(KEY_DATE_DATABASE_INITIALIZED, Long.toString(date));
	}

	public boolean isIssueProcessed(GHRepository repo, int issueNumber) {
		String key = "processed-" + repo.getOwnerName() + "_" + repo.getName() + "-" + issueNumber;
		Optional<String> result = db.getString(key);
		return result.isPresent();

	}

	public Optional<String> getHighestIssueSeveritySeen(GHRepository repo, int issueNumber) {
		String key = "highest-issue-severity-" + repo.getOwnerName() + "_" + repo.getName() + "-" + issueNumber;
		Optional<String> result = db.getString(key);
		return result;
	}

	public void setHighestIssueSeveritySeen(GHRepository repo, int issueNumber, String severity) {
		String key = "highest-issue-severity-" + repo.getOwnerName() + "_" + repo.getName() + "-" + issueNumber;
		db.persistString(key, severity);
	}

	public void addIssueToProcessed(GHRepository repo, int issueNumber) {
		String key = "processed-" + repo.getOwnerName() + "_" + repo.getName() + "-" + issueNumber;
		db.persistString(key, Long.toString(System.currentTimeMillis()));
	}

	public void setResourceEventAsProcessed(String uuid) {
		String key = "resource-event-" + uuid;

		db.persistString(key, Long.toString(System.currentTimeMillis()));
	}

	public boolean isResourceEventProcessed(String uuid) {
		String key = "resource-event-" + uuid;
		Optional<String> result = db.getString(key);
		return result.isPresent();
	}

	public void addToWaitList(GHRepository repo, int issueNumber) {
		WaitListEntryJson wlej = new WaitListEntryJson();
		wlej.setIssueNumber(issueNumber);
		wlej.setRepo(repo.getName());
		wlej.setStartedWaitingTimeInMsecs(System.currentTimeMillis());
		wlej.setOwner(repo.getOwnerName());

		ObjectMapper om = new ObjectMapper();

		String key = "wait-list-" + repo.getName() + "_" + issueNumber;

		try {
			db.persistString(key, om.writeValueAsString(wlej));
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e); // Convert to unchecked
		}
	}

	public void removeFromWaitList(GHRepository repo, int issueNumber) {
		String key = "wait-list-" + repo.getName() + "_" + issueNumber;
		db.removeByKey(key);
	}

	public List<WaitListEntryJson> getAllWaitList() {
		ObjectMapper om = new ObjectMapper();

		return db.getKeysByPrefix("wait-list-").stream().map(e -> db.getString(e).orElse(null)).filter(e -> e != null)
				.map(e -> {
					try {
						return om.readValue(e, WaitListEntryJson.class);
					} catch (IOException e1) {
						throw new UncheckedIOException(e1);
					}
				}).collect(Collectors.toList());

	}

	public Optional<Long> getLastStatisticsReportJobRun() {
		Optional<String> o = db.getString(KEY_TIME_WHEN_STATS_REPORT_JOB_LAST_RUN);
		if (o.isPresent()) {
			return Optional.of(Long.parseLong(o.get()));
		} else {
			return Optional.empty();
		}

	}

	public void setLastStatisticsReportJobRun(long timeInMsecs) {
		db.persistString(KEY_TIME_WHEN_STATS_REPORT_JOB_LAST_RUN, Long.toString(timeInMsecs));
	}

	public void cleanOldEntriesIfApplicable() {

		// Run at most once per day

		String lastCleanup = db.getString(KEY_LAST_CLEANUP_IN_MSECS).orElse(null);

		if (lastCleanup != null && System.currentTimeMillis() - Long.parseLong(lastCleanup) <= ONE_DAY) {
			return;
		}

		log.out("Running database cleanup.");

		db.persistString(KEY_LAST_CLEANUP_IN_MSECS, Long.toString(System.currentTimeMillis()));

		long expireTimeInMsecs = System.currentTimeMillis() - TimeUnit.MILLISECONDS.convert(14, TimeUnit.DAYS);

		// Remove old resource-events based on their long body
		db.getKeysByPrefix("resource-event-").stream().map(e -> new String[] { e, db.getString(e).orElse(null) })
				.filter(e -> e[1] != null).filter(e -> Long.parseLong(e[1]) < expireTimeInMsecs).forEach(e -> {
					System.out.println("- Deleting " + e[0]);
					db.removeByKey(e[0]);
				});

		long lastCommandExpirationInMsecs = System.currentTimeMillis()
				- TimeUnit.MILLISECONDS.convert(BotConstants.PROCESS_COMMANDS_LESS_THAN_X_DAYS_OLD, TimeUnit.DAYS);

		// Remove old last command timestamp based on their long body
		db.getKeysByPrefix("last-command-timestamp-").stream()
				.map(e -> new String[] { e, db.getString(e).orElse(null) }).filter(e -> e[1] != null)
				.filter(e -> Long.parseLong(e[1]) < lastCommandExpirationInMsecs).forEach(e -> {
					System.out.println("- Deleting " + e[0]);
					db.removeByKey(e[0]);
				});

		log.out("Database cleanup complete.");
	}
}
