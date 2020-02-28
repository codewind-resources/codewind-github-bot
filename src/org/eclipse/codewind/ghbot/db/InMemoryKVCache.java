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

/**
 * This class caches reads to a given key-value db (see IKVStore java doc).
 * 
 * The contents of this class are not persisted across process restarts. This
 * class is thread safe.
 */
public class InMemoryKVCache implements IKVStore {

	private final boolean DEBUG_PRINT_CACHE_RATE = false;

	private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

	private final Lock readLock = rwLock.readLock();
	private final Lock writeLock = rwLock.writeLock();

	/** Acquire read or write lock when accessing. */
	private final Map<String /* key */, String /* value */> map = new HashMap<>();

	private final IKVStore innerDb;

	private long cacheHitRate_synch_readLock = 0;
	private long cacheTotalRate_synch_readLock = 0;

	public InMemoryKVCache(IKVStore innerDb) {
		this.innerDb = innerDb;
	}

	@Override
	public void persistString(String key, String value) {

		try {
			writeLock.lock();

			innerDb.persistString(key, value);
			map.put(key, value);
			ensureCapacity();

		} finally {
			writeLock.unlock();
		}
	}

	@SuppressWarnings("unused")
	@Override
	public Optional<String> getString(String key) {

		boolean valueWritten = false;
		try {
			readLock.lock();
			cacheTotalRate_synch_readLock++;

			String resultStr = map.get(key);
			if (resultStr != null) {
				cacheHitRate_synch_readLock++;
				return Optional.of(resultStr);
			}

			resultStr = innerDb.getString(key).orElse(null);

			if (resultStr != null) {
				map.put(key, resultStr);
				valueWritten = true;
			}

			return Optional.ofNullable(resultStr);

		} finally {
			if (DEBUG_PRINT_CACHE_RATE && cacheTotalRate_synch_readLock > 0
					&& cacheTotalRate_synch_readLock % 100 == 0) {
				System.out.println(this.getClass().getName() + " - cache rate: "
						+ (int) (100 * cacheHitRate_synch_readLock / cacheTotalRate_synch_readLock));
			}
			readLock.unlock();

			if (valueWritten) {
				ensureCapacity(); // Do not acquire this in a read lock
			}

		}
	}

	@Override
	public boolean removeByKey(String key) {
		try {

			writeLock.lock();

			boolean result = innerDb.removeByKey(key);

			map.remove(key);

			return result;

		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public List<String> getKeysByPrefix(String prefix) {
		try {
			readLock.lock();

			return innerDb.getKeysByPrefix(prefix);

		} finally {
			readLock.unlock();
		}
	}

	private void ensureCapacity() {
		try {
			writeLock.lock();

			// Lazy solution to cache clearing, should use LRU, but this works for now
			if (map.size() > 4000) {
				map.clear();
			}

		} finally {
			writeLock.unlock();
		}
	}
}
