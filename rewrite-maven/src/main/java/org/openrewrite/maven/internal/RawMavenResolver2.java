package org.openrewrite.maven.internal;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.openrewrite.ExecutionContext;
import org.openrewrite.internal.PropertyPlaceholderHelper;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.tree.*;

import java.net.URI;
import java.util.*;

import static java.util.stream.Collectors.joining;
import static org.openrewrite.Tree.randomId;

public class RawMavenResolver2 {

    private static final PropertyPlaceholderHelper placeholderHelper = new PropertyPlaceholderHelper("${", "}", null);

    private final Map<PartialPom, Pom> resolvedPoms = new HashMap<>();

    private final MavenPomDownloader downloader;

    private final Collection<String> activeProfiles;

    private final boolean resolveOptional;

    private final MavenExecutionContextView ctx;

    public RawMavenResolver2(MavenPomDownloader downloader, Collection<String> activeProfiles,
                            boolean resolveOptional, ExecutionContext ctx) {

        this.downloader = downloader;
        this.activeProfiles = activeProfiles;
        this.resolveOptional = resolveOptional;
        this.ctx = new MavenExecutionContextView(ctx);
    }

    @Nullable
    public MavenModel resolve(RawMaven rawMaven) {
        Map<String, String> effectiveProperties = new HashMap<>();
        Map<GroupArtifact, PartialDependency> managedDependency = new HashMap<>();
        return new MavenModel(randomId(), resolvePom(rawMaven, effectiveProperties, managedDependency));
    }


    @Nullable
    public Pom resolvePom(RawMaven rawMaven, Map<String, String> effectiveProperties, Map<GroupArtifact, PartialDependency> managedDependencies) {
        PartialPom partialPom = resolveParents(rawMaven, effectiveProperties, new LinkedHashSet<>());
        return complete(partialPom, effectiveProperties, managedDependencies);
    }

    @Nullable
    private PartialPom resolveParents(@Nullable RawMaven rawMaven, Map<String, String> effectiveProperties, Set<String> visitedArtifacts) {

        if (rawMaven == null) {
            return null;
        }
        //Add any properties defined by the pom into the effective properties.
        effectiveProperties.putAll(rawMaven.getActiveProperties(activeProfiles));

        //Resolve maven coordinates and (replacing any property place-holders)
        RawPom.Parent rawParent = rawMaven.getPom().getParent();
        String artifactId = getValue(rawMaven.getPom().getArtifactId(), effectiveProperties);

        String groupId = getValue(rawMaven.getPom().getGroupId(), effectiveProperties);
        if (groupId == null && rawParent != null) {
            //If group is not defined, use the parent's group.
            groupId = getValue(rawParent.getGroupId(), effectiveProperties);
        }
        String version = getValue(rawMaven.getPom().getVersion(), effectiveProperties);
        if (version == null && rawParent != null) {
            //If version is not defined, use the parent's version.
            version = getValue(rawParent.getVersion(), effectiveProperties);
        }

        boolean resolutionError = false;
        if (artifactId == null || artifactId.contains("${")) {
            //The only way this can happen is if the artifact ID is a property place-holder and that property does not
            //exist in the effective properties.
            ctx.getOnError().accept(new MavenParsingException("Unable to resolve artifact ID for raw pom [" + rawMaven.getPom().getCoordinates() + "]."));
            resolutionError = true;
        }
        if (groupId == null || groupId.contains("${")) {
            ctx.getOnError().accept(new MavenParsingException("Unable to resolve group ID for raw pom [" + rawMaven.getPom().getCoordinates() + "]."));
            resolutionError = true;
        }
        if (version == null || version.contains("${")) {
            ctx.getOnError().accept(new MavenParsingException("Unable to resolve version for raw pom [" + rawMaven.getPom().getCoordinates() + "]."));
            resolutionError = true;
        }
        if (resolutionError) {
            return null;
        }
        String coordinates = groupId + ":" + artifactId + ":" + version;
        // With "->" indicating a "has parent" relationship, visitedArtifacts is used to detect cycles like
        // A -> B -> A
        // And cut them off early with a clearer, more actionable error than a stack overflow
        if (visitedArtifacts.contains(coordinates)) {
            ctx.getOnError().accept(new MavenParsingException("Cycle in parent poms detected: " +  coordinates + " is its own parent by way of these poms:\n"
                    + String.join("\n", visitedArtifacts)));
            return null;
        }
        visitedArtifacts.add(coordinates);

        //This list/order of repositories used to resolve the parent:
        //
        // See https://maven.apache.org/guides/mini/guide-multiple-repositories.html#repository-order
        //
        // 1) repositories in the settings.xml
        // 2) any repositories defined in the current pom
        // 3) maven central (defined in the maven downloader)
        Set<MavenRepository> repositories = new LinkedHashSet<>(ctx.getRepositories());
        Set<MavenRepository> pomRepositories = new LinkedHashSet<>();
        for (RawRepositories.Repository repo : rawMaven.getPom().getActiveRepositories(activeProfiles)) {
            MavenRepository mapped = resolveRepository(repo, effectiveProperties);
            if (mapped == null) {
                continue;
            }
            mapped = MavenRepositoryMirror.apply(ctx.getMirrors(), mapped);
            mapped = MavenRepositoryCredentials.apply(ctx.getCredentials(), mapped);
            pomRepositories.add(mapped);
            repositories.add(mapped);
        }

        PartialPom parent = null;
        if (rawParent != null) {
            String parentGroupId = getValue(rawParent.getGroupId(), effectiveProperties);
            String parentArtifactId = getValue(rawParent.getArtifactId(), effectiveProperties);
            String parentVersion = getValue(rawParent.getVersion(), effectiveProperties);

            RawMaven rawParentModel = downloader.download(rawParent.getGroupId(), rawParent.getArtifactId(),
                    rawParent.getVersion(), rawParent.getRelativePath(), rawMaven,
                    repositories, ctx);
            parent = resolveParents(rawParentModel, effectiveProperties, visitedArtifacts);
        }
        return new PartialPom(rawMaven.getPom(), groupId, artifactId, version, parent, pomRepositories.isEmpty() ? Collections.emptySet() : pomRepositories);
    }

