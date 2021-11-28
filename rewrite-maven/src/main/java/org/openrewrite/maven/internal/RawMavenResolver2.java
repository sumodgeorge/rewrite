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
import java.util.stream.Collectors;

import static java.util.stream.Collectors.joining;
import static org.openrewrite.Tree.randomId;

public class RawMavenResolver2 {

    private static final PropertyPlaceholderHelper placeholderHelper = new PropertyPlaceholderHelper("${", "}", null);

    private final Map<PartialPom, Pom> resolvedPoms = new HashMap<>();

    private final MavenPomDownloader downloader;

    private final Collection<String> activeProfiles;

    private final boolean resolveOptional;

    private final MavenExecutionContextView exectionContext;

    public RawMavenResolver2(MavenPomDownloader downloader, Collection<String> activeProfiles,
                            boolean resolveOptional, ExecutionContext ctx) {

        this.downloader = downloader;
        this.activeProfiles = activeProfiles;
        this.resolveOptional = resolveOptional;
        this.exectionContext = new MavenExecutionContextView(ctx);
    }

    @Nullable
    public MavenModel resolve(RawMaven rawMaven) {
        return new MavenModel(randomId(), resolvePom(rawMaven, new EffectiveContext(Collections.emptyMap(), Collections.emptyMap())));
    }

    @Nullable
    public Pom resolvePom(RawMaven rawMaven, EffectiveContext effective) {
        //The first pass of resolve will resolve a partial pom (not yet complete), but also downloading the parents
        //and resolving those as partial poms as well. A side effect of this operation is that the effective properties
        //and effective dependency management are also computed in this initial phase.
        PartialPom partialPom = resolveParents(rawMaven, effective, new LinkedHashSet<>());

        //Complete will populate the remaining state of the partial pom using the effective context. After the partial
        //is complete, it contains all state that would impact the final pom. and the partial can act as a key to the
        complete(partialPom, effective);

        //Final step is to map the partial into its final form, this step will recursively resolve the poms for
        //any dependencies.
        return partialToPom(partialPom, effective);
    }

