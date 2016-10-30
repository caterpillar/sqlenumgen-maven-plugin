/*
 * Copyright (C) 2016 Nicolai Ehemann (en@enlightened.de).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.enlightened.maven.plugin.sqlenumgen;

import de.enlightened.maven.plugin.sqlenumgen.util.Column;
import de.enlightened.maven.plugin.sqlenumgen.configuration.Configuration;
import de.enlightened.maven.plugin.sqlenumgen.configuration.EnumCfg;
import de.enlightened.maven.plugin.sqlenumgen.configuration.GeneratorCfg;
import de.enlightened.maven.plugin.sqlenumgen.configuration.JDBCCfg;
import de.enlightened.maven.plugin.sqlenumgen.repr.EnumRepr;
import de.enlightened.maven.plugin.sqlenumgen.repr.MemberRepr;
import de.enlightened.maven.plugin.sqlenumgen.repr.ValueRepr;
import de.enlightened.maven.plugin.sqlenumgen.util.SqlType;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections4.map.LinkedMap;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.apache.velocity.tools.generic.DisplayTool;

/**
 * Generates enum classes from database tables
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, requiresProject = true, threadSafe = true)
public class SqlEnumGeneratorMojo extends AbstractMojo {

  static final String DEFAULT_OUTPUT_DIRECTORY = "target/generated-sources/sql-enum";
  static final String DEFAULT_PACKAGE = "de.enlightened.sqlenum";
  static final String VELOCITY_TEMPLATE = "enum.vtl";

  static final ApplicationProperties CONFIGURATION = ApplicationProperties.getInstance();

  /**
   * The Maven Project Object
   */
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  /**
   * The JDBC class
   */
  @Parameter(required = true)
  private JDBCCfg jdbc;

  /**
   * The generator configuration
   */
  @Parameter(required = true)
  private GeneratorCfg generator;

  private final FileSystem fileSystem;

  private final VelocityEngine velocityEngine;

  SqlEnumGeneratorMojo(final FileSystem fileSystem, final VelocityEngine velocityEngine) {
    this.fileSystem = fileSystem;
    this.velocityEngine = velocityEngine;
  }

  SqlEnumGeneratorMojo(final VelocityEngine velocityEngine) {
    this(FileSystems.getDefault(), velocityEngine);
  }

  SqlEnumGeneratorMojo(final FileSystem fileSystem) {
    this(fileSystem,  new VelocityEngine());
  }

  public SqlEnumGeneratorMojo() {
    this(FileSystems.getDefault(), new VelocityEngine());
  }

  @Override
  public final void execute() throws MojoFailureException {
    final Template template = this.loadTemplate();

    try (Connection connection = this.jdbc.getConnection()) {
      for (final EnumCfg enumCfg : this.generator.getDatabase().getEnums()) {
        final LinkedMap<String, Column> columns = readColumnsFromDB(connection, enumCfg);
        this.completeEnumCfgFromColumns(enumCfg, columns);
        final List<String> enumNames;
        if (enumCfg.getNameColumn() == null) {
          enumNames = Arrays.asList(enumCfg.getName());
        } else {
          enumNames = this.readEnumNamesFromDB(connection, enumCfg);
        }
        for (final String enumName : enumNames) {
          enumCfg.setName(enumName);
          final EnumRepr enumRepr = this.generateEnumRepr(connection, enumCfg, columns);

          final VelocityContext context = this.createContext(enumRepr);

          this.project.addCompileSourceRoot(
              fileSystem
                  .getPath(this.generator.getTarget().getDirectory())
                  .toAbsolutePath().toString());
          this.generateEnum(template, enumRepr.getName(), context);
        }
      }
    } catch (SQLException exception) {
      throw new MojoFailureException("Mojo execution failed due to database error (" + exception.getMessage() + ").");
    }
  }

  private void generateEnum(final Template template, final String enumName, final VelocityContext context) throws MojoFailureException {
    try {
      final Path pkgDirectory = this.getPackageDirectory();
      Files.createDirectories(pkgDirectory.toAbsolutePath());

      final Path classFile = pkgDirectory.toAbsolutePath().resolve(enumName + ".java");
      try (BufferedWriter writer = Files.newBufferedWriter(classFile)) {
        template.merge(context, writer);
        writer.flush();
      }
    } catch (IOException exception) {
      throw new MojoFailureException("Mojo execution failed due to I/O error (" + exception.getMessage() + ").");
    }
  }

  private EnumCfg completeEnumCfgFromColumns(final EnumCfg enumCfg, final LinkedMap<String, Column> columns) throws MojoFailureException, SQLException {

    if (columns.size() == 0) {
      throw new MojoFailureException(String.format("No columns found for enum %s.", enumCfg.getName()));
    }

    if (enumCfg.getNameColumn() != null) {
      if (columns.indexOf(enumCfg.getNameColumn()) == -1) {
        throw new MojoFailureException(String.format("Configured nameColumn missing for enum %s.", enumCfg.getName()));
      } else if (!columns.get(enumCfg.getNameColumn()).getType().getJavaClass().equals(String.class)) {
        throw new MojoFailureException(String.format("Configured nameColumn %s for enum must have String representation (for enum name).", enumCfg.getNameColumn()));
      }
      columns.remove(enumCfg.getNameColumn());
    }

    if (columns.size() == 1) {
      final Column column = columns.getValue(0);
      if (!column.getType().getJavaClass().equals(String.class)) {
        throw new MojoFailureException(String.format("Only column for enum %s must have String representation (for enum value).", enumCfg.getName()));
      }
      if (enumCfg.getValueColumn() == null) {
        enumCfg.setValueColumn(columns.getValue(0).getName());
      } else if (enumCfg.getValueColumn() != columns.getValue(0).getName()) {
        throw new MojoFailureException(String.format("Only column does not match configured name column for enum %s.", enumCfg.getName()));
      }
    } else {
      if (enumCfg.getValueColumn() == null) {
        for (final Column column : columns.values()) {
          if (column.getType().equals(SqlType.VARCHAR)) {
            enumCfg.setValueColumn(column.getName());
            break;
          }
        }
        if (enumCfg.getValueColumn() == null) {
          throw new MojoFailureException(String.format("Enum %s must have at least one column with String representation (for enum value).", enumCfg.getName()));
        }
      }
    }
    return enumCfg;
  }

  private Template loadTemplate() {
    final String templatePath = VELOCITY_TEMPLATE;
    final URI uri;
    try {
      uri = getClass()
          .getClassLoader()
          .getResource(templatePath)
          .toURI();
      this.velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
      this.velocityEngine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
      this.velocityEngine.init();
      return this.velocityEngine.getTemplate(templatePath);
    } catch (URISyntaxException | NullPointerException ex) {
      throw new RuntimeException("Error loading template (invalid syntax or missing on classpath): '" + templatePath + "'");
    }
  }

  public final void setProject(final MavenProject project) {
    this.project = project;
  }

  public final void setConfiguration(final Configuration configuration) {
    this.jdbc = configuration.getJdbc();
    this.generator = configuration.getGenerator();
  }

  private VelocityContext createContext(final EnumRepr enumRepr) {
    final VelocityContext context = new VelocityContext();

    context.put("display", new DisplayTool());

    // sqlenumgen properties
    context.put("version", CONFIGURATION.PROJECT_VERSION);
    context.put("url", CONFIGURATION.PROJECT_URL);

    // build specific properties
    context.put("date", ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT));

    // project configuration
    context.put("package", this.generator.getTarget().getPackage());

    // database-generated properties
    context.put("schema-version", ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT));

    context.put("enum", enumRepr);

    return context;
  }

  private Path getPackageDirectory() {
    return this.fileSystem.getPath(
        this.generator.getTarget().getDirectory(),
        this.generator.getTarget().getPackage().replace('.', '/'));
  }

  private List<String> readEnumNamesFromDB(final Connection connection, final EnumCfg enumCfg) throws SQLException {
    final List<String> enumNames = new ArrayList<>();
    try (
      PreparedStatement stmt = connection.prepareStatement("SELECT DISTINCT " + enumCfg.getNameColumn() + " FROM " + enumCfg.getTable());
      ResultSet result = stmt.executeQuery();
    ) {
      while (result.next()) {
        enumNames.add(result.getString(enumCfg.getNameColumn()));
      }
    }
    return enumNames;
  }

  private EnumRepr generateEnumRepr(final Connection connection, final EnumCfg enumCfg, final LinkedMap<String, Column> columns) throws MojoFailureException, SQLException {
    final EnumRepr enumRepr = new EnumRepr(enumCfg.getName());
    for (final Column column : columns.values()) {
      if (!column.getName().equals(enumCfg.getValueColumn())) {
        enumRepr.addMember(column.getName(), column.getType());
      }
    }
    final String condition;
    if (enumCfg.getNameColumn() == null) {
      condition = "";
    } else {
      condition = " WHERE " + enumCfg.getNameColumn() + "='" + enumCfg.getName() + "'";
    }

    try (
        PreparedStatement stmt = connection.prepareStatement("SELECT * FROM " + enumCfg.getTable() + condition);
        ResultSet result = stmt.executeQuery()
    ) {
      while (result.next()) {
        final String value = result.getString(enumCfg.getValueColumn());
        if (enumRepr.hasValue(value)) {
          throw new MojoFailureException(String.format("Duplicate enum entry '%s' for enum %s.", value, enumRepr.getName()));
        }
        final Map<String, String> memberValues = new HashMap<String, String>();
        for (final MemberRepr member : enumRepr.getMembers()) {
          final Object memberValue = result.getObject(member.getName());
          if (memberValue == null) {
            memberValues.put(member.getName(), String.format(columns.get(member.getName()).getType().getLiteralFormat(), "null"));
          } else {
            memberValues.put(member.getName(), String.format(columns.get(member.getName()).getType().getLiteralFormat(), memberValue.toString()));
          }
        }
        enumRepr.addValue(new ValueRepr(value, memberValues));
      }
    }
    return enumRepr;
  }

  private LinkedMap<String, Column> readColumnsFromDB(final Connection connection, final EnumCfg enumCfg) throws SQLException {
      final LinkedMap<String, Column> columns = new LinkedMap<String, Column>();
      final DatabaseMetaData metaData = connection.getMetaData();
      try (ResultSet result = metaData.getColumns(null, this.generator.getDatabaseSchema(), enumCfg.getTable(), null)) {
        while (result.next()) {
          System.out.println("TPYE" + result.getString("DATA_TYPE"));
          final Column column = new Column(result.getString("COLUMN_NAME"), SqlType.valueOf(result.getInt("DATA_TYPE")));
          columns.put(column.getName(), column);
        }
      }
      return columns;
  }
}
