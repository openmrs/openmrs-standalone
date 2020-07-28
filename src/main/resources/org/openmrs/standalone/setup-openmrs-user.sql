CREATE USER 'openmrs'@'%' IDENTIFIED BY 'root';
GRANT ALL PRIVILEGES ON openmrs.* to 'openmrs'@'%';
FLUSH PRIVILEGES;