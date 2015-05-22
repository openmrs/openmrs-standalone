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

import org.apache.commons.lang3.StringUtils;

import ch.vorburger.exec.ManagedProcessException;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;


public class DatabaseManager {

	private static DatabaseManager instance;
	
	protected DB db;
	protected DBConfigurationBuilder configBuilder;
	
	private DatabaseManager() {
		
	}
	
	public static DatabaseManager getInstance() {
		if (instance == null) {
			instance = new DatabaseManager();
		}
		return instance;
	}
	
	public DBConfigurationBuilder buildConfiguration() {
		if (configBuilder == null) {
			configBuilder = DBConfigurationBuilder.newBuilder();
		}
		return configBuilder;
	}
	
	public void stop() throws ManagedProcessException {
		if (db != null) {
			db.stop();
			db = null;
			configBuilder = null;
		}
	}
	
	public void start(String port) throws ManagedProcessException {
		start(port, null, null);
	}
	
	public void start(String port, String baseDir, String dataDir) throws ManagedProcessException {
		if (db != null) {
			System.out.println("Database already started");
			return;
		}
		
	    System.out.println(".......Starting Database.......");
	    
	    if (dataDir == null || !new File(dataDir).exists()) {
	    	db = DB.newEmbeddedDB(getConfiguration(port, baseDir, dataDir));
	    }
	    else {
	    	db = DB.existingEmbeddedDB(getConfiguration(port, baseDir, dataDir));
	    }
	    
		db.start();
		
		System.out.println(".......Database Started Successfully.......");
	}
	
	public void startNewDB(String port) throws ManagedProcessException {
		startNewDB(port, null, null);
	}
	
	public void startNewDB(String port, String baseDir, String dataDir) throws ManagedProcessException {
		if (db != null) {
			System.out.println("Database already started");
			return;
		}
		
	    System.out.println(".......Starting Database.......");
	    
		db = DB.newEmbeddedDB(getConfiguration(port, baseDir, dataDir));
		db.start();
		
		System.out.println(".......Database Started Successfully.......");
	}
	
	private DBConfiguration getConfiguration(String port, String baseDir, String dataDir) {
		DBConfiguration config = buildConfiguration().build();
	    config.setPort(Integer.parseInt(port));
	    config.setBaseDir(StringUtils.isBlank(baseDir) ? "database" : baseDir);
	    config.setDataDir(StringUtils.isBlank(dataDir) ? "database/data" : dataDir);
	    config.setSkipGrantTables(false);
	    return config;
	}
}