    @Nullable
    private Pom complete(@Nullable PartialPom partialPom, Map<String, String> effectiveProperties, Map<GroupArtifact, PartialDependency> managedDependencies) {

        if (partialPom == null) {
            return null;
        }

        //Compute property and managed dependency overrides.
        completeProperties(partialPom, effectiveProperties);
        completeDependencyManagement(partialPom, managedDependencies);

        //At this point, all state that can impact the state of the final pom is present in the partial, we can safely
        //use the cache of resolved poms to make sure we do not solve the same sub-problem again.
        Pom pom = resolvedPoms.get(partialPom);
        if (pom != null) {
            return pom;
        }

        Pom parent = complete(partialPom.getParent(), effectiveProperties, managedDependencies);

        List<Pom.License> licenses = processLicences(partialPom);
        List<Pom.Dependency> dependencies = processDependencies(partialPom);

        RawPom rawPom = partialPom.getRawPom();
        pom = Pom.build(
                partialPom.getGroupId(),
                partialPom.getArtifactId(),
                partialPom.getVersion(),
                rawPom.getSnapshotVersion(),
                partialPom.getValue(rawPom.getName()),
                partialPom.getValue(rawPom.getDescription()),
                partialPom.getValue(rawPom.getPackaging()),
                null,
                parent,
                dependencies,
                null,
                licenses,
                partialPom.getRepositories(),
                partialPom.getProperties(),
                partialPom.getPropertyOverrides(),
                false
        );
        resolvedPoms.put(partialPom, pom);
        return pom;
    }

    private void completeProperties(PartialPom partialPom, Map<String, String> effectiveProperties) {

        Map<String, String> propertyOverrides = new HashMap<>();

        partialPom.getRawPom().getPropertyPlaceHolderNames().forEach((k) -> {
            String effectiveValue = effectiveProperties.get(k);
            if (!Objects.equals(partialPom.getProperties().get(k), effectiveValue)) {
                propertyOverrides.put(k, effectiveValue);
            }
        });
        if (!propertyOverrides.isEmpty()) {
            partialPom.setPropertyOverrides(propertyOverrides);
        }
    }

    private void completeDependencyManagement(PartialPom partialPom, Map<GroupArtifact, PartialDependency> effectiveManagedDependencies) {
        //If there are any managed dependencies in ths pom, add those to the effective managed dependencies. If there is already a managed dependency defined,
        //Resolve which one should "win"

        //Iterate over the dependencies, if there is a managed dependency for the artifact, collect it as a dependency override.
    }

    private Set<MavenRepository> resolveRepositories(RawMaven rawMaven, Set<MavenRepository> additionalRepositories, Map<String,String> effectiveProperties) {

        Set<MavenRepository> repositories = new LinkedHashSet<>();
        for (RawRepositories.Repository repo : rawMaven.getPom().getActiveRepositories(activeProfiles)) {
            MavenRepository mapped = resolveRepository(repo, effectiveProperties);
            if (mapped == null) {
                continue;
            }
            mapped = MavenRepositoryMirror.apply(ctx.getMirrors(), mapped);
            mapped = MavenRepositoryCredentials.apply(ctx.getCredentials(), mapped);
            repositories.add(mapped);
        }
        repositories.addAll(additionalRepositories);
        return repositories;
    }

