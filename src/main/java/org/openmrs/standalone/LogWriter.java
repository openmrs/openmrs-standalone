/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.standalone;

import java.io.*;
import java.util.Calendar;


/**
 * Handles writing to the log file.
 */
public class LogWriter extends FilterOutputStream {
	
	public LogWriter() {
		super(new ByteArrayOutputStream());
	}

	@Override
	public void write(byte[] b, int off, int len) {
		write(new String(b, off, len));
	}
	
	/**
	 * Writes a given text to the log file.
	 * 
	 * @param aString the text.
	 */
	public static void write(String aString) {
		//Append to the log file under currentdir/tomcat/logs
		FileWriter aWriter = null;
		try {
			Calendar cal = Calendar.getInstance();
			String fileName = cal.get(Calendar.YEAR) + "-" + (cal.get(Calendar.MONTH) + 1) + "-" + cal.get(Calendar.DATE)
					+ ".log";
			String path = new File(fileName).getAbsolutePath();
			path = path.substring(0, path.lastIndexOf(File.separatorChar) + 1) + "tomcat" + File.separatorChar + "logs";
			new File(path).mkdirs();
			String filePath = path + File.separatorChar + fileName;
			aWriter = new FileWriter(filePath, true);
			aWriter.write(aString);
		} catch (Exception ex) {
			// printing to the std streams here may result in an infinite loop since will keep calling into this same routine.
		} finally {
			if (aWriter != null) {
				try {
					aWriter.close();
				} catch (IOException e) {
					//handle io exceptions occurred by file writer
				}
			}
		}
	}

}
