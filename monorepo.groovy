/**
 * The step entry point.
 */

println "Hello World ${rootFolderPath}"

def call() {
    String repositoryName = env.JOB_NAME.split('/')[1]
    String rootFolderPath = "CCV/$repositoryName"
    println "Hello World ${rootFolderPath}"
    List<String> jenkinsfilePaths = provisionItems(rootFolderPath, env.GIT_URL)
    List<String> multibranchPipelinesToRun = findMultibranchPipelinesToRun(jenkinsfilePaths)
    runPipelines(rootFolderPath, multibranchPipelinesToRun)
}
