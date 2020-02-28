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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * Test our ability to extract the existing taglist from a Mattermost message.
 */
public class BotMainTest {

	@Test
	public void testTagExtraction() {

		List<String> tags = ChannelJobs.extractTagsFromMessage("[_user_]");
		assertTrue(tags.size() == 0);

		tags = ChannelJobs.extractTagsFromMessage("[_user_] :1:");
		assertTrue(tags.size() == 1 && tags.get(0).equals("1"));

		tags = ChannelJobs.extractTagsFromMessage("[_user_] :1: :2:");
		assertTrue(tags.size() == 2 && tags.get(0).equals("1") && tags.get(1).equals("2"));

		tags = ChannelJobs.extractTagsFromMessage("[_user_] :1: :2: :3: ");
		assertTrue(tags.size() == 3 && tags.get(0).equals("1") && tags.get(1).equals("2") && tags.get(2).equals("3"));

		tags = ChannelJobs.extractTagsFromMessage("[_user_]:1::2::3:");
		assertTrue(tags.size() == 3 && tags.get(0).equals("1") && tags.get(1).equals("2") && tags.get(2).equals("3"));

		tags = ChannelJobs.extractTagsFromMessage("[_user_]    :1:       :2:    :3:    ");
		assertTrue(tags.size() == 3 && tags.get(0).equals("1") && tags.get(1).equals("2") && tags.get(2).equals("3"));

		try {
			tags = ChannelJobs.extractTagsFromMessage("[_user_]    :1:       :    :3:    ");
			fail("Expected Unbalanced Exception");
		} catch (RuntimeException e) {
			/* pass */
		}

		try {
			tags = ChannelJobs.extractTagsFromMessage("[_user_]    :1:       ::    :3    ");
			fail("Expected Unbalanced Exception");
		} catch (RuntimeException e) {
			/* pass */
		}

	}
}
