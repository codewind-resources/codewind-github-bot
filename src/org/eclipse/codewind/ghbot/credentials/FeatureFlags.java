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

package org.eclipse.codewind.ghbot.credentials;

import java.util.Collections;
import java.util.List;

/**
 * Different code paths within the bot may be triggered by specifying feature
 * flags in the configuration YAML. This allows different behaviour (for example
 * debug-only behaviour) is different environments.
 * 
 * Thread-safe.
 */
public class FeatureFlags {

	public static final String UpgradeDetection = "UpgradeDetection";
	public static final String Issue844Only = "Issue844Only";
	public static final String DisableExternalWrites = "DisableExternalWrites";
	public static final String EphemeralDBWrites = "EphemeralDBWrites";
	public static final String UseTriageAPI = "UseTriageAPI";

	private final boolean upgradeDetection;
	private final boolean issue844Only;
	private final boolean disableExternalWrites;
	private final boolean ephemeralDBWrites;
	private final boolean useTriageAPI;

	public FeatureFlags(List<String> featureFlags) {

		if (featureFlags == null) {
			featureFlags = Collections.emptyList();
		}

		boolean upgradeDetection = false;
		boolean issue844Only = false;
		boolean disableExternalWrites = false;
		boolean ephemeralDBWrites = false;
		boolean useTriageAPI = false;

		for (String flag : featureFlags) {

			flag = flag.toLowerCase().trim();

			if (flag.isEmpty()) {
				continue;
			}

			if (flag.equalsIgnoreCase(UpgradeDetection)) {
				upgradeDetection = true;
			} else if (flag.equalsIgnoreCase(Issue844Only)) {
				issue844Only = true;
			} else if (flag.equalsIgnoreCase(DisableExternalWrites)) {
				disableExternalWrites = true;
			} else if (flag.equalsIgnoreCase(EphemeralDBWrites)) {
				ephemeralDBWrites = true;
			} else if (flag.equalsIgnoreCase(UseTriageAPI)) {
				useTriageAPI = true;
			} else {
				throw new IllegalArgumentException("Error - unrecognized feature flag: " + flag);
			}

		}

		this.issue844Only = issue844Only;
		this.upgradeDetection = upgradeDetection;
		this.disableExternalWrites = disableExternalWrites;
		this.ephemeralDBWrites = ephemeralDBWrites;
		this.useTriageAPI = useTriageAPI;
	}

	public boolean isUpgradeDetection() {
		return upgradeDetection;
	}

	public boolean isIssue844Only() {
		return issue844Only;
	}

	public boolean isDisableExternalWrites() {
		return disableExternalWrites;
	}

	public boolean isEphemeralDBWrites() {
		return ephemeralDBWrites;
	}

	public boolean isUseTriageAPI() {
		return useTriageAPI;
	}
}
