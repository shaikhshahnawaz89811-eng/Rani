package com.sa.aidesktop.core.project

data class ProjectMetadata(val name:String,val language:String,val rootPath:String,val dependencies:List<String> = emptyList(),val runtime:String?=null,val buildConfiguration:Map<String,String> = emptyMap(),val testConfiguration:Map<String,String> = emptyMap(),val gitConfiguration:Map<String,String> = emptyMap())
sealed interface ProjectError{data class Validation(val message:String):ProjectError;data class AlreadyExists(val message:String):ProjectError;data class NotFound(val message:String):ProjectError}
sealed interface ProjectResult<out T>{data class Success<T>(val value:T):ProjectResult<T>;data class Failure(val error:ProjectError):ProjectResult<Nothing>}
interface ProjectManager{fun create(metadata:ProjectMetadata):ProjectResult<ProjectMetadata>;fun open(name:String):ProjectResult<ProjectMetadata>;fun list():List<ProjectMetadata>;fun delete(name:String):ProjectResult<Unit>}


class InMemoryProjectManager : ProjectManager {
    private val projects = linkedMapOf<String, ProjectMetadata>()
    override fun create(metadata: ProjectMetadata): ProjectResult<ProjectMetadata> {
        if (metadata.name.isBlank() || metadata.rootPath.isBlank()) return ProjectResult.Failure(ProjectError.Validation("Project name and root path are required"))
        if (projects.containsKey(metadata.name)) return ProjectResult.Failure(ProjectError.AlreadyExists("Project already exists: ${metadata.name}"))
        projects[metadata.name] = metadata
        return ProjectResult.Success(metadata)
    }
    override fun open(name: String): ProjectResult<ProjectMetadata> = projects[name]?.let { ProjectResult.Success(it) } ?: ProjectResult.Failure(ProjectError.NotFound("Project not found: $name"))
    override fun list(): List<ProjectMetadata> = projects.values.toList()
    override fun delete(name: String): ProjectResult<Unit> = if (projects.remove(name) != null) ProjectResult.Success(Unit) else ProjectResult.Failure(ProjectError.NotFound("Project not found: $name"))
}
