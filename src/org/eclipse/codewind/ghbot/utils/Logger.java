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

package org.eclipse.codewind.ghbot.utils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Simple generic logging class. */
public class Logger {

	private static final Logger instance = new Logger();

	FileLogger fileLogger;

	private Logger() {

		String fileLoggerPath = System.getenv("FILE_LOGGER_PATH");

		Path path;

		if (fileLoggerPath != null) {
			path = Paths.get(fileLoggerPath, "out.log");
		} else {
			path = Paths.get(System.getProperty("user.home"), "out.log");
		}

		fileLogger = new FileLogger(path);
	}

	public static Logger getInstance() {
		return instance;
	}

	public FileLogger getFileLogger() {
		return fileLogger;
	}

	private static final SimpleDateFormat PRETTY_DATE_FORMAT = new SimpleDateFormat("MMM d h:mm:ss.SSS a");

	public void out(String msg) {
		msg = "[" + (PRETTY_DATE_FORMAT.format(new Date())) + "] " + msg;
		System.out.println(msg);
	}

	public void err(String msg) {
		msg = "[" + (PRETTY_DATE_FORMAT.format(new Date())) + "] " + msg;
		System.err.println(msg);
	}

	public void out() {
		this.out("");
	}
}
