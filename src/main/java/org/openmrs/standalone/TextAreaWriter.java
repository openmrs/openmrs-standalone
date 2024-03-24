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

import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;


/**
 * Provides a stream which enables us redirect the standard output and error 
 * streams to a swing text area.
 */
public class TextAreaWriter extends FilterOutputStream {
	
	private final JTextArea text;
	private int length;
	public static long LOG_LENGTH = 10000;
	
	public TextAreaWriter(JTextArea text) {
		super(new ByteArrayOutputStream());
		this.text = text;
	}

	@Override
	public void write(byte[] b) {
		String aString = new String(b);
		text.append(aString);
	}

	@Override
	public void write(byte[] b, int off, int len) {
		
		String aString = null;
		
		try{
			length += len;
			aString = new String(b, off, len);
			text.append(aString);
			
			//Not to run out memory, start trimming the oldest characters whenever we reach
			//a particular threshold.
			if (length > LOG_LENGTH) {
				text.setText(text.getText().substring(len));
				length = length - len;
			}
			
			//scroll to the bottom.
			text.setCaretPosition(length);
		}
		catch(Exception ex){
			//This is most likely thrown at text.setCaretPosition() due to the user having removed some of the log output.
			length = 0;
			text.append(aString);
			//printing to the std streams here may result in an infinite loop since will keep calling into this same routine.
		}
		
		//Append to the log file under currentdir/tomcat/logs
		LogWriter.write(aString);
	}
	
	public void clear(){
		length = 0;
		text.setText("");
	}
}