    @Nullable
    private MavenRepository resolveRepository(@Nullable RawRepositories.Repository repo, Map<String, String> effectiveProperties) {
        if (repo == null) {
            return null;
        }
        String url = getValue(repo.getUrl(), effectiveProperties);
        if (url == null || url.startsWith("${")) {
            ctx.getOnError().accept(new MavenParsingException("Invalid repository URL %s", repo.getUrl()));
            return null;
        }

        try {
            // Prevent malformed URLs from being used
            return new MavenRepository(repo.getId(), URI.create(repo.getUrl().trim()),
                    repo.getReleases() == null || repo.getReleases().isEnabled(),
                    repo.getSnapshots() != null && repo.getSnapshots().isEnabled(),
                    null, null);
        } catch (Throwable t) {
            ctx.getOnError().accept(new MavenParsingException("Invalid repository URL %s", t, repo.getUrl()));
            return null;
        }
    }

    private List<Pom.License> processLicences(PartialPom partialPom) {
        return null;
    }

    private List<Pom.Dependency> processDependencies(PartialPom partialPom) {
        return null;
    }


    @FieldDefaults(level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @Getter
    @Setter
    public class PartialPom {
        final RawPom rawPom;

        //There are never property placeholders in these three values!
        @EqualsAndHashCode.Include
        final String groupId;
        @EqualsAndHashCode.Include
        final String artifactId;
        @EqualsAndHashCode.Include
        final String version;

        @EqualsAndHashCode.Include
        final PartialPom parent;

        final Set<MavenRepository> repositories;

        @EqualsAndHashCode.Include
        Map<String, String> propertyOverrides = Collections.emptyMap();

        @EqualsAndHashCode.Include
        Map<GroupArtifact, PartialDependency> dependencyOverrides = Collections.emptyMap();


        public Map<String, String> getProperties() {
            return rawPom.getProperties() == null ? Collections.emptyMap() : rawPom.getProperties();
        }

        @Nullable
        public String getValue(@Nullable String v) {
            return getValue(v, false);
        }

        @Nullable
        public String getRequiredValue(@Nullable String v) {
            return getValue(v, true);
        }

        @Nullable
        private String getValue(@Nullable String v, boolean required) {
            if (v == null) {
                return null;
            }

            try {
                return placeholderHelper.replacePlaceholders(v, key -> {
                    if (key == null) {
                        return null;
                    }
                    switch (key) {
                        case "groupId":
                        case "project.groupId":
                        case "pom.groupId":
                            return groupId;
                        case "project.parent.groupId":
                            return parent != null ? parent.getGroupId() : null;
                        case "artifactId":
                        case "project.artifactId":
                        case "pom.artifactId":
                            return artifactId;
                        case "project.parent.artifactId":
                            return parent == null ? null : parent.getArtifactId();
                        case "version":
                        case "project.version":
                        case "pom.version":
                            return version;
                        case "project.parent.version":
                            return parent != null ? parent.getVersion() : null;
                    }

                    String value = System.getProperty(key);
                    if (value != null) {
                        return value;
                    }

                    value = propertyOverrides.get(key);
                    if (value != null) {
                        return value;
                    }
                    value = getProperties().get(key);
                    if (value != null) {
                        return value;
                    }

                    if (parent != null) {
                        return parent.getValue(key);
                    }
                    if (required) {
                        ctx.getOnError().accept(new MavenParsingException("Unable to resolve property %s. Including POM is at %s", v, rawPom));
                    }
                    return null;
                });
            } catch (Throwable t) {
                if (required) {
                    ctx.getOnError().accept(new MavenParsingException("Unable to resolve property %s. Including POM is at %s", v, rawPom));
                }
                return null;
            }
        }

        /**
         * Returns any repositories defined in this pom or its ancestors (ordering the set such that repositories
         * defined in the current pom are ordered ahead of any repositories defined in the parent(s).
         *
         * @return An ordered Set of repositories defined in the pom along with any defined in its ancestors.
         */
        Set<MavenRepository> getEffectiveRepositories() {
            Set<MavenRepository> effectiveRepositories = new LinkedHashSet<>(this.repositories);
            if (parent != null) {
                effectiveRepositories.addAll(parent.getRepositories());
            }
            return effectiveRepositories;
        }
    }

    @Value
    public static class PartialDependency {
        String groupId;
        String artifactId;
        String originalVersion;
        String version;

        @Nullable
        String getClassifier;

        @Nullable
        Scope getScope;

        @Nullable
        String type;

        boolean optional;

        Set<GroupArtifact> getExclusions;
    }

    /**
     * Used to resolve a value (while recursively resolving any property place-holders) with the passed in set of
     * properties. This is only used in the context of resolving gav coordinates for a partial maven (or its parent)
     * @param v A value that may or may not have property place-holders.
     * @param properties A list of properties in scope for property place-holder resolution
     * @return The value with any property place-holders being replaced with the property values.
     */
    @Nullable
    private String getValue(@Nullable String v, Map<String, String> properties) {
        if (v == null) {
            return null;
        }

        return placeholderHelper.replacePlaceholders(v, key -> {
            String value = System.getProperty(key);
            if (value != null) {
                return value;
            }
            return properties.get(key);
        });
    }
}
