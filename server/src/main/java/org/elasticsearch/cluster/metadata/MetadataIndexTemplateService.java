/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.cluster.metadata;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.CollectionUtil;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.Operations;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.MasterNodeRequest;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.ValidationException;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MapperService.MergeReason;
import org.elasticsearch.indices.IndexTemplateMissingException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.InvalidIndexTemplateException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.elasticsearch.indices.cluster.IndicesClusterStateService.AllocatedIndices.IndexRemovalReason.NO_LONGER_ASSIGNED;

/**
 * Service responsible for submitting index templates updates
 */
public class MetadataIndexTemplateService {

    private static final Logger logger = LogManager.getLogger(MetadataIndexTemplateService.class);
    private static final DeprecationLogger deprecationLogger = new DeprecationLogger(logger);

    private final ClusterService clusterService;
    private final AliasValidator aliasValidator;
    private final IndicesService indicesService;
    private final MetadataCreateIndexService metadataCreateIndexService;
    private final IndexScopedSettings indexScopedSettings;
    private final NamedXContentRegistry xContentRegistry;

    @Inject
    public MetadataIndexTemplateService(ClusterService clusterService,
                                        MetadataCreateIndexService metadataCreateIndexService,
                                        AliasValidator aliasValidator, IndicesService indicesService,
                                        IndexScopedSettings indexScopedSettings, NamedXContentRegistry xContentRegistry) {
        this.clusterService = clusterService;
        this.aliasValidator = aliasValidator;
        this.indicesService = indicesService;
        this.metadataCreateIndexService = metadataCreateIndexService;
        this.indexScopedSettings = indexScopedSettings;
        this.xContentRegistry = xContentRegistry;
    }

    public void removeTemplates(final RemoveRequest request, final RemoveListener listener) {
        clusterService.submitStateUpdateTask("remove-index-template [" + request.name + "]", new ClusterStateUpdateTask(Priority.URGENT) {

            @Override
            public TimeValue timeout() {
                return request.masterTimeout;
            }

            @Override
            public void onFailure(String source, Exception e) {
                listener.onFailure(e);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                Set<String> templateNames = new HashSet<>();
                for (ObjectCursor<String> cursor : currentState.metadata().templates().keys()) {
                    String templateName = cursor.value;
                    if (Regex.simpleMatch(request.name, templateName)) {
                        templateNames.add(templateName);
                    }
                }
                if (templateNames.isEmpty()) {
                    // if its a match all pattern, and no templates are found (we have none), don't
                    // fail with index missing...
                    if (Regex.isMatchAllPattern(request.name)) {
                        return currentState;
                    }
                    throw new IndexTemplateMissingException(request.name);
                }
                Metadata.Builder metadata = Metadata.builder(currentState.metadata());
                for (String templateName : templateNames) {
                    logger.info("removing template [{}]", templateName);
                    metadata.removeTemplate(templateName);
                }
                return ClusterState.builder(currentState).metadata(metadata).build();
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                listener.onResponse(new RemoveResponse(true));
            }
        });
    }

