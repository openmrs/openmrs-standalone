#!/bin/sh

# Assumes you have installed the required jars to your local maven repo.  
# See readme.txt
#mvn package

read -p "Enter the name of this release (e.g 1.8.0-beta2): " version

name="openmrs-standalone-$version"

echo Cleaning up any past builds
rm -rf $name
rm $name.zip

echo Grouping files to zip
mkdir $name
cd $name
# ln -s ../database database
cp ../target/*database.zip .
ln -s ../tomcat tomcat
cp ../*.png .
cp ../openmrs-runtime.properties .
cp ../target/*.jar .
cp ../target/openmrs.war tomcat/webapps/openmrs.war

# TODO: Add an "if statement" here to look for this property?

echo "application_data_directory=appdata" >> openmrs-runtime.properties
echo "reset_connection_password=true" >> openmrs-runtime.properties
echo "tomcatport=8081" >> openmrs-runtime.properties
echo "connection.url=jdbc:mariadb://localhost:3316/openmrs?" >> openmrs-runtime.properties
echo "connection.username=openmrs" >> openmrs-runtime.properties
echo "connection.password=Admin123" >> openmrs-runtime.properties

cd ..

echo Creating zip file
zip -q -r $name.zip $name -x \*.svn/\* \*.DS_Store/\* \*__MACOSX/\* \*database/bin/\* \*database/share/\* \*tomcat/logs/\* \*tomcat/work/\* \*tomcat/webapps/openmrs/\*

#echo Cleaning up temp files
rm -rf $name

#echo for testing, re-expand
#unzip $name.zip

echo Done.  Zip $name.zip created
