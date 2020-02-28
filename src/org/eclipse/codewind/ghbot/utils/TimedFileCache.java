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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * Cache the contents of files on the file system for X seconds; after X
 * seconds, read a new version of the file and cache that.
 * 
 * Used in this project for the GitHub login list, so that changes to the login
 * list will take effect without restarting the application.
 */
public class TimedFileCache {

	private final long expireTimeInMsecs;

	private final HashMap<Path, TFCEntry> entries_synch = new HashMap<>();

	public TimedFileCache(long expireTimeInMsecsParam) {
		this.expireTimeInMsecs = expireTimeInMsecsParam;
	}

	public String getFileContents(Path path) throws IOException {

		synchronized (entries_synch) {
			cleanEntries();

			TFCEntry entry = entries_synch.get(path);

			if (entry != null) {
				return entry.contents;
			}
		}

		String contents = new String(Files.readAllBytes(path));

		synchronized (entries_synch) {
			entries_synch.put(path,
					new TFCEntry(
							System.nanoTime() + TimeUnit.NANOSECONDS.convert(expireTimeInMsecs, TimeUnit.MILLISECONDS),
							contents));

			return contents;

		}

	}

	private void cleanEntries() {

		long currTimeInNanos = System.nanoTime();

		synchronized (entries_synch) {

			for (Iterator<Entry<Path, TFCEntry>> it = entries_synch.entrySet().iterator(); it.hasNext();) {

				Entry<Path, TFCEntry> e = it.next();

				if (e.getValue().expireTimeInNanos < currTimeInNanos) {
					it.remove();
				}

			}

		}

	}

	/**
	 * Contents of a file, and the point in time at which to remove it from the
	 * cache.
	 */
	private static class TFCEntry {

		long expireTimeInNanos;

		String contents;

		public TFCEntry(long expireTimeInNanos, String contents) {
			this.expireTimeInNanos = expireTimeInNanos;
			this.contents = contents;
		}

	}

}
