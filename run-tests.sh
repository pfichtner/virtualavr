#!/bin/bash
set -e  # Exit immediately if any command exits with a non-zero status

# 1. Set Docker Image Tag
export DOCKER_IMAGE_TAG=$(date +'%s-snapshot')
echo "Docker image tag: $DOCKER_IMAGE_TAG"

# Function to clean up the Docker image
cleanup() {
  echo "Cleaning up Docker image: pfichtner/virtualavr:$DOCKER_IMAGE_TAG"
  docker rmi -f pfichtner/virtualavr:$DOCKER_IMAGE_TAG || echo "Image removal failed. It may not exist."
}

# Set trap to run cleanup on script exit (success or failure)
trap cleanup EXIT

# Build the Docker image
docker build . --file Dockerfile --tag pfichtner/virtualavr:$DOCKER_IMAGE_TAG

# Run Java tests
echo "Running Java tests..."
mvn -B -DTESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=localhost \
    -Dvirtualavr.docker.tag=$DOCKER_IMAGE_TAG \
    '-Dtest=com.github.pfichtner.virtualavr.virtualavrtests.*IT' verify --file demo/java/sketchtest/pom.xml

# Run Java demo
echo "Running Java demo..."
mvn -B -DTESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=localhost \
    -Dvirtualavr.docker.tag=$DOCKER_IMAGE_TAG \
    -Dvirtualavr.sketchfile=../../../test-artifacts/ino-file/noiselevelindicator/noiselevelindicator.ino \
    '-Dtest=com.github.pfichtner.virtualavr.demo.NoiseLevelIndicatorTest' verify --file demo/java/sketchtest/pom.xml

if [ "$SKIP_PIP_INSTALL" != "true" ]; then
	python -m pip install --upgrade pip
	pip install -r demo/python/requirements.txt
fi
echo "Running Python tests (ino-file)..."
pytest demo/python -v --junit-xml=demo/python/ino-file/test-results/pytest.xml
echo "Running Python tests (hex-file)..."
pytest demo/python -v --junit-xml=demo/python/hex-file/test-results/pytest.xml
echo "Running Python tests (wokwi-zip)..."
pytest demo/python -v --junit-xml=demo/python/wokwi-zip/test-results/pytest.xml

echo "Running Gherkin tests..."
if [ "$SKIP_PIP_INSTALL" != "true" ]; then
	pip install -r demo/python-gherkin/requirements.txt
fi
behave --junit --junit-directory demo/python-gherkin/test-results demo/python-gherkin

echo "All tests completed successfully."

