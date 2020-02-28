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

import java.util.List;
import java.util.Optional;

/**
 * A generic key/value store API, that is designed to be generic enough to be
 * implemented by either writing to the local filesystem, or to a NoSQL database
 * such as CouchDB/Cloudant (and the semantics of the API -- specifically
 * getKeysByPrefix -- ensure it is efficient to write to a key-sorted
 * B-tree-based DB like this)
 * 
 * As of this writing, there are two implementation of this interface:
 * FileKVStore, which writes to the local filesystem, and InMemoryKVCache which
 * caches access to another IKVStore implementation.
 */
public interface IKVStore {

	void persistString(String key, String value);

	Optional<String> getString(String key);

	boolean removeByKey(String key);

	List<String> getKeysByPrefix(String prefix);

}