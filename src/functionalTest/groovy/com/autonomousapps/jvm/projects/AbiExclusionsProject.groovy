package com.autonomousapps.jvm.projects

import com.autonomousapps.AbstractProject
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.Plugin
import com.autonomousapps.kit.Source
import com.autonomousapps.kit.SourceType
import com.autonomousapps.model.Advice
import com.autonomousapps.model.ProjectAdvice

import static com.autonomousapps.AdviceHelper.*
import static com.autonomousapps.kit.Dependency.okHttp
import static com.autonomousapps.kit.Dependency.openTelemetry

final class AbiExclusionsProject extends AbstractProject {

  private final okhttp = okHttp('api')
  private final openTelemetry = openTelemetry('implementation')
  final GradleProject gradleProject

  AbiExclusionsProject() {
    this.gradleProject = build()
  }

  private GradleProject build() {
    def builder = newGradleProjectBuilder()
    builder.withRootProject { r ->
      r.withBuildScript { bs ->
        bs.additions = """\
          dependencyAnalysis {
            abi {
              exclusions {
                excludeClasses("com\\\\.example\\\\.Main")
                excludeAnnotations("io\\\\.opentelemetry\\\\.extension\\\\.annotations\\\\.WithSpan")
              }
            }
          }
        """.stripIndent()
      }
    }
    builder.withSubproject('proj') { s ->
      s.sources = sources
      s.withBuildScript { bs ->
        bs.plugins = [Plugin.javaLibraryPlugin]
        bs.dependencies = [okhttp, openTelemetry]
      }
    }

    def project = builder.build()
    project.writer().write()
    return project
  }

  def sources = [
    new Source(
      SourceType.JAVA, 'Main', 'com/example',
      """\
        package com.example;
        
        import okhttp3.OkHttpClient;
        
        public class Main {
          public Main() {}
        
          public OkHttpClient ok() {
            return new OkHttpClient.Builder().build();
          }
        }
      """.stripIndent()
    ),
    new Source(
      SourceType.JAVA, 'UsesAnnotation', 'com/example',
      """\
        package com.example;
        
        import io.opentelemetry.extension.annotations.WithSpan;
        
        public class UsesAnnotation {
          @WithSpan
          public UsesAnnotation() {}
        }
      """.stripIndent()
    )
  ]

  Set<ProjectAdvice> actualProjectAdvice() {
    return actualProjectAdvice(gradleProject)
  }

  private final changeAdvice = [Advice.ofChange(
    moduleCoordinates(okhttp), okhttp.configuration, 'implementation'
  )] as Set<Advice>

  final Set<ProjectAdvice> expectedProjectAdvice = [
    projectAdviceForDependencies(':proj', changeAdvice)
  ]
}
