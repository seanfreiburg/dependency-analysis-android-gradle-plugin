package com.autonomousapps.tasks

import com.autonomousapps.TASK_GROUP_DEP
import com.autonomousapps.graph.Graphs.shortestPath
import com.autonomousapps.internal.unsafeLazy
import com.autonomousapps.internal.utils.*
import com.autonomousapps.internal.utils.Colors.colorize
import com.autonomousapps.model.*
import com.autonomousapps.model.declaration.SourceSetKind
import com.autonomousapps.model.declaration.Variant
import com.autonomousapps.model.intermediates.Reason
import com.autonomousapps.model.intermediates.Usage
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.support.appendReproducibleNewLine

abstract class ReasonTask : DefaultTask() {

  init {
    group = TASK_GROUP_DEP
    description = "Explain how a dependency is used"
  }

  @get:Input
  abstract val projectPath: Property<String>

  // Not really optional, but we want to handle validation ourselves, rather than let Gradle do it
  @get:Optional
  @get:Input
  @set:Option(
    option = "id",
    description = "The dependency you'd like to reason about (com.foo:bar:1.0 or :other:module)"
  )
  var id: String? = null

  @get:PathSensitive(PathSensitivity.NONE)
  @get:InputFile
  abstract val dependencyUsageReport: RegularFileProperty

  @get:PathSensitive(PathSensitivity.NONE)
  @get:InputFile
  abstract val annotationProcessorUsageReport: RegularFileProperty

  @get:PathSensitive(PathSensitivity.NONE)
  @get:InputFile
  abstract val bundleTracesReport: RegularFileProperty

  @get:PathSensitive(PathSensitivity.NONE)
  @get:InputFile
  abstract val unfilteredAdviceReport: RegularFileProperty

  @get:PathSensitive(PathSensitivity.NONE)
  @get:InputFile
  abstract val finalAdviceReport: RegularFileProperty

  @get:PathSensitive(PathSensitivity.NONE)
  @get:InputFile
  abstract val graph: RegularFileProperty

  // TODO InputDirectory of all dependencies for finding capabilities

  private val coord by unsafeLazy { getRequestedCoordinates() }
  private val dependencyUsages by unsafeLazy { dependencyUsageReport.fromJsonMapSet<String, Usage>() }
  private val annotationProcessorUsages by unsafeLazy { annotationProcessorUsageReport.fromJsonMapSet<String, Usage>() }
  private val unfilteredProjectAdvice by unsafeLazy { unfilteredAdviceReport.fromJson<ProjectAdvice>() }
  private val finalProjectAdvice by unsafeLazy { finalAdviceReport.fromJson<ProjectAdvice>() }
  private val finalAdvice by unsafeLazy { findAdviceIn(finalProjectAdvice) }
  private val unfilteredAdvice by unsafeLazy { findAdviceIn(unfilteredProjectAdvice) }

  @TaskAction fun action() {
    val usages = getUsageFor(coord.gav())
    val graph = graph.fromJson<DependencyGraphView>()

    val reason = DeepThought(
      project = ProjectCoordinates(projectPath.get()),
      coordinates = coord,
      usages = usages,
      advice = finalAdvice,
      graph = graph,
      wasInBundle = wasInBundle(),
      wasFiltered = wasFiltered()
    ).computeReason()

    logger.quiet(reason)
  }

  /** Returns the requested ID as [Coordinates], even if user passed in a prefix. */
  private fun getRequestedCoordinates(): Coordinates {
    val id: String = id ?: throw InvalidUserDataException(
      """
        You must call 'reason' with the `--id` option. For example:
          ./gradlew ${projectPath.get()}:reason --id com.foo:bar:1.0
          ./gradlew ${projectPath.get()}:reason --id :other:module
          
        For external dependencies, the version string is optional.
      """.trimIndent()
    )

    // Guaranteed to find full GAV or throw
    val gav = dependencyUsages.entries.find(id::equalsKey)?.key
      ?: dependencyUsages.entries.find(id::startsWithKey)?.key
      ?: annotationProcessorUsages.entries.find(id::equalsKey)?.key
      ?: annotationProcessorUsages.entries.find(id::startsWithKey)?.key
      ?: throw InvalidUserDataException("There is no dependency with coordinates '$id' in this project.")
    return Coordinates.of(gav)
  }

  private fun getUsageFor(id: String): Set<Usage> {
    // One of these is guaranteed to be non-null
    return dependencyUsages.entries.find(id::equalsKey)?.value?.toSortedSet(Usage.BY_VARIANT)
      ?: annotationProcessorUsages.entries.find(id::equalsKey)?.value?.toSortedSet(Usage.BY_VARIANT)
      // This should really be impossible
      ?: throw InvalidUserDataException("No usage found for coordinates '$id'.")
  }

