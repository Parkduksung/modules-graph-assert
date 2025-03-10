package com.jraska.module.graph.assertion

import com.jraska.module.graph.DependencyGraph
import com.jraska.module.graph.assertion.Api.Tasks
import com.jraska.module.graph.assertion.tasks.AssertGraphTask
import com.jraska.module.graph.assertion.tasks.GenerateModulesGraphStatisticsTask
import com.jraska.module.graph.assertion.tasks.GenerateModulesGraphTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin.CHECK_TASK_NAME
import org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP

@Suppress("unused") // Used as plugin
class ModuleGraphAssertionsPlugin : Plugin<Project> {

  private val moduleGraph by lazy {
    GradleDependencyGraphFactory.create(evaluatedProject, configurationsToLook).serializableGraph()
  }

  private val aliases by lazy {
    GradleModuleAliasExtractor.extractModuleAliases(evaluatedProject)
  }

  private lateinit var evaluatedProject: Project
  private lateinit var configurationsToLook: Set<String>

  override fun apply(project: Project) {
    val graphRules = project.extensions.create(GraphRulesExtension::class.java, Api.EXTENSION_ROOT, GraphRulesExtension::class.java)

    project.afterEvaluate {
      addModulesAssertions(project, graphRules)

      if (graphRules.assertOnAnyBuild) {
        project.gradle.projectsEvaluated {
          project.runAssertionsDirectly(graphRules)
        }
      }
    }
  }

  internal fun addModulesAssertions(project: Project, graphRules: GraphRulesExtension) {
    evaluatedProject = project
    configurationsToLook = graphRules.configurations

    project.addModuleGraphGeneration()
    project.addModuleGraphStatisticsGeneration()

    val allAssertionsTask = project.tasks.register(Tasks.ASSERT_ALL) { it.group = VERIFICATION_GROUP }

    try {
      project.tasks.named(CHECK_TASK_NAME).configure { it.dependsOn(allAssertionsTask) }
    } catch (checkNotFound: UnknownTaskException) {
      // We register other tasks, but we don't add a dependency to 'check' task
    }

    val childTasks = mutableListOf<TaskProvider<AssertGraphTask>>()
    project.addMaxHeightTask(graphRules)?.also { childTasks.add(it) }
    project.addModuleRestrictionsTask(graphRules)?.also { childTasks.add(it) }
    project.addModuleAllowedRulesTask(graphRules)?.also { childTasks.add(it) }

    allAssertionsTask.configure { allTask ->
      childTasks.forEach {
        allTask.dependsOn(it)
      }
    }
  }

  private fun Project.runAssertionsDirectly(graphRules: GraphRulesExtension) {
    val dependencyGraph = DependencyGraph.create(moduleGraph)

    if (graphRules.shouldAssertHeight()) {
      moduleTreeHeightAssert(graphRules).assert(dependencyGraph)
    }

    if (graphRules.shouldAssertRestricted()) {
      restrictedDependenciesAssert(graphRules).assert(dependencyGraph)
    }

    if (graphRules.shouldAssertAllowed()) {
      onlyAllowedAssert(graphRules).assert(dependencyGraph)
    }
  }

  private fun Project.addModuleGraphGeneration() {
    tasks.register(Tasks.GENERATE_GRAPHVIZ, GenerateModulesGraphTask::class.java) {
      it.dependencyGraph = moduleGraph
      it.aliases = aliases
      it.outputFilePath = GenerateModulesGraphTask.outputFilePath(this)
      it.onlyModuleToPrint = GenerateModulesGraphTask.onlyModuleToPrint(this)
    }
  }

  private fun Project.addModuleGraphStatisticsGeneration() {
    tasks.register(
      Tasks.GENERATE_GRAPH_STATISTICS,
      GenerateModulesGraphStatisticsTask::class.java
    ) {
      it.dependencyGraph = moduleGraph
    }
  }

  private fun Project.addMaxHeightTask(graphRules: GraphRulesExtension): TaskProvider<AssertGraphTask>? {
    if (graphRules.shouldAssertHeight()) {
      return tasks.register(Tasks.ASSERT_MAX_HEIGHT, AssertGraphTask::class.java) {
        it.assertion = moduleTreeHeightAssert(graphRules)
        it.dependencyGraph = moduleGraph
        it.outputs.upToDateWhen { true }
        it.group = VERIFICATION_GROUP
      }
    } else {
      return null
    }
  }

  private fun Project.moduleTreeHeightAssert(graphRules: GraphRulesExtension) =
    ModuleTreeHeightAssert(moduleNameForHeightAssert(), graphRules.maxHeight)

  private fun Project.addModuleRestrictionsTask(graphRules: GraphRulesExtension): TaskProvider<AssertGraphTask>? {
    if (graphRules.shouldAssertRestricted()) {
      return tasks.register(Tasks.ASSERT_RESTRICTIONS, AssertGraphTask::class.java) {
        it.assertion = restrictedDependenciesAssert(graphRules)
        it.dependencyGraph = moduleGraph
        it.outputs.upToDateWhen { true }
        it.group = VERIFICATION_GROUP
      }
    } else {
      return null
    }
  }

  private fun restrictedDependenciesAssert(graphRules: GraphRulesExtension) =
    RestrictedDependenciesAssert(graphRules.restricted, aliases)

  private fun Project.addModuleAllowedRulesTask(graphRules: GraphRulesExtension): TaskProvider<AssertGraphTask>? {
    if (graphRules.shouldAssertAllowed()) {
      return tasks.register(Tasks.ASSERT_ALLOWED, AssertGraphTask::class.java) {
        it.assertion = onlyAllowedAssert(graphRules)
        it.dependencyGraph = moduleGraph
        it.outputs.upToDateWhen { true }
        it.group = VERIFICATION_GROUP
      }
    } else {
      return null
    }
  }

  private fun onlyAllowedAssert(graphRules: GraphRulesExtension) =
    OnlyAllowedAssert(graphRules.allowed, aliases)
}

private fun Project.moduleNameForHeightAssert(): String? {
  if (this == rootProject) {
    return null
  } else {
    return moduleDisplayName()
  }
}

fun Project.moduleDisplayName(): String {
  return displayName.replace("project", "")
    .replace("'", "")
    .trim()
}