    /**
     * This method will map a rawMaven into its partial form.
     *
     * This method also recursively resolves the parent(s):
     *
     * Effective properties are collected as we recurse down the parents. Properties established in a child will not be
     * overridden if the same value is later encountered in a parent.
     *
     * Any repositories defined in the pom are also resolved.
     *
     * As this method recursively returns, the effective dependency management is computed. This is done as we traverse
     * back from the parents because the effective properties may impact the dependency management. If an artifact is
     * managed by parent and a child, the effective dependency management will reflect the managed dependency of the
     * child.
     *
     * Imported dependency management will result in resolveParents being called on the imported pom.
     *
     * TODO : We should resolve effective plugin management as we recurse back up the parents as well.
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
        for (Map.Entry<String, String> entry : rawMaven.getActiveProperties(activeProfiles).entrySet()) {
            effectiveProperties.computeIfAbsent(entry.getKey(), k -> entry.getValue());
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
            exectionContext.getOnError().accept(new MavenParsingException("Unable to resolve artifact ID for raw pom [" + rawMaven.getPom().getCoordinates() + "]."));
            resolutionError = true;
        }
        if (groupId == null || groupId.contains("${")) {
            exectionContext.getOnError().accept(new MavenParsingException("Unable to resolve group ID for raw pom [" + rawMaven.getPom().getCoordinates() + "]."));
            resolutionError = true;
        }
        if (version == null || version.contains("${")) {
            exectionContext.getOnError().accept(new MavenParsingException("Unable to resolve version for raw pom [" + rawMaven.getPom().getCoordinates() + "]."));
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
            exectionContext.getOnError().accept(new MavenParsingException("Cycle in parent poms detected: " +  coordinates + " is its own parent by way of these poms:\n"
                    + String.join("\n", visitedArtifacts)));
            return null;
        }
        visitedArtifacts.add(coordinates);

        //The list/order of repositories used to resolve the parent:
        //
        // See https://maven.apache.org/guides/mini/guide-multiple-repositories.html#repository-order
        //
        // 1) repositories in the settings.xml
        // 2) any repositories defined in the current pom
        // 3) maven central (defined in the maven downloader)
        Set<MavenRepository> repositories = new LinkedHashSet<>(exectionContext.getRepositories());
        Set<MavenRepository> pomRepositories = new LinkedHashSet<>();
        for (RawRepositories.Repository repo : rawMaven.getPom().getActiveRepositories(activeProfiles)) {
            MavenRepository mapped = resolveRepository(repo, effectiveProperties);
            if (mapped == null) {
                continue;
            }
            mapped = MavenRepositoryMirror.apply(exectionContext.getMirrors(), mapped);
            mapped = MavenRepositoryCredentials.apply(exectionContext.getCredentials(), mapped);
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
                    repositories, exectionContext);
            parent = resolveParents(rawParentModel, effective, visitedArtifacts);
        }
        //At this point, the effective context will have all effective properties:

        // Resolve dependency management (using the effective properties).
        resolveDependencyManagement(rawMaven.getPom(), repositories, effective);
        // TODO resolve plugin management.

        return new PartialPom(groupId, artifactId, version, rawMaven,  parent, pomRepositories.isEmpty() ? Collections.emptySet() : pomRepositories);
    }

    @Nullable
    private MavenRepository resolveRepository(@Nullable RawRepositories.Repository repo, Map<String, String> effectiveProperties) {
        if (repo == null) {
            return null;
        }
        String url = getValue(repo.getUrl(), effectiveProperties);
        if (url == null || url.startsWith("${")) {
            exectionContext.getOnError().accept(new MavenParsingException("Invalid repository URL %s", repo.getUrl()));
            return null;
        }

        try {
            // Prevent malformed URLs from being used
            return new MavenRepository(repo.getId(), URI.create(repo.getUrl().trim()),
                    repo.getReleases() == null || repo.getReleases().isEnabled(),
                    repo.getSnapshots() != null && repo.getSnapshots().isEnabled(),
                    null, null);
        } catch (Throwable t) {
            exectionContext.getOnError().accept(new MavenParsingException("Invalid repository URL %s", t, repo.getUrl()));
            return null;
        }
    }

    private void resolveDependencyManagement(RawPom rawPom, Set<MavenRepository> repositories, EffectiveContext effective) {

        Map<String, String> effectiveProperties = effective.getProperties();

        for (RawPom.Dependency d : rawPom.getActiveDependencyManagementDependencies(activeProfiles)) {

            String groupId = getValue(d.getGroupId(), effectiveProperties);
            String artifactId = getValue(d.getArtifactId(), effectiveProperties);

            if (groupId == null || artifactId == null) {
                continue;
            }
            String version = getValue(d.getVersion(), effectiveProperties);

            GroupArtifact artifactKey = new GroupArtifact(groupId, artifactId);

            // https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#importing-dependencies

            if (Objects.equals(d.getType(), "pom") && Objects.equals(d.getScope(), "import")) {
                if (version == null) {
                    exectionContext.getOnError().accept(new MavenParsingException(
                            "Problem with dependencyManagement section of %s:%s:%s. Unable to determine version of " +
                                    "managed dependency %s:%s.",
                            rawPom.getGroupId(), rawPom.getArtifactId(), rawPom.getVersion(), d.getGroupId(), d.getArtifactId()));
                } else {
                    RawMaven rawMaven = downloader.download(groupId, artifactId, version, null, null,
                            repositories, exectionContext);
                    if (rawMaven != null) {
                        //resolve the parent of the imported pom with a new effective context and then collect up any
                        //managed dependencies from the importedContext.
                        EffectiveContext importedContext = new EffectiveContext(Collections.emptyMap(), Collections.emptyMap());
                        resolveParents(rawMaven, importedContext, Collections.emptySet());
                        effective.getManagedDependencies().putAll(importedContext.getManagedDependencies());
                    }
                }
            } else {
                Scope scope = d.getScope() == null ? null : Scope.fromName(d.getScope());
                if (!Scope.Invalid.equals(scope)) {
                    DependencyManagementDependency.Defined managedDependency = new DependencyManagementDependency.Defined(
                            groupId, artifactId, version, d.getVersion(),
                            scope,
                            d.getClassifier(), d.getExclusions());
                    effective.getManagedDependencies().computeIfAbsent(artifactKey, k -> managedDependency);
                }
            }
        }
    }

    /**
     * Complete the state of the partial pom, along with its parent(s)
     *
     * @param partialPom The partial to be completed.
     * @param effective The effective context
     */
    @Nullable
    private void complete(@Nullable PartialPom partialPom, EffectiveContext effective) {

        if (partialPom == null) {
            return;
        }

        //Compute property overrides in the partial pom using the effective properties.
        completeProperties(partialPom, effective.getProperties());

        //Compute the dependencies for the partial pom given the current effective context.
        completePartialDependencies(partialPom, effective);

        //Recursively complete the parents.
        complete(partialPom.getParent(), effective);
    }