  private fun findAdviceIn(projectAdvice: ProjectAdvice): Advice? {
    // Would be null if there is no advice for the given id.
    return projectAdvice.dependencyAdvice.find { it.coordinates.gav() == coord.gav() }
  }

  private fun wasInBundle(): Boolean {
    val gav = coord.gav()
    return bundleTracesReport.fromJsonSet<String>().find { it == gav } != null
  }

  private fun wasFiltered(): Boolean = finalAdvice == null && unfilteredAdvice != null

  internal class DeepThought(
    private val project: ProjectCoordinates,
    private val coordinates: Coordinates,
    private val usages: Set<Usage>,
    private val advice: Advice?,
    private val graph: DependencyGraphView,
    private val wasInBundle: Boolean,
    private val wasFiltered: Boolean
  ) {

    fun computeReason() = buildString {
      // Header
      appendReproducibleNewLine()
      append(Colors.BOLD)
      appendReproducibleNewLine("-".repeat(40))
      append("You asked about the dependency '${coordinates.gav()}'.")
      appendReproducibleNewLine(Colors.NORMAL)
      appendReproducibleNewLine(adviceText())
      append(Colors.BOLD)
      append("-".repeat(40))
      appendReproducibleNewLine(Colors.NORMAL)

      // Shortest path
      val nodes = graph.graph.shortestPath(source = project, target = coordinates)
      appendReproducibleNewLine()
      append(Colors.BOLD)
      append("Shortest path from ${project.gav()} to ${coordinates.gav()}:")
      appendReproducibleNewLine(Colors.NORMAL)
      appendReproducibleNewLine(project.gav())
      nodes.drop(1).forEachIndexed { i, node ->
        append("      ".repeat(i))
        append("\\--- ")
        appendReproducibleNewLine(node.gav())
      }

      // Usages
      usages.forEach { usage ->
        val variant = usage.variant

        appendReproducibleNewLine()
        sourceText(variant).let { txt ->
          append(Colors.BOLD)
          appendReproducibleNewLine(txt)
          append("-".repeat(txt.length))
          appendReproducibleNewLine(Colors.NORMAL)
        }

        val reasons = usage.reasons.filter { it !is Reason.Unused && it !is Reason.Undeclared }
        val isCompileOnly = reasons.any { it is Reason.CompileTimeAnnotations }
        reasons.forEach { reason ->
          append("""* """)
          val prefix = if (variant.kind == SourceSetKind.MAIN) "" else "test"
          appendReproducibleNewLine(reason.reason(prefix, isCompileOnly))
        }
        if (reasons.isEmpty()) {
          appendReproducibleNewLine("(no usages)")
        }
      }
    }

    private fun adviceText(): String = when {
      advice == null -> {
        if (wasInBundle) {
          val bundle = "bundle".colorize(Colors.BOLD)
          "There is no advice regarding this dependency. It was removed because it matched a $bundle rule."
        } else if (wasFiltered) {
          val exclude = "exclude".colorize(Colors.BOLD)
          "There is no advice regarding this dependency. It was removed because it matched an $exclude rule."
        } else {
          "There is no advice regarding this dependency."
        }
      }
      advice.isAdd() || advice.isCompileOnly() -> {
        "You have been advised to add this dependency to '${advice.toConfiguration!!.colorize(Colors.GREEN)}'."
      }
      advice.isRemove() || advice.isProcessor() -> {
        "You have been advised to remove this dependency from '${advice.fromConfiguration!!.colorize(Colors.RED)}'."
      }
      advice.isChange() || advice.isRuntimeOnly() -> {
        "You have been advised to change this dependency to '${advice.toConfiguration!!.colorize(Colors.GREEN)}' " +
          "from '${advice.fromConfiguration!!.colorize(Colors.YELLOW)}'."
      }
      else -> error("Unknown advice type: $advice")
    }

    private fun sourceText(variant: Variant): String = when (variant.variant) {
      Variant.MAIN_NAME, Variant.TEST_NAME -> "Source: ${variant.variant}"
      else -> "Source: ${variant.variant}, ${variant.kind.name.lowercase()}"
    }
  }
}

private fun <T> String.equalsKey(mapEntry: Map.Entry<String, T>) = mapEntry.key == this
private fun <T> String.startsWithKey(mapEntry: Map.Entry<String, T>) = mapEntry.key.startsWith(this)
