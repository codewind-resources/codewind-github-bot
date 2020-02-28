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

/**
 * Server URL and password for a server running GHAM
 * (https://github.com/jgwest/github-api-mirror/)
 */
public class YamlGHAM {

	String serverUrl;
	String psk;

	public String getServerUrl() {
		return serverUrl;
	}

	public void setServerUrl(String serverUrl) {
		this.serverUrl = serverUrl;
	}

	public String getPsk() {
		return psk;
	}

	public void setPsk(String psk) {
		this.psk = psk;
	}

	public void validate() {
		assertNonEmptyInYaml("serverUrl", serverUrl);
		assertNonEmptyInYaml("psk", psk);
	}
}
