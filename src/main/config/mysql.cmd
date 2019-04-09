@echo off
for /f "tokens=2 delims==" %%a in ('findstr connection.password openmrs-standalone-runtime.properties') do (set password=%%a)
for /f "tokens=2 delims==" %%a in ('findstr mysqlport openmrs-standalone-runtime.properties') do (set port=%%a)
database\bin\mysql -u openmrs --password="%password%" --port="%port%" --max_allowed_packet=96M