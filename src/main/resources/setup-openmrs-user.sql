CREATE USER 'openmrs'@'%' IDENTIFIED BY 'test';
GRANT ALL PRIVILEGES ON openmrs.* to 'openmrs'@'%';
FLUSH PRIVILEGES;
