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

import static org.openmrs.standalone.OpenmrsUtil.importSqlFile;

public class DbInitializer {
    // This is to be called via pom-step-04 with id 'import-demo-sql'
    public static void main(String[] args) throws Exception {
        Class.forName("org.mariadb.jdbc.Driver").getDeclaredConstructor().newInstance();

        String sqlFilePath = args[0];
        String jdbcUrl = args[1];
        String username = args[2];
        String password = args[3];

        importSqlFile(new File(sqlFilePath), jdbcUrl, username, password);
    }
}
