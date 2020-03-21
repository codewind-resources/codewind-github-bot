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

import static org.eclipse.codewind.ghbot.yaml.YamlUtils.assertNonEmptyInYaml;

/** YAML representation of credentials required to use ZenHub API. */
public class YamlZenHubCredentials {
	String apiKey;
	String serverUrl;

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getServerUrl() {
		return serverUrl;
	}

	public void setServerUrl(String serverUrl) {
		this.serverUrl = serverUrl;
	}

	public void validate() {

		assertNonEmptyInYaml("apiKey", apiKey);
		assertNonEmptyInYaml("serverUrl", serverUrl);
	}

}
