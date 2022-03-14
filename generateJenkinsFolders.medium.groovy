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
