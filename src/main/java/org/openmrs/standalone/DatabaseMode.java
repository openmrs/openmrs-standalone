package org.openmrs.standalone;
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
/**
 * List of the different settings the user can choose for the database
 */
public enum DatabaseMode {
	NO_CHANGES, // just use whatever database is set up, and don't do anything.
	USE_INITIALIZATION_WIZARD, // clear the database and invoke the initialization wizard
	EMPTY_DATABASE, // use the empty database
	DEMO_DATABASE// Use the demo database
}
