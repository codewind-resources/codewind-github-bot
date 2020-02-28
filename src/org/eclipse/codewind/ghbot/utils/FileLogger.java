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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Optionally one may call this class (rather than the standard logger), to
 * write the log statement to a file. This is useful in some niche cases (mainly
 * for maintaining a verbose always-on log for debugging hard-to-reproduce
 * bugs).
 */
public class FileLogger {

	private static final SimpleDateFormat PRETTY_DATE_FORMAT = new SimpleDateFormat("MMM d h:mm:ss.SSS a");

	private final List<FLEntry> entries_synch = new ArrayList<>();

	private final FLThread thread;

	private final Path path;

	private boolean pathOutput_synch_entries = false;

	public FileLogger(Path outputFile) {
		this.path = outputFile;
		thread = new FLThread(outputFile);
		thread.start();
	}

	public void out(String str) {
		add(str, false);
	}

	public void err(String str) {
		add(str, true);
	}

	private void add(String str, boolean stdErr) {

		str = "" + (PRETTY_DATE_FORMAT.format(new Date())) + " " + str;

		FLEntry e = new FLEntry(str, stdErr);

		synchronized (entries_synch) {

			if (!pathOutput_synch_entries) {
				pathOutput_synch_entries = true;
				System.out.println("Logging to " + path);
			}

			entries_synch.add(e);
			entries_synch.notify();
		}

	}

	/**
	 * This is the thread that is actually responsible for writing to the output log
	 * file. We maintain this as a separate thread in order to keep I/O writing off
	 * the calling thread.
	 */
	private class FLThread extends Thread {

		private final OutputStream os;

		public FLThread(Path outputFile) {
			setDaemon(true);
			setName(FLThread.class.getName());

			try {
				if (Files.exists(outputFile)) {
					os = Files.newOutputStream(outputFile, StandardOpenOption.APPEND);
				} else {
					os = Files.newOutputStream(outputFile);
				}

			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

		}

		@Override
		public void run() {

			List<FLEntry> local = new ArrayList<>();

			while (true) {
				synchronized (entries_synch) {
					try {
						entries_synch.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}

					local.addAll(entries_synch);
					entries_synch.clear();
				}

				local.forEach(e -> {
					byte[] barr = (e.msg + "\n").getBytes();
					try {
						if (e.stderr) {
							os.write(barr);
						} else {
							os.write(barr);
						}
					} catch (IOException e1) {
						throw new UncheckedIOException(e1);
					}
				});

				try {
					os.flush();
				} catch (IOException e1) {
					throw new UncheckedIOException(e1);
				}

				local.clear();
			}
		}
	}

	/** Container for messages from out/err */
	private static class FLEntry {
		final String msg;
		final boolean stderr;

		FLEntry(String msg, boolean stderr) {
			this.msg = msg;
			this.stderr = stderr;
		}

	}
}
