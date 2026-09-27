import org.jooq.meta.jaxb.*
import org.jooq.tools.ClassUtils

import java.io.File

object JooqCodeGenScript {
  def main(args: Array[String]): Unit = {
    val outputDir = args(0)
    val jooqConfig = new Configuration()
      .withJdbc(new Jdbc()
        .withDriver("org.postgresql.Driver")
        .withUrl("jdbc:postgresql://localhost:5432/bor_dev")
        .withUser("bor_dev_user")
        .withPassword("dev")
      )
      .withGenerator(new Generator()
        .withName("org.jooq.codegen.Scala3Generator")
        .withStrategy(new Strategy()
          .withName("custom.SchemaPrefixGeneratorStrategy")
        )
        .withDatabase(new Database()
          .withName("org.jooq.meta.postgres.PostgresDatabase")
          .withIncludes(".*")
          .withSchemata(
            new SchemaMappingType().withInputSchema("public"),
            new SchemaMappingType().withInputSchema("stripe"),
            new SchemaMappingType().withInputSchema("metronome"),
          )
          .withForcedTypes(

            new ForcedType()
              .withName("instant")
              .withTypes("timestamp\\ with\\ time\\ zone")
          )
        )
        .withTarget(new Target()
          .withPackageName("jooq.generated")
          .withDirectory(new File(outputDir).getParent)
        )
      )

    org.jooq.codegen.GenerationTool.generate(jooqConfig);
  }
}
