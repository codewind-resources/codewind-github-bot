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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import org.eclipse.codewind.ghbot.utils.Logger;

/**
 * This database store will initialize itself using the contents of the database
 * object specified in the constructor parameter, but any subsequent calls to
 * EphemeralWritesKVStore will only read/write from memory.
 * 
 * The underlying database (constructor param) will never be written to, and
 * will no longer be read from after initialization.
 * 
 * This behaviour can be enabled in the bot using the 'ephemeralDBWrites'
 * feature flag. This is useful for testing a release before deploying it to
 * production.
 */
public class EphemeralWritesKVStore implements IKVStore {

	private static final boolean DEBUG = false;

	private final Logger log = Logger.getInstance();

	private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

	private final Lock readLock = rwLock.readLock();
	private final Lock writeLock = rwLock.writeLock();

	/** Acquire read or write lock when accessing. */
	private final Map<String /* key */, String /* value */> map = new HashMap<>();

	public EphemeralWritesKVStore(IKVStore innerDb) {

		// Copy the contents of the inner database into our map
		innerDb.getKeysByPrefix("").forEach(key -> {

			String value = innerDb.getString(key).orElse(null);

			if (value == null || value.trim().isEmpty()) {
				log.err("Database key '" + key + "' had empty value:" + value);
			}
			map.put(key, value);

		});

	}

	@Override
	public void persistString(String key, String value) {
		if (key == null || value == null) {
			throw new RuntimeException("Invalid key or value " + key + " " + value);
		}
		if (DEBUG) {
			log.out("* Write " + key + " -> " + value);
		}
		try {
			writeLock.lock();
			map.put(key, value);
		} finally {
			writeLock.unlock();
		}

	}

	@Override
	public Optional<String> getString(String key) {

		try {

			readLock.lock();

			String resultStr = map.get(key);

			if (DEBUG) {
				log.out("* Read " + key + " -> " + resultStr);
			}

			return Optional.ofNullable(resultStr);

		} finally {
			readLock.unlock();

		}
	}

	@Override
	public boolean removeByKey(String key) {
		try {

			writeLock.lock();

			boolean result = map.remove(key) != null;
			if (DEBUG) {
				log.out("* Removed" + key + ", with result " + result);
			}

			return result;

		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public List<String> getKeysByPrefix(String prefix) {
		try {

			readLock.lock();

			List<String> result = map.keySet().stream().filter(e -> e.startsWith(prefix)).collect(Collectors.toList());

			if (DEBUG) {
				log.out("* Returned keys by prefix: " + result);
			}

			return result;

		} finally {
			readLock.unlock();
		}
	}

}
