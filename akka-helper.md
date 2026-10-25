winget install Akka.Cli
akka --version
akka code init --name helloworld-agent --repo akka-samples/helloworld-agent.git

##OPENAI
set OPENAI_API_KEY=

##Gemini
set GEMINI_API_KEY=

in environment variable

$env:GEMINI_API_KEY

java --version -> should be 25 else run 
winget install --id Microsoft.OpenJDK.25 --exact

mvn compile exec:java

mvn dependency:tree '-Dincludes=com.fasterxml.jackson.core:jackson-databind'


curl -i -XPOST --location "http://localhost:9000/hello" --header "Content-Type: application/json" --data '{"user": "alice", "text": "Hello, I am Alice"}'
	
Invoke-RestMethod -Uri "http://localhost:9000/hello" -Method Post -ContentType "json" -Body '{"user": "alice", "text": "Hello, I am Alice"}'	

Invoke-RestMethod -Uri "http://localhost:9000/hello" -Method Post -ContentType "application/json" -Body '{"user": "alice", "text": "Hello, I am Alice"}'

Invoke-RestMethod -Uri "http://localhost:9000/hello" -Method Post -ContentType "application/json" -Body '{"user": "alice", "text": "I live in New York"}'

Invoke-RestMethod -Uri "http://localhost:9000/hello" -Method Post -ContentType "application/json" -Body '{"user": "alice", "text": "I like the botanical garden"}'

Invoke-RestMethod -Uri "http://localhost:9000/hello" -Method Post -ContentType "application/json" -Body '{"user": "blackbeard", "text": "Ahoy there, matey! My name is Blackbeard"}'

curl.exe -i -XPOST --location "http://localhost:9000/hello" --header "Content-Type: application/json" --data '{"user": "alice", "text": "Hello, I am Alice"}'

##Get

Invoke-RestMethod -Uri "http://localhost:9000/health" -Method Get -ContentType "application/json" -Body '{"user": "alice", "text": "Hello, I am Alice"}'

##AKKA Local Console

akka local console

http://localhost:9889/

##Reference
https://akka.io/install