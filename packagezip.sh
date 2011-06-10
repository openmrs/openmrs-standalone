#!/bin/sh

# Assumes you have installed the required jars to your local maven repo.  
# See readme.txt
#mvn package

read -p "Enter the name of this release (e.g 1.8.0-beta2): " versionname

name="openmrs-standalone-$versionname"

echo Cleaning up any past builds
rm -rf $name
rm $name.zip

echo Grouping files to zip
mkdir $name
cd $name
ln -s ../database database
ln -s ../tomcat tomcat
cp ../*.png .
cp ../openmrs-standalone-runtime.properties .
cp ../target/*.jar .

# TODO: Add an "if statement" here to look for this property?

echo "application_data_directory=appdata" >> openmrs-standalone-runtime.properties
echo "reset_connection_password=true" >> openmrs-standalone-runtime.properties

cd ..

echo Creating zip file
zip -q -r $name.zip $name -x \*.svn/\* \*database/bin/\* \*database/share/\* \*tomcat/logs/\* \*tomcat/work/\* \*tomcat/webapps/openmrs/\*

echo Cleaning up temp files
rm -rf $name

echo Done.  Zip $name.zip created
