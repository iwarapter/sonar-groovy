/*
 * Sonar Groovy Plugin
 * Copyright (C) 2010 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.groovy.codenarc;

import com.google.common.collect.ImmutableList;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.codenarc.CodeNarcRunner;
import org.codenarc.analyzer.FilesystemSourceAnalyzer;
import org.codenarc.report.XmlReportWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.issue.Issuable;
import org.sonar.api.issue.Issue;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.rules.Rule;
import org.sonar.api.rules.RuleFinder;
import org.sonar.api.rules.RuleQuery;
import org.sonar.api.scan.filesystem.ModuleFileSystem;
import org.sonar.plugins.groovy.GroovyPlugin;
import org.sonar.plugins.groovy.codenarc.CodeNarcXMLParser.CodeNarcViolation;
import org.sonar.plugins.groovy.foundation.GroovyFileSystem;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CodeNarcSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(CodeNarcSensor.class);

  private final Settings settings;
  private final ResourcePerspectives perspectives;
  private final ModuleFileSystem moduleFileSystem;
  private final FileSystem fileSystem;
  private final RulesProfile rulesProfile;
  private final RuleFinder ruleFinder;
  private final GroovyFileSystem groovyFileSystem;

  public CodeNarcSensor(
    Settings settings,
    ResourcePerspectives perspectives,
    ModuleFileSystem moduleFileSystem,
    FileSystem fileSystem,
    RulesProfile profile,
    RuleFinder ruleFinder) {
    this.settings = settings;
    this.perspectives = perspectives;
    this.moduleFileSystem = moduleFileSystem;
    this.fileSystem = fileSystem;
    this.rulesProfile = profile;
    this.ruleFinder = ruleFinder;
    this.groovyFileSystem = new GroovyFileSystem(fileSystem);
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    return groovyFileSystem.hasGroovyFiles() && !rulesProfile.getActiveRulesByRepository(CodeNarcRulesDefinition.REPOSITORY_KEY).isEmpty();
  }

  @Override
  public void analyse(Project project, SensorContext context) {
    // Should we reuse existing report from CodeNarc ?
    String codeNarcReportPath = settings.getString(GroovyPlugin.CODENARC_REPORT_PATH);
    if (StringUtils.isNotBlank(codeNarcReportPath)) {
      // Yes
      File report = new File(codeNarcReportPath);
      if (!report.isAbsolute()) {
        report = new File(fileSystem.baseDir(), codeNarcReportPath);
      }
      if (report == null || !report.isFile()) {
        LOG.warn("Groovy report " + GroovyPlugin.CODENARC_REPORT_PATH + " not found at {}", report);
        return;
      }
      parse(Collections.singletonList(report), context);
    } else {
      // No, run CodeNarc
      List<File> reports = executeCodeNarc();
      parse(reports, context);
    }
  }

  private void parse(List<File> reports, SensorContext context) {
    for (File report : reports) {
      Collection<CodeNarcViolation> violations = CodeNarcXMLParser.parse(report, fileSystem);
      for (CodeNarcViolation violation : violations) {
        RuleQuery ruleQuery = RuleQuery.create()
          .withRepositoryKey(CodeNarcRulesDefinition.REPOSITORY_KEY)
          .withConfigKey(violation.getRuleName());
        Rule rule = ruleFinder.find(ruleQuery);
        if (rule != null) {
          InputFile sonarFile = fileSystem.inputFile(fileSystem.predicates().hasAbsolutePath(violation.getFilename()));
          if (sonarFile != null) {
            insertIssue(violation, rule, sonarFile);
          }
        } else {
          LOG.warn("No such rule in Sonar, so violation from CodeNarc will be ignored: ", violation.getRuleName());
        }
      }
    }
  }

  private void insertIssue(CodeNarcViolation violation, Rule rule, InputFile sonarFile) {
    Issuable issuable = perspectives.as(Issuable.class, sonarFile);
    if (issuable != null) {
      Issue issue = issuable.newIssueBuilder()
        .ruleKey(rule.ruleKey())
        .line(violation.getLine())
        .message(violation.getMessage())
        .build();
      issuable.addIssue(issue);
    }
  }

  /**
   * @return list of files with generated reports
   */
  private List<File> executeCodeNarc() {
    LOG.info("Executing CodeNarc");

    File workdir = new File(fileSystem.workDir(), "/codenarc/");
    prepareWorkDir(workdir);

    File codeNarcConfiguration = new File(workdir, "profile.xml");
    exportCodeNarcConfiguration(codeNarcConfiguration);
    ImmutableList.Builder<File> result = ImmutableList.builder();
    int i = 1;
    for (File sourceDir : moduleFileSystem.sourceDirs()) {
      CodeNarcRunner runner = new CodeNarcRunner();
      // TODO SONARGROOV-24
      FilesystemSourceAnalyzer analyzer = new FilesystemSourceAnalyzer();

      // only one source directory
      analyzer.setBaseDirectory(sourceDir.getAbsolutePath());
      analyzer.setIncludes("**/*.groovy");
      runner.setSourceAnalyzer(analyzer);

      // generated XML report
      XmlReportWriter xmlReport = new XmlReportWriter();
      xmlReport.setTitle("Sonar");
      File reportFile = new File(workdir, "report" + i + ".xml");
      xmlReport.setDefaultOutputFile(reportFile.getAbsolutePath());
      runner.setReportWriters(Arrays.asList(xmlReport));

      runner.setRuleSetFiles("file:" + codeNarcConfiguration.getAbsolutePath());

      runner.execute();
      result.add(reportFile);
      i++;
    }
    return result.build();
  }

  private void exportCodeNarcConfiguration(File file) {
    try {
      StringWriter writer = new StringWriter();
      new CodeNarcProfileExporter(writer).exportProfile(rulesProfile);
      FileUtils.writeStringToFile(file, writer.toString());
    } catch (IOException e) {
      throw new IllegalStateException("Can not generate CodeNarc configuration file", e);
    }
  }

  private static void prepareWorkDir(File dir) {
    try {
      FileUtils.forceMkdir(dir);
      // directory is cleaned, because Sonar 3.0 will not do this for us
      FileUtils.cleanDirectory(dir);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create directory: " + dir, e);
    }
  }

  @Override
  public String toString() {
    return "CodeNarc";
  }

}
