/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSet;
import org.gradle.util.GradleVersion;
import org.openrewrite.gradle.RewriteReflectiveFacade.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public abstract class AbstractRewriteTask extends DefaultTask implements RewriteTask {

    private Configuration configuration;
    private List<Project> projects;
    private RewriteExtension extension;
    private RewriteReflectiveFacade rewrite;

    AbstractRewriteTask setConfiguration(Configuration configuration) {
        this.configuration = configuration;
        return this;
    }

    AbstractRewriteTask setProjects(List<Project> projects) {
        this.projects = projects;
        return this;
    }

    AbstractRewriteTask setExtension(RewriteExtension extension) {
        this.extension = extension;
        return this;
    }

    @Internal
    RewriteExtension getExtension() {
        return extension;
    }

    @Internal
    RewriteReflectiveFacade getRewrite() {
        if(rewrite == null) {
            rewrite = new RewriteReflectiveFacade(configuration, extension, this);
        }
        return rewrite;
    }

    @Internal
    protected abstract Logger getLog();

    @Input
    public SortedSet<String> getActiveRecipes() {
        String activeRecipeProp = System.getProperty("activeRecipe");
        if(activeRecipeProp == null) {
            return new TreeSet<>(extension.getActiveRecipes());
        } else {
            return new TreeSet<>(Collections.singleton(activeRecipeProp));
        }
    }

    @Input
    public SortedSet<String> getActiveStyles() {
        return new TreeSet<>(extension.getActiveStyles());
    }

    /**
     * The prefix used to left-pad log messages, multiplied per "level" of log message.
     */
    private static final String LOG_INDENT_INCREMENT = "    ";
    private static final int HOURS_PER_DAY = 24;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    private static final int SECONDS_PER_DAY = SECONDS_PER_HOUR * HOURS_PER_DAY;

    protected Environment environment() {
        Map<Object, Object> gradleProps = getProject().getProperties().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));

        Properties properties = new Properties();
        properties.putAll(gradleProps);

        EnvironmentBuilder env = getRewrite().environmentBuilder(properties)
                .scanRuntimeClasspath()
                .scanUserHome();
        List<Path> recipeJars = configuration.getFiles().stream()
                .map(File::toPath)
                .collect(toList());
        for(Path rewriteJar : recipeJars) {
            env.scanJar(rewriteJar);
        }

        File rewriteConfig = extension.getConfigFile();
        if (rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                YamlResourceLoader resourceLoader = getRewrite().yamlResourceLoader(is, rewriteConfig.toURI(), properties);
                env.load(resourceLoader);
            } catch (IOException e) {
                throw new RuntimeException("Unable to load rewrite configuration", e);
            }
        } else if (extension.getConfigFileSetDeliberately()) {
            getLog().warn("Rewrite configuration file " + rewriteConfig + " does not exist.");
        }

        return env.build();
    }

    protected InMemoryExecutionContext executionContext() {
        return getRewrite().inMemoryExecutionContext(t -> getLog().warn(t.getMessage(), t));
    }

    protected ResultsContainer listResults() {
        Path baseDir = getProject().getRootProject().getRootDir().toPath();
        Environment env = environment();
        Set<String> activeRecipes = getActiveRecipes();
        Set<String> activeStyles = getActiveStyles();
        getLog().lifecycle(String.format("Using active recipe(s) %s", activeRecipes));
        getLog().lifecycle(String.format("Using active styles(s) %s", activeStyles));
        if (activeRecipes.isEmpty()) {
            return new ResultsContainer(baseDir, emptyList());
        }
        List<NamedStyles> styles = env.activateStyles(activeStyles);
        File checkstyleConfig = extension.getCheckstyleConfigFile();
        if(checkstyleConfig != null && checkstyleConfig.exists()) {
            NamedStyles checkstyle = getRewrite().loadCheckstyleConfig(checkstyleConfig.toPath(), extension.getCheckstyleProperties());
            styles.add(checkstyle);
        }

        Recipe recipe = env.activateRecipes(activeRecipes);

        getLog().lifecycle("Validating active recipes");
        Collection<Validated> validated = recipe.validateAll();
        List<Validated.Invalid> failedValidations = validated.stream().map(Validated::failures)
                .flatMap(Collection::stream).collect(toList());
        if (!failedValidations.isEmpty()) {
            failedValidations.forEach(failedValidation -> getLog().error(
                    "Recipe validation error in " + failedValidation.getProperty() + ": " +
                            failedValidation.getMessage(), failedValidation.getException()));
            if (getExtension().getFailOnInvalidActiveRecipes()) {
                throw new RuntimeException("Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
            } else {
                getLog().error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
            }
        }

        InMemoryExecutionContext ctx = executionContext();
        List<SourceFile> sourceFiles = projects.stream()
                .flatMap(p -> parse(p, styles, ctx).stream())
                .collect(toList());

        getLog().lifecycle("Running recipe(s)...");
        List<Result> results = recipe.run(sourceFiles);

        return new ResultsContainer(baseDir, results);
    }

    protected List<SourceFile> parse(Project subproject, List<NamedStyles> styles, InMemoryExecutionContext ctx) {
        try {
            Path baseDir = getProject().getRootProject().getRootDir().toPath();

            @SuppressWarnings("deprecation")
            JavaPluginConvention javaConvention = subproject.getConvention().findPlugin(JavaPluginConvention.class);

            JavaProvenanceBuilder sharedProvenance = getRewrite().javaProvenanceBuilder()
                    .projectName(getProject().getName())
                    .buildToolVersion(GradleVersion.current().getVersion())
                    .vmRuntimeVersion(System.getProperty("java.runtime.version"))
                    .vmVendor(System.getProperty("java.vm.vendor"));

            Set<SourceSet> sourceSets;
            if(javaConvention == null) {
                sourceSets = emptySet();
            } else {
                sourceSets = javaConvention.getSourceSets();
                sharedProvenance.sourceCompatibility(javaConvention.getSourceCompatibility().toString())
                        .targetCompatibility(javaConvention.getTargetCompatibility().toString());
            }

            List<SourceFile> sourceFiles = new ArrayList<>();
            for(SourceSet sourceSet : sourceSets) {
                JavaProvenance javaProvenance = getRewrite()
                        .javaProvenanceBuilder(sharedProvenance)
                        .sourceSetName(sourceSet.getName())
                        .build();

                List<Path> javaPaths = sourceSet.getAllJava().getFiles().stream()
                        .filter(it -> it.isFile() && it.getName().endsWith(".java"))
                        .map(File::toPath)
                        .map(AbstractRewriteTask::toRealPath)
                        .collect(toList());

                List<Path> dependencyPaths = sourceSet.getCompileClasspath().getFiles().stream()
                        .map(File::toPath)
                        .map(AbstractRewriteTask::toRealPath)
                        .collect(toList());

                if(javaPaths.size() > 0) {
                    getLog().lifecycle("Parsing " + javaPaths.size() + " Java files from " + sourceSet.getAllJava().getSourceDirectories().getAsPath());
                    Instant start = Instant.now();
                    sourceFiles.addAll(map(getRewrite().javaParserFromJavaVersion()
                                    .relaxedClassTypeMatching(true)
                                    .styles(styles)
                                    .classpath(dependencyPaths)
                                    .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                                    .build()
                                    .parse(javaPaths, baseDir, ctx),
                            s -> s.withMarkers(s.getMarkers().addIfAbsent(javaProvenance))));
                    Instant end = Instant.now();
                    Duration duration = Duration.between(start, end);
                    getLog().lifecycle("Parsed " + javaPaths.size() + " Java files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(javaPaths.size())) + " per file)");
                }
            }
            JavaProvenance sourceSetAgnosticProvenance = sharedProvenance.build();
            List<Path> yamlPaths = new ArrayList<>();
            List<Path> propertiesPaths = new ArrayList<>();
            List<Path> xmlPaths = new ArrayList<>();
            Files.walk(subproject.getProjectDir().toPath())
                    .forEach(file -> {
                        String fileName = file.toString().toLowerCase();
                        if(fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
                            yamlPaths.add(file);
                        } else if(fileName.endsWith(".properties")) {
                            propertiesPaths.add(file);
                        } else if(fileName.endsWith(".xml")) {
                            xmlPaths.add(file);
                        }
                    });

            if (yamlPaths.size() > 0) {
                getLog().lifecycle("Parsing " + yamlPaths.size() + " YAML files from " + subproject.getProjectDir());
                Instant start = Instant.now();
                sourceFiles.addAll(map(getRewrite().yamlParser().parse(yamlPaths, baseDir, ctx),
                        s -> s.withMarkers(s.getMarkers().addIfAbsent(sourceSetAgnosticProvenance))));
                Instant end = Instant.now();
                Duration duration = Duration.between(start, end);
                getLog().lifecycle("Parsed " + yamlPaths.size() + " YAML files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(yamlPaths.size())) + " per file)");
            }

            if(propertiesPaths.size() > 0) {
                getLog().lifecycle("Parsing " + propertiesPaths.size() + " properties files from " + subproject.getProjectDir());
                Instant start = Instant.now();
                sourceFiles.addAll(map(getRewrite().propertiesParser().parse(propertiesPaths, baseDir, ctx),
                        s -> s.withMarkers(s.getMarkers().addIfAbsent(sourceSetAgnosticProvenance))));

                Instant end = Instant.now();
                Duration duration = Duration.between(start, end);
                getLog().lifecycle("Parsed " + propertiesPaths.size() + " properties files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(propertiesPaths.size())) + " per file)");
            }

            if (xmlPaths.size() > 0) {
                getLog().lifecycle("Parsing " + xmlPaths.size() + " XML files from " + subproject.getProjectDir());
                Instant start = Instant.now();
                sourceFiles.addAll(map(getRewrite().yamlParser().parse(yamlPaths, baseDir, ctx),
                        s -> s.withMarkers(s.getMarkers().addIfAbsent(sourceSetAgnosticProvenance))));

                Instant end = Instant.now();
                Duration duration = Duration.between(start, end);
                getLog().lifecycle("Parsed " + xmlPaths.size() + " XML files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(xmlPaths.size())) + " per file)");
            }

            return sourceFiles;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;
            for (Result result : results) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    refactoredInPlace.add(result);
                }
            }
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }
    }

    protected void logRecipesThatMadeChanges(Result result) {
        for (Recipe recipe : result.getRecipesThatMadeChanges()) {
            getLog().warn(indent(1, recipe.getName()));
        }
    }

    private static Path toRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    protected static String indent(int indent, CharSequence content) {
        StringBuilder prefix = repeat(indent, LOG_INDENT_INCREMENT);
        return prefix.append(content).toString();
    }

    private static StringBuilder repeat(int repeat, String str) {
        StringBuilder buffer = new StringBuilder(repeat * str.length());
        for (int i = 0; i < repeat; i++) {
            buffer.append(str);
        }
        return buffer;
    }

    private static String prettyPrint(Duration duration) {
        StringBuilder result = new StringBuilder();
        long days = duration.getSeconds() / SECONDS_PER_DAY;
        boolean startedPrinting = false;
        if(days > 0) {
            startedPrinting = true;
            result.append(days);
            result.append(" day");
            if(days != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long hours =  duration.toHours() % 24;
        if(startedPrinting || hours > 0) {
            startedPrinting = true;
            result.append(hours);
            result.append(" hour");
            if(hours != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long minutes = (duration.getSeconds() / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR;
        if(startedPrinting || minutes > 0) {
            result.append(minutes);
            result.append(" minute");
            if(minutes != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long seconds = duration.getSeconds() % SECONDS_PER_MINUTE;
        if(startedPrinting || seconds > 0) {
            result.append(seconds);
            result.append(" second");
            if (seconds != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long millis = duration.getNano() / 1000_000;
        result.append(millis);
        result.append(" millisecond");
        if(millis != 1) {
            result.append("s");
        }

        return result.toString();
    }

    @SuppressWarnings("ConstantConditions")
    public static <T> List<T> map(List<T> ls, UnaryOperator<T> map) {
        if (ls == null || ls.isEmpty()) {
            return ls;
        }
        List<T> newLs = ls;
        for (int i = 0; i < ls.size(); i++) {
            T tree = ls.get(i);
            T newTree = map.apply(tree);
            if (newTree != tree) {
                if (newLs == ls) {
                    newLs = new ArrayList<>(ls);
                }
                newLs.set(i, newTree);
            }
        }
        if (newLs != ls) {
            //noinspection StatementWithEmptyBody
            while (newLs.remove(null)) ;
        }

        return newLs;
    }
}
