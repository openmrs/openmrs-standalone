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

import java.io.FilterOutputStream;
import java.io.IOException;

/**
 * Provides a stream which enables us redirect the command line's 
 * standard output and error streams to a log file.
 */
public class CommandLineWriter extends FilterOutputStream {

	public CommandLineWriter(){
		super(System.out);
	}
	
	@Override
	public void write(byte b[]) throws IOException {
		super.write(b);
		LogWriter.write(new String(b));
	}
	
	@Override
	public void write(int b) throws IOException {
		super.write(b);
		LogWriter.write(new String(new byte[]{(byte)b}));
	}
	
	@Override
	public void write(byte b[], int off, int len) throws IOException {
		super.write(b, off, len);
		LogWriter.write(new String(b, off, len));
	}
}
