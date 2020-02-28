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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import org.eclipse.codewind.ghbot.utils.BotConstants;
import org.eclipse.codewind.ghbot.utils.Utils;

/**
 * Simple key/value store that writes to the local file system. Thread safe: a
 * RWlock ensures that only one write operation occurs at a time, and ensures
 * there are no reads during a write.
 */
@SuppressWarnings("unused")
public class FileKVStore implements IKVStore {

	private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

	private final Lock readLock = rwLock.readLock();
	private final Lock writeLock = rwLock.writeLock();

	private final File outputDirectory;

	public FileKVStore(File outputDirectory) {
		this.outputDirectory = outputDirectory;
	}

	private Optional<String> readFromFile(File f) {
		try {
			readLock.lock();

			if (!f.exists()) {
				return Optional.empty();
			}

			StringBuilder sb = new StringBuilder();

			try {

				byte[] barr = new byte[1024 * 64];
				int c;

				FileInputStream fis = new FileInputStream(f);
				while (-1 != (c = fis.read(barr))) {

					sb.append(new String(barr, 0, c));
				}
				fis.close();

			} catch (IOException e) {
				System.err.println("Error from file: " + f.getPath());
				Utils.throwAsUnchecked(e);
			}

			return Optional.of(sb.toString());

		} finally {
			readLock.unlock();
		}
	}

	private void writeToFile(List<String> contents, File f) {
		StringBuilder sb = new StringBuilder();
		for (String str : contents) {
			sb.append(str);
			sb.append("\n");
		}

		writeToFile(sb.toString(), f);

	}

	private void writeToFile(String contents, File f) {

		if (BotConstants.READONLY_DATABASE) {

			String digest = "";
			if (contents != null) {
				digest = contents.substring(0, Math.min(contents.length(), 64));
			}

			return;
		}

		try {
			writeLock.lock();

			f.getParentFile().mkdirs();

			FileWriter fw = null;
			try {
				fw = new FileWriter(f);
				fw.write(contents);
				fw.close();
			} catch (IOException e) {
				Utils.throwAsUnchecked(e);
			} finally {
				if (fw != null) {
					try {
						fw.close();
					} catch (IOException e) {
						/* ignore */ }
				}
			}

		} finally {
			writeLock.unlock();
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.codewind.ghbot.IKVStore#persistString(java.lang.String,
	 * java.lang.String)
	 */
	@Override
	public void persistString(String key, String value) {
		File outputFile = new File(outputDirectory, "keys/" + key + ".txt");

//		System.out.println("* Writing to database - " + key + " -> " + value);

		writeToFile(value, outputFile);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.codewind.ghbot.IKVStore#getString(java.lang.String)
	 */
	@Override
	public Optional<String> getString(String key) {
		File inputFile = new File(outputDirectory, "keys/" + key + ".txt");
		if (!inputFile.exists()) {
			return Optional.empty();
		}

		String contents = readFromFile(inputFile).orElse(null);

		return Optional.ofNullable(contents);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.codewind.ghbot.IKVStore#removeByKey(java.lang.String)
	 */
	@Override
	public boolean removeByKey(String key) {

		try {
			writeLock.lock();

			File inputFile = new File(outputDirectory, "keys/" + key + ".txt");
			if (!inputFile.exists()) {
				return false;
			}

			return inputFile.delete();

		} finally {
			writeLock.unlock();
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.codewind.ghbot.IKVStore#getKeysByPrefix(java.lang.String)
	 */
	@Override
	public List<String> getKeysByPrefix(String prefix) {

		String fPrefix = prefix == null ? "" : prefix;

		File keysDir = new File(outputDirectory, "keys");
		if (!keysDir.exists()) {
			return Collections.emptyList();
		}

		return Arrays.asList(keysDir.listFiles()).stream().map(e -> e.getName()).filter(e -> e.startsWith(fPrefix))
				.filter(e -> e.endsWith(".txt")).map(e -> {
					int index = e.lastIndexOf(".");
					return e.substring(0, index);
				}).collect(Collectors.toList());

	}

}
