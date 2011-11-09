# Zips standalone folder contents without including unnecessary files and folders
# This script should be run from the standalone root folder
# Also remember to replace openmrs-1.9.0-alpha with the name of your war file.

#!/bin/sh

name="OpenMRS-1.9.0-alpha-standalone-without-demo-data"

echo Cleaning up any past builds
rm -rf $name
#rm $name.zip

echo Grouping files to zip
mkdir $name
cd $name
ln -s ../database database
ln -s ../tomcat tomcat
cp ../*.png .
cp ../openmrs-1.9.0-alpha-runtime.properties .
cp ../*.jar .

cd ..

echo Creating zip file
zip -q -r $name.zip $name -x *.DS_Store \*.svn/\* \*.DS_Store/\* \*__MACOSX/\* \*database/bin/\* \*database/share/\* \*tomcat/logs/\* \*tomcat/work/\* \*tomcat/webapps/openmrs-1.9.0-alpha/\*

#echo Cleaning up temp files
rm -rf $name

echo Done.  Zip $name.zip created
