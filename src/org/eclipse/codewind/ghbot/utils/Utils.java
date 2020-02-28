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

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import com.githubapimirror.client.api.GHIssue;
import com.githubapimirror.client.api.GHRepository;

/** Various standalone utilities used by the codebase. */
public class Utils {

	private static final Logger log = Logger.getInstance();

	private static JobUtil jobUtil = new JobUtil();
	private static TimedFileCache timedFileCache = new TimedFileCache(60 * 1000);

	/** Keep running the given runnable until it no longer throws an exception */
	public static void runUntilSuccess(RunnableWithException r, long delayOnFailure) {

		outer: while (true) {

			try {
				r.run();
				break outer;
			} catch (Exception e) {
				System.err.println("Exception occurred, waiting " + delayOnFailure + " msecs");
				e.printStackTrace();
				sleep(delayOnFailure);
			}

		}

	}

	/** Keep running the given runnable until it no longer throws an exception */
	public static Exception runWithMaxRetries(RunnableWithException r, long delayOnFailure, int maxAttempts) {

		Exception lastException = null;
		int attempts = 1;

		outer: while (attempts <= maxAttempts) {

			try {
				r.run();
				lastException = null;
				break outer;
			} catch (Exception e) {
				lastException = e;
				System.err.println("Exception occurred, waiting " + delayOnFailure + " msecs");
				e.printStackTrace();
				sleep(delayOnFailure);
			}

			attempts++;
		}

		return lastException;
	}

	public static void sleep(long timeToWaitInMsecs) {
		try {
			Thread.sleep(timeToWaitInMsecs);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	/** A version of 'Runnable' that throws a checked exception. */
	public interface RunnableWithException {
		public void run() throws Exception;
	}

	public static void throwAsUnchecked(Exception e) {
		if (e instanceof RuntimeException) {
			throw (RuntimeException) e;
		} else {
			throw new RuntimeException(e);
		}
	}

	public static JobUtil jobUtil() {
		return jobUtil;
	}

	public static Triplet triplet(GHRepository repo, GHIssue issue, Object obj) {
		return new Triplet(repo, issue, obj);
	}

	public static String getTimeCachedFileContents(Path path) throws IOException {
		return timedFileCache.getFileContents(path);
	}

	/** A generic [repo, issue, generic object] tuple */
	public static class Triplet {

		private final GHRepository repo;

		private final GHIssue issue;
		private final Object obj;

		public Triplet(GHRepository repo, GHIssue issue, Object obj) {
			super();
			this.repo = repo;
			this.issue = issue;
			this.obj = obj;
		}

		public GHIssue getIssue() {
			return issue;
		}

		public Object getObj() {
			return obj;
		}

		public GHRepository getRepo() {
			return repo;
		}
	}

	/** Ensure that a job (generic Runnable) is run at most every X seconds. */
	public static class JobUtil {

		private final HashMap<String, Long /* time last run in nanos */> lastRunInNanos_synch = new HashMap<>();

		private JobUtil() {
		}

		public void run(String jobName, long intervalInMsecs, Runnable r) {

			synchronized (lastRunInNanos_synch) {
				Long elapsedTimeInMsecs = TimeUnit.MILLISECONDS.convert(
						System.nanoTime() - lastRunInNanos_synch.getOrDefault(jobName, 0l), TimeUnit.NANOSECONDS);

				if (elapsedTimeInMsecs < intervalInMsecs) {
					return;
				}
				lastRunInNanos_synch.put(jobName, System.nanoTime());

			}

			log.out("Running " + jobName);

			r.run();
		}
	}

}
