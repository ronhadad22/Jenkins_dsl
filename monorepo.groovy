/**
 * The step entry point.
 */

println "ron1 "
monorepo.call() 
println "ron2 "
def call() {
    String repositoryName = env.JOB_NAME.split('/')[1]
    String rootFolderPath = "CCV/$repositoryName"
    println "Hello World ${rootFolderPath}"
    List<String> jenkinsfilePaths = provisionItems(rootFolderPath, env.GIT_URL)
    List<String> multibranchPipelinesToRun = findMultibranchPipelinesToRun(jenkinsfilePaths)
    runPipelines(rootFolderPath, multibranchPipelinesToRun)
}
