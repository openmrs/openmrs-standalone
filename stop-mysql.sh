#!/bin/bash

stopMariaDB() {
    fuser -k 33326/tcp
    fuser -k 33328/tcp
    exit 0
}

stopMariaDB
