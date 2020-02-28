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

package org.eclipse.codewind.ghbot.yaml;

/** Misc util methods used only by YAML classes. */
public class YamlUtils {

	public static void assertNonEmptyInYaml(String field, String value) {

		if (value == null || value.trim().isEmpty()) {
			throw new RuntimeException("Value for " + field + ", '" + value + "' is empty or null.");
		}

	}
}
