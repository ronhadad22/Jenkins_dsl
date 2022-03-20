
   
/**
 * Get all ancestor folders paths.
 * <pre>
 *     ancestorFoldersPath(Paths.get('root/parent/child/file'))
 *     // returns [root, root/parent, root/parent/child]
 * </pre>
 * @param path A path.
 * @return All its ancestors paths.
 */
List<Path> ancestorFoldersPath(Path path) {
    if (path.parent == null) return []
    ancestorFoldersPath(path.parent) + [path.parent]
}

/**
 * Generate folders.
 * @param jenkinsfilePaths A list of Jenkinsfile paths.
 */
def generateFolders(List<Path> jenkinsfilePaths, Path rootFolder) {
    jenkinsfilePaths
            .collect { ancestorFoldersPath(rootFolder.resolve(it).parent) }
            .flatten()
    // Remove duplicates in case some Jenkinsfiles share ancestors for optimization.
            .unique()
            .each {
                // Effectively provision the folder.
                folder(it.toString())
            }
}


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


/**
 * Provision items on Jenkins.
 * @param rootFolderPath A root folder path.
 * @param repositoryURL The repository URL.
 * @return The list of Jenkinsfile paths for which corresponding items have been provisioned.
 */
List<String> provisionItems(String rootFolderPath, String repositoryURL) {
    // Find all Jenkinsfiles.
    List<String> jenkinsfilePaths = findFiles(glob: '**/*/Jenkinsfile').collect { it.path }

    // Provision folder and Multibranch Pipelines.
    jobDsl(
            scriptText: libraryResource('multiPipelines.groovy'),
            additionalParameters: [
                    jenkinsfilePathsStr: jenkinsfilePaths,
                    rootFolderStr      : rootFolderPath,
                    repositoryURL      : env.GIT_URL
            ],
            // The following may be set to 'DELETE'. Note that branches will compete to delete and recreate items
            // unless you only provision items from the default branch.
            removedJobAction: 'IGNORE'
    )

    return jenkinsfilePaths
}

/**
 * Get the most relevant baseline revision.
 * @return A revision.
 */
String getBaselineRevision() {
    // Depending on your seed pipeline configuration and preferences, you can set the baseline revision to a target
    // branch, e.g. the repository's default branch or even `env.CHANGE_TARGET` if Jenkins is configured to discover
    // pull requests.
    [env.GIT_PREVIOUS_SUCCESSFUL_COMMIT, env.GIT_PREVIOUS_COMMIT]
    // Look for the first existing existing revision. Commits can be removed (e.g. with a `git push --force`), so a
    // previous build revision may not exist anymore.
            .find { revision ->
                revision != null && sh(script: "git rev-parse --quiet --verify $revision", returnStdout: true) == 0
            } ?: 'HEAD^'
}

/**
 * Get the list of changed directories.
 * @param baselineRevision A revision to compare to the current revision.
 * @return The list of directories which include changes.
 */
List<String> getChangedDirectories(String baselineRevision) {
    // Jenkins native interface to retrieve changes, i.e. `currentBuild.changeSets`, returns an empty list for newly
    // created branches (see https://issues.jenkins.io/browse/JENKINS-14138), so let's use `git` instead.
    sh(
            label: 'List changed directories',
            script: "git diff --name-only $baselineRevision | xargs -L1 dirname | uniq",
            returnStdout: true,
    ).split().toList()
}

/**
 * Find Multibranch Pipelines which Jenkinsfile is located in a directory that includes changes.
 * @param changedFilesPathStr List of changed files paths.
 * @param jenkinsfilePathsStr List of Jenkinsfile paths.
 * @return A list of Pipeline names, relative to the repository root.
 */
// `java.nio.file.Path(s)` instances are not serializable, so we have to add the following annotation.
@NonCPS
static List<String> findRelevantMultibranchPipelines(List<String> changedFilesPathStr, List<String> jenkinsfilePathsStr) {
    List<Path> changedFilesPath = changedFilesPathStr.collect { Paths.get(it) }
    List<Path> jenkinsfilePaths = jenkinsfilePathsStr.collect { Paths.get(it) }

    changedFilesPath.inject([]) { pipelines, changedFilePath ->
        def matchingJenkinsfile = jenkinsfilePaths
                .find { jenkinsfilePath -> changedFilePath.startsWith(jenkinsfilePath.parent) }
        matchingJenkinsfile != null ? pipelines + [matchingJenkinsfile.parent.toString()] : pipelines
    }.unique()
}

/**
 * Get the list of Multibranch Pipelines that should be run according to the changeset.
 * @param jenkinsfilePaths The list of Jenkinsfiles paths.
 * @return The list of Multibranch Pipelines to run relative to the repository root.
 */
List<String> findMultibranchPipelinesToRun(List<String> jenkinsfilePaths) {
    findRelevantMultibranchPipelines(getChangedDirectories(baselineRevision), jenkinsfilePaths)
}

/**
 * Run pipelines.
 * @param rootFolderPath The common root folder of Multibranch Pipelines.
 * @param multibranchPipelinesToRun The list of Multibranch Pipelines for which a Pipeline is run.
 */
def runPipelines(String rootFolderPath, List<String> multibranchPipelinesToRun) {
    parallel(multibranchPipelinesToRun.inject([:]) { stages, multibranchPipelineToRun ->
        stages + [("Build $multibranchPipelinesToRun"): {
            def pipelineName = "$rootFolderPath/$multibranchPipelinesToRun/${URLEncoder.encode(env.CHANGE_BRANCH ?: env.GIT_BRANCH, 'UTF-8')}"
            // For new branches, Jenkins will receive an event from the version control system to provision the
            // corresponding Pipeline under the Multibranch Pipeline item. We have to wait for Jenkins to process the
            // event so a build can be triggered.
            timeout(time: 5, unit: 'MINUTES') {
                waitUntil(initialRecurrencePeriod: 1e3) {
                    def pipeline = Jenkins.instance.getItemByFullName(pipelineName)
                    pipeline && !pipeline.isDisabled()
                }
            }

            // Trigger downstream builds.
            build(job: pipelineName, propagate: true, wait: true)
        }]
    })
}








String repositoryName = env.JOB_NAME.split('/')[1]
String rootFolderPath = "CCV/$repositoryName"
println "Hello World ${rootFolderPath}"
List<String> jenkinsfilePaths = provisionItems(rootFolderPath, env.GIT_URL)
List<String> multibranchPipelinesToRun = findMultibranchPipelinesToRun(jenkinsfilePaths)
runPipelines(rootFolderPath, multibranchPipelinesToRun)