    private void completeProperties(PartialPom partialPom, Map<String, String> effectiveProperties) {

        Map<String, String> propertyOverrides = new HashMap<>();

        partialPom.getRawMaven().getPom().getPropertyPlaceHolderNames().forEach((k) -> {
            String effectiveValue = effectiveProperties.get(k);
            if (!Objects.equals(partialPom.getProperties().get(k), effectiveValue)) {
                propertyOverrides.put(k, effectiveValue);
            }
        });
        if (!propertyOverrides.isEmpty()) {
            partialPom.setPropertyOverrides(propertyOverrides);
        }
    }

    private void completePartialDependencies(PartialPom partialPom, EffectiveContext effective) {
        // Now iterate over all dependencies of the pom and resolve versions. This method also handles version conflicts,
        // if a dependency for an artifact already exists in the context, that dependency will "win" over a dependency
        // defined in the pom.

        Map<GroupArtifact, List<ResolvedDependency>> dependencies = new HashMap<>();
        for (RawPom.Dependency rawDependency : partialPom.getRawMaven().getPom().getActiveDependencies(activeProfiles)) {
            String groupId = partialPom.getRequiredValue(rawDependency.getGroupId());
            String artifactId = partialPom.getRequiredValue(rawDependency.getArtifactId());
            if (groupId == null || artifactId == null) {
                continue;
            }
            GroupArtifact artifactKey = new GroupArtifact(groupId, artifactId);
            List<ResolvedDependency> dependencyDescriptors = dependencies.computeIfAbsent(artifactKey, k -> new ArrayList<>());

            Scope scope = Scope.fromName(rawDependency.getScope());

            String requestedVersion = rawDependency.getVersion();
            if (requestedVersion == null) {
                // If the version is null, resolve the version from the effective context.
                requestedVersion = effective.findManagedVersion(artifactKey, scope);
            }

            // If there is already a dependency (in the same transitive scope) within the effective context, use that
            // dependency.
            ResolvedDependency existingDependency = effective.findEffectiveDependency(artifactKey, scope);

            if (existingDependency != null) {
                dependencyDescriptors.add(new ResolvedDependency(
                        groupId,
                        artifactId,
                        existingDependency.getVersion(),
                        requestedVersion,
                        scope,
                        rawDependency.getType(),
                        rawDependency.getOptional(),
                        rawDependency.getClassifier(),
                        rawDependency.getExclusions()));
                continue;
            }

            //TODO : Resolve any dynamic versions/ranges
            String version = requestedVersion;

            ResolvedDependency resolvedDependency = new ResolvedDependency(
                    groupId,
                    artifactId,
                    version,
                    requestedVersion,
                    scope,
                    rawDependency.getType(),
                    rawDependency.getOptional(),
                    rawDependency.getClassifier(),
                    rawDependency.getExclusions()
            );
            dependencyDescriptors.add(resolvedDependency);
            effective.addEffectiveDependency(artifactKey, resolvedDependency);
        }

        if (!dependencies.isEmpty()) {
            partialPom.setDependencies(dependencies);
        }
    }

    private Pom partialToPom(PartialPom partialPom, EffectiveContext effective) {

        if (partialPom == null) {
            return null;
        }

        //At this point, all state that can impact the state of the final pom is present in the partial, we can safely
        //use the cache of resolved poms to make sure we do not solve the same sub-problem again.
        Pom pom = resolvedPoms.get(partialPom);
        if (pom != null) {
            return pom;
        }

        Pom parent = partialToPom(partialPom.getParent(), effective);
        List<Pom.License> licenses = completeLicences(partialPom);
        Pom.DependencyManagement dependencyManagement = completeDependencyManagement(partialPom);
        List<Pom.Dependency> dependencies = completeDependencies(partialPom, effective);

        pom = Pom.build(
                partialPom.getGroupId(),
                partialPom.getArtifactId(),
                partialPom.getVersion(),
                partialPom.getRawMaven().getPom().getSnapshotVersion(),
                partialPom.getValue(partialPom.getRawMaven().getPom().getName()),
                partialPom.getValue(partialPom.getRawMaven().getPom().getDescription()),
                partialPom.getValue(partialPom.getRawMaven().getPom().getPackaging()),
                null,
                parent,
                dependencies,
                dependencyManagement,
                licenses,
                partialPom.getRepositories(),
                partialPom.getProperties(),
                partialPom.getPropertyOverrides(),
                false
        );
        resolvedPoms.put(partialPom, pom);
        return pom;
    }


