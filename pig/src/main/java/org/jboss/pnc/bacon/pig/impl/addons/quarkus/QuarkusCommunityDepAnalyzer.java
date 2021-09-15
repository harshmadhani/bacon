package org.jboss.pnc.bacon.pig.impl.addons.quarkus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.collect.Sets;
import lombok.SneakyThrows;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jboss.pnc.bacon.pig.impl.PigContext;
import org.jboss.pnc.bacon.pig.impl.addons.AddOn;
import org.jboss.pnc.bacon.pig.impl.addons.runtime.CommunityDepAnalyzer;
import org.jboss.pnc.bacon.pig.impl.config.PigConfiguration;
import org.jboss.pnc.bacon.pig.impl.documents.Deliverables;
import org.jboss.pnc.bacon.pig.impl.pnc.PncBuild;
import org.jboss.pnc.bacon.pig.impl.utils.FileUtils;
import org.jboss.pnc.bacon.pig.impl.utils.GAV;
import org.jboss.pnc.bacon.pig.impl.utils.MavenRepositoryUtils;
import org.jboss.pnc.bacon.pig.impl.utils.OSCommandExecutor;
import org.jboss.pnc.bacon.pig.impl.utils.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.jboss.pnc.bacon.pig.impl.utils.FileUtils.mkTempDir;

/**
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com <br>
 *         Date: 12/08/2019
 */
public class QuarkusCommunityDepAnalyzer extends AddOn {
    private static final Logger log = LoggerFactory.getLogger(QuarkusCommunityDepAnalyzer.class);

    private static final Set<String> importantScopes = Sets.newHashSet("compile", "runtime");

    private static final ObjectMapper jsonMapper;
    private static final Set<String> skipped = new HashSet<>();
    public static final String NAME = "quarkusCommunityDepAnalyzer";

    private final Deliverables deliverables;

    private String repoDefinition;
    private Path repoPath;
    private String quarkusVersion;
    private Collection<String> repoZipContents;

