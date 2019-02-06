#!/bin/bash
for i in {100..300}
do
    java -jar netsample.jar get -v "http://localhost:8080/test.txt"
done	