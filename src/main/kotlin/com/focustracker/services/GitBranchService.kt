package com.focustracker.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * Service for resolving current Git branch for a project.
 * This service is only available when Git4Idea plugin is present.
 */
@Service(Service.Level.APP)
class GitBranchService {

    private val log = Logger.getInstance(GitBranchService::class.java)

    /**
     * Gets the current branch name for the given project.
     * Returns null if:
     * - Project has no Git repositories
     * - Project is disposed
     * - Git is in detached HEAD state (returns the short commit hash instead)
     */
    fun getCurrentBranch(project: Project): String? {
        if (project.isDisposed) return null

        return try {
            val repositoryManager = GitRepositoryManager.getInstance(project)
            val repositories = repositoryManager.repositories

            if (repositories.isEmpty()) return null

            // If multiple repos, use the first one (usually the root repo)
            val repository = repositories.firstOrNull() ?: return null
            getBranchName(repository)
        } catch (e: Exception) {
            log.debug("Unable to get current branch for project ${project.name}", e)
            null
        }
    }

    /**
     * Gets the current branch name for a specific repository.
     */
    private fun getBranchName(repository: GitRepository): String? {
        val currentBranch = repository.currentBranch
        if (currentBranch != null) {
            return currentBranch.name
        }

        // Detached HEAD - return short revision
        val revision = repository.currentRevision
        return revision?.take(7)?.let { "detached:$it" }
    }

    companion object {
        fun getInstanceOrNull(): GitBranchService? {
            return try {
                ApplicationManager.getApplication().getService(GitBranchService::class.java)
            } catch (e: Exception) {
                // Git4Idea not available
                null
            }
        }
    }
}
