package org.openmrs.standalone;

import java.io.File;

import static org.openmrs.standalone.OpenmrsUtil.importSqlFile;

public class DbInitializer {
    // This is to be called via pom-step-04 with id 'import-demo-sql'
    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver").newInstance();

        String sqlFilePath = args[0];
        String jdbcUrl = args[1];
        String username = args[2];
        String password = args[3];

        importSqlFile(new File(sqlFilePath), jdbcUrl, username, password);
    }
}
