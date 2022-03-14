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