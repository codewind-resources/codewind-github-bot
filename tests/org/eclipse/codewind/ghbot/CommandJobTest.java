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

package org.eclipse.codewind.ghbot;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.codewind.ghbot.CommandJob.IssueState;
import org.eclipse.codewind.ghbot.credentials.GitHubCredentials;
import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.service.UserService;
import org.junit.Test;

/**
 * Test that command job correctly updates the user list for various commands.
 */
public class CommandJobTest {

	private static final UserService us = getUserService();

	private static final String myUser = System.getProperty("username");

	private static UserService getUserService() {

		try {

			String ghamPsk = System.getProperty("psk");
			String githubActorUsername = System.getProperty("username");

			String githubActorPassword = System.getProperty("password");

			// example: "https://(hostname):9443/GitHubApiMirrorService/v1"
			String url = System.getProperty("ghamurl");

			GitHubCredentials ghCreds = new GitHubCredentials(url, ghamPsk, githubActorUsername, githubActorPassword,
					null, null);

			return new UserService(ghCreds.getEgitClient());

		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}

	}

	private List<Entry> getEntries() throws IOException {

		List<Entry> entries = new ArrayList<>();

		entries.add(new Entry(l("/assign"), l(), l(), l(), l(myUser)));
		entries.add(new Entry(l("/assign"), l(), l(), l("one"), l("one", myUser)));
		entries.add(new Entry(l("/assign"), l(), l(), l("one", "two"), l("one", "two", myUser)));

		entries.add(new Entry(l("/assign one"), l(), l(), l(), l("one")));

		entries.add(new Entry(l("/assign one"), l(), l(), l("two", "three"), l("one", "two", "three")));

		entries.add(new Entry(l("/unassign"), l(), l(), l(myUser), l()));
		entries.add(new Entry(l("/unassign"), l(), l(), l(myUser, "one", "two"), l("one", "two")));

		Arrays.asList("kind", "area", "priority").forEach(e -> {
			entries.add(new Entry(l("/" + e + " one"), l(), l(e + "/one"), l(), l()));
			entries.add(new Entry(l("/" + e + " one"), l(e + "/two"), l(e + "/one", e + "/two"), l(), l()));

			entries.add(new Entry(l("/remove-" + e + " one"), l(e + "/one"), l(), l(), l()));
			entries.add(new Entry(l("/remove-" + e + " one"), l(e + "/one", e + "/two"), l(e + "/two"), l(), l()));
			entries.add(new Entry(l("/remove-" + e + " one"), l(e + "/one", e + "/two", e + "/three"),
					l(e + "/two", e + "/three"), l(), l()));

		});

		return entries;
	}

	@Test
	public void testCompareStringList() {

		{
			List<String> a = Arrays.asList("1", "2", "3");
			List<String> b = Arrays.asList("4", "5", "6");

			List<String> outputAOnly = new ArrayList<>();
			List<String> outputBOnly = new ArrayList<>();
			List<String> outputInBoth = new ArrayList<>();

			CommandJob.compareStringLists(a, b, outputAOnly, outputBOnly, outputInBoth);

			assertListEquals(outputAOnly, a);
			assertListEquals(outputBOnly, b);
		}

		{
			List<String> a = Arrays.asList("1", "2", "4");
			List<String> b = Arrays.asList("4", "5", "6");

			List<String> outputAOnly = new ArrayList<>();
			List<String> outputBOnly = new ArrayList<>();
			List<String> outputInBoth = new ArrayList<>();

			CommandJob.compareStringLists(a, b, outputAOnly, outputBOnly, outputInBoth);

			assertListEquals(outputAOnly, Arrays.asList("1", "2"));
			assertListEquals(outputBOnly, Arrays.asList("5", "6"));
			assertListEquals(outputInBoth, Arrays.asList("4"));
		}

		{
			List<String> a = Arrays.asList("1", "2");
			List<String> b = Arrays.asList("1", "2");

			List<String> outputAOnly = new ArrayList<>();
			List<String> outputBOnly = new ArrayList<>();
			List<String> outputInBoth = new ArrayList<>();

			CommandJob.compareStringLists(a, b, outputAOnly, outputBOnly, outputInBoth);

			assertListEquals(outputAOnly, Collections.emptyList());
			assertListEquals(outputBOnly, Collections.emptyList());
			assertListEquals(outputInBoth, Arrays.asList("1", "2"));
		}

	}

	@Test
	public void testProcessEntries() throws IOException {

		List<Entry> entries = getEntries();

		User jgw = us.getUser(myUser);

		for (Entry e : entries) {

			List<String> labels = new ArrayList<>(e.existingLabels);
			List<String> assignees = new ArrayList<>(e.existingAssignees);

			IssueState is = new IssueState(labels, assignees, true, null);

			CommandJob.processBody(e.getLines(), is, jgw, us, null, null);

			assertListEquals(e.expectedAssignees, assignees);

			assertListEquals(e.expectedLabels, labels);

		}

	}

	private static void assertListEquals(List<String> one, List<String> two) {

		String oneStr = one.stream().sorted().reduce((a, b) -> a + "|" + b).orElse("");
		String twoStr = two.stream().sorted().reduce((a, b) -> a + "|" + b).orElse("");

		assertEquals("Differing: " + one + " vs " + two, oneStr, twoStr);

	}

	private static List<String> l(String... s) {
		return Arrays.asList(s);
	}

	/** List of test values to check */
	private static class Entry {
		List<String> lines;
		List<String> existingLabels;
		List<String> expectedLabels;
		List<String> existingAssignees;
		List<String> expectedAssignees;

		public Entry(List<String> lines, List<String> existingLabels, List<String> expectedLabels,
				List<String> existingAssignees, List<String> expectedAssignees) {

			this.lines = lines;
			this.existingLabels = existingLabels;
			this.expectedLabels = expectedLabels;
			this.existingAssignees = existingAssignees;
			this.expectedAssignees = expectedAssignees;
		}

		public String getLines() {
			return lines.stream().map(e -> e + '\n').reduce((a, b) -> a + b).get();
		}

	}

}