    /**
     * Add the given component template to the cluster state. If {@code create} is true, an
     * exception will be thrown if the component template already exists
     */
    public void putComponentTemplate(final String cause, final boolean create, final String name, final TimeValue masterTimeout,
                                     final ComponentTemplate template, final ActionListener<AcknowledgedResponse> listener) {
        clusterService.submitStateUpdateTask("create-component-template [" + name + "], cause [" + cause + "]",
            new ClusterStateUpdateTask(Priority.URGENT) {

                @Override
                public TimeValue timeout() {
                    return masterTimeout;
                }

                @Override
                public void onFailure(String source, Exception e) {
                    listener.onFailure(e);
                }

                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    return addComponentTemplate(currentState, create, name, template);
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    listener.onResponse(new AcknowledgedResponse(true));
                }
            });
    }

    // Package visible for testing
    ClusterState addComponentTemplate(final ClusterState currentState, final boolean create,
                                      final String name, final ComponentTemplate template) throws Exception {
        if (create && currentState.metadata().componentTemplates().containsKey(name)) {
            throw new IllegalArgumentException("component template [" + name + "] already exists");
        }

        CompressedXContent mappings = template.template().mappings();
        String stringMappings = mappings == null ? null : mappings.string();

        // We may need to normalize index settings, so do that also
        Settings finalSettings = template.template().settings();
        if (finalSettings != null) {
            finalSettings = Settings.builder()
                .put(finalSettings).normalizePrefix(IndexMetadata.INDEX_SETTING_PREFIX)
                .build();
        }

        validateTemplate(finalSettings, stringMappings, indicesService, xContentRegistry);

        // if we're updating a component template, let's check if it's part of any V2 template that will yield the CT update invalid
        if (create == false && finalSettings != null) {
            // if the CT is specifying the `index.hidden` setting it cannot be part of any global template
            if (IndexMetadata.INDEX_HIDDEN_SETTING.exists(finalSettings)) {
                Map<String, IndexTemplateV2> existingTemplates = currentState.metadata().templatesV2();
                List<String> globalTemplatesThatUseThisComponent = new ArrayList<>();
                for (Map.Entry<String, IndexTemplateV2> entry : existingTemplates.entrySet()) {
                    IndexTemplateV2 templateV2 = entry.getValue();
                    if (templateV2.composedOf().contains(name) && templateV2.indexPatterns().stream().anyMatch(Regex::isMatchAllPattern)) {
                        // global templates don't support configuring the `index.hidden` setting so we don't need to resolve the settings as
                        // no other component template can remove this setting from the resolved settings, so just invalidate this update
                        globalTemplatesThatUseThisComponent.add(entry.getKey());
                    }
                }
                if (globalTemplatesThatUseThisComponent.isEmpty() == false) {
                    throw new IllegalArgumentException("cannot update component template ["
                        + name + "] because the following global templates would resolve to specifying the ["
                        + IndexMetadata.SETTING_INDEX_HIDDEN + "] setting: [" + String.join(",", globalTemplatesThatUseThisComponent)
                        + "]");
                }
            }
        }

        // Mappings in component templates don't include _doc, so update the mappings to include this single type
        if (stringMappings != null) {
            Map<String, Object> parsedMappings = MapperService.parseMapping(xContentRegistry, stringMappings);
            if (parsedMappings.size() > 0) {
                stringMappings = Strings.toString(XContentFactory.jsonBuilder()
                    .startObject()
                    .field(MapperService.SINGLE_MAPPING_NAME, parsedMappings)
                    .endObject());
            }
        }

        final Template finalTemplate = new Template(finalSettings,
            stringMappings == null ? null : new CompressedXContent(stringMappings), template.template().aliases());
        final ComponentTemplate finalComponentTemplate = new ComponentTemplate(finalTemplate, template.version(), template.metadata());
        logger.info("adding component template [{}]", name);
        return ClusterState.builder(currentState)
            .metadata(Metadata.builder(currentState.metadata()).put(name, finalComponentTemplate))
            .build();
    }

    /**
     * Remove the given component template from the cluster state. The component template name
     * supports simple regex wildcards for removing multiple component templates at a time.
     */
    public void removeComponentTemplate(final String name, final TimeValue masterTimeout,
                                        final ActionListener<AcknowledgedResponse> listener) {
        clusterService.submitStateUpdateTask("remove-component-template [" + name + "]",
            new ClusterStateUpdateTask(Priority.URGENT) {

                @Override
                public TimeValue timeout() {
                    return masterTimeout;
                }

                @Override
                public void onFailure(String source, Exception e) {
                    listener.onFailure(e);
                }

                @Override
                public ClusterState execute(ClusterState currentState) {
                    Set<String> templateNames = new HashSet<>();
                    for (String templateName : currentState.metadata().componentTemplates().keySet()) {
                        if (Regex.simpleMatch(name, templateName)) {
                            templateNames.add(templateName);
                        }
                    }
                    if (templateNames.isEmpty()) {
                        // if its a match all pattern, and no templates are found (we have none), don't
                        // fail with index missing...
                        if (Regex.isMatchAllPattern(name)) {
                            return currentState;
                        }
                        // TODO: perhaps introduce a ComponentTemplateMissingException?
                        throw new IndexTemplateMissingException(name);
                    }
                    Metadata.Builder metadata = Metadata.builder(currentState.metadata());
                    for (String templateName : templateNames) {
                        logger.info("removing component template [{}]", templateName);
                        metadata.removeComponentTemplate(templateName);
                    }
                    return ClusterState.builder(currentState).metadata(metadata).build();
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    listener.onResponse(new AcknowledgedResponse(true));
                }
            });
    }

    /**
     * Add the given index template to the cluster state. If {@code create} is true, an
     * exception will be thrown if the component template already exists
     */
    public void putIndexTemplateV2(final String cause, final boolean create, final String name, final TimeValue masterTimeout,
                                   final IndexTemplateV2 template, final ActionListener<AcknowledgedResponse> listener) {
        validateV2TemplateRequest(clusterService.state().metadata(), name, template);
        clusterService.submitStateUpdateTask("create-index-template-v2 [" + name + "], cause [" + cause + "]",
            new ClusterStateUpdateTask(Priority.URGENT) {

                @Override
                public TimeValue timeout() {
                    return masterTimeout;
                }

                @Override
                public void onFailure(String source, Exception e) {
                    listener.onFailure(e);
                }

                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    return addIndexTemplateV2(currentState, create, name, template);
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    listener.onResponse(new AcknowledgedResponse(true));
                }
            });
    }

    static void validateV2TemplateRequest(Metadata metadata, String name, IndexTemplateV2 template) {
        if (template.indexPatterns().stream().anyMatch(Regex::isMatchAllPattern)) {
            Settings mergedSettings = resolveSettings(metadata, template);
            if (IndexMetadata.INDEX_HIDDEN_SETTING.exists(mergedSettings)) {
                throw new InvalidIndexTemplateException(name, "global V2 templates may not specify the setting "
                    + IndexMetadata.INDEX_HIDDEN_SETTING.getKey());
            }
        }
    }

    // Package visible for testing
    ClusterState addIndexTemplateV2(final ClusterState currentState, final boolean create,
                                    final String name, final IndexTemplateV2 template) throws Exception {
        if (create && currentState.metadata().templatesV2().containsKey(name)) {
            throw new IllegalArgumentException("index template [" + name + "] already exists");
        }

        Map<String, List<String>> overlaps = findConflictingV2Templates(currentState, name, template.indexPatterns(), true,
            template.priority());
        overlaps.remove(name);
        if (overlaps.size() > 0) {
            String error = String.format(Locale.ROOT, "index template [%s] has index patterns %s matching patterns from " +
                    "existing templates [%s] with patterns (%s) that have the same priority [%d], multiple index templates may not " +
                    "match during index creation, please use a different priority",
                name,
                template.indexPatterns(),
                Strings.collectionToCommaDelimitedString(overlaps.keySet()),
                overlaps.entrySet().stream()
                    .map(e -> e.getKey() + " => " + e.getValue())
                    .collect(Collectors.joining(",")),
                template.priority());
            throw new IllegalArgumentException(error);
        }

        overlaps = findConflictingV1Templates(currentState, name, template.indexPatterns());
        if (overlaps.size() > 0) {
            String warning = String.format(Locale.ROOT, "index template [%s] has index patterns %s matching patterns from " +
                    "existing older templates [%s] with patterns (%s); this template [%s] will take precedence during new index creation",
                name,
                template.indexPatterns(),
                Strings.collectionToCommaDelimitedString(overlaps.keySet()),
                overlaps.entrySet().stream()
                    .map(e -> e.getKey() + " => " + e.getValue())
                    .collect(Collectors.joining(",")),
                name);
            logger.warn(warning);
            deprecationLogger.deprecatedAndMaybeLog("index_template_pattern_overlap", warning);
        }

        IndexTemplateV2 finalIndexTemplate = template;
        Template innerTemplate = template.template();
        if (innerTemplate != null) {
            // We may need to normalize index settings, so do that also
            Settings finalSettings = innerTemplate.settings();
            if (finalSettings != null) {
                finalSettings = Settings.builder()
                    .put(finalSettings).normalizePrefix(IndexMetadata.INDEX_SETTING_PREFIX)
                    .build();
            }
            // If an inner template was specified, its mappings may need to be
            // adjusted (to add _doc) and it should be validated
            CompressedXContent mappings = innerTemplate.mappings();
            String stringMappings = mappings == null ? null : mappings.string();
            validateTemplate(finalSettings, stringMappings, indicesService, xContentRegistry);

            // Mappings in index templates don't include _doc, so update the mappings to include this single type
            if (stringMappings != null) {
                Map<String, Object> parsedMappings = MapperService.parseMapping(xContentRegistry, stringMappings);
                if (parsedMappings.size() > 0) {
                    stringMappings = Strings.toString(XContentFactory.jsonBuilder()
                        .startObject()
                        .field(MapperService.SINGLE_MAPPING_NAME, parsedMappings)
                        .endObject());
                }
            }
            final Template finalTemplate = new Template(finalSettings,
                stringMappings == null ? null : new CompressedXContent(stringMappings), innerTemplate.aliases());
            finalIndexTemplate = new IndexTemplateV2(template.indexPatterns(), finalTemplate, template.composedOf(),
                template.priority(), template.version(), template.metadata());
        }

        logger.info("adding index template [{}]", name);
        return ClusterState.builder(currentState)
            .metadata(Metadata.builder(currentState.metadata()).put(name, finalIndexTemplate))
            .build();
    }

    /**
     * Return a map of v1 template names to their index patterns for v1 templates that would overlap
     * with the given v2 template's index patterns.
     */
    static Map<String, List<String>> findConflictingV1Templates(final ClusterState state, final String candidateName,
                                                                final List<String> indexPatterns) {
        Automaton v2automaton = Regex.simpleMatchToAutomaton(indexPatterns.toArray(Strings.EMPTY_ARRAY));
        Map<String, List<String>> overlappingTemplates = new HashMap<>();
        for (ObjectObjectCursor<String, IndexTemplateMetadata> cursor : state.metadata().templates()) {
            String name = cursor.key;
            IndexTemplateMetadata template = cursor.value;
            Automaton v1automaton = Regex.simpleMatchToAutomaton(template.patterns().toArray(Strings.EMPTY_ARRAY));
            if (Operations.isEmpty(Operations.intersection(v2automaton, v1automaton)) == false) {
                logger.debug("index template {} and old template {} would overlap: {} <=> {}",
                    candidateName, name, indexPatterns, template.patterns());
                overlappingTemplates.put(name, template.patterns());
            }
        }
        return overlappingTemplates;
    }

    /**
     * Return a map of v2 template names to their index patterns for v2 templates that would overlap
     * with the given v1 template's index patterns.
     */
    static Map<String, List<String>> findConflictingV2Templates(final ClusterState state, final String candidateName,
                                                                final List<String> indexPatterns) {
        return findConflictingV2Templates(state, candidateName, indexPatterns, false, null);
    }

    /**
     * Return a map of v2 template names to their index patterns for v2 templates that would overlap
     * with the given template's index patterns.
     */
    static Map<String, List<String>> findConflictingV2Templates(final ClusterState state, final String candidateName,
                                                                final List<String> indexPatterns, boolean checkPriority, Long priority) {
        Automaton v1automaton = Regex.simpleMatchToAutomaton(indexPatterns.toArray(Strings.EMPTY_ARRAY));
        Map<String, List<String>> overlappingTemplates = new HashMap<>();
        for (Map.Entry<String, IndexTemplateV2> entry : state.metadata().templatesV2().entrySet()) {
            String name = entry.getKey();
            IndexTemplateV2 template = entry.getValue();
            Automaton v2automaton = Regex.simpleMatchToAutomaton(template.indexPatterns().toArray(Strings.EMPTY_ARRAY));
            if (Operations.isEmpty(Operations.intersection(v1automaton, v2automaton)) == false) {
                if (checkPriority == false || Objects.equals(priority, template.priority())) {
                    logger.debug("old template {} and index template {} would overlap: {} <=> {}",
                        candidateName, name, indexPatterns, template.indexPatterns());
                    overlappingTemplates.put(name, template.indexPatterns());
                }
            }
        }
        return overlappingTemplates;
    }

    /**
     * Remove the given index template from the cluster state. The index template name
     * supports simple regex wildcards for removing multiple index templates at a time.
     */
    public void removeIndexTemplateV2(final String name, final TimeValue masterTimeout,
                                      final ActionListener<AcknowledgedResponse> listener) {
        clusterService.submitStateUpdateTask("remove-index-template-v2 [" + name + "]",
            new ClusterStateUpdateTask(Priority.URGENT) {

                @Override
                public TimeValue timeout() {
                    return masterTimeout;
                }

                @Override
                public void onFailure(String source, Exception e) {
                    listener.onFailure(e);
                }

                @Override
                public ClusterState execute(ClusterState currentState) {
                    return innerRemoveIndexTemplateV2(currentState, name);
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    listener.onResponse(new AcknowledgedResponse(true));
                }
            });
    }

    // Package visible for testing
    static ClusterState innerRemoveIndexTemplateV2(ClusterState currentState, String name) {
        Set<String> templateNames = new HashSet<>();
        for (String templateName : currentState.metadata().templatesV2().keySet()) {
            if (Regex.simpleMatch(name, templateName)) {
                templateNames.add(templateName);
            }
        }
        if (templateNames.isEmpty()) {
            // if its a match all pattern, and no templates are found (we have none), don't
            // fail with index missing...
            if (Regex.isMatchAllPattern(name)) {
                return currentState;
            }
            throw new IndexTemplateMissingException(name);
        }
        Metadata.Builder metadata = Metadata.builder(currentState.metadata());
        for (String templateName : templateNames) {
            logger.info("removing index template [{}]", templateName);
            metadata.removeIndexTemplate(templateName);
        }
        return ClusterState.builder(currentState).metadata(metadata).build();
    }

    public void putTemplate(final PutRequest request, final PutListener listener) {
        Settings.Builder updatedSettingsBuilder = Settings.builder();
        updatedSettingsBuilder.put(request.settings).normalizePrefix(IndexMetadata.INDEX_SETTING_PREFIX);
        request.settings(updatedSettingsBuilder.build());

        if (request.name == null) {
            listener.onFailure(new IllegalArgumentException("index_template must provide a name"));
            return;
        }
        if (request.indexPatterns == null) {
            listener.onFailure(new IllegalArgumentException("index_template must provide a template"));
            return;
        }

        try {
            validate(request);
        } catch (Exception e) {
            listener.onFailure(e);
            return;
        }

        final IndexTemplateMetadata.Builder templateBuilder = IndexTemplateMetadata.builder(request.name);

        clusterService.submitStateUpdateTask("create-index-template [" + request.name + "], cause [" + request.cause + "]",
                new ClusterStateUpdateTask(Priority.URGENT) {

            @Override
            public TimeValue timeout() {
                return request.masterTimeout;
            }

            @Override
            public void onFailure(String source, Exception e) {
                listener.onFailure(e);
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                validateTemplate(request.settings, request.mappings, indicesService, xContentRegistry);
                return innerPutTemplate(currentState, request, templateBuilder);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                listener.onResponse(new PutResponse(true));
            }
        });
    }

    // Package visible for testing
    static ClusterState innerPutTemplate(final ClusterState currentState, PutRequest request,
                                         IndexTemplateMetadata.Builder templateBuilder) {
        // Flag for whether this is updating an existing template or adding a new one
        // TODO: in 8.0+, only allow updating index templates, not adding new ones
        boolean isUpdate = currentState.metadata().templates().containsKey(request.name);
        if (request.create && isUpdate) {
            throw new IllegalArgumentException("index_template [" + request.name + "] already exists");
        }
        boolean isUpdateAndPatternsAreUnchanged = isUpdate &&
            currentState.metadata().templates().get(request.name).patterns().equals(request.indexPatterns);

        Map<String, List<String>> overlaps = findConflictingV2Templates(currentState, request.name, request.indexPatterns);
        if (overlaps.size() > 0) {
            // Be less strict (just a warning) if we're updating an existing template or this is a match-all template
            if (isUpdateAndPatternsAreUnchanged || request.indexPatterns.stream().anyMatch(Regex::isMatchAllPattern)) {
                String warning = String.format(Locale.ROOT, "template [%s] has index patterns %s matching patterns" +
                        " from existing index templates [%s] with patterns (%s); this template [%s] may be ignored in favor of " +
                        "an index template at index creation time",
                    request.name,
                    request.indexPatterns,
                    Strings.collectionToCommaDelimitedString(overlaps.keySet()),
                    overlaps.entrySet().stream()
                        .map(e -> e.getKey() + " => " + e.getValue())
                        .collect(Collectors.joining(",")),
                    request.name);
                logger.warn(warning);
                deprecationLogger.deprecatedAndMaybeLog("index_template_pattern_overlap", warning);
            } else {
                // Otherwise, this is a hard error, the user should use V2 index templates instead
                String error = String.format(Locale.ROOT, "template [%s] has index patterns %s matching patterns" +
                        " from existing index templates [%s] with patterns (%s), use index templates (/_index_template) instead",
                    request.name,
                    request.indexPatterns,
                    Strings.collectionToCommaDelimitedString(overlaps.keySet()),
                    overlaps.entrySet().stream()
                        .map(e -> e.getKey() + " => " + e.getValue())
                        .collect(Collectors.joining(",")));
                logger.error(error);
                throw new IllegalArgumentException(error);
            }
        }

        templateBuilder.order(request.order);
        templateBuilder.version(request.version);
        templateBuilder.patterns(request.indexPatterns);
        templateBuilder.settings(request.settings);

        if (request.mappings != null) {
            try {
                templateBuilder.putMapping(MapperService.SINGLE_MAPPING_NAME, request.mappings);
            } catch (Exception e) {
                throw new MapperParsingException("Failed to parse mapping: {}", e, request.mappings);
            }
        }

        for (Alias alias : request.aliases) {
            AliasMetadata aliasMetadata = AliasMetadata.builder(alias.name()).filter(alias.filter())
                .indexRouting(alias.indexRouting()).searchRouting(alias.searchRouting()).build();
            templateBuilder.putAlias(aliasMetadata);
        }
        IndexTemplateMetadata template = templateBuilder.build();

        Metadata.Builder builder = Metadata.builder(currentState.metadata()).put(template);

        logger.info("adding template [{}] for index patterns {}", request.name, request.indexPatterns);
        return ClusterState.builder(currentState).metadata(builder).build();
    }

    /**
     * Finds index templates whose index pattern matched with the given index name. In the case of
     * hidden indices, a template with a match all pattern or global template will not be returned.
     *
     * @param metadata The {@link Metadata} containing all of the {@link IndexTemplateMetadata} values
     * @param indexName The name of the index that templates are being found for
     * @param isHidden Whether or not the index is known to be hidden. May be {@code null} if the index
     *                 being hidden has not been explicitly requested. When {@code null} if the result
     *                 of template application results in a hidden index, then global templates will
     *                 not be returned
     * @return a list of templates sorted by {@link IndexTemplateMetadata#order()} descending.
     *
     */
    public static List<IndexTemplateMetadata> findV1Templates(Metadata metadata, String indexName, @Nullable Boolean isHidden) {
        final Predicate<String> patternMatchPredicate = pattern -> Regex.simpleMatch(pattern, indexName);
        final List<IndexTemplateMetadata> matchedTemplates = new ArrayList<>();
        for (ObjectCursor<IndexTemplateMetadata> cursor : metadata.templates().values()) {
            final IndexTemplateMetadata template = cursor.value;
            if (isHidden == null || isHidden == Boolean.FALSE) {
                final boolean matched = template.patterns().stream().anyMatch(patternMatchPredicate);
                if (matched) {
                    matchedTemplates.add(template);
                }
            } else {
                assert isHidden == Boolean.TRUE;
                final boolean isNotMatchAllTemplate = template.patterns().stream().noneMatch(Regex::isMatchAllPattern);
                if (isNotMatchAllTemplate) {
                    if (template.patterns().stream().anyMatch(patternMatchPredicate)) {
                        matchedTemplates.add(template);
                    }
                }
            }
        }
        CollectionUtil.timSort(matchedTemplates, Comparator.comparingInt(IndexTemplateMetadata::order).reversed());

        // this is complex but if the index is not hidden in the create request but is hidden as the result of template application,
        // then we need to exclude global templates
        if (isHidden == null) {
            final Optional<IndexTemplateMetadata> templateWithHiddenSetting = matchedTemplates.stream()
                .filter(template -> IndexMetadata.INDEX_HIDDEN_SETTING.exists(template.settings())).findFirst();
            if (templateWithHiddenSetting.isPresent()) {
                final boolean templatedIsHidden = IndexMetadata.INDEX_HIDDEN_SETTING.get(templateWithHiddenSetting.get().settings());
                if (templatedIsHidden) {
                    // remove the global templates
                    matchedTemplates.removeIf(current -> current.patterns().stream().anyMatch(Regex::isMatchAllPattern));
                }
                // validate that hidden didn't change
                final Optional<IndexTemplateMetadata> templateWithHiddenSettingPostRemoval = matchedTemplates.stream()
                    .filter(template -> IndexMetadata.INDEX_HIDDEN_SETTING.exists(template.settings())).findFirst();
                if (templateWithHiddenSettingPostRemoval.isEmpty() ||
                    templateWithHiddenSetting.get() != templateWithHiddenSettingPostRemoval.get()) {
                    throw new IllegalStateException("A global index template [" + templateWithHiddenSetting.get().name() +
                        "] defined the index hidden setting, which is not allowed");
                }
            }
        }
        return Collections.unmodifiableList(matchedTemplates);
    }

    /**
     * Return the name (id) of the highest matching index template for the given index name. In
     * the event that no templates are matched, {@code null} is returned.
     */
    @Nullable
    public static String findV2Template(Metadata metadata, String indexName, boolean isHidden) {
        final Predicate<String> patternMatchPredicate = pattern -> Regex.simpleMatch(pattern, indexName);
        final Map<IndexTemplateV2, String> matchedTemplates = new HashMap<>();
        for (Map.Entry<String, IndexTemplateV2> entry : metadata.templatesV2().entrySet()) {
            final String name = entry.getKey();
            final IndexTemplateV2 template = entry.getValue();
            if (isHidden == false) {
                final boolean matched = template.indexPatterns().stream().anyMatch(patternMatchPredicate);
                if (matched) {
                    matchedTemplates.put(template, name);
                }
            } else {
                final boolean isNotMatchAllTemplate = template.indexPatterns().stream().noneMatch(Regex::isMatchAllPattern);
                if (isNotMatchAllTemplate) {
                    if (template.indexPatterns().stream().anyMatch(patternMatchPredicate)) {
                        matchedTemplates.put(template, name);
                    }
                }
            }
        }

        if (matchedTemplates.size() == 0) {
            return null;
        }

        final List<IndexTemplateV2> candidates = new ArrayList<>(matchedTemplates.keySet());
        CollectionUtil.timSort(candidates, Comparator.comparing(IndexTemplateV2::priority,
            Comparator.nullsLast(Comparator.reverseOrder())));

        assert candidates.size() > 0 : "we should have returned early with no candidates";
        IndexTemplateV2 winner = candidates.get(0);
        String winnerName = matchedTemplates.get(winner);

        // if the winner template is a global template that specifies the `index.hidden` setting (which is not allowed, so it'd be due to
        // a restored index cluster state that modified a component template used by this global template such that it has this setting)
        // we will fail and the user will have to update the index template and remove this setting or update the corresponding component
        // template that contributes to the index template resolved settings
        if (winner.indexPatterns().stream().anyMatch(Regex::isMatchAllPattern) &&
            IndexMetadata.INDEX_HIDDEN_SETTING.exists(resolveSettings(metadata, winnerName))) {
            throw new IllegalStateException("global index template [" + winnerName + "], composed of component templates [" +
                String.join(",", winner.composedOf()) + "] defined the index.hidden setting, which is not allowed");
        }

        return winnerName;
    }

    /**
     * Resolve the given v2 template into an ordered list of mappings
     */
    public static List<CompressedXContent> resolveMappings(final ClusterState state, final String templateName) {
        final IndexTemplateV2 template = state.metadata().templatesV2().get(templateName);
        assert template != null : "attempted to resolve mappings for a template [" + templateName +
            "] that did not exist in the cluster state";
        if (template == null) {
            return List.of();
        }
        final Map<String, ComponentTemplate> componentTemplates = state.metadata().componentTemplates();
        // TODO: more fine-grained merging of component template mappings, ie, merge fields as distint entities
        List<CompressedXContent> mappings = template.composedOf().stream()
            .map(componentTemplates::get)
            .filter(Objects::nonNull)
            .map(ComponentTemplate::template)
            .map(Template::mappings)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        // Add the actual index template's mappings, since it takes the highest precedence
        Optional.ofNullable(template.template())
            .map(Template::mappings)
            .ifPresent(mappings::add);
        // When actually merging mappings, the highest precedence ones should go first, so reverse the list
        Collections.reverse(mappings);
        return Collections.unmodifiableList(mappings);
    }

    /**
     * Resolve index settings for the given list of v1 templates, templates are apply in reverse
     * order since they should be provided in order of priority/order
     */
    public static Settings resolveSettings(final List<IndexTemplateMetadata> templates) {
        Settings.Builder templateSettings = Settings.builder();
        // apply templates, here, in reverse order, since first ones are better matching
        for (int i = templates.size() - 1; i >= 0; i--) {
            templateSettings.put(templates.get(i).settings());
        }
        return templateSettings.build();
    }

    /**
     * Resolve the given v2 template into a collected {@link Settings} object
     */
    public static Settings resolveSettings(final Metadata metadata, final String templateName) {
        final IndexTemplateV2 template = metadata.templatesV2().get(templateName);
        assert template != null : "attempted to resolve settings for a template [" + templateName +
            "] that did not exist in the cluster state";
        if (template == null) {
            return Settings.EMPTY;
        }
        return resolveSettings(metadata, template);
    }

    private static Settings resolveSettings(Metadata metadata, IndexTemplateV2 template) {
        final Map<String, ComponentTemplate> componentTemplates = metadata.componentTemplates();
        List<Settings> componentSettings = template.composedOf().stream()
            .map(componentTemplates::get)
            .filter(Objects::nonNull)
            .map(ComponentTemplate::template)
            .map(Template::settings)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        Settings.Builder templateSettings = Settings.builder();
        componentSettings.forEach(templateSettings::put);
        // Add the actual index template's settings to the end, since it takes the highest precedence.
        Optional.ofNullable(template.template())
            .map(Template::settings)
            .ifPresent(templateSettings::put);
        return templateSettings.build();
    }

    /**
     * Resolve the given v1 templates into an ordered list of aliases
     */
    public static List<Map<String, AliasMetadata>> resolveAliases(final List<IndexTemplateMetadata> templates) {
        final List<Map<String, AliasMetadata>> resolvedAliases = new ArrayList<>();
        templates.forEach(template -> {
            if (template.aliases() != null) {
                Map<String, AliasMetadata> aliasMeta = new HashMap<>();
                for (ObjectObjectCursor<String, AliasMetadata> cursor : template.aliases()) {
                    aliasMeta.put(cursor.key, cursor.value);
                }
                resolvedAliases.add(aliasMeta);
            }
        });
        return Collections.unmodifiableList(resolvedAliases);
    }

    /**
     * Resolve the given v2 template into an ordered list of aliases
     */
    public static List<Map<String, AliasMetadata>> resolveAliases(final Metadata metadata, final String templateName) {
        final IndexTemplateV2 template = metadata.templatesV2().get(templateName);
        assert template != null : "attempted to resolve aliases for a template [" + templateName +
            "] that did not exist in the cluster state";
        if (template == null) {
            return List.of();
        }
        final Map<String, ComponentTemplate> componentTemplates = metadata.componentTemplates();
        List<Map<String, AliasMetadata>> aliases = template.composedOf().stream()
            .map(componentTemplates::get)
            .filter(Objects::nonNull)
            .map(ComponentTemplate::template)
            .map(Template::aliases)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        // Add the actual index template's aliases to the end if they exist
        Optional.ofNullable(template.template())
            .map(Template::aliases)
            .ifPresent(aliases::add);
        // Aliases are applied in order, but subsequent alias configuration from the same name is
        // ignored, so in order for the order to be correct, alias configuration should be in order
        // of precedence (with the index template first)
        Collections.reverse(aliases);
        return Collections.unmodifiableList(aliases);
    }

    private static void validateTemplate(Settings validateSettings, String mappings,
                                         IndicesService indicesService, NamedXContentRegistry xContentRegistry) throws Exception {
        // First check to see if mappings are valid XContent
        if (mappings != null) {
            try {
                new CompressedXContent(mappings);
            } catch (Exception e) {
                throw new MapperParsingException("Failed to parse mapping: {}", e, mappings);
            }
        }

        // Hard to validate settings if they're non-existent, so used empty ones if none were provided
        Settings settings = validateSettings;
        if (settings == null) {
            settings = Settings.EMPTY;
        }

        Index createdIndex = null;
        final String temporaryIndexName = UUIDs.randomBase64UUID();
        try {
            // use the provided values, otherwise just pick valid dummy values
            int dummyPartitionSize = IndexMetadata.INDEX_ROUTING_PARTITION_SIZE_SETTING.get(settings);
            int dummyShards = settings.getAsInt(IndexMetadata.SETTING_NUMBER_OF_SHARDS,
                    dummyPartitionSize == 1 ? 1 : dummyPartitionSize + 1);
            int shardReplicas = settings.getAsInt(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0);


            //create index service for parsing and validating "mappings"
            Settings dummySettings = Settings.builder()
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(settings)
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, dummyShards)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, shardReplicas)
                .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
                .build();

            final IndexMetadata tmpIndexMetadata = IndexMetadata.builder(temporaryIndexName).settings(dummySettings).build();
            IndexService dummyIndexService = indicesService.createIndex(tmpIndexMetadata, Collections.emptyList(), false);
            createdIndex = dummyIndexService.index();

            if (mappings != null) {
                dummyIndexService.mapperService().merge(MapperService.SINGLE_MAPPING_NAME,
                    MapperService.parseMapping(xContentRegistry, mappings), MergeReason.MAPPING_UPDATE);
            }

        } finally {
            if (createdIndex != null) {
                indicesService.removeIndex(createdIndex, NO_LONGER_ASSIGNED, " created for parsing template mapping");
            }
        }
    }

    private void validate(PutRequest request) {
        List<String> validationErrors = new ArrayList<>();
        if (request.name.contains(" ")) {
            validationErrors.add("name must not contain a space");
        }
        if (request.name.contains(",")) {
            validationErrors.add("name must not contain a ','");
        }
        if (request.name.contains("#")) {
            validationErrors.add("name must not contain a '#'");
        }
        if (request.name.startsWith("_")) {
            validationErrors.add("name must not start with '_'");
        }
        if (!request.name.toLowerCase(Locale.ROOT).equals(request.name)) {
            validationErrors.add("name must be lower cased");
        }
        for(String indexPattern : request.indexPatterns) {
            if (indexPattern.contains(" ")) {
                validationErrors.add("template must not contain a space");
            }
            if (indexPattern.contains(",")) {
                validationErrors.add("template must not contain a ','");
            }
            if (indexPattern.contains("#")) {
                validationErrors.add("template must not contain a '#'");
            }
            if (indexPattern.startsWith("_")) {
                validationErrors.add("template must not start with '_'");
            }
            if (!Strings.validFileNameExcludingAstrix(indexPattern)) {
                validationErrors.add("template must not contain the following characters " + Strings.INVALID_FILENAME_CHARS);
            }
        }

        try {
            indexScopedSettings.validate(request.settings, true); // templates must be consistent with regards to dependencies
        } catch (IllegalArgumentException iae) {
            validationErrors.add(iae.getMessage());
            for (Throwable t : iae.getSuppressed()) {
                validationErrors.add(t.getMessage());
            }
        }
        List<String> indexSettingsValidation = metadataCreateIndexService.getIndexSettingsValidationErrors(request.settings, true);
        validationErrors.addAll(indexSettingsValidation);

        if (request.indexPatterns.stream().anyMatch(Regex::isMatchAllPattern)) {
            if (IndexMetadata.INDEX_HIDDEN_SETTING.exists(request.settings)) {
                validationErrors.add("global templates may not specify the setting " + IndexMetadata.INDEX_HIDDEN_SETTING.getKey());
            }
        }

        if (!validationErrors.isEmpty()) {
            ValidationException validationException = new ValidationException();
            validationException.addValidationErrors(validationErrors);
            throw new InvalidIndexTemplateException(request.name, validationException.getMessage());
        }

        for (Alias alias : request.aliases) {
            //we validate the alias only partially, as we don't know yet to which index it'll get applied to
            aliasValidator.validateAliasStandalone(alias);
            if (request.indexPatterns.contains(alias.name())) {
                throw new IllegalArgumentException("Alias [" + alias.name() +
                    "] cannot be the same as any pattern in [" + String.join(", ", request.indexPatterns) + "]");
            }
        }
    }

    public interface PutListener {

        void onResponse(PutResponse response);

        void onFailure(Exception e);
    }

    public static class PutRequest {
        final String name;
        final String cause;
        boolean create;
        int order;
        Integer version;
        List<String> indexPatterns;
        Settings settings = Settings.Builder.EMPTY_SETTINGS;
        String mappings = null;
        List<Alias> aliases = new ArrayList<>();

        TimeValue masterTimeout = MasterNodeRequest.DEFAULT_MASTER_NODE_TIMEOUT;

        public PutRequest(String cause, String name) {
            this.cause = cause;
            this.name = name;
        }

        public PutRequest order(int order) {
            this.order = order;
            return this;
        }

        public PutRequest patterns(List<String> indexPatterns) {
            this.indexPatterns = indexPatterns;
            return this;
        }

        public PutRequest create(boolean create) {
            this.create = create;
            return this;
        }

        public PutRequest settings(Settings settings) {
            this.settings = settings;
            return this;
        }

        public PutRequest mappings(String mappings) {
            this.mappings = mappings;
            return this;
        }

        public PutRequest aliases(Set<Alias> aliases) {
            this.aliases.addAll(aliases);
            return this;
        }

        public PutRequest masterTimeout(TimeValue masterTimeout) {
            this.masterTimeout = masterTimeout;
            return this;
        }

        public PutRequest version(Integer version) {
            this.version = version;
            return this;
        }
    }

    public static class PutResponse {
        private final boolean acknowledged;

        public PutResponse(boolean acknowledged) {
            this.acknowledged = acknowledged;
        }

        public boolean acknowledged() {
            return acknowledged;
        }
    }

    public static class RemoveRequest {
        final String name;
        TimeValue masterTimeout = MasterNodeRequest.DEFAULT_MASTER_NODE_TIMEOUT;

        public RemoveRequest(String name) {
            this.name = name;
        }

        public RemoveRequest masterTimeout(TimeValue masterTimeout) {
            this.masterTimeout = masterTimeout;
            return this;
        }
    }

    public static class RemoveResponse {
        private final boolean acknowledged;

        public RemoveResponse(boolean acknowledged) {
            this.acknowledged = acknowledged;
        }

        public boolean acknowledged() {
            return acknowledged;
        }
    }

    public interface RemoveListener {

        void onResponse(RemoveResponse response);

        void onFailure(Exception e);
    }
}
