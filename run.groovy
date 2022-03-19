#!/usr/bin/env groovy

// Configure using microservice-pipelines and using "part2" branch
@Library("air-multi@part2") _

// Entry point into microservice-pipelines
jenkinsJob.call()
