#!/bin/bash
for i in {100..200}
do
    java -jar netsample.jar post -d  \'{"Assignment": $i}\' "http://localhost:8080/test.json"
done	