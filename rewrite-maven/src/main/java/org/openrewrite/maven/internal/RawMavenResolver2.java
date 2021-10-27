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
        return new MavenModel(randomId(), resolvePom(rawMaven, new EffectiveContext()));
    }

    @Nullable
    public Pom resolvePom(RawMaven rawMaven, EffectiveContext effective) {
        PartialPom partialPom = resolveParents(rawMaven, effective, new LinkedHashSet<>());
        return complete(partialPom, effective);
    }

    /**
     * This method will map a rawMaven into its partial form. It will also resolve any of the parents into their
     * partial form as well. If the effective context has not yet been resolved, this methods will populate the
     * effective properties as it recursively navigates down the parents.
     *
     * @param rawMaven RawMaven to be converted into a partial.
     * @param effective The effective context represents the merged environment for a given child/parent chain.
     * @param visitedArtifacts A list of artifacts visisted so far. This allows for detecting cycles in the child/parent chain.
     * @return PartialPom representing the rawMaven.
     */
    @Nullable
    private PartialPom resolveParents(@Nullable RawMaven rawMaven, EffectiveContext effective, Set<String> visitedArtifacts) {

        if (rawMaven == null) {
            return null;
        }
        Map<String, String> effectiveProperties = effective.getProperties();
        if (!effective.isResolved()) {
            //If the effective context has not yet been established, add any properties defined by the pom
            // (that do not already exist) into the effective properties.
            for (Map.Entry<String, String> entry : rawMaven.getActiveProperties(activeProfiles).entrySet()) {
                effective.getProperties().computeIfAbsent(entry.getKey(), k -> entry.getValue());
            }
        }
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
            parent = resolveParents(rawParentModel, effective, visitedArtifacts);
        }

        return new PartialPom(rawMaven.getPom(), groupId, artifactId, version, parent, pomRepositories.isEmpty() ? Collections.emptySet() : pomRepositories);
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

    @Nullable
    private Pom complete(@Nullable PartialPom partialPom, EffectiveContext effective) {

        if (partialPom == null) {
            return null;
        }

        //Compute property overrides in the partial pom using the effective properties.
        completeProperties(partialPom, effective.getProperties());

        if (!effective.isResolved()) {
            //If the effective context has not yet been resolved, complete dependency management for the partial
            //and its parents. Once we are done with this step, the effective context is complete.
            completeDependencyManagement(partialPom, effective);
            effective.setResolved(true);
        }

        //Compute the effective dependencies for the current pom and it's parents and then merge those with the effective properties
        completeDependencyOverrides(partialPom, effective);

        //At this point, all state that can impact the state of the final pom is present in the partial, we can safely
        //use the cache of resolved poms to make sure we do not solve the same sub-problem again.
        Pom pom = resolvedPoms.get(partialPom);
        if (pom != null) {
            return pom;
        }

        Pom parent = complete(partialPom.getParent(), effective);

        List<Pom.License> licenses = completeLicences(partialPom);
        List<Pom.Dependency> dependencies = completeDependencies(partialPom);

        pom = Pom.build(
                partialPom.getGroupId(),
                partialPom.getArtifactId(),
                partialPom.getVersion(),
                partialPom.getRawPom().getSnapshotVersion(),
                partialPom.getValue(partialPom.getRawPom().getName()),
                partialPom.getValue(partialPom.getRawPom().getDescription()),
                partialPom.getValue(partialPom.getRawPom().getPackaging()),
                null,
                parent,
                dependencies,
                partialPom.getDependencyManagement(),
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

    private void completeDependencyManagement(PartialPom partialPom, EffectiveContext effective) {

        List<DependencyManagementDependency> managedDependencies = new ArrayList<>();

        for (RawPom.Dependency d : partialPom.getRawPom().getActiveDependencyManagementDependencies(activeProfiles)) {

            String groupId = partialPom.getRequiredValue(d.getGroupId());
            String artifactId = partialPom.getRequiredValue(d.getArtifactId());

            if (groupId == null || artifactId == null) {
                continue;
            }

            String version = partialPom.getValue(d.getVersion());
            //TODO what if a dynamic version is used in a managed version?

            GroupArtifact artifactKey = new GroupArtifact(groupId, artifactId);

            // https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#importing-dependencies

            if (Objects.equals(d.getType(), "pom") && Objects.equals(d.getScope(), "import")) {
                if (version == null) {
                    ctx.getOnError().accept(new MavenParsingException(
                            "Problem with dependencyManagement section of %s:%s:%s. Unable to determine version of " +
                                    "managed dependency %s:%s.",
                            partialPom.getGroupId(), partialPom.getArtifactId(), partialPom.getVersion(), d.getGroupId(), d.getArtifactId()));
                } else {
                    RawMaven rawMaven = downloader.download(groupId, artifactId, version, null, null,
                            partialPom.getRepositories(), ctx);
                    if (rawMaven != null) {
                        Pom maven = resolvePom(rawMaven, new EffectiveContext());
                        if (maven != null) {
                            DependencyManagementDependency imported = new DependencyManagementDependency.Imported(groupId, artifactId,
                                    version, d.getVersion(), maven);
                            managedDependencies.add(imported);
                            for (DependencyDescriptor descriptor : imported.getDependencies()) {
                                effective.getManagedDependencies().computeIfAbsent(new GroupArtifact(descriptor.getGroupId(), d.getArtifactId()), k -> descriptor);
                            }
                        }
                    }
                }
            } else {
                Scope scope = d.getScope() == null ? null : Scope.fromName(d.getScope());
                if (!Scope.Invalid.equals(scope)) {
                    DependencyManagementDependency.Defined managedDependency = new DependencyManagementDependency.Defined(
                            groupId, artifactId, version, d.getVersion(),
                            scope,
                            d.getClassifier(), d.getExclusions());
                    managedDependencies.add(managedDependency);
                    effective.getManagedDependencies().computeIfAbsent(artifactKey, k -> managedDependency);
                }
            }
            if (!managedDependencies.isEmpty()) {
                partialPom.setDependencyManagement(new Pom.DependencyManagement(managedDependencies));
            }
            if (partialPom.getParent() != null) {
                //Recurse up to the parent. (Cycles have already detected cycles in resolveParent)
                completeDependencyManagement(partialPom.getParent(), effective);
            }
        }
    }

    private void completeDependencyOverrides(PartialPom partialPom, EffectiveContext effective) {
        //Now iterate over all dependencies of the pom and collect any managed dependencies for those dependencies. These
        //may act as overrides for a given dependency.
        Map<GroupArtifact, DependencyDescriptor> dependencyOverrides = new HashMap<>();
        for (RawPom.Dependency dependency : partialPom.getRawPom().getActiveDependencies(activeProfiles)) {
            String groupId = partialPom.getRequiredValue(dependency.getGroupId());
            String artifactId = partialPom.getRequiredValue(dependency.getArtifactId());
            if (groupId == null || artifactId == null) {
                continue;
            }
            GroupArtifact artifactKey = new GroupArtifact(groupId, artifactId);
            DependencyDescriptor managementDependency = effective.getManagedDependencies().get(artifactKey);
            if (managementDependency != null) {
                dependencyOverrides.put(artifactKey, managementDependency);
            }
        }
        if (!dependencyOverrides.isEmpty()) {
            partialPom.setDependencyOverrides(dependencyOverrides);
        }
    }

    private List<Pom.License> completeLicences(PartialPom partialPom) {
        List<RawPom.License> rawLicenses = partialPom.getRawPom().getInnerLicenses();
        List<Pom.License> licenses = new ArrayList<>();
        for (RawPom.License rawLicense : rawLicenses) {
            Pom.License license = Pom.License.fromName(rawLicense.getName());
            licenses.add(license);
        }
        return licenses;
    }

    private List<Pom.Dependency> completeDependencies(PartialPom partialPom) {
        List<Pom.Dependency> dependencies = new ArrayList<>();

        for (RawPom.Dependency rawDependency : partialPom.getRawPom().getDependencies().getDependencies()) {
            String groupId = partialPom.getRequiredValue(rawDependency.getGroupId());
            String artifactId = partialPom.getRequiredValue(rawDependency.getArtifactId());
            GroupArtifact artifactKey = new GroupArtifact(rawDependency.getGroupId(), rawDependency.getArtifactId());
            DependencyDescriptor managementDependency = partialPom.getDependencyOverrides().get(artifactKey);

            String rawVersion = rawDependency.getVersion();
            if (rawDependency == null && managementDependency != null) {
                rawVersion = managementDependency.getVersion();
            }

            RequestedVersion requestedVersion = new RequestedVersion()

            dependencies.add(new Pom.Dependency(
                    depTask.getRawMaven().getRepository(),
                    depTask.getScope(),
                    depTask.getClassifier(),
                    depTask.getType(),
                    optional,
                    resolved,
                    depTask.getRequestedVersion(),
                    depTask.getExclusions()
            ));

        }
        return dependencies;
    }

    @Data
    public static class EffectiveContext {
        final Map<String, String> properties = new HashMap<>();
        final Map<GroupArtifact, DependencyDescriptor> managedDependencies = new HashMap<>();
        boolean resolved = false;
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

        @EqualsAndHashCode.Include
        Map<String, String> propertyOverrides = Collections.emptyMap();

        //This is a map of any effective dependencies that override one of the dependencies defined in the partial maven.
        @EqualsAndHashCode.Include
        Map<GroupArtifact, DependencyDescriptor> dependencyOverrides = Collections.emptyMap();

        //Repositories is a computed field that is static once effective properties have been established.
        final Set<MavenRepository> repositories;

        //This represents the dependency management section original defined in the raw pom and does not change
        //once effective properties have been established.
        Pom.DependencyManagement dependencyManagement = new Pom.DependencyManagement(Collections.emptyList());

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
