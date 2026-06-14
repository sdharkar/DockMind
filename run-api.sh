#!/bin/bash
# Helper script to load environment variables and run docmind-api

if [ -f .env ]; then
  echo "Loading environment variables from .env"
  # Export variables while ignoring comments and blank lines
  export $(grep -v '^#' .env | xargs)
else
  echo "Warning: .env file not found. Ensure OPENAI_API_KEY is set in your environment."
fi

if [ -z "$OPENAI_API_KEY" ]; then
  echo "Error: OPENAI_API_KEY is not set. Please create a .env file with OPENAI_API_KEY=your_key or set it in your environment."
  exit 1
fi

./mvnw spring-boot:run -pl docmind-api
