#!/bin/bash

stopMysql() {
    fuser -k 33326/tcp
    fuser -k 33328/tcp
}

stopMysql