    private Pom.DependencyManagement completeDependencyManagement(PartialPom partialPom) {
        //TODO finish this.
        return null;
    }


    private List<Pom.License> completeLicences(PartialPom partialPom) {
        List<RawPom.License> rawLicenses = partialPom.getRawMaven().getPom().getInnerLicenses();
        List<Pom.License> licenses = new ArrayList<>();
        for (RawPom.License rawLicense : rawLicenses) {
            Pom.License license = Pom.License.fromName(rawLicense.getName());
            licenses.add(license);
        }
        return licenses;
    }

    private List<Pom.Dependency> completeDependencies(PartialPom partialPom, EffectiveContext effective) {
        List<Pom.Dependency> dependencies = new ArrayList<>();

        List<ResolvedDependency> resolvedDependencies = partialPom.getDependencies().values().stream().flatMap(v -> v.stream()).collect(Collectors.toList());
        for (ResolvedDependency resolvedDependency : resolvedDependencies) {


            RawMaven rawDependency = downloader.download(resolvedDependency.getGroupId(), resolvedDependency.getArtifactId(),
                    resolvedDependency.getVersion(), null, partialPom.getRawMaven(),
                    partialPom.getRepositories(), exectionContext);

            Pom dependency = resolvePom(rawDependency, new EffectiveContext(Collections.emptyMap(), effective.getEffectiveDependencies()));

            dependencies.add(new Pom.Dependency(
                    rawDependency.getRepository(),
                    resolvedDependency.getScope(),
                    resolvedDependency.getClassifier(),
                    resolvedDependency.getType(),
                    resolvedDependency.getOptional(),
                    dependency,
                    resolvedDependency.getRequestedVersion(),
                    resolvedDependency.getExclusions()
            ));
        }
        return dependencies;
    }

    @Value
    private static class EffectiveContext {
        Map<String, String> properties;
        Map<GroupArtifact, DependencyDescriptor> managedDependencies = new HashMap<>();
        Map<GroupArtifact, List<ResolvedDependency>> effectiveDependencies;

        public String findManagedVersion(GroupArtifact artifactKey, Scope scope) {
            return null;
        }

        public ResolvedDependency findEffectiveDependency(GroupArtifact artifactKey, Scope scope) {
            return null;
        }

        public void addEffectiveDependency(GroupArtifact artifactKey, ResolvedDependency resolvedDependency) {
        }
    }

    @Value
    private static class ResolvedDependency {

        String groupId;
        String artifactId;
        String version;
        String requestedVersion;

        @Nullable
        Scope scope;

        @Nullable
        String type;

        @Nullable
        Boolean optional;

        @Nullable
        String classifier;

        Set<GroupArtifact> exclusions;
    }

    @FieldDefaults(level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @Getter
    @Setter
    private class PartialPom {

        //There are never property placeholders in these three values!
        @EqualsAndHashCode.Include
        final String groupId;
        @EqualsAndHashCode.Include
        final String artifactId;
        @EqualsAndHashCode.Include
        final String version;

        final RawMaven rawMaven;
        final PartialPom parent;
        final Set<MavenRepository> repositories;

        @EqualsAndHashCode.Include
        Map<String, String> propertyOverrides = Collections.emptyMap();

        //This is a map of resolved dependencies for the partial pom computed using the effective context.
        @EqualsAndHashCode.Include
        Map<GroupArtifact, List<ResolvedDependency>> dependencies = Collections.emptyMap();

        public Map<String, String> getProperties() {
            return rawMaven.getPom().getProperties() == null ? Collections.emptyMap() : rawMaven.getPom().getProperties();
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
                        exectionContext.getOnError().accept(new MavenParsingException("Unable to resolve property %s. Including POM is at %s", v, rawMaven.getPom()));
                    }
                    return null;
                });
            } catch (Throwable t) {
                if (required) {
                    exectionContext.getOnError().accept(new MavenParsingException("Unable to resolve property %s. Including POM is at %s", v, rawMaven.getPom()));
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
                effectiveRepositories.addAll(parent.getEffectiveRepositories());
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
