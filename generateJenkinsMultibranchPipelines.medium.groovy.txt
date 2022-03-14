/**
 * Generate Multibranch Pipelines.
 * @param repositoryURL The Git repository URL.
 * @param jenkinsfilePaths A list of Jenkinsfiles paths.
 */
def generateMultibranchPipelines(List<Path> jenkinsfilePaths, Path rootFolder, String repositoryURL) {
    // The following variables are needed to configure the branch source for GitHub. Configuration for other version
    // control providers vary.
    def matcher = repositoryURL =~ /.+[\/:](?<owner>[^\/]+)\/(?<repository>[^\/]+)\.git$/
    matcher.matches()
    String repositoryOwner = matcher.group('owner')
    String repositoryName = matcher.group('repository')

    // Discover branches strategies
    final int EXCLUDE_PULL_REQUESTS_STRATEGY_ID = 1

    // Discover pull requests from origin strategies
    final int USE_CURRENT_SOURCE_STRATEGY_ID = 2

    jenkinsfilePaths.each { jenkinsfilePath ->
        String pipelineName = jenkinsfilePath.parent

        multibranchPipelineJob(rootFolder.resolve(pipelineName).toString()) {
            branchSources {
                branchSource {
                    source {
                        github {
                            // We must set a branch source ID.
                            id('github')

                            // repoOwner, repository, repositoryUrl and configuredByUrl are all required
                            repoOwner(repositoryOwner)
                            repository(repositoryName)
                            repositoryUrl(repositoryURL)
                            configuredByUrl(false)

                            // Make sure to properly set this.
                            credentialsId('github-token')

                            traits {
                                // Depending on your preferences and root pipeline configuration, you can decide to
                                // discover branches, pull requests, perhaps even tags.
                                gitHubBranchDiscovery {
                                    strategyId(EXCLUDE_PULL_REQUESTS_STRATEGY_ID)
                                }
                                gitHubPullRequestDiscovery {
                                    strategyId(USE_CURRENT_SOURCE_STRATEGY_ID)
                                }

                                // By default, Jenkins notifies GitHub with a constant context, i.e. a string that
                                // identifies the check. We want each individual build result to have its own context so
                                // they do not conflict. Requires the github-scm-trait-notification-context-plugin to be
                                // installed on the Jenkins instance.
                                notificationContextTrait {
                                    contextLabel("continuous-integration/jenkins/$pipelineName")
                                    typeSuffix(false)
                                }
                            }
                        }
                    }

                    // By default, Jenkins will trigger builds as it detects changes on the source repository. We want
                    // to avoid that since we will trigger child pipelines on our own only when relevant.
                    buildStrategies {
                        skipInitialBuildOnFirstBranchIndexing()
                    }
                    strategy {
                        defaultBranchPropertyStrategy {
                            props {
                                noTriggerBranchProperty()
                            }
                        }
                    }
                }
            }
            factory {
                workflowBranchProjectFactory {
                    scriptPath(jenkinsfilePath.toString())
                }
            }
            orphanedItemStrategy {
                discardOldItems {
                    // Keeping pipelines a few days for branches that do not exist anymore can be useful for
                    // troubleshooting purposes.
                    daysToKeep(3)
                }
            }
            triggers {
                periodicFolderTrigger {
                    // Scan branches once a week at least to remove orphan pipelines.
                    interval('7d')
                }
            }
        }
    }
}

// `jenkinsfilePathsStr` and `rootFolderStr` are global variables that are set through `jobDsl`'s `additionalParameters`
// options.
List<Path> jenkinsfilePaths = jenkinsfilePathsStr.collect { Paths.get(it) }
Path rootFolder = Paths.get(rootFolderStr)

generateFolders(jenkinsfilePaths, rootFolder)
generateMultibranchPipelines(jenkinsfilePaths, rootFolder, repositoryURL)