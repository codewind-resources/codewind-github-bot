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
 * Credentials for reading from and posting to a Mattermost channel.
 */
public class YamlMattermostCredentials {

	String mattermostServer;
	String outputChannel;
	String username;
	String password;

	public String getMattermostServer() {
		return mattermostServer;
	}

	public void setMattermostServer(String mattermostServer) {
		this.mattermostServer = mattermostServer;
	}

	public String getOutputChannel() {
		return outputChannel;
	}

	public void setOutputChannel(String outputChannel) {
		this.outputChannel = outputChannel;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void validate() {
		assertNonEmptyInYaml("mattermostServer", mattermostServer);
		assertNonEmptyInYaml("outputChannel", outputChannel);
		assertNonEmptyInYaml("username", username);
		assertNonEmptyInYaml("password", password);
	}

}