    static {
        jsonMapper = new ObjectMapper();
        jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public QuarkusCommunityDepAnalyzer(
            PigConfiguration pigConfiguration,
            Map<String, PncBuild> builds,
            String releasePath,
            String extrasPath,
            Deliverables deliverables) {
        super(pigConfiguration, builds, releasePath, extrasPath);
        this.deliverables = deliverables;
    }

    @Override
    protected String getName() {
        return NAME;
    }

    private String getBomArtifactId() {
        return PigContext.get().getPigConfiguration().getFlow().getRepositoryGeneration().getBomArtifactId();
    }

    private boolean isProductBom(String bomArtifactId) {
        return bomArtifactId.equals("quarkus-product-bom");
    }

    @Override
    public void trigger() {
        log.info("releasePath: {}, extrasPath: {}, deliverables: {}", releasePath, extrasPath, deliverables);
        skipped.addAll(skippedExtensions());

        if (PigContext.get().getRepositoryData() == null) {
            throw new RuntimeException(
                    "No repository data available for document generation. Please make sure to run `pig repo` before");
        }

        unpackRepository(PigContext.get().getRepositoryData().getRepositoryPath());

        String additionalRepository = (String) getAddOnConfiguration().get("additionalRepository");
        String settingsSelector = "";
        if (additionalRepository != null) {
            try {
                String settingsTemplate = ResourceUtils.getResourceAsString("/settings-template.xml");
                String settings = settingsTemplate.replace("ADD_REPO_URL", additionalRepository);
                File settingsFile = File.createTempFile("settings-for-dep-analysis", ".xml");
                org.apache.commons.io.FileUtils.write(settingsFile, settings, "UTF-8");
                settingsSelector = " -s " + settingsFile.getAbsolutePath();
            } catch (IOException e) {
                throw new RuntimeException("Failed to prepare settings.xml to pass the additional repository", e);
            }
        }

        Collection<String> extensionsContainingQuarkusMicrometerOpenshift = Arrays.asList(
                "quarkus-maven-plugin",
                "quarkus-bootstrap-maven-plugin",
                "quarkus-bom",
                "quarkus-bom-quarkus-platform-properties",
                "quarkus-logging-json-deployment",
                "quarkus-smallrye-opentracing-deployment",
                "quarkus-smallrye-reactive-messaging-deployment",
                "quarkus-vertx-deployment",
                "quarkus-spring-data-jpa-deployment",
                "quarkus-spring-di-deployment",
                "quarkus-spring-web-deployment",
                "quarkus-spring-boot-properties-deployment",
                "quarkus-spring-security-deployment",
                "quarkus-smallrye-reactive-streams-operators-deployment",
                "quarkus-smallrye-reactive-messaging-amqp-deployment",
                "quarkus-smallrye-reactive-messaging-kafka-deployment",
                "quarkus-smallrye-openapi-deployment",
                "quarkus-smallrye-jwt-deployment",
                "quarkus-smallrye-fault-tolerance-deployment",
                "quarkus-smallrye-context-propagation-deployment",
                "quarkus-rest-client-deployment",
                "quarkus-scheduler-deployment",
                "quarkus-oidc-deployment",
                "quarkus-oidc-client-deployment",
                "quarkus-oidc-client-filter-deployment",
                "quarkus-quartz-deployment",
                "quarkus-reactive-pg-client-deployment",
                "quarkus-keycloak-authorization-deployment",
                "quarkus-narayana-jta-deployment",
                "quarkus-jsonp-deployment",
                "quarkus-jsonb-deployment",
                "quarkus-jackson-deployment",
                "quarkus-jdbc-mariadb-deployment",
                "quarkus-jdbc-mssql-deployment",
                "quarkus-jdbc-mysql-deployment",
                "quarkus-jdbc-postgresql-deployment",
                "quarkus-jaxb-deployment",
                "quarkus-config-yaml-deployment",
                "quarkus-hibernate-orm-deployment",
                "quarkus-hibernate-validator-deployment",
                "quarkus-resteasy-deployment",
                "quarkus-resteasy-jsonb-deployment",
                "quarkus-resteasy-jaxb-deployment",
                "quarkus-resteasy-jackson-deployment",
                "quarkus-agroal-deployment",
                "quarkus-smallrye-metrics-deployment",
                "quarkus-netty-deployment",
                "quarkus-smallrye-health-deployment",
                "quarkus-hibernate-orm-panache-deployment",
                "quarkus-kafka-client-deployment",
                "quarkus-cache-deployment",
                "quarkus-grpc-deployment",
                "quarkus-infinispan-client-deployment",
                "quarkus-kafka-streams-deployment",
                "quarkus-kubernetes-client-deployment",
                "quarkus-kubernetes-config-deployment",
                "quarkus-mailer-deployment",
                "quarkus-qute-deployment",
                "quarkus-reactive-db2-client-deployment",
                "quarkus-reactive-mysql-client-deployment",
                "quarkus-rest-client-jackson-deployment",
                "quarkus-rest-client-jaxb-deployment",
                "quarkus-rest-client-jsonb-deployment",
                "quarkus-resteasy-qute-deployment",
                "quarkus-security-jpa-deployment",
                "quarkus-spring-cache-deployment",
                "quarkus-spring-cloud-config-client-deployment",
                "quarkus-spring-scheduled-deployment",
                "quarkus-vertx-graphql-deployment",
                "quarkus-micrometer-deployment",
                "quarkus-resteasy-multipart-deployment",
                "quarkus-rest-client-mutiny-deployment",
                "quarkus-jaxp-deployment",
                "quarkus-openshift-client-deployment",
                "quarkus-spring-data-rest-deployment",
                "quarkus-kubernetes-service-binding-deployment",
                "quarkus-micrometer-registry-prometheus-deployment",
                "quarkus-container-image-openshift-deployment",
                "quarkus-container-image-s2i-deployment",
                "quarkus-smallrye-jwt-build-deployment",
                "quarkus-hibernate-orm-rest-data-panache-deployment",
                "quarkus-resteasy-mutiny-deployment",
                "quarkus-reactive-messaging-http-deployment",
                "quarkus-vertx-web-deployment",
                "quarkus-resteasy-reactive-common-deployment",
                "quarkus-resteasy-reactive-deployment",
                "quarkus-resteasy-reactive-jackson-deployment",
                "quarkus-resteasy-reactive-jackson-common-deployment",
                "quarkus-resteasy-reactive-jsonb-deployment",
                "quarkus-resteasy-reactive-qute-deployment",
                "quarkus-undertow-deployment",
                "quarkus-hibernate-reactive-deployment",
                "quarkus-avro-deployment",
                "quarkus-openshift-deployment",
                "quarkus-rest-client-reactive-deployment",
                "quarkus-mongodb-client-deployment",
                "quarkus-websockets-client-deployment",
                "quarkus-reactive-pg-client-deployment",
                "quarkus-reactive-mysql-client-deployment",
                "quarkus-reactive-mssql-client-deployment",
                "quarkus-mutiny-deployment",
                "quarkus-opentelemetry-exporter-jaeger-deployment",
                "quarkus-opentelemetry-deployment",
                "quarkus-oidc-client-reactive-filter-deployment",
                "quarkus-websockets-deployment",
                "quarkus-jdbc-oracle-deployment");

        Path excludingQuarkusMicrometerProject = buildProjectExcludingSelectedExtensions(
                settingsSelector,
                extensionsContainingQuarkusMicrometerOpenshift);

        Set<GAV> gavs = listDependencies(
                excludingQuarkusMicrometerProject,
                Paths.get(extrasPath, "community-analysis-excluding-quarkus-micrometer-tree.txt"),
                settingsSelector);

        CommunityDepAnalyzer depAnalyzer = new CommunityDepAnalyzer(gavs);

        Path targetPath = Paths.get(extrasPath, "community-dependencies.csv");
        depAnalyzer.generateAnalysis(targetPath.toAbsolutePath().toString());

        Set<String> problematicDeps = gatherProblematicDeps();
        Path problematicDepsOut = Paths.get(extrasPath, "nonexistent-redhat-deps.txt");

        try (BufferedWriter writer = Files.newBufferedWriter(problematicDepsOut)) {
            writer.write(String.join("\n", problematicDeps));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write problematic dependencies to the output file", e);
        }
    }

    private Path buildProjectExcludingSelectedExtensions(
            String settingsSelector,
            Collection<String> extensionExcludeFilterList) {
        Path projectPath = generateQuarkusProject(
                artifactId -> extensionExcludeFilterList.stream().noneMatch(it -> it.contains(artifactId)),
                settingsSelector);
        buildProject(projectPath, settingsSelector);
        return projectPath;
    }

    private Collection<String> skippedExtensions() {
        // noinspection unchecked
        Collection<String> skipped = (Collection<String>) getAddOnConfiguration().get("skippedExtensions");
        return skipped == null ? Collections.emptyList() : skipped;
    }

    private void unpackRepository(Path repoZipPath) {
        File unzippedRepo = mkTempDir("repoZipForDepAnalysis");
        repoZipContents = FileUtils.unzip(repoZipPath.toFile(), unzippedRepo);

        Optional<String> quarkusCore = repoZipContents.stream()
                .filter(file -> FilenameUtils.normalize(file, true).matches(".*/io/quarkus/quarkus-core/.*\\.jar"))
                .findAny();

        String quarkusCorePath = quarkusCore.orElseThrow(
                () -> new RuntimeException(
                        "Quarkus core not found in the repository, unable to determine Quarkus version"));
        String quarkusCoreDir = quarkusCorePath.substring(0, quarkusCorePath.lastIndexOf("/"));
        quarkusVersion = quarkusCoreDir.substring(quarkusCoreDir.lastIndexOf("/") + 1);

        repoPath = MavenRepositoryUtils.getContentsDirPath(unzippedRepo.toPath());
        repoDefinition = " -Dmaven.repo.local=" + repoPath;
    }

    @SneakyThrows
    private Set<GAV> listDependencies(Path projectPath, Path depThreeOut, String settingsSelector) {
        List<String> result = OSCommandExecutor // mstodo!!!
                .runCommandIn("mvn dependency:tree " + repoDefinition + settingsSelector, projectPath);

        Files.write(depThreeOut, result);
        return depTreeToNonRedhatGAVs(result);
    }

    private Set<GAV> depTreeToNonRedhatGAVs(List<String> result) {
        return result.stream()
                .filter(l -> l.startsWith("[INFO] "))
                .map(this::parseLineToGav)
                .filter(Objects::nonNull)
                .filter(GAV::isCommunity)
                .collect(Collectors.toSet());
    }

    protected GAV parseLineToGav(String mvnDepTreeLine) {
        String gavString = mvnDepTreeLine.replaceFirst("\\[INFO] [+|\\\\\\-\\s]+", "");
        String[] splitGav = gavString.split(":");
        if (splitGav.length < 5 || !importantScopes.contains(splitGav[splitGav.length - 1])) {
            return null;
        }
        switch (splitGav.length) {
            case 5:
                return new GAV(splitGav[0], splitGav[1], splitGav[3], splitGav[2]);
            case 6:
                return new GAV(splitGav[0], splitGav[1], splitGav[4], splitGav[2], splitGav[3]);
            default:
                log.warn(
                        "A suspicious line in the dependency tree '{}', assuming it's not a dependency and skipping",
                        gavString);
                return null;
        }
    }

    private void buildProject(Path projectPath, String settingsSelector) {
        log.info("Building the project {}", projectPath.toAbsolutePath());
        OSCommandExecutor.runCommandIn(
                "mvn -Dmaven.test.skip=true -B clean package " + repoDefinition + settingsSelector,
                projectPath);
    }

    private Path generateQuarkusProject(Predicate<String> artifactSelector, String settingsSelector) {
        Path tempProjectLocation = mkTempDir("q-dep-analysis-generated-project").toPath();
        List<String> extensionArtifactIds = findProductizedExtensions().stream()
                .filter(artifactSelector)
                .collect(Collectors.toList());
        String command = String.format(
                "mvn -X io.quarkus:quarkus-maven-plugin:%s:create -DprojectGroupId=tmp -DprojectArtifactId=tmp "
                        + "-DplatformArtifactId=%s -DplatformVersion=%s -Dextensions=%s%s%s",
                quarkusVersion,
                "quarkus-bom",
                quarkusVersion,
                String.join(",", extensionArtifactIds),
                repoDefinition,
                settingsSelector);
        log.info("will create project with {}", command);
        OSCommandExecutor.runCommandIn(command, tempProjectLocation);

        return tempProjectLocation.resolve("tmp");
    }

    private List<String> findProductizedExtensions() {
        List<Path> allQuarkusJars = findAllByExtension("jar");
        Set<String> extensionsJson = extractExtensionsJsonArtifactIds();
        return allQuarkusJars.stream()
                .filter(this::hasQuarkusExtensionMetadata)
                .map(this::extractArtifactId)
                .filter(extensionsJson::contains)
                .filter(ext -> !skipped.contains(ext))
                .collect(Collectors.toList());
    }

    private Set<String> extractExtensionsJsonArtifactIds() {
        List<Path> devtoolsCommonJars = findAllByExtension("json").stream()
                .filter(path -> path.endsWith(devtoolsJarName()))
                .collect(Collectors.toList());
        for (Path p : devtoolsCommonJars) {
            if (p.getParent().toString().contains("com/redhat")) {
                return unpackArtifactIdsFrom(p);
            } else
                continue;
        }
        throw new RuntimeException(
                "Expected a single " + devtoolsJarName() + " in the repo, found: " + devtoolsCommonJars.size());
    }

    private String devtoolsJarName() {
        String bomArtifactId = getBomArtifactId();
        if (!isProductBom(bomArtifactId)) {
            return "quarkus-bom-quarkus-platform-descriptor-" + quarkusVersion + "-" + quarkusVersion + ".json";
        } else {
            return "quarkus-product-bom-quarkus-platform-descriptor-" + quarkusVersion + "-" + quarkusVersion + ".json";
        }
    }

    private Set<String> unpackArtifactIdsFrom(Path extensionsPath) {
        Set<String> artifactIds = new HashSet<>();
        ObjectReader extensionReader = jsonMapper.readerFor(QuarkusExtensions.class);

        try (FileReader reader = new FileReader(extensionsPath.toFile())) {
            QuarkusExtensions extensions = extensionReader.readValue(reader);
            extensions.getExtensions().stream().map(QuarkusExtension::getArtifactId).forEach(artifactIds::add);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read extensions.json from " + extensionsPath, e);
        }

        return artifactIds;
    }

    private String extractArtifactId(Path path) {
        // path is a path to jar, the parent path is version
        // its parent is the artifactId
        Path artifactPath = path.getParent().getParent();
        return artifactPath.getFileName().toString();
    }

    private boolean hasQuarkusExtensionMetadata(Path path) {
        return FileUtils.listZipContents(path.toFile())
                .stream()
                .anyMatch(e -> e.contains("META-INF/quarkus-extension.properties"));
    }

    private List<Path> findAllByExtension(String extension) {
        ByExtensionCollectingVisitor visitor = new ByExtensionCollectingVisitor(extension);
        try {
            Files.walkFileTree(repoPath, visitor);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to walk through repository contents: " + repoPath.toAbsolutePath().toString());
        }
        return visitor.getFilePaths();
    }

    private Set<String> gatherProblematicDeps() {
        Set<String> problemmaticDeps = new TreeSet<>();
        problemmaticDeps.addAll(checkBomContents(".*/io/quarkus/quarkus-bom/.*\\.pom"));
        if (isProductBom(getBomArtifactId())) {
            problemmaticDeps.addAll(checkBomContents(".*/com/redhat/quarkus/quarkus-product-bom/.*\\.pom"));
        }

        if (Boolean.TRUE.equals(getAddOnConfiguration().get("checkDeploymentBoms"))) {
            problemmaticDeps.addAll(checkBomContents(".*/io/quarkus/quarkus-bom-deployment/.*\\.pom"));
            if (isProductBom(getBomArtifactId())) {
                problemmaticDeps
                        .addAll(checkBomContents(".*/com/redhat/quarkus/quarkus-product-bom-deployment/.*\\.pom"));
            }
        }
        return problemmaticDeps;
    }

    private Collection<String> checkBomContents(String bomLocator) {
        String quarkusRuntimeBom = repoZipContents.stream()
                .filter(file -> FilenameUtils.normalize(file, true).matches(bomLocator))
                .findAny()
                .get();
        return checkReferencesInRepo(quarkusRuntimeBom);
    }

    private Collection<String> checkReferencesInRepo(String quarkusRuntimeBom) {
        try {
            String str = "/maven-repository/";
            int repoDirIdx = quarkusRuntimeBom.indexOf(str);
            quarkusRuntimeBom = quarkusRuntimeBom.substring(repoDirIdx + str.length());
            Path bomFile = repoPath.resolve(quarkusRuntimeBom).toAbsolutePath();
            Model model = new MavenXpp3Reader().read(Files.newInputStream(bomFile));

            List<Dependency> dependencies = model.getDependencyManagement().getDependencies();

            return dependencies.stream()
                    .filter(dep -> isRHAndMissing(dep, model))
                    .map(
                            dep -> String.format(
                                    "'%s:%s:%s:%s',",
                                    dep.getGroupId(),
                                    dep.getArtifactId(),
                                    deVar(model, dep.getVersion()),
                                    dep.getClassifier() != null ? dep.getClassifier() : ""))
                    .collect(Collectors.toSet());
        } catch (XmlPullParserException | IOException e) {
            log.error("Parsing error when generating quarkus artifact references", e);
            return Collections.emptySet();
        }
    }

    private boolean isRHAndMissing(Dependency dependency, Model model) {
        String version = dependency.getVersion();
        version = deVar(model, version);
        if (!version.contains("redhat")) {
            return false;
        }
        GAV gav = new GAV(
                dependency.getGroupId(),
                dependency.getArtifactId(),
                version,
                dependency.getType() != null && dependency.getType().equals("jar") ? dependency.getType() : "jar",
                dependency.getClassifier() != null ? dependency.getClassifier() : null);
        Path filePath = repoPath.resolve(gav.toVersionPath()).resolve(gav.toFileName());
        return !filePath.toFile().exists();
    }

    private String deVar(Model model, String version) {
        if (version.startsWith("$")) {
            version = version.substring(2, version.length() - 1);
            version = model.getProperties().getProperty(version);
        }
        return version;
    }
}
