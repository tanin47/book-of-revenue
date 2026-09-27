package custom;

import org.jooq.codegen.DefaultGeneratorStrategy;
import org.jooq.meta.Definition;
import org.jooq.meta.TableDefinition;

public class SchemaPrefixGeneratorStrategy extends DefaultGeneratorStrategy {
  @Override
  public String getJavaClassName(Definition definition, Mode mode) {
    // Fall back to the default naming logic (e.g., PascalCase table names)
    String defaultClassName = super.getJavaClassName(definition, mode);

    // Grab the schema name if it exists on this database object
    if (definition.getSchema() != null && !"public".equals(definition.getSchema().getName())) {
      String schemaName = definition.getSchema().getOutputName();

      // Format the schema name as desired (e.g., capitalized or camel case)
      String prefix = schemaName.substring(0, 1).toUpperCase() + schemaName.substring(1);
      return prefix + defaultClassName;
    }

    return defaultClassName;
  }

  @Override
  public String getJavaIdentifier(Definition definition) {
    if (definition instanceof TableDefinition) {
      if (definition.getSchema() != null && !"public".equals(definition.getSchema().getName())) {
        String schemaName = definition.getSchema().getOutputName();
        return schemaName.toUpperCase() + "_" + super.getJavaIdentifier(definition);
      }
    }
    return super.getJavaIdentifier(definition);
  }
}
