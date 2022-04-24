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

import java.io.File;

import com.mysql.management.driverlaunched.ServerLauncherSocketFactory;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;

/**
 * It is used to stop embedded database.
 */
public class StopEmbeddedMySQL {
	
	protected DB db;
	protected DBConfigurationBuilder configBuilder;
	
	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			throw new IllegalArgumentException("Must be called with at least one argument pointing to a database directory!");
		}
		
		new StopEmbeddedMySQL().stopEmbeddedMariadb();
	}
	public void stopEmbeddedMariadb() {
		if (db != null) {
			db.stop();
			db = null;
			configBuilder = null;
		}
	}
}