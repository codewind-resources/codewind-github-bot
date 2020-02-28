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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.codewind.ghbot.utils.Logger;

import com.google.gson.Gson;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * GitHub has a 'triage' team role which is less powerful than a full committer,
 * but this is what we can get from Eclipse org webmasters.
 * 
 * However, to use triage access, one cannot use either of the existing GitHub
 * Java Client APIs (EGit/Kohsuke GH) due to them using non-triage supported
 * APIs.
 * 
 * So, here we manually create Web requests to the GitHub API for the
 * triage-supported actions.
 * 
 */
public class GitHubTriageAPI {

	private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient client;

	private static final Logger log = Logger.getInstance();

	private final String username, password;

	public GitHubTriageAPI(String username, String password) {
		this.client = generateClient(username, password);
		this.username = username;
		this.password = password;

		log.out("- Using GitHubTriage API credential username: " + username + ", password: [... " + password.length()
				+ " characters ...]");

	}

	public void addAssignees(String orgOrUser, String repo, int issue, List<String> assignees) throws IOException {
		Map<String, List<String>> jsonBody = new HashMap<>();
		jsonBody.put("assignees", assignees);

		String body = new Gson().toJson(jsonBody);

		Request req = new Request.Builder()
				.url("https://api.github.com/repos/" + orgOrUser + "/" + repo + "/issues/" + issue + "/assignees")
				.post(RequestBody.create(MEDIA_TYPE_JSON, body))
				.addHeader("Authorization", Credentials.basic(username, password)).build();

		Response response = client.newCall(req).execute();
		if (response.isSuccessful()) {
			outputResponse(response);
			response.close();
		} else {
			reportFailure(response);
		}

	}

	public void removeAssignees(String orgOrUser, String repo, int issue, List<String> assignees) throws IOException {
		Map<String, List<String>> jsonBody = new HashMap<>();
		jsonBody.put("assignees", assignees);

		String body = new Gson().toJson(jsonBody);

		Request req = new Request.Builder()
				.url("https://api.github.com/repos/" + orgOrUser + "/" + repo + "/issues/" + issue + "/assignees")
				.delete(RequestBody.create(MEDIA_TYPE_JSON, body))
				.addHeader("Authorization", Credentials.basic(username, password)).build();

		Response response = client.newCall(req).execute();
		if (response.isSuccessful()) {
			outputResponse(response);
			response.close();
		} else {
			reportFailure(response);
		}

	}

	public void removeLabels(String orgOrUser, String repo, int issue, List<String> labels) throws IOException {

		labels.forEach(label -> {
			Request req = new Request.Builder().url(
					"https://api.github.com/repos/" + orgOrUser + "/" + repo + "/issues/" + issue + "/labels/" + label)
					.addHeader("Authorization", Credentials.basic(username, password)).delete().build();

			Response response;
			try {
				response = client.newCall(req).execute();
				if (response.isSuccessful()) {
					outputResponse(response);
					response.close();
				} else {
					reportFailure(response);
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

		});

	}

	public void addLabels(String orgOrUser, String repo, int issue, List<String> labels) throws IOException {

		Map<String, List<String>> jsonBody = new HashMap<>();
		jsonBody.put("labels", labels);

		String body = new Gson().toJson(jsonBody);

		Request req = new Request.Builder()
				.url("https://api.github.com/repos/" + orgOrUser + "/" + repo + "/issues/" + issue + "/labels")
				.post(RequestBody.create(MEDIA_TYPE_JSON, body))
				.addHeader("Authorization", Credentials.basic(username, password)).build();

		Response response = client.newCall(req).execute();
		if (response.isSuccessful()) {
			outputResponse(response);
			response.close();

		} else {
			reportFailure(response);
		}

	}

	private void outputResponse(Response r) {
		ResponseBody body = r.body();

		log.out("");
		log.out("Message: " + r.message());
		try {
			log.out("Body: " + body.string());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		body.close();

		log.out("Headers: ");
		r.headers().toMultimap().entrySet().stream().filter(e -> e.getKey().contains("ratelimit")).forEach(e -> {
			log.out(e.getKey() + " -> " + e.getValue());
		});
		log.out();

	}

	private void reportFailure(Response r) {

		log.err("HTTP Response failed:");
		outputResponse(r);
		r.close();

		throw new RuntimeException("Request failed.");
	}

	private static OkHttpClient generateClient(String username, String password) {
		OkHttpClient client = new OkHttpClient.Builder()
//				.authenticator(new Authenticator() {
//			@Override
//			public Request authenticate(Route route, Response response) throws IOException {
//				if (response.request().header("Authorization") != null) {
//					log.out("Returning null authentication request.");
//					return null;
//				}
//
//				String credential = Credentials.basic(username, password);
//				log.out("- Authenticator: Triage API credential username: " + username + ", password: [... "
//						+ password.length() + " characters ...]");
//				return response.request().newBuilder().header("Authorization", credential).build();
//			}
//		})
				.build();

		return client;
	}

}
