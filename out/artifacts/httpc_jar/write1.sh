#!/bin/bash
for i in {1..100}
do
    java -jar netsample.jar post -d  \'{"Assignment": $i}\' "http://localhost:8080/test.json"
done	